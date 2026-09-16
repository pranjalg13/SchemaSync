# SchemaSync

Version control for your Postgres schema — branch it, evolve it, see exactly what diverged, and
merge it back. Every change is applied to a real database, and merges into large tables run online:
reads never block, writes block for milliseconds.

> **The promise, stated precisely.** No `ACCESS EXCLUSIVE` lock is *held* for more than a few
> milliseconds, and no DDL statement *waits* for a lock for more than a few seconds. "Online" does
> not mean "instant" — a large backfill still takes minutes. It means your application keeps
> serving traffic throughout.

---

## Quick start

Requires Docker (or OrbStack) and nothing else.

```bash
git clone <this repo> && cd SchemaSync
docker compose up
```

Then open **http://localhost:5173**. The stack seeds itself with a demo e-commerce schema, so
there is nothing to configure before you can branch something.

To work on the backend directly instead:

```bash
docker compose up -d db          # Postgres only
./scripts/seed.sh                # ~3.5M orders, ≈500MB
cd backend && mvn spring-boot:run
```

### Seeding more (or less) data

```bash
./scripts/seed.sh 100000         # ≈15MB   — fast iteration
./scripts/seed.sh 3500000        # ≈500MB  — the development default
./scripts/seed.sh 35000000       # ≈5GB    — the full-scale validation run
```

Rows are generated server-side with `generate_series`, so seeding 5GB does not stream gigabytes
through a client connection.

---

## What it looks like

The merge screen is where the product's point lands: every step carries what it costs and what it
blocks, in plain English, *before* anything runs.

```
shipping_fields → main            online — staged, not atomic

Plan — 4 step(s)
 1  Rename orders.status to order_status (instant, no data is touched)
    [instant] [blocks reads + writes]
    Renames the column in the catalog. No data is read or written -- this is why a
    rename must never be applied as a drop plus an add.
 2  Add column orders.shipped_at timestamptz (instant)
    [instant] [blocks reads + writes]
    Adds a nullable column. Catalog change only, no rows are touched.
 4  Build index orders_shipped_idx without blocking writes (two passes over 1.9M rows)
    [reads every row] [blocks nothing]
    CONCURRENTLY makes two passes and waits for open transactions, so it takes longer
    but never blocks reads or writes.
```

## What it does

- **Branch** a schema. A branch is a real Postgres schema (namespace) containing the same tables,
  seeded with a bounded row sample. Branch creation is `O(schema)`, not `O(data)` — it takes about
  the same time whether `main` holds 5MB or 5GB.
- **Evolve** it through a fixed palette of nine operations: add / drop / rename column, change type,
  toggle nullable, set-drop default, create / drop table, add-drop index. Each one is applied to the
  branch's real schema immediately.
- **Diff** against the branch point. A rename shows as a *rename* — never as a drop plus an add,
  which is the difference between a free catalog update and losing a column of production data.
- **Merge** back, with three-way conflict detection, a preflight that tells you what each step will
  cost before anything runs, and an executor that applies it online.

## Running the tests

```bash
cd backend
mvn verify                 # unit + integration (Testcontainers spins up Postgres)
mvn verify -Pscale         # adds the slow concurrent-workload test
```

There is also an end-to-end walkthrough that drives the real HTTP API against a real database and
asserts on what actually landed in Postgres:

```bash
docker compose up -d db && ./scripts/seed.sh 100000
cd backend && mvn spring-boot:run     # in another shell
./scripts/e2e.sh
```

It resets to a known state first, then checks the things that would matter if they broke: that
branching a 100k-row table stays under a second, that sampled child rows never reference a missing
parent, that a rename reaches Postgres as `ALTER ... RENAME` and shows in the diff as a rename
rather than a drop plus an add, that `main` stays untouched until the merge, that the merge actually
lands on `main` with the renamed column's data intact, and that DDL run outside SchemaSync is caught
as drift.

### Seeing the zero-downtime claim hold up

```bash
pip3 install psycopg2-binary requests
./scripts/zero_downtime_demo.py            # branch, retype, merge -- the online path
./scripts/zero_downtime_demo.py --naive    # the same change as one ALTER TABLE
```

Both run a continuous read/write workload against `main.orders` while the migration happens
underneath. Measured on 2,000,000 rows (354MB):

| | Naive `ALTER TABLE` | SchemaSync online |
| --- | --- | --- |
| Wall clock | **4.9s** | 75.3s |
| Requests served | 2,525 | 27,838 |
| Failed requests | 0 | 0 |
| **Worst single request** | **4,894 ms** | **1,112 ms** |

Note that the naive run also reports zero errors. That is the trap: nothing failed, it just made
every request during those five seconds hang. The online path is 15× slower in wall clock and that
is the trade being made — bounded impact during business hours, not speed.

The tests worth looking at first:

| Test | What it actually catches |
| --- | --- |
| `SafetyClassifierTest` | Every row of the operation-classification table, with no database. `ADD COLUMN DEFAULT 0` is instant; `DEFAULT now()` rewrites the table. |
| `RenameIntegrityTest` | A rename-then-merge emits `ALTER ... RENAME` and **zero** `DROP COLUMN`, asserted against the executed statements. This guards the core claim. |
| `MergeAlgebraTest` | `merge(base, x, x) == x`, `merge(base, x, base) == x`, and symmetry. Catches asymmetry bugs immediately. |
| `RoundTripPropertyTest` | Materialise A, apply `diff(A, B)`, re-introspect — the canonical hash must equal B's. One property covering differ, planner, orderer and SQL renderer. |
| `VolatilityContractTest` | Proves the classification against Postgres by comparing `relfilenode` before and after: `DEFAULT now()` must not rewrite the table, `DEFAULT gen_random_uuid()` must. |
| `MigrationPlannerTest` | The same retype produces 1 step on a small table and a 9-step online plan on a large one, with the sync trigger before the backfill and the swap after it. |

---

## Known limitations

Stated up front rather than discovered.

- **Supported objects:** tables, columns (type / nullability / default / identity), primary keys,
  unique / check / foreign-key constraints, and btree indexes including unique, multi-column and
  partial. Constraints are carried faithfully through branch and merge but are not editable in v1.
- **Unsupported objects** — views, materialised views, triggers, functions, partitioned tables,
  enums, domains, RLS policies — are **detected** and the schema is marked *partially managed*.
  They are never silently dropped from a snapshot.
- **Object identity depends on changes going through SchemaSync.** DDL run directly against a branch
  schema breaks the rename-tracking guarantee. Drift detection catches this and blocks the merge
  rather than letting it corrupt silently.
- **Merge requires a common ancestor.** Two independently imported databases share no object
  identity, so merging between them is undefined.
- **Zero-downtime is not zero-impact.** A backfill writes several GB of WAL, temporarily grows the
  table by up to 2×, and will increase replica lag. SchemaSync throttles and reports this; it does
  not eliminate it. Check you have ~2× the table size free before a large retype.
- **No authentication.** There is an author name field and no login. Do not point this at a
  production database you care about.

---

## How it works

See **[decisions.md](decisions.md)** for the reasoning behind each choice — it is the honest
version, including what was rejected and why.

Architecture in one paragraph: the control plane (schema `sv`) lives in the *same* database as the
managed branch schemas, so a merge's DDL and its metadata commit can share one transaction. Each
commit stores a full canonicalised schema snapshot as JSONB, keyed by stable object IDs that
survive renames — which makes diff and three-way merge pure functions over immutable documents,
testable without a database. Merging computes `diff(target, merged)`, lowers it to a dependency-
ordered plan, classifies every step as instant / scan / rewrite, and executes it either atomically
(one transaction, when everything is metadata-only) or online (staged, resumable, with batched
backfill) when something needs to touch every row.

```
backend/src/main/java/com/schemasync/
  core/model    immutable snapshots, canonicalisation, content hashing
  core/diff     two-way and three-way diff, rename-aware
  core/merge    merge base, attribute-level merge, conflict taxonomy
  core/plan     safety classification, plan compilation, dependency ordering
  catalog       pg_catalog introspection
  exec          lock-safe DDL, batched backfill, concurrent index builds
  api           REST + SSE progress
```

`core/*` has no Spring and no JDBC on purpose: the entire diff, merge and planning engine runs in
plain unit tests with no database.
