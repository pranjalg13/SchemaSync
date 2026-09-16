package com.schemasync.core.diff;

import com.schemasync.core.model.*;

import java.util.*;

/**
 * Compares two snapshots.
 *
 * <p>A key-set walk at two levels -- tables, then their columns, indexes and constraints -- keyed
 * throughout by <b>stable id</b>. That single choice is what makes the whole thing correct:
 *
 * <pre>
 *   id in both, name differs   -> RENAMED
 *   id in both, type differs   -> TYPE_CHANGED
 *   id only in target          -> ADDED
 *   id only in source          -> DROPPED
 * </pre>
 *
 * <p>A name-keyed diff would report a rename as DROPPED + ADDED, which the executor would then
 * faithfully carry out -- destroying a column of data where the user asked for a free catalog
 * update. There is no heuristic here and no similarity threshold, because there is nothing to
 * guess: the identity was recorded when the rename happened.
 *
 * <p>Pure: no Spring, no JDBC, so the guarantee above is asserted in unit tests without a database.
 */
public final class SchemaDiff {

    private SchemaDiff() {
    }

    /** Changes that turn {@code from} into {@code to}. */
    public static List<SchemaChange> diff(SchemaSnapshot from, SchemaSnapshot to) {
        List<SchemaChange> changes = new ArrayList<>();

        Set<String> allTableIds = new LinkedHashSet<>();
        allTableIds.addAll(from.tables().keySet());
        allTableIds.addAll(to.tables().keySet());

        List<String> ordered = new ArrayList<>(allTableIds);
        ordered.sort(Comparator.comparing(id -> {
            TableDef t = to.tables().get(id);
            if (t == null) {
                t = from.tables().get(id);
            }
            return t == null ? "" : t.name();
        }));

        for (String tableId : ordered) {
            TableDef before = from.tables().get(tableId);
            TableDef after = to.tables().get(tableId);

            if (before == null) {
                changes.add(SchemaChange.of(SchemaChange.Kind.TABLE_ADDED, after.name(), after.name(), tableId));
                // A new table's columns are implied by the table itself; listing each one would
                // bury the signal. Indexes are listed, because they are a separate decision.
                after.indexes().values().stream()
                        .filter(i -> !i.constraintBacked())
                        .forEach(i -> changes.add(SchemaChange.of(
                                SchemaChange.Kind.INDEX_ADDED, after.name(), i.name(), i.id())));
                continue;
            }
            if (after == null) {
                changes.add(SchemaChange.of(SchemaChange.Kind.TABLE_DROPPED, before.name(), before.name(), tableId));
                continue;
            }

            if (!before.name().equals(after.name())) {
                changes.add(SchemaChange.modified(SchemaChange.Kind.TABLE_RENAMED,
                        after.name(), after.name(), tableId, before.name(), after.name()));
            }

            diffColumns(before, after, changes);
            diffIndexes(before, after, changes);
            diffConstraints(before, after, changes);
        }

        return changes;
    }

    private static void diffColumns(TableDef before, TableDef after, List<SchemaChange> changes) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(before.columns().keySet());
        ids.addAll(after.columns().keySet());

        for (String id : ids) {
            ColumnDef b = before.columns().get(id);
            ColumnDef a = after.columns().get(id);

            if (b == null) {
                changes.add(SchemaChange.modified(SchemaChange.Kind.COLUMN_ADDED,
                        after.name(), a.name(), id, null, a.type().sql()));
                continue;
            }
            if (a == null) {
                changes.add(SchemaChange.modified(SchemaChange.Kind.COLUMN_DROPPED,
                        after.name(), b.name(), id, b.type().sql(), null));
                continue;
            }

            // Same id on both sides. Every difference below is an attribute change on ONE column,
            // never a pair of unrelated add/drop events -- and they compose, so a column that was
            // both renamed and retyped reports both.
            if (!b.name().equals(a.name())) {
                changes.add(SchemaChange.modified(SchemaChange.Kind.COLUMN_RENAMED,
                        after.name(), a.name(), id, b.name(), a.name()));
            }
            if (!b.type().sql().equals(a.type().sql())) {
                changes.add(SchemaChange.modified(SchemaChange.Kind.COLUMN_TYPE_CHANGED,
                        after.name(), a.name(), id, b.type().sql(), a.type().sql()));
            }
            if (b.nullable() != a.nullable()) {
                changes.add(SchemaChange.modified(SchemaChange.Kind.COLUMN_NULLABILITY_CHANGED,
                        after.name(), a.name(), id,
                        b.nullable() ? "nullable" : "NOT NULL",
                        a.nullable() ? "nullable" : "NOT NULL"));
            }
            if (!Objects.equals(b.defaultExpr(), a.defaultExpr())) {
                changes.add(SchemaChange.modified(SchemaChange.Kind.COLUMN_DEFAULT_CHANGED,
                        after.name(), a.name(), id, b.defaultExpr(), a.defaultExpr()));
            }
            // position is deliberately not compared: Postgres column order is cosmetic, and
            // diffing it would turn one DROP COLUMN into a reported change on every later column.
        }
    }

    private static void diffIndexes(TableDef before, TableDef after, List<SchemaChange> changes) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(before.indexes().keySet());
        ids.addAll(after.indexes().keySet());

        for (String id : ids) {
            IndexDef b = before.indexes().get(id);
            IndexDef a = after.indexes().get(id);
            // A constraint-backed index is an artefact of its constraint, not an independent
            // object; reporting both would double-count one user decision.
            if ((b != null && b.constraintBacked()) || (a != null && a.constraintBacked())) {
                continue;
            }
            if (b == null) {
                changes.add(SchemaChange.of(SchemaChange.Kind.INDEX_ADDED, after.name(), a.name(), id));
            } else if (a == null) {
                changes.add(SchemaChange.of(SchemaChange.Kind.INDEX_DROPPED, after.name(), b.name(), id));
            } else if (!b.fingerprint().equals(a.fingerprint()) || !b.name().equals(a.name())) {
                changes.add(SchemaChange.modified(SchemaChange.Kind.INDEX_CHANGED,
                        after.name(), a.name(), id, b.name(), a.name()));
            }
        }
    }

    private static void diffConstraints(TableDef before, TableDef after, List<SchemaChange> changes) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(before.constraints().keySet());
        ids.addAll(after.constraints().keySet());

        for (String id : ids) {
            ConstraintDef b = before.constraints().get(id);
            ConstraintDef a = after.constraints().get(id);
            if (b == null) {
                changes.add(SchemaChange.of(SchemaChange.Kind.CONSTRAINT_ADDED, after.name(), a.name(), id));
            } else if (a == null) {
                changes.add(SchemaChange.of(SchemaChange.Kind.CONSTRAINT_DROPPED, after.name(), b.name(), id));
            } else if (!b.fingerprint().equals(a.fingerprint()) || !b.name().equals(a.name())) {
                changes.add(SchemaChange.modified(SchemaChange.Kind.CONSTRAINT_CHANGED,
                        after.name(), a.name(), id, b.name(), a.name()));
            }
        }
    }

    public static boolean isEmpty(SchemaSnapshot from, SchemaSnapshot to) {
        return diff(from, to).isEmpty();
    }
}
