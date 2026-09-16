package com.schemasync.ops;

import com.schemasync.core.model.*;
import com.schemasync.core.plan.DdlRenderer;
import com.schemasync.exec.DdlSql;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders one operation as the SQL to apply it to a branch schema.
 *
 * <p>This is the <em>branch</em> path, where tables hold a bounded sample and a direct
 * {@code ALTER} is cheap and safe. The merge path into a large table is deliberately different:
 * there the same logical operation is compiled by the migration planner into an online sequence
 * (shadow column, sync trigger, batched backfill, swap). Same intent, two strategies, chosen by
 * how much data is actually underneath.
 *
 * @param before the snapshot as it was BEFORE the operation, used to resolve stable ids to the
 *               names the database currently knows
 */
public final class OperationSqlRenderer {

    private OperationSqlRenderer() {
    }

    public static List<String> render(String schema, SchemaSnapshot before, SchemaOperation op) {
        List<String> sql = new ArrayList<>();

        switch (op) {
            case SchemaOperation.AddColumn a -> {
                TableDef t = table(before, a.tableId());
                StringBuilder sb = new StringBuilder("ALTER TABLE ")
                        .append(DdlSql.qualify(schema, t.name()))
                        .append(" ADD COLUMN ").append(DdlSql.quote(a.name()))
                        .append(' ').append(TypeCanonicalizer.parse(a.type()).sql());
                if (a.defaultExpr() != null) {
                    sb.append(" DEFAULT ").append(a.defaultExpr());
                }
                if (!a.nullable()) {
                    sb.append(" NOT NULL");
                }
                sql.add(sb.toString());
            }

            case SchemaOperation.DropColumn d -> {
                TableDef t = table(before, d.tableId());
                sql.add("ALTER TABLE " + DdlSql.qualify(schema, t.name())
                        + " DROP COLUMN " + DdlSql.quote(column(t, d.columnId()).name()));
            }

            case SchemaOperation.RenameColumn r -> {
                TableDef t = table(before, r.tableId());
                sql.add("ALTER TABLE " + DdlSql.qualify(schema, t.name())
                        + " RENAME COLUMN " + DdlSql.quote(column(t, r.columnId()).name())
                        + " TO " + DdlSql.quote(r.newName()));
            }

            case SchemaOperation.ChangeColumnType ct -> {
                TableDef t = table(before, ct.tableId());
                ColumnDef c = column(t, ct.columnId());
                DataType target = TypeCanonicalizer.parse(ct.newType());
                StringBuilder sb = new StringBuilder("ALTER TABLE ")
                        .append(DdlSql.qualify(schema, t.name()))
                        .append(" ALTER COLUMN ").append(DdlSql.quote(c.name()))
                        .append(" TYPE ").append(target.sql());
                // Postgres only applies an implicit cast when one exists; anything else needs
                // USING. Defaulting to a plain cast of the column keeps the common cases working
                // without making the caller spell it out.
                sb.append(" USING ").append(ct.usingExpr() != null
                        ? ct.usingExpr()
                        : DdlSql.quote(c.name()) + "::" + target.sql());
                sql.add(sb.toString());

                // Postgres does not rewrite the column's DEFAULT when the column is retyped: after
                // ALTER COLUMN status TYPE text, the default is still 'pending'::character varying.
                // That stale cast is harmless to Postgres but shows up as a spurious
                // COLUMN_DEFAULT_CHANGED in every later diff, because the cast no longer matches
                // the column type and so cannot be canonicalised away. Re-stating the default
                // lets Postgres re-cast it to the new type and keeps the diff honest.
                if (c.defaultExpr() != null && c.identity() == null) {
                    sql.add("ALTER TABLE " + DdlSql.qualify(schema, t.name())
                            + " ALTER COLUMN " + DdlSql.quote(c.name())
                            + " SET DEFAULT " + c.defaultExpr());
                }
            }

            case SchemaOperation.SetColumnNullable sn -> {
                TableDef t = table(before, sn.tableId());
                ColumnDef c = column(t, sn.columnId());
                String qualified = DdlSql.qualify(schema, t.name());
                if (sn.nullable()) {
                    sql.add("ALTER TABLE " + qualified + " ALTER COLUMN "
                            + DdlSql.quote(c.name()) + " DROP NOT NULL");
                } else {
                    // Existing NULLs would make SET NOT NULL fail outright. On a branch the data
                    // is a small sample, so filling in place is fine; the merge path handles this
                    // with a batched backfill instead.
                    if (sn.fillValue() != null) {
                        sql.add("UPDATE " + qualified + " SET " + DdlSql.quote(c.name())
                                + " = " + sn.fillValue() + " WHERE " + DdlSql.quote(c.name()) + " IS NULL");
                    }
                    sql.add("ALTER TABLE " + qualified + " ALTER COLUMN "
                            + DdlSql.quote(c.name()) + " SET NOT NULL");
                }
            }

            case SchemaOperation.SetColumnDefault sd -> {
                TableDef t = table(before, sd.tableId());
                ColumnDef c = column(t, sd.columnId());
                String qualified = DdlSql.qualify(schema, t.name());
                sql.add(sd.defaultExpr() == null
                        ? "ALTER TABLE " + qualified + " ALTER COLUMN " + DdlSql.quote(c.name()) + " DROP DEFAULT"
                        : "ALTER TABLE " + qualified + " ALTER COLUMN " + DdlSql.quote(c.name())
                                + " SET DEFAULT " + sd.defaultExpr());
            }

            case SchemaOperation.CreateTable ct -> {
                List<String> parts = new ArrayList<>();
                List<String> pk = new ArrayList<>();
                for (SchemaOperation.CreateTable.NewColumn nc : ct.columns()) {
                    StringBuilder sb = new StringBuilder(DdlSql.quote(nc.name()))
                            .append(' ').append(TypeCanonicalizer.parse(nc.type()).sql());
                    if (nc.defaultExpr() != null) {
                        sb.append(" DEFAULT ").append(nc.defaultExpr());
                    }
                    if (!nc.nullable() || nc.primaryKey()) {
                        sb.append(" NOT NULL");
                    }
                    parts.add("    " + sb);
                    if (nc.primaryKey()) {
                        pk.add(DdlSql.quote(nc.name()));
                    }
                }
                if (!pk.isEmpty()) {
                    parts.add("    CONSTRAINT " + DdlSql.quote(ct.name() + "_pkey")
                            + " PRIMARY KEY (" + String.join(", ", pk) + ")");
                }
                sql.add("CREATE TABLE " + DdlSql.qualify(schema, ct.name())
                        + " (\n" + String.join(",\n", parts) + "\n)");
            }

            case SchemaOperation.DropTable dt ->
                    sql.add("DROP TABLE " + DdlSql.qualify(schema, table(before, dt.tableId()).name()));

            case SchemaOperation.AddIndex ai -> {
                TableDef t = table(before, ai.tableId());
                List<IndexDef.IndexColumn> cols = ai.columnIds().stream()
                        .map(id -> new IndexDef.IndexColumn(id, "ASC", "LAST"))
                        .toList();
                IndexDef index = new IndexDef("pending", ai.name(),
                        ai.method() == null ? "btree" : ai.method(), ai.unique(), cols, null, false);
                sql.add(DdlRenderer.createIndex(schema, t, index));
            }

            case SchemaOperation.DropIndex di -> {
                TableDef t = table(before, di.tableId());
                IndexDef index = t.indexes().get(di.indexId());
                if (index == null) {
                    throw new SnapshotMutator.InvalidOperationException("no such index");
                }
                sql.add("DROP INDEX " + DdlSql.qualify(schema, index.name()));
            }
        }
        return sql;
    }

    private static TableDef table(SchemaSnapshot snapshot, String tableId) {
        TableDef t = snapshot.tables().get(tableId);
        if (t == null) {
            throw new SnapshotMutator.InvalidOperationException("no such table: " + tableId);
        }
        return t;
    }

    private static ColumnDef column(TableDef table, String columnId) {
        ColumnDef c = table.columns().get(columnId);
        if (c == null) {
            throw new SnapshotMutator.InvalidOperationException(
                    "no such column on '" + table.name() + "': " + columnId);
        }
        return c;
    }
}
