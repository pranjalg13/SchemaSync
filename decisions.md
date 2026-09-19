# Decisions

---

## 1. Commit stored as Jsonb

**Chose:** each commit stores the whole canonical schema as JSONB, deduplicated by SHA-256.

**Rejected:** *replaying an operation log*.

## 2. Stable IDs

**Chose:** every table, column, index and constraint gets an ID when first seen, carried unchanged through renames. Diff matches objects by ID.

**Rejected:** guessing renames from similarity (type, position, name distance).

## 3. Closed set operations

**Chose:** ten typed operations behind the UI (add / drop / rename column, change type, nullability, default, create / drop table, add / drop index). Constraints are shown and carried through merges
but not editable.

**Rejected:** accepting typed DDL and parsing it.

## 4. A branch is a Postgres schema with sampled rows

**Chose:** `CREATE SCHEMA br_…`, the same tables, and up to 1,000 rows per table, copied parent-first so every sampled child row's foreign key still resolves.

**Rejected:** Copying entire db data can take a measurable latency.

## 5. Canonicalise everything that Postgres rewrites

**Chose:** normalise every introspected type and default before hashing (`character varying(32)`
→ `varchar(32)`, `'pending'::character varying` → `'pending'`, `serial` → `integer` + identity).

**Why:** without it, re-reading an unchanged schema reports every column as modified and content
hashes mean nothing. The alias table is the spec and is tested row by row.

## 6. The recorded snapshot is read back from the database

**Chose:** after each branch operation, re-introspect the live schema and store that, using the in-memory result only as the source of IDs.

**Rejected:** trusting the in-memory mutation, my first version.

## 7. The snapshot must never silently disagree with the database

**Chose:** three rules, one principle.
- **Drift detection:** before diffing or merging, re-introspect *both* branches and compare hashes.
  A mismatch blocks the merge. (The target check was added late — the plan is `diff(target,
  merged)`, so a stale target yields DDL aimed at a schema that no longer exists.)
- **An escape hatch:** `refresh` re-imports the live schema. A check with no way out traps whoever
  just fixed something by hand. Cost, stated in the API: hand-made renames come back as new IDs.
- **Unsupported objects are recorded, never dropped.** Views, triggers, enums and partitioned tables
  are listed as *unmanaged*. Omitting them would be dangerous: the merge plan would read "missing"
  as "deleted" and drop them.

## 8. Three-way merge, per attribute

**Chose:** merge base = lowest common ancestor in the commit graph; then, for every attribute of
every object: same on both sides → keep; changed on one side → take it; changed identically on
both → agreement; changed differently → conflict. *Ours* = target, *theirs* = source, as in git.

**Rejected:** per-object merging, which would call "renamed on one side, retyped on the other" a
conflict when the two changes do not actually disagree.

## 9. Pre-flight against the real target before anything runs

**Chose:** before a destructive change, query the actual target table — rows that would fail the
cast (via a small `sv.can_cast` function), `NULL`s that would block `NOT NULL` — and show those
counts as blockers.

## 10. The executor

**Choose:** no `ACCESS EXCLUSIVE` lock is *held* for more than milliseconds, and no DDL *waits*
for one for more than a few seconds.

## 11. `main` is read-only, and history is immutable

**Chose:** `main` accepts changes only through a merge. Deleting a branch drops its Postgres
schema but keeps its commits; branch-name uniqueness ignores those tombstones, so a name can be reused.

**Why:** direct edits to `main` would be an unaudited path to production that skips every safety
check. A merged branch's head is the second parent of `main`'s merge commit, so deleting it would
tear the history graph. Postgres refused the delete, and it was right.