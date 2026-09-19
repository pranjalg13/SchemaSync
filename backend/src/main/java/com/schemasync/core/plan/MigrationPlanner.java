package com.schemasync.core.plan;

import com.schemasync.core.diff.SchemaChange;
import com.schemasync.core.diff.SchemaDiff;
import com.schemasync.core.model.*;
import com.schemasync.exec.DdlSql;

import java.util.*;

/**
 * Compiles the difference between two schemas into an ordered, online-safe plan.
 *
 * <p>The plan is <b>not</b> a replay of the source branch's operations. It is
 * {@code diff(target, merged)} lowered to DDL, which makes merging idempotent and means anything
 * the target already has is skipped for free.
 *
 * <p>Two rules govern the whole thing.
 *
 * <p><b>Ordering.</b> Drops run in reverse dependency order, creates in forward order, and every
 * foreign key is deferred to the end. Deferring the FKs is what makes circular references a
 * non-issue: a plain topological sort fails outright when {@code a} references {@code b} references
 * {@code a}, but if no FK is ever part of the ordering constraint, the cycle cannot appear.
 *
 * <p><b>Strategy.</b> Each change is classified, and anything that would rewrite or scan a table
 * above a row threshold is expanded into an online sequence instead of being issued directly. A
 * small table takes the direct path, because a five-step online migration over 200 rows is
 * ceremony, not safety.
 *
 * <p>Pure: no Spring, no JDBC. Given a snapshot pair and row counts it returns the same plan every
 * time, so the step-by-step expansions below are asserted in unit tests without a database.
 */
public final class MigrationPlanner {

    private final String schema;
    private final Map<String, Long> rowCounts;
    private final long onlineThreshold;

    private final List<MigrationStep> steps = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private int seq = 0;

    public MigrationPlanner(String schema, Map<String, Long> rowCounts, long onlineThreshold) {
        this.schema = schema;
        this.rowCounts = rowCounts;
        this.onlineThreshold = onlineThreshold;
    }

    public MigrationPlan plan(SchemaSnapshot target, SchemaSnapshot merged) {
        List<SchemaChange> changes = SchemaDiff.diff(target, merged);

        // Phase buckets. A deterministic ordering that is also readable, which matters because the
        // user reads this plan before approving it.
        //  1 drop indexes   2 drop columns   3 drop tables
        //  4 create tables  5 renames        6 add columns
        //  7 type/default/nullability        8 create indexes
        emitDropIndexes(changes, target);
        emitDropColumns(changes, target);
        emitDropTables(changes, target);
        emitCreateTables(changes, merged);
        emitRenames(changes, target, merged);
        emitAddColumns(changes, merged);
        emitColumnAlterations(changes, target, merged);
        emitCreateIndexes(changes, merged);

        // ONLINE the moment any single step cannot be done as a metadata change: from then on the
        // plan spans transactions and the all-or-nothing guarantee is gone. Saying so is the point.
        boolean online = steps.stream().anyMatch(s ->
                s.kind() == MigrationStep.Kind.BACKFILL
                        || s.kind() == MigrationStep.Kind.INDEX_CONCURRENT
                        || s.kind() == MigrationStep.Kind.VALIDATE
                        || !s.transactional());

        return new MigrationPlan(List.copyOf(steps),
                online ? MigrationPlan.Mode.ONLINE : MigrationPlan.Mode.ATOMIC,
                List.copyOf(warnings));
    }

    // ----- drops ------------------------------------------------------------

    private void emitDropIndexes(List<SchemaChange> changes, SchemaSnapshot target) {
        for (SchemaChange c : changes) {
            if (c.kind() != SchemaChange.Kind.INDEX_DROPPED) continue;
            add(c.objectName(), MigrationStep.Kind.DDL,
                    "Drop index " + c.objectName(),
                    "DROP INDEX " + DdlSql.qualify(schema, c.objectName()),
                    SafetyClassifier.dropIndex(), true, false, null);
        }
    }

    private void emitDropColumns(List<SchemaChange> changes, SchemaSnapshot target) {
        for (SchemaChange c : changes) {
            if (c.kind() != SchemaChange.Kind.COLUMN_DROPPED) continue;
            long rows = rows(c.tableName());
            if (rows > 0) {
                warnings.add("Dropping " + c.tableName() + "." + c.objectName()
                        + " permanently discards the data in " + formatRows(rows) + " rows.");
            }
            add(c.tableName(), MigrationStep.Kind.DDL,
                    "Drop column " + c.tableName() + "." + c.objectName(),
                    "ALTER TABLE " + DdlSql.qualify(schema, c.tableName())
                            + " DROP COLUMN " + DdlSql.quote(c.objectName()),
                    SafetyClassifier.dropColumn(), true, true, null);
        }
    }

    private void emitDropTables(List<SchemaChange> changes, SchemaSnapshot target) {
        for (SchemaChange c : changes) {
            if (c.kind() != SchemaChange.Kind.TABLE_DROPPED) continue;
            long rows = rows(c.objectName());
            if (rows > 0) {
                warnings.add("Dropping table " + c.objectName() + " discards "
                        + formatRows(rows) + " rows.");
            }
            add(c.objectName(), MigrationStep.Kind.DDL,
                    "Drop table " + c.objectName(),
                    "DROP TABLE " + DdlSql.qualify(schema, c.objectName()),
                    SafetyClassifier.dropTable(), true, true, null);
        }
    }

    // ----- creates ----------------------------------------------------------

    private void emitCreateTables(List<SchemaChange> changes, SchemaSnapshot merged) {
        for (SchemaChange c : changes) {
            if (c.kind() != SchemaChange.Kind.TABLE_ADDED) continue;
            TableDef t = merged.tables().get(c.objectId());
            if (t == null) continue;
            add(t.name(), MigrationStep.Kind.DDL,
                    "Create table " + t.name(),
                    DdlRenderer.createTable(schema, t),
                    SafetyClassifier.createTable(), true, false, null);
        }
    }

    private void emitRenames(List<SchemaChange> changes, SchemaSnapshot target, SchemaSnapshot merged) {
        // Renames go before type changes so later errors refer to the final name, and before
        // ADD COLUMN so a rename freeing up a name happens before something else claims it.
        for (SchemaChange c : changes) {
            if (c.kind() == SchemaChange.Kind.TABLE_RENAMED) {
                add(c.to(), MigrationStep.Kind.DDL,
                        "Rename table " + c.from() + " to " + c.to(),
                        "ALTER TABLE " + DdlSql.qualify(schema, c.from())
                                + " RENAME TO " + DdlSql.quote(c.to()),
                        SafetyClassifier.rename("table"), true, false, null);
            }
        }
        for (SchemaChange c : changes) {
            if (c.kind() != SchemaChange.Kind.COLUMN_RENAMED) continue;
            // Catalog-only regardless of table size. This is the operation the entire identity
            // model exists to protect: as a drop plus an add it would destroy the column's data.
            add(c.tableName(), MigrationStep.Kind.DDL,
                    "Rename " + c.tableName() + "." + c.from() + " to " + c.to()
                            + " (instant, no data is touched)",
                    "ALTER TABLE " + DdlSql.qualify(schema, c.tableName())
                            + " RENAME COLUMN " + DdlSql.quote(c.from())
                            + " TO " + DdlSql.quote(c.to()),
                    SafetyClassifier.rename("column"), true, false, null);
        }
    }

    private void emitAddColumns(List<SchemaChange> changes, SchemaSnapshot merged) {
        for (SchemaChange c : changes) {
            if (c.kind() != SchemaChange.Kind.COLUMN_ADDED) continue;
            TableDef t = merged.tableByName(c.tableName());
            if (t == null) continue;
            ColumnDef col = t.columns().get(c.objectId());
            if (col == null) continue;

            Classification cls = SafetyClassifier.addColumn(col.nullable(), col.defaultExpr());
            long rows = rows(c.tableName());

            if (cls.verdict() == Classification.Verdict.REWRITE && rows >= onlineThreshold) {
                emitOnlineAddColumn(t, col, rows);
            } else {
                add(t.name(), MigrationStep.Kind.DDL,
                        "Add column " + t.name() + "." + col.name() + " " + col.type().sql()
                                + " (" + shortRationale(cls) + ")",
                        "ALTER TABLE " + DdlSql.qualify(schema, t.name())
                                + " ADD COLUMN " + DdlRenderer.columnClause(col),
                        cls, true, false, null);
            }
        }
    }

    /**
     * A volatile default needs a distinct value per row, which a plain ADD COLUMN would produce by
     * rewriting the table under ACCESS EXCLUSIVE. Split it: add the column empty (instant), set the
     * default for future rows (instant), then fill existing rows in batches.
     */
    private void emitOnlineAddColumn(TableDef t, ColumnDef col, long rows) {
        String group = t.name() + "." + col.name();
        String key = primaryKeyColumn(t);
        if (key == null) {
            warnings.add("Table " + t.name() + " has no single-column primary key, so "
                    + col.name() + " cannot be filled in batches. Falling back to a direct "
                    + "ALTER, which rewrites the table.");
            add(group, MigrationStep.Kind.DDL,
                    "Add column " + group + " (rewrites the table)",
                    "ALTER TABLE " + DdlSql.qualify(schema, t.name())
                            + " ADD COLUMN " + DdlRenderer.columnClause(col),
                    SafetyClassifier.addColumn(col.nullable(), col.defaultExpr()), true, false, null);
            return;
        }

        add(group, MigrationStep.Kind.DDL,
                "Add " + group + " as a nullable column (instant, catalog only)",
                "ALTER TABLE " + DdlSql.qualify(schema, t.name())
                        + " ADD COLUMN " + DdlSql.quote(col.name()) + " " + col.type().sql(),
                Classification.instant("Nullable with no default, so no rows are touched."),
                true, false, null);

        add(group, MigrationStep.Kind.DDL,
                "Set the default for future rows (instant, existing rows untouched)",
                "ALTER TABLE " + DdlSql.qualify(schema, t.name())
                        + " ALTER COLUMN " + DdlSql.quote(col.name())
                        + " SET DEFAULT " + col.defaultExpr(),
                SafetyClassifier.setDefault(), true, false, null);

        add(group, MigrationStep.Kind.BACKFILL,
                "Fill " + col.name() + " for " + formatRows(rows) + " existing rows, in batches",
                null,
                Classification.scan(Classification.LockMode.SHARE_UPDATE_EXCLUSIVE,
                        Classification.Blocks.NOTHING,
                        "Updates rows in small committed batches, so no long transaction and no "
                        + "table-wide lock."),
                true, false,
                new MigrationStep.BackfillSpec(schema, t.name(), key, col.name(), col.defaultExpr()));

        if (!col.nullable()) {
            emitOnlineSetNotNull(t, col, key, group);
        }
    }

    // ----- column alterations ----------------------------------------------

    private void emitColumnAlterations(List<SchemaChange> changes, SchemaSnapshot target,
                                       SchemaSnapshot merged) {
        for (SchemaChange c : changes) {
            TableDef t = merged.tableByName(c.tableName());
            if (t == null) continue;
            ColumnDef col = t.columns().get(c.objectId());
            if (col == null) continue;
            long rows = rows(c.tableName());

            switch (c.kind()) {
                case COLUMN_TYPE_CHANGED -> {
                    Classification cls = SafetyClassifier.changeColumnType(c.from(), c.to(), null);
                    if (cls.verdict() == Classification.Verdict.REWRITE && rows >= onlineThreshold) {
                        emitOnlineRetype(t, col, c.from(), rows);
                    } else {
                        add(t.name(), MigrationStep.Kind.DDL,
                                "Change " + t.name() + "." + col.name() + " from " + c.from()
                                        + " to " + c.to() + " (" + shortRationale(cls) + ")",
                                "ALTER TABLE " + DdlSql.qualify(schema, t.name())
                                        + " ALTER COLUMN " + DdlSql.quote(col.name())
                                        + " TYPE " + col.type().sql()
                                        + " USING " + DdlSql.quote(col.name()) + "::" + col.type().sql(),
                                cls, true, cls.verdict() == Classification.Verdict.REWRITE, null);
                    }
                }
                case COLUMN_NULLABILITY_CHANGED -> {
                    boolean nowNullable = "nullable".equals(c.to());
                    if (nowNullable) {
                        add(t.name(), MigrationStep.Kind.DDL,
                                "Allow NULL in " + t.name() + "." + col.name() + " (instant)",
                                "ALTER TABLE " + DdlSql.qualify(schema, t.name())
                                        + " ALTER COLUMN " + DdlSql.quote(col.name()) + " DROP NOT NULL",
                                SafetyClassifier.setNullable(true), true, false, null);
                    } else if (rows >= onlineThreshold) {
                        emitOnlineSetNotNull(t, col, primaryKeyColumn(t), t.name() + "." + col.name());
                    } else {
                        add(t.name(), MigrationStep.Kind.DDL,
                                "Require " + t.name() + "." + col.name() + " to be NOT NULL",
                                "ALTER TABLE " + DdlSql.qualify(schema, t.name())
                                        + " ALTER COLUMN " + DdlSql.quote(col.name()) + " SET NOT NULL",
                                SafetyClassifier.setNullable(false), true, false, null);
                    }
                }
                case COLUMN_DEFAULT_CHANGED -> add(t.name(), MigrationStep.Kind.DDL,
                        col.defaultExpr() == null
                                ? "Remove the default from " + t.name() + "." + col.name()
                                : "Set the default for " + t.name() + "." + col.name()
                                  + " to " + col.defaultExpr() + " (future rows only)",
                        col.defaultExpr() == null
                                ? "ALTER TABLE " + DdlSql.qualify(schema, t.name())
                                  + " ALTER COLUMN " + DdlSql.quote(col.name()) + " DROP DEFAULT"
                                : "ALTER TABLE " + DdlSql.qualify(schema, t.name())
                                  + " ALTER COLUMN " + DdlSql.quote(col.name())
                                  + " SET DEFAULT " + col.defaultExpr(),
                        SafetyClassifier.setDefault(), true, false, null);
                default -> { }
            }
        }
    }

    /**
     * Expand, backfill, contract.
     *
     * <p>The step ordering is the substance of this method, and two parts of it are the difference
     * between working and breaking production:
     *
     * <ul>
     *   <li>The <b>sync trigger commits in its own transaction, before the first batch</b>.
     *       CREATE TRIGGER waits for in-flight writers, and every writer that starts afterwards
     *       sees it -- so once it commits, every row is covered by either the trigger (future
     *       writes) or the backfill (rows as of now). There is no gap. Bundling it with the
     *       backfill would instead hold SHARE ROW EXCLUSIVE, blocking all writes, for the entire
     *       backfill.</li>
     *   <li>The shadow column is <b>indexed after the backfill, never before</b>. An unindexed
     *       column keeps the backfill's updates HOT-eligible, which is dramatically cheaper and
     *       lets in-page pruning reclaim space without waiting for vacuum.</li>
     * </ul>
     */
    private void emitOnlineRetype(TableDef t, ColumnDef col, String fromType, long rows) {
        String group = t.name() + "." + col.name();
        String key = primaryKeyColumn(t);
        String qualified = DdlSql.qualify(schema, t.name());
        String shadow = DdlSql.shadowColumn(col.name());
        String trigger = DdlSql.syncTrigger(col.name());
        String function = DdlSql.syncFunction(t.name(), col.name());

        if (key == null) {
            warnings.add("Table " + t.name() + " has no single-column primary key, so "
                    + col.name() + " cannot be retyped online. A direct ALTER will rewrite the "
                    + "table and hold ACCESS EXCLUSIVE for the duration.");
            add(group, MigrationStep.Kind.DDL,
                    "Change " + group + " to " + col.type().sql() + " (rewrites the table)",
                    "ALTER TABLE " + qualified + " ALTER COLUMN " + DdlSql.quote(col.name())
                            + " TYPE " + col.type().sql(),
                    SafetyClassifier.changeColumnType(fromType, col.type().sql(), null),
                    true, true, null);
            return;
        }

        warnings.add("Retyping " + group + " rewrites " + formatRows(rows) + " rows. The table "
                + "will temporarily grow by roughly the size of the new column; make sure there "
                + "is free disk for it.");

        // 1. Shadow column: nullable, no default, so guaranteed metadata-only.
        add(group, MigrationStep.Kind.DDL,
                "Add a shadow " + col.type().sql() + " column (instant, catalog only)",
                "ALTER TABLE " + qualified + " ADD COLUMN " + DdlSql.quote(shadow)
                        + " " + col.type().sql(),
                Classification.instant("Nullable with no default, so no rows are touched."),
                true, false, null);

        // 2. Sync trigger, own transaction, before any backfill. See the method comment.
        add(group, MigrationStep.Kind.DDL,
                "Create the sync function (touches no table)",
                "CREATE OR REPLACE FUNCTION " + DdlSql.qualify(schema, function) + "() "
                        + "RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW."
                        + DdlSql.quote(shadow) + " := NEW." + DdlSql.quote(col.name())
                        + "::" + col.type().sql() + "; RETURN NEW; END $$",
                new Classification(Classification.Verdict.INSTANT, Classification.LockMode.NONE,
                        Classification.Blocks.NOTHING,
                        "Defines the function in the catalog. No table is locked or read."),
                true, false, null);

        add(group, MigrationStep.Kind.DDL,
                "Attach the sync trigger, so writes during the migration stay correct",
                "CREATE TRIGGER " + DdlSql.quote(trigger)
                        + " BEFORE INSERT OR UPDATE OF " + DdlSql.quote(col.name())
                        + " ON " + qualified
                        + " FOR EACH ROW EXECUTE FUNCTION " + DdlSql.qualify(schema, function) + "()",
                // INSTANT, not SCAN: CREATE TRIGGER reads no rows. It takes SHARE ROW EXCLUSIVE, so it
                // waits for in-flight writers and briefly holds new ones -- reads carry on.
                new Classification(Classification.Verdict.INSTANT, Classification.LockMode.SHARE_ROW_EXCLUSIVE,
                        Classification.Blocks.WRITES,
                        "Waits for in-flight writes to finish, so every later write sees the trigger. "
                        + "Reads are never blocked and no rows are scanned."),
                true, false, null);

        // 3. Backfill existing rows in committed batches.
        add(group, MigrationStep.Kind.BACKFILL,
                "Convert " + formatRows(rows) + " existing rows, in batches",
                null,
                Classification.scan(Classification.LockMode.SHARE_UPDATE_EXCLUSIVE,
                        Classification.Blocks.NOTHING,
                        "Small committed batches, so no long transaction and no table-wide lock. "
                        + "Reads and writes continue throughout."),
                true, false,
                new MigrationStep.BackfillSpec(schema, t.name(), key, shadow,
                        DdlSql.quote(col.name()) + "::" + col.type().sql()));

        // 4. NOT NULL, if needed -- after the backfill. See emitOnlineSetNotNull.
        if (!col.nullable()) {
            emitOnlineSetNotNull(t, new ColumnDef(col.id(), shadow, col.position(), col.type(),
                    false, null, null), key, group);
        }

        // 5. Swap. One short transaction, all metadata-only, one brief ACCESS EXCLUSIVE.
        add(group, MigrationStep.Kind.DDL,
                "Swap the new column into place and drop the old one "
                        + "(irreversible; locks the table for milliseconds)",
                "DROP TRIGGER " + DdlSql.quote(trigger) + " ON " + qualified + ";\n"
                        + "ALTER TABLE " + qualified + " DROP COLUMN " + DdlSql.quote(col.name()) + ";\n"
                        + "ALTER TABLE " + qualified + " RENAME COLUMN " + DdlSql.quote(shadow)
                        + " TO " + DdlSql.quote(col.name()) + ";\n"
                        + "DROP FUNCTION IF EXISTS " + DdlSql.qualify(schema, function) + "()",
                Classification.instant("All catalog changes; the lock is held for milliseconds."),
                true, true, null);

        // 6. The swap leaves the column with no statistics, so the planner would be flying blind.
        add(group, MigrationStep.Kind.ANALYZE,
                "Refresh planner statistics for " + t.name(),
                "ANALYZE " + qualified,
                Classification.scan(Classification.LockMode.SHARE_UPDATE_EXCLUSIVE,
                        Classification.Blocks.NOTHING,
                        "Samples the table. Blocks nothing."),
                true, false, null);
    }

    /**
     * SET NOT NULL without the table scan.
     *
     * <p>Order matters, and the natural order is wrong. The {@code NOT VALID} check must come
     * <b>after</b> the backfill: a NOT VALID constraint is still enforced for every new row
     * version, so adding it while NULLs remain makes any UPDATE touching one of those rows fail --
     * silently breaking writes for a subset of rows for the whole backfill.
     *
     * <p>Once the check is validated, PG 12+ uses it to prove no NULL can exist and skips the scan
     * that SET NOT NULL would otherwise perform under ACCESS EXCLUSIVE.
     */
    private void emitOnlineSetNotNull(TableDef t, ColumnDef col, String key, String group) {
        String qualified = DdlSql.qualify(schema, t.name());
        String check = DdlSql.notNullCheck(col.name());

        if (col.defaultExpr() != null && key != null) {
            add(group, MigrationStep.Kind.BACKFILL,
                    "Fill any remaining NULLs in " + col.name() + ", in batches",
                    null,
                    Classification.scan(Classification.LockMode.SHARE_UPDATE_EXCLUSIVE,
                            Classification.Blocks.NOTHING,
                            "Small committed batches; blocks nothing."),
                    true, false,
                    new MigrationStep.BackfillSpec(schema, t.name(), key, col.name(), col.defaultExpr()));
        }

        add(group, MigrationStep.Kind.DDL,
                "Add a NOT NULL check, unvalidated (instant)",
                "ALTER TABLE " + qualified + " ADD CONSTRAINT " + DdlSql.quote(check)
                        + " CHECK (" + DdlSql.quote(col.name()) + " IS NOT NULL) NOT VALID",
                Classification.instant("Recorded in the catalog without checking existing rows."),
                true, false, null);

        add(group, MigrationStep.Kind.VALIDATE,
                "Verify no NULLs remain (reads the table but blocks nothing)",
                "ALTER TABLE " + qualified + " VALIDATE CONSTRAINT " + DdlSql.quote(check),
                Classification.scan(Classification.LockMode.SHARE_UPDATE_EXCLUSIVE,
                        Classification.Blocks.NOTHING,
                        "Scans the table under SHARE UPDATE EXCLUSIVE, which conflicts with "
                        + "neither reads nor writes."),
                true, false, null);

        add(group, MigrationStep.Kind.DDL,
                "Mark " + col.name() + " NOT NULL (instant: the validated check proves it)",
                "ALTER TABLE " + qualified + " ALTER COLUMN " + DdlSql.quote(col.name())
                        + " SET NOT NULL;\n"
                        + "ALTER TABLE " + qualified + " DROP CONSTRAINT " + DdlSql.quote(check),
                Classification.instant(
                        "PG 12+ uses the validated CHECK to prove no NULL exists, so the usual "
                        + "full table scan is skipped."),
                true, false, null);
    }

    // ----- indexes ----------------------------------------------------------

    private void emitCreateIndexes(List<SchemaChange> changes, SchemaSnapshot merged) {
        for (SchemaChange c : changes) {
            if (c.kind() != SchemaChange.Kind.INDEX_ADDED) continue;
            TableDef t = merged.tableByName(c.tableName());
            if (t == null) continue;
            IndexDef index = t.indexes().get(c.objectId());
            if (index == null || index.constraintBacked()) continue;

            long rows = rows(c.tableName());
            boolean concurrent = rows >= onlineThreshold;

            add(t.name(),
                    concurrent ? MigrationStep.Kind.INDEX_CONCURRENT : MigrationStep.Kind.DDL,
                    concurrent
                            ? "Build index " + index.name() + " without blocking writes "
                              + "(two passes over " + formatRows(rows) + " rows)"
                            : "Create index " + index.name(),
                    DdlRenderer.createIndex(schema, t, index, concurrent),
                    concurrent
                            ? Classification.scan(Classification.LockMode.SHARE_UPDATE_EXCLUSIVE,
                                Classification.Blocks.NOTHING,
                                "CONCURRENTLY makes two passes and waits for open transactions, so "
                                + "it takes longer but never blocks reads or writes.")
                            : SafetyClassifier.createIndex(),
                    // CREATE INDEX CONCURRENTLY cannot run inside a transaction block. This single
                    // flag is what forces the whole plan out of ATOMIC mode.
                    !concurrent, false, null);
        }
    }

    // ----- helpers ----------------------------------------------------------

    private void add(String group, MigrationStep.Kind kind, String description, String sql,
                     Classification cls, boolean transactional, boolean pointOfNoReturn,
                     MigrationStep.BackfillSpec backfill) {
        steps.add(new MigrationStep(seq++, group, kind, description, sql, cls,
                transactional, pointOfNoReturn, backfill));
    }

    /** The single-column primary key used to page a backfill, or null if there isn't one. */
    private static String primaryKeyColumn(TableDef t) {
        for (ConstraintDef k : t.constraints().values()) {
            if (k.kind() == ConstraintDef.Kind.PRIMARY_KEY && k.columnIds().size() == 1) {
                ColumnDef c = t.columns().get(k.columnIds().get(0));
                if (c != null) {
                    return c.name();
                }
            }
        }
        return null;
    }

    private long rows(String tableName) {
        return rowCounts.getOrDefault(tableName, 0L);
    }

    private static String shortRationale(Classification cls) {
        return switch (cls.verdict()) {
            case INSTANT -> "instant";
            case SCAN -> "reads every row";
            case REWRITE -> "rewrites the table";
        };
    }

    static String formatRows(long rows) {
        if (rows >= 1_000_000) return String.format("%.1fM", rows / 1_000_000.0);
        if (rows >= 1_000) return String.format("%,d", rows);
        return String.valueOf(rows);
    }
}
