# Decisions

Fifteen calls that shaped SchemaSync, each with what I chose, what I rejected, and why. The last
section lists what I cut or deferred — including things I designed and then did not build, so the
line between the two is clear.

---

## 1. Schema version control, with depth in two places that are really one

**Chose:** branch / diff / merge for the *structure* of a Postgres database, applied to a real
database, going deep on (a) merging without ever mistaking a rename for a drop, and (b) applying the
merge to a large table without taking it offline.

**Rejected:** data versioning (Dolt-style row history) — a multi-year product, and the brief's
vocabulary (add, drop, rename, retype, constraints, indexes) is entirely schema.

**Why these two:** they fail together. A merge that emits `DROP COLUMN` + `ADD COLUMN` for a rename
destroys data, so merge correctness is a safety property. A correct merge that then locks a 5GB
table for four minutes is not shippable. Either one alone leaves an obvious hole.

## 2. A commit is a full snapshot, not an operation log to replay

**Chose:** each commit stores the whole canonical schema as JSONB, deduplicated by SHA-256.

**Rejected:** *replaying an operation log* (diff becomes O(history), and one replay bug silently
corrupts every later answer); *versioned rows with `valid_from/valid_to`* (assumes a linear
timeline — branches form a DAG).

**Why:** three-way merge needs base, ours and theirs at once. With snapshots that is three lookups
and then a pure function, so diff and merge are unit-tested with no database. Snapshots are
kilobytes. The op log is still written, for history and audit, but never trusted for state. Git
stores snapshots for the same reason.

## 3. Stable IDs, so a rename is never inferred

**Chose:** every table, column, index and constraint gets an ID when first seen, carried unchanged
through renames. Diff matches objects by ID; the name is just an attribute.

**Rejected:** guessing renames from similarity (type, position, name distance).

**Why:** guessing fails silently in both directions — a missed rename destroys a column of data; a
false one resurrects old data under a new meaning. It has no signal when columns look alike and
none at all when a column is renamed *and* retyped at once, and a similarity threshold cannot be
tested to a correct answer. **Cost:** identity only holds for changes made through SchemaSync (see 8).

## 4. A closed set of operations instead of a SQL box

**Chose:** ten typed operations behind the UI (add / drop / rename column, change type, nullability,
default, create / drop table, add / drop index). Constraints are shown and carried through merges
but not editable.

**Rejected:** accepting typed DDL and parsing it.

**Why:** a SQL box means a Postgres parser *and* inferring renames again — undoing decision 3. With
a closed set, every click is one typed record against a known ID, and the conflict space is finite,
so each conflict has a known resolution.

## 5. A branch is a Postgres schema with sampled rows

**Chose:** `CREATE SCHEMA br_…`, the same tables, and up to 1,000 rows per table, copied parent-first
so every sampled child row's foreign key still resolves.

**Rejected:** empty tables (nothing to test against); `CREATE DATABASE … TEMPLATE` (needs every
other connection to the source closed); full copies (minutes and gigabytes per branch);
copy-on-write storage like Neon or ZFS (the right answer at scale, but it needs control of the
storage layer).

**Why:** branch cost is O(schema), not O(data) — 175–225ms measured against 100k and 2M-row tables.
**Cost:** a branch is structurally real but not data-real, so it cannot prove a cast is safe on
the full table. Decision 10 closes that gap at merge time.

## 6. Canonicalise everything Postgres rewrites

**Chose:** normalise every introspected type and default before hashing (`character varying(32)`
→ `varchar(32)`, `'pending'::character varying` → `'pending'`, `serial` → `integer` + identity).

**Why:** without it, re-reading an unchanged schema reports every column as modified and content
hashes mean nothing. The alias table is the spec and is tested row by row.

## 7. The recorded snapshot is read back from the database, not predicted

**Chose:** after each branch operation, re-introspect the live schema and store that, using the
in-memory result only as the source of IDs.

**Rejected:** trusting the in-memory mutation — my first version.

**Why:** found by running it. `ALTER COLUMN … TYPE` does not rewrite the column's `DEFAULT`, so the
model and the database disagreed and drift detection fired on SchemaSync's *own* change. Any model
that predicts Postgres will eventually mispredict. Reading back costs one small query and gives the
database's content with the operation log's identity.

## 8. The snapshot must never silently disagree with the database

**Chose:** three rules, one principle.
- **Drift detection:** before diffing or merging, re-introspect *both* branches and compare hashes.
  A mismatch blocks the merge. (The target check was added late — the plan is `diff(target,
  merged)`, so a stale target yields DDL aimed at a schema that no longer exists.)
- **An escape hatch:** `refresh` re-imports the live schema. A check with no way out traps whoever
  just fixed something by hand. Cost, stated in the API: hand-made renames come back as new IDs.
- **Unsupported objects are recorded, never dropped.** Views, triggers, enums and partitioned tables
  are listed as *unmanaged*. Omitting them would be dangerous: the merge plan would read "missing"
  as "deleted" and drop them.

## 9. Three-way merge, per attribute

**Chose:** merge base = lowest common ancestor in the commit graph; then, for every attribute of
every object: same on both sides → keep; changed on one side → take it; changed identically on
both → agreement; changed differently → conflict. *Ours* = target, *theirs* = source, as in git.

**Rejected:** per-object merging, which would call "renamed on one side, retyped on the other" a
conflict when the two changes do not actually disagree.

**Why:** stable IDs line attributes up the way line numbers do for git. Conflicts carry a severity
(`AUTO`, `SAFE`, `DESTRUCTIVE`, `STRUCTURAL`); identical indexes added under different names are
de-duplicated by fingerprint and reported rather than asked about.

## 10. Pre-flight against the real target before anything runs

**Chose:** before a destructive change, query the actual target table — rows that would fail the
cast (via a small `sv.can_cast` function), `NULL`s that would block `NOT NULL` — and show those
counts as blockers.

**Why:** it closes the gap decision 5 opens. Finding out after the plan runs means a failure 14
million rows into a backfill; finding out first means a sentence before anyone presses Merge.

## 11. The executor: a precise promise, and a strategy chosen by table size

**Promise:** no `ACCESS EXCLUSIVE` lock is *held* for more than milliseconds, and no DDL *waits*
for one for more than a few seconds. Online means non-blocking, not instant.

**Chose:** a pure `SafetyClassifier` (instant / scan / rewrite) and a planner that picks per table:
one plain `ALTER` below 100k rows; above it, **expand → sync trigger → batched backfill → validate →
swap** for rewrites, `CONCURRENTLY` for indexes, `CHECK … NOT VALID` → `VALIDATE` for `NOT NULL`.
All-instant plans run **ATOMIC** — one transaction, all or nothing. Anything else runs **ONLINE** —
staged, and the UI says it is not atomic.

**Two orderings that decide correctness:** the trigger commits *before* the first batch (so every
row is covered by either the trigger or the backfill); the `NOT VALID` check comes *after* the
backfill (it is enforced for new rows, so adding it earlier breaks updates to rows still `NULL`).

**Measured**, 2M rows under continuous read/write load: naive `ALTER` 4.9s with the worst request
stalled **4,894ms**; online 75.3s with the worst request **1,112ms**, zero failures either way. The
naive run's zero errors is the trap — nothing failed, everyone waited. It is a trade (15× the wall
clock for bounded impact), right in business hours and wrong at 3am with the site drained. Also
learned: `now()` is STABLE, not volatile — `DEFAULT now()` does not rewrite; `clock_timestamp()` does.

## 12. Lock safety, and three bugs found by checking the code against its comments

**Chose:** `lock_timeout` plus full-jitter retry, only for `55P03`/`40P01` (lock not available,
deadlock), with a fresh transaction each attempt so a waiting retry never holds `VACUUM` back.
`statement_timeout` as a tripwire: if a "metadata-only" step runs long, it was misclassified, so it
is killed instead of rewriting under a lock.

**Bugs, all fixed with tests:**
- `@Transactional` on methods called through `this` is ignored by Spring's proxy, so the backfill
  batch and its cursor were not committed together. Now a `TransactionTemplate` commits them atomically.
- Session `SET`s leaked into the shared pool: an API request could inherit a 15s statement timeout
  after a migration. Now `SET LOCAL`, or `RESET` where `CONCURRENTLY` forbids a transaction.
  `LockSafeExecutorTest` uses a one-connection pool, so the leak cannot pass by luck.
- The advisory lock could be released from a different pooled connection than took it. It is now
  held on one dedicated connection.

## 13. `main` is read-only, and history is immutable

**Chose:** `main` accepts changes only through a merge. Deleting a branch drops its Postgres
schema but keeps its commits; branch-name uniqueness ignores those tombstones, so a name can be reused.

**Why:** direct edits to `main` would be an unaudited path to production that skips every safety
check. A merged branch's head is the second parent of `main`'s merge commit, so deleting it would
tear the history graph. Postgres refused the delete, and it was right.

## 14. Plain, testable plumbing

**Chose:** `JdbcTemplate` everywhere (DDL cannot go through an ORM, and two persistence styles for
ten tables is not worth it); Testcontainers against real Postgres (every claim here is about
Postgres behaviour, so H2 would prove nothing — pinned to 1.21.4, since older versions send a Docker
API version that Docker 29 rejects); and one production image that serves both the API and the
built UI from one origin, with no CORS and one free-tier service, taking `DATABASE_URL` as given.

## 15. The UI asks one question per object, and never loses input

**Chose:** one dialog edits all of a column's attributes and sends one commit. Dialogs stay open
and show the server's reason when a submit is rejected. Destructive actions need the object's name
typed. Every plan step shows what it blocks, in words.

**Rejected:** a browser `prompt()` per attribute — my first version. It recorded one change as
several commits, and closed before the server answered, so a rejected name lost what you typed.

---

## Cut or deferred

| Item | Status and reason |
| --- | --- |
| Data branching / merging | Cut. A separate product; schema is the stated scope. |
| Free-form SQL editing | Cut. Would undo decision 3. |
| Copy-on-write branches | Deferred. Right at scale; needs the storage layer. |
| Revert of an applied merge | Cut on principle: `DROP COLUMN` cannot be undone, so "undo" would be a lie. |
| Online primary-key retypes, shadow-table rewrites | Deferred. Refused by name instead. |
| **Automatic resume after a crash** | **Designed, not built.** The backfill cursor is durable and each batch is idempotent, but nothing re-claims an interrupted run on restart. |
| **Postgres-backed job queue, separate migration pool** | **Designed, not built.** Runs execute on a small in-process thread pool sharing the API's connection pool. |
| **Streaming progress (SSE)** | **Designed, not built.** The UI polls every 700ms, which is simpler and self-repairing at this size. |
| Auth, multi-tenancy, other engines | Cut. Not what is being evaluated. |
| 5GB in CI | Deferred. Tests use ~200k rows; 2.4M rows is the largest verified run so far. |
