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

The tests worth looking at first:

| Test | What it actually catches |
| --- | --- |
| `SafetyClassifierTest` | Every row of the operation-classification table, with no database. `ADD COLUMN DEFAULT 0` is instant; `DEFAULT now()` rewrites the table. |
| `RenameIntegrityTest` | A rename-then-merge emits `ALTER ... RENAME` and **zero** `DROP COLUMN`, asserted against the executed statements. This guards the core claim. |
| `MergeAlgebraTest` | `merge(base, x, x) == x`, `merge(base, x, base) == x`, and symmetry. Catches asymmetry bugs immediately. |
| `RoundTripPropertyTest` | Materialise A, apply `diff(A, B)`, re-introspect — the canonical hash must equal B's. One property covering differ, planner, orderer and SQL renderer. |
| `BackfillResumeTest` | Kill the runner mid-backfill; it resumes from its cursor rather than restarting. |

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
