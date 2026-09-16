package com.schemasync.ops;

import com.schemasync.core.model.*;

import java.util.*;

/**
 * Applies an operation to a snapshot, in memory.
 *
 * <p>Pure: no Spring, no JDBC, no side effects. That matters because this is the half of an
 * operation that defines what the schema now <em>is</em>, and being able to test it without a
 * database means the rename-preserves-identity guarantee is asserted in milliseconds rather than
 * behind a container.
 *
 * <p>The counterpart {@link OperationService} applies the same operation to the real Postgres
 * schema. Keeping the two in lockstep is what makes the snapshot an accurate description of the
 * database rather than an aspiration.
 */
public final class SnapshotMutator {

    private SnapshotMutator() {
    }

    public static SchemaSnapshot apply(SchemaSnapshot snapshot, SchemaOperation op) {
        Map<String, TableDef> tables = new LinkedHashMap<>(snapshot.tables());

        switch (op) {
            case SchemaOperation.AddColumn a -> {
                TableDef t = require(tables, a.tableId());
                requireNoColumnNamed(t, a.name());
                Map<String, ColumnDef> columns = new LinkedHashMap<>(t.columns());
                String id = StableId.mint(StableId.COLUMN);
                int position = columns.values().stream()
                        .mapToInt(ColumnDef::position).max().orElse(0) + 1;
                columns.put(id, new ColumnDef(id, a.name(), position,
                        TypeCanonicalizer.parse(a.type()), a.nullable(), a.defaultExpr(), null));
                tables.put(t.id(), t.withColumns(columns));
            }

            case SchemaOperation.DropColumn d -> {
                TableDef t = require(tables, d.tableId());
                requireColumn(t, d.columnId());
                Map<String, ColumnDef> columns = new LinkedHashMap<>(t.columns());
                columns.remove(d.columnId());

                // Dropping a column takes its dependent indexes and constraints with it. Postgres
                // does this implicitly; the snapshot has to mirror it or the two diverge.
                Map<String, IndexDef> indexes = new LinkedHashMap<>(t.indexes());
                indexes.values().removeIf(i -> i.columns().stream()
                        .anyMatch(c -> c.columnId().equals(d.columnId())));
                Map<String, ConstraintDef> constraints = new LinkedHashMap<>(t.constraints());
                constraints.values().removeIf(c -> c.columnIds().contains(d.columnId()));

                tables.put(t.id(), t.withColumns(columns).withIndexes(indexes).withConstraints(constraints));
            }

            case SchemaOperation.RenameColumn r -> {
                TableDef t = require(tables, r.tableId());
                ColumnDef c = requireColumn(t, r.columnId());
                requireNoColumnNamed(t, r.newName());
                Map<String, ColumnDef> columns = new LinkedHashMap<>(t.columns());
                // The whole point: same key, new name. Identity is untouched, so a later diff
                // reports a rename rather than a drop plus an add.
                columns.put(r.columnId(), c.withName(r.newName()));
                tables.put(t.id(), t.withColumns(columns));
            }

            case SchemaOperation.ChangeColumnType ct -> {
                TableDef t = require(tables, ct.tableId());
                ColumnDef c = requireColumn(t, ct.columnId());
                Map<String, ColumnDef> columns = new LinkedHashMap<>(t.columns());
                columns.put(ct.columnId(), c.withType(TypeCanonicalizer.parse(ct.newType())));
                tables.put(t.id(), t.withColumns(columns));
            }

            case SchemaOperation.SetColumnNullable sn -> {
                TableDef t = require(tables, sn.tableId());
                ColumnDef c = requireColumn(t, sn.columnId());
                Map<String, ColumnDef> columns = new LinkedHashMap<>(t.columns());
                columns.put(sn.columnId(), c.withNullable(sn.nullable()));
                tables.put(t.id(), t.withColumns(columns));
            }

            case SchemaOperation.SetColumnDefault sd -> {
                TableDef t = require(tables, sd.tableId());
                ColumnDef c = requireColumn(t, sd.columnId());
                Map<String, ColumnDef> columns = new LinkedHashMap<>(t.columns());
                columns.put(sd.columnId(), c.withDefault(sd.defaultExpr()));
                tables.put(t.id(), t.withColumns(columns));
            }

            case SchemaOperation.CreateTable ctab -> {
                if (snapshot.tableByName(ctab.name()) != null) {
                    throw new InvalidOperationException("a table named '" + ctab.name() + "' already exists");
                }
                if (ctab.columns() == null || ctab.columns().isEmpty()) {
                    throw new InvalidOperationException("a table needs at least one column");
                }
                String tableId = StableId.mint(StableId.TABLE);
                Map<String, ColumnDef> columns = new LinkedHashMap<>();
                List<String> pkColumnIds = new ArrayList<>();
                int position = 1;
                for (SchemaOperation.CreateTable.NewColumn nc : ctab.columns()) {
                    String id = StableId.mint(StableId.COLUMN);
                    // A primary key column is implicitly NOT NULL; recording it as nullable would
                    // make the snapshot disagree with what Postgres actually creates.
                    boolean nullable = nc.nullable() && !nc.primaryKey();
                    columns.put(id, new ColumnDef(id, nc.name(), position++,
                            TypeCanonicalizer.parse(nc.type()), nullable, nc.defaultExpr(), null));
                    if (nc.primaryKey()) {
                        pkColumnIds.add(id);
                    }
                }
                Map<String, ConstraintDef> constraints = new LinkedHashMap<>();
                if (!pkColumnIds.isEmpty()) {
                    String pkId = StableId.mint(StableId.CONSTRAINT);
                    constraints.put(pkId, new ConstraintDef(pkId, ctab.name() + "_pkey",
                            ConstraintDef.Kind.PRIMARY_KEY, pkColumnIds, null, null));
                }
                tables.put(tableId, new TableDef(tableId, ctab.name(), columns, constraints, new LinkedHashMap<>()));
            }

            case SchemaOperation.DropTable dt -> {
                TableDef t = require(tables, dt.tableId());
                // Refuse while something still references it, rather than emitting a CASCADE that
                // would quietly delete the referencing constraints too.
                for (TableDef other : tables.values()) {
                    if (other.id().equals(t.id())) {
                        continue;
                    }
                    boolean referencesIt = other.constraints().values().stream()
                            .anyMatch(c -> c.kind() == ConstraintDef.Kind.FOREIGN_KEY
                                    && c.references() != null
                                    && t.id().equals(c.references().tableId()));
                    if (referencesIt) {
                        throw new InvalidOperationException(
                                "cannot drop '" + t.name() + "': '" + other.name()
                                        + "' still has a foreign key to it");
                    }
                }
                tables.remove(dt.tableId());
            }

            case SchemaOperation.AddIndex ai -> {
                TableDef t = require(tables, ai.tableId());
                if (t.indexes().values().stream().anyMatch(i -> i.name().equals(ai.name()))) {
                    throw new InvalidOperationException("an index named '" + ai.name() + "' already exists");
                }
                if (ai.columnIds() == null || ai.columnIds().isEmpty()) {
                    throw new InvalidOperationException("an index needs at least one column");
                }
                ai.columnIds().forEach(id -> requireColumn(t, id));
                Map<String, IndexDef> indexes = new LinkedHashMap<>(t.indexes());
                String id = StableId.mint(StableId.INDEX);
                List<IndexDef.IndexColumn> cols = ai.columnIds().stream()
                        .map(c -> new IndexDef.IndexColumn(c, "ASC", "LAST"))
                        .toList();
                indexes.put(id, new IndexDef(id, ai.name(),
                        ai.method() == null ? "btree" : ai.method(),
                        ai.unique(), cols, null, false));
                tables.put(t.id(), t.withIndexes(indexes));
            }

            case SchemaOperation.DropIndex di -> {
                TableDef t = require(tables, di.tableId());
                IndexDef index = t.indexes().get(di.indexId());
                if (index == null) {
                    throw new InvalidOperationException("no such index on '" + t.name() + "'");
                }
                if (index.constraintBacked()) {
                    throw new InvalidOperationException(
                            "index '" + index.name() + "' backs a constraint and cannot be dropped "
                                    + "directly; drop the constraint instead");
                }
                Map<String, IndexDef> indexes = new LinkedHashMap<>(t.indexes());
                indexes.remove(di.indexId());
                tables.put(t.id(), t.withIndexes(indexes));
            }
        }

        return snapshot.withTables(tables);
    }

    private static TableDef require(Map<String, TableDef> tables, String tableId) {
        TableDef t = tables.get(tableId);
        if (t == null) {
            throw new InvalidOperationException("no such table: " + tableId);
        }
        return t;
    }

    private static ColumnDef requireColumn(TableDef table, String columnId) {
        ColumnDef c = table.columns().get(columnId);
        if (c == null) {
            throw new InvalidOperationException(
                    "no such column on '" + table.name() + "': " + columnId);
        }
        return c;
    }

    private static void requireNoColumnNamed(TableDef table, String name) {
        if (table.columnByName(name) != null) {
            throw new InvalidOperationException(
                    "'" + table.name() + "' already has a column named '" + name + "'");
        }
    }

    public static class InvalidOperationException extends RuntimeException {
        public InvalidOperationException(String message) {
            super(message);
        }
    }
}
