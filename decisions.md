# Decisions

A running log of the real calls made while building SchemaSync, in the order they came up.
Each entry is: what I chose, what else I seriously considered, why, and what I cut.

---

## 1. Which problem, and what "version control for a database" means here

**Chose:** schema version control — branch / diff / merge for the *structure* of a Postgres
database, with every change applied to a real database.

**Considered:** data version control (Dolt-style row-level history), or a hybrid.

**Reasoning:** the brief asks for "add, drop, rename, and retype columns; change constraints and
indexes; create and drop tables" — that is entirely a schema vocabulary. It also says the solution
must "work smoothly even if the table has ~5GB of data", which I read as *the data is the
constraint, not the content*: the thing being versioned is small, the thing it sits on top of is
large. Data-level merge is a multi-year product (Dolt has been building it since 2018); schema
merge done properly in five days is a better use of the time.

**Cut:** row-level history, time-travel queries, data merges.

---

## 2. The hard sub-problem to go deep on

**Chose:** two, treated as one system — (a) rename-aware three-way merge, (b) a migration executor
that applies the merged result to a large table without downtime.

**Considered:** going deep on only the merge algebra, or only the online-migration machinery.

**Reasoning:** they are the same problem seen from two ends. A merge that emits `DROP COLUMN` +
`ADD COLUMN` where the user meant a rename is not just wrong, it destroys data — so merge
correctness *is* a safety property, not just a UX nicety. And a correct merge that then locks a 5GB
table for four minutes is not shippable. Doing one without the other leaves an obvious hole.

**Accepted tradeoff:** less breadth elsewhere. No auth, no multi-engine support, no data merges.

---

## 3. Snapshots per commit, not an operation log to replay

**Chose:** each commit stores the **full canonical schema as JSONB**, keyed by stable object ID.

**Considered:**
- *Operation log + replay.* Diff becomes O(history); every read has to reconstruct state; and a bug
  in replay silently corrupts every downstream answer with no way to notice.
- *Normalised versioned object tables* (`valid_from` / `valid_to` on column rows). This one is
  genuinely tempting and I rejected it for a specific reason: temporal validity ranges assume a
  **linear** timeline. Branching is a DAG. You end up re-implementing a version DAG inside SQL
  predicates, which is both slow and very hard to test.

**Reasoning:** three-way merge needs base, ours and theirs *simultaneously*. With snapshots that is
three primary-key lookups followed by a pure in-memory function — which means the entire merge
engine is unit-testable with plain JUnit and no database at all. Snapshots are a few hundred KB;
the storage cost is irrelevant at this scale. Git itself stores snapshots rather than diffs for the
same reason.

The operation log is still written — it powers the history view and the audit trail — but it is
**never the source of truth for state**. That separation means a bug in the op log cannot corrupt a
schema.

---

## 4. Stable object IDs, so a rename is never inferred

**Chose:** every table, column, constraint and index gets a **stable ULID at creation**, carried
unchanged through renames. Diff compares by ID; `name` is an ordinary attribute.

**Considered:** heuristic rename detection — compare two snapshots and guess which drop+add pair is
"really" a rename, using type equality, ordinal position and name similarity.

**Reasoning:** heuristic detection fails in both directions and the failures are invisible at
review time.
- A **false negative** emits `DROP COLUMN` + `ADD COLUMN`. On a 5GB table that is data destroyed
  where the user intended a free catalog update.
- A **false positive** emits `ALTER ... RENAME` where a genuinely new column was meant, silently
  resurfacing old data under new semantics.

It also has no signal in the common case — a table with five `text NOT NULL` columns offers nothing
to match on — and it degrades exactly when you need it most, because renaming *and* retyping in one
step erases both signals. Any similarity threshold is a tuning parameter, and a tuning parameter
cannot be unit-tested to a correct answer.

**What this costs:** identity only holds if every change comes through our API. Someone running DDL
directly against a branch schema breaks it. Mitigated by drift detection (§7), not ignored.

---

## 5. A fixed 9-button palette instead of free-form SQL

**Chose:** the branch editor exposes exactly nine operations — add / drop / rename column, change
type, toggle nullable, set-drop default, create / drop table, add-drop index.

**Considered:** a raw DDL text box (parse it and map to our model), or a hybrid.

**Reasoning:** this looked like a scope cut and turned out to be a load-bearing design decision.
Accepting free-form SQL means writing a Postgres DDL parser, and — much worse — it means rename
intent has to be *inferred* again, which is exactly the failure mode §4 exists to prevent. With a
fixed palette, every click produces one typed operation record against a known object ID, and the
conflict matrix becomes finite and enumerable: nine verbs over a fixed attribute set. That is what
makes it possible to give every conflict a predetermined resolution UI rather than a
general-purpose merge editor.

**Cut, with a compromise:** constraints (PK/FK/UNIQUE/CHECK) are introspected, displayed and
carried faithfully through branch and merge, but are not editable in v1. A **read-only** `SELECT`
console per branch gives most of the value of a SQL box at none of the identity risk.

---

## 6. Branches are Postgres schemas with sampled data, not copies

**Chose:** a branch is a real Postgres schema (namespace) containing the same tables, seeded with a
bounded sample (~1,000 rows per table).

**Considered:**
- *Empty tables.* Cheapest, but you cannot sanity-check a migration against anything, and cast
  failures only surface at merge time.
- *`CREATE DATABASE ... TEMPLATE`.* Requires **zero other connections** to the template database,
  so you would have to evict everyone from `main` in order to branch. Fatal for "branching feels
  instant."
- *Full data copy.* Minutes and gigabytes per branch. This is precisely the trap the "~5GB"
  constraint is testing for.
- *Copy-on-write at the storage layer* (ZFS snapshots, Neon, Aurora clones). This is the correct
  answer at product scale and I want to be clear that I know it — it needs control of the storage
  layer and a Postgres process per branch, which is not a five-day deliverable.

**Reasoning:** branch creation stays O(schema), not O(data), so it takes the same ~200ms whether
`main` holds 5MB or 5GB — while still giving a real, connectable, queryable database.

**The honest tradeoff, stated plainly:** a branch is structurally real but not data-real. You
*cannot* prove a destructive cast is safe from inside the branch. That loop is closed at merge time
by running pre-flight validation against the actual target table (§9) — which is where the
interesting engineering went anyway.

---

## 7. Canonicalisation, and drift detection

**Chose:** normalise every introspected type and expression before hashing, and store a SHA-256
content hash per snapshot.

**Reasoning:** this is the landmine under every schema-diff tool. Postgres rewrites what you give
it: `character varying(50)` vs `varchar(50)`, `int4` vs `integer`, `serial` (which is really
`integer` plus a sequence plus a default), `'x'` stored as `'x'::text`, `now()` vs
`CURRENT_TIMESTAMP`. Without a canonicaliser you get phantom diffs on *every* import and the
content hashes are meaningless. It cost about half a day and it is the difference between a demo
and a tool.

The hash pays for itself twice: no-op commits are free to detect, and so is **drift** — before any
diff or merge we re-introspect the branch, canonicalise, and compare against the head snapshot. A
mismatch marks the branch `DRIFTED` and blocks the merge. That turns the silent-corruption failure
mode of §4 into a visible, honest error.

---

## 8. Unsupported objects are detected and refused, never silently ignored

**Chose:** views, triggers, functions, partitions, enums, materialised views and RLS policies are
detected during introspection and the schema is marked *partially managed*; operations that would
touch them are blocked.

**Considered:** just omitting them from the snapshot.

**Reasoning:** omitting them is actively dangerous rather than merely incomplete. The merge plan is
computed as `diff(target, merged)` — so an object that is missing from the snapshot looks exactly
like an object the user deleted, and the merge would cheerfully drop it from the real database. A
loud, named limitation is worth far more than fake breadth, and it is a much smaller amount of code
than supporting them properly.

---

## 9. The executor: what "zero downtime" actually promises

**Chose, and stated in the product:**

> No `ACCESS EXCLUSIVE` lock is *held* for more than a few milliseconds, and no DDL statement
> *waits* for a lock for more than a few seconds.

**Reasoning:** "online" does not mean "instant", and conflating the two is how people end up
trusting a tool that then takes their site down. A 5GB backfill takes minutes no matter what. What
it *can* promise is that reads never block and writes block only momentarily. Being precise about
this is the whole design.

The corollary is that the naive statement is never emitted. `ALTER COLUMN TYPE` on a large table
compiles to expand → sync trigger → batched backfill → validate → swap. The
ordering within that is not arbitrary, and two orderings in particular are the difference between
working and breaking production — both documented inline in the executor:

- The **sync trigger must commit in its own transaction, before the first backfill batch**. That is
  what guarantees no gap: `CREATE TRIGGER` waits for in-flight writers, and every writer starting
  afterwards sees it, so every row is covered by either the trigger or the backfill.
- The **`CHECK ... NOT VALID` goes after the backfill, not before**. A `NOT VALID` constraint is
  still enforced for new row versions, so adding it while NULLs remain makes any `UPDATE` touching
  one of those rows fail — silently breaking writes for a subset of rows for the whole backfill.

**Cut:** shadow-*table* rewrites (the `pg_repack` / `pgroll` / `gh-ost` model). That is 2+ days on
its own. Shadow *column* covers the operations in our palette. Also cut: online primary-key type
changes with foreign-key repointing — detected and **refused** with an explanation, because
refusing intelligently is better than attempting it badly.

---

## 10. Postgres as the job queue, rather than a job framework

**Chose:** `FOR UPDATE SKIP LOCKED` to claim runs, a heartbeat column to reclaim crashed ones, and
all step state in tables.

**Considered:** Quartz, or a queue plus workers.

**Reasoning:** the state has to be in Postgres regardless — resumability is a hard requirement,
since a backfill that cannot resume will eventually be a backfill that restarts from zero ten
minutes in. Once the state is there, `SKIP LOCKED` gives a correct multi-instance work queue in a
single statement. Adding a broker would introduce a second failure domain to buy nothing.

Two details that matter more than the queue choice: the migration executor gets its **own
connection pool**, so a stuck migration cannot starve the API; and every step carries an
`isAlreadyApplied()` catalog probe, so resume never has to trust our own bookkeeping.

---

## 11. `JdbcTemplate` throughout, no JPA

**Chose:** plain `JdbcTemplate` with hand-written row mappers for the control plane, as well as for
all DDL against the target.

**Considered:** JPA/Hibernate for the metadata tables (which is what I initially planned).

**Reasoning:** JPA is the wrong tool for the target database — you cannot express
`CREATE INDEX CONCURRENTLY` or a lock-timeout-wrapped `ALTER TABLE` through an ORM — so it was only
ever going to cover the control plane. That would mean two persistence idioms in one codebase to
save very little: the control plane is about ten tables, several of which store JSONB that JPA
needs converters for. One idiom, explicit SQL, and no `ddl-auto` footgun is the simpler system.

---

## 12. Testcontainers over a shared test database

**Chose:** every integration test runs against a real Postgres 16 in Testcontainers, one container
shared across the suite, with each test isolating itself in its own schema.

**Considered:** pointing tests at the Compose Postgres, or an in-memory substitute like H2.

**Reasoning:** H2 is disqualified outright. This entire product is a set of claims about what
specific DDL does in Postgres — which operations rewrite a table, which take `ACCESS EXCLUSIVE`,
whether `SET NOT NULL` skips its scan when a validated `CHECK` exists. Only Postgres can adjudicate
those, so a test against anything else would prove nothing. A shared Compose database would work
but makes `mvn verify` depend on external state a reviewer has to set up first.

**A real snag worth recording**, since it cost time and would cost a reviewer the same: Spring Boot
3.4's BOM pins Testcontainers 1.20.x, which pings the daemon advertising Docker API **1.32**.
Docker 29 — what OrbStack currently ships — removed support for anything below 1.40 and rejects the
client. The symptom is `Could not find a valid Docker environment`, which reads like a misconfigured
machine rather than a version incompatibility, and sends you looking at socket paths. It is neither:
pinning Testcontainers to **1.21.4** fixes it. I also tried overriding docker-java to 3.5.3 on the
theory that the version came from the transport layer; it did not, and the override was removed
again rather than left in the pom as cargo cult.

---

## 13. The snapshot is read back from the database, not predicted

**Chose:** after applying operations to a branch, re-introspect the schema and store *that* as the
new snapshot — passing the in-memory mutated snapshot in only as the source of stable IDs.

**Considered:** trusting the in-memory mutation, which is what I built first. It is faster (no
extra round trip) and obviously correct in the common case.

**Reasoning:** it is not correct in the uncommon case, and I only found this by running the thing
end to end. Postgres does **not** rewrite a column's `DEFAULT` when you retype the column. After
`ALTER COLUMN status TYPE text`, the default is still stored as `'pending'::character varying`.
My model predicted `'pending'`; the database said otherwise. Drift detection — which exists
precisely to catch the recorded snapshot disagreeing with reality — then fired on SchemaSync's
*own* changes, marking a healthy branch `DRIFTED`.

That is the general shape of the problem: any model that predicts what Postgres will do will
eventually mispredict, and each mispredict poisons the snapshot for every later diff and merge.
Reading back is one extra query against a schema we already know is small.

The subtlety that makes it work: introspection alone cannot know a rename happened, so reading
back naively would mint fresh IDs and destroy the identity the rename depended on. Passing the
mutated snapshot as the ID source fixes that — its names are already post-rename, so IDs carry
across by name. The result has **the database's content with the operation log's identity**, which
is the combination we actually want.

**A second, smaller fix from the same finding:** the stale `::character varying` cast left on a
retyped column is harmless to Postgres but cannot be canonicalised away (the cast no longer matches
the column type), so it showed up as a spurious `COLUMN_DEFAULT_CHANGED` in every subsequent diff.
Re-stating the default immediately after a retype lets Postgres re-cast it and keeps the diff
honest.

---

## 14. Editing `main` directly is refused

**Chose:** operations against the `main` branch are rejected outright. Changes reach `main` only
through a merge.

**Reasoning:** `main` is the branch backed by the real, full-size schema. It is the one place where
an `ALTER TABLE` is genuinely dangerous, and it is also the only path that gets the safety
classification, the pre-flight validation and the online execution strategy. Allowing a direct edit
would mean a second, unaudited route to production that bypasses every protection the product
exists to provide. Refusing is one line and removes the whole category.

`deleteBranch` refuses `main` for the same reason: its Postgres schema *is* the project's data.

---

## 15. Measured: what the online path actually buys

The same change (`amount_cents` from `integer` to `numeric(14,2)`) on the same 2,000,000-row,
354MB table, with four clients doing continuous `SELECT` + `UPDATE` against it
(`scripts/zero_downtime_demo.py`):

| | Naive `ALTER TABLE` | SchemaSync online |
| --- | --- | --- |
| Wall clock | **4.9s** | 75.3s |
| Requests served during | 2,525 | 27,838 |
| Failed requests | 0 | 0 |
| p99 latency | 11.6 ms | 31.0 ms |
| **Worst single request** | **4,894 ms** | **1,112 ms** |

Read those numbers honestly, because they do not say "the online path is better at everything":

- **The online path is 15× slower in wall clock.** It does strictly more work — a shadow column, a
  trigger on every write, a full backfill in throttled batches, a validation scan. If you have a
  maintenance window and nobody is using the database, the naive `ALTER` is the right call and this
  machinery is waste.
- **The naive run reports zero failures too.** That is the trap: nothing errored, so a migration
  tool could truthfully claim success. What actually happened is that every request arriving during
  those 4.9 seconds sat in the lock queue — the worst one for 4.9 seconds. Nobody got an error;
  everybody got a hung page. This is why the demo reports worst-case latency and not just an error
  count, and why "no errors" is not the metric the product optimises.
- **1,112ms is not zero, and the promise was never that it would be.** The stated guarantee is that
  no `ACCESS EXCLUSIVE` lock is *held* for more than milliseconds and no DDL *waits* more than a few
  seconds. The worst wait came from the swap step queueing behind four active writers, bounded by
  the 2s `lock_timeout` exactly as designed. A quieter table would show a much smaller number.

The honest summary: the online path trades total duration for bounded impact. That is the right
trade during business hours and the wrong one at 3am with the site drained.

---

## 16. Drift detection needed an escape hatch

**Chose:** added `POST /branches/{id}/refresh`, which re-introspects a branch's live schema and
records it as a new commit, clearing the `DRIFTED` state.

**Found by:** running the zero-downtime demo. I reset a column type with raw `psql` between runs,
which is exactly the out-of-band change drift detection exists to catch — and it caught it. But
then the branch was stuck: the error message said "re-import or revert it before making further
changes" and there was no way to do either.

**Reasoning:** a safety mechanism that detects a problem and offers no way out is not a safety
mechanism, it is a trap. And the person most likely to hit it is someone who just fixed something
by hand during an incident, which is the worst possible moment to be told the tool will no longer
help them.

**What it costs, stated in the code and the API:** re-import has no intent to work from, so a
column renamed by hand comes back as a *new* column with a new stable ID. That means a rename
performed outside SchemaSync will subsequently merge as a drop plus an add — precisely the outcome
the identity model exists to prevent. The escape hatch restores usability, not history.

---

## Deliberately cut, with reasons

| Cut | Why |
| --- | --- |
| Data-level branching and merging | A multi-year product on its own. Schema is the stated vocabulary of the brief. |
| Free-form SQL editing | Destroys rename intent (§4, §5). Read-only query console instead. |
| Storage-level CoW branching | The right answer at scale; needs the storage layer. This is the "what I'd build next". |
| Rebase, cherry-pick | Branch from a base, merge back. Enough to demonstrate the model. |
| **Revert of an applied merge** | Cut on *principle*, not time. `DROP COLUMN` is not invertible — the data is gone. Offering "undo" would be a lie. Instead: reversible up to an explicit point-of-no-return step, which the UI gates. |
| Multi-engine (MySQL, etc.) | A dialect abstraction built for a second engine that never arrives is the canonical over-engineering trap. |
| Auth, RBAC, multi-tenancy | An author name field, no login. Not what is being evaluated. |
| Real 5GB in CI | Correctness tests run against ~200k rows via Testcontainers. The 5GB run is a documented manual validation. |
