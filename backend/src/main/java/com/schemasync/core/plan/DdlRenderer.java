package com.schemasync.core.plan;

import com.schemasync.core.model.*;
import com.schemasync.exec.DdlSql;

import java.util.*;

/**
 * Renders a {@link SchemaSnapshot} as the DDL needed to recreate it in an empty schema.
 *
 * <p>Used to materialise a branch. The ordering rules here are the same ones the merge planner
 * uses, stated once: tables first without their foreign keys, then data, then foreign keys, then
 * indexes.
 *
 * <p>Deferring every foreign key to a separate pass is what makes circular references between
 * tables a non-issue. A naive topological sort over tables fails outright on a cycle
 * ({@code a} references {@code b} references {@code a}); by creating all tables bare and adding
 * every FK afterwards, the cycle simply never appears in the dependency graph. The classic failure
 * case is designed out rather than handled.
 */
public final class DdlRenderer {

    private DdlRenderer() {
    }

    /** Bare {@code CREATE TABLE}: columns and inline table constraints, but no foreign keys. */
    public static String createTable(String schema, TableDef table) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ")
                .append(DdlSql.qualify(schema, table.name()))
                .append(" (\n");

        List<ColumnDef> columns = orderedColumns(table);
        List<String> parts = new ArrayList<>();
        for (ColumnDef c : columns) {
            parts.add("    " + columnClause(c));
        }

        // Primary keys, unique and check constraints are emitted inline. Foreign keys are not:
        // see the class comment.
        for (ConstraintDef k : sortedConstraints(table)) {
            switch (k.kind()) {
                case PRIMARY_KEY -> parts.add("    CONSTRAINT " + DdlSql.quote(k.name())
                        + " PRIMARY KEY (" + columnList(table, k.columnIds()) + ")");
                case UNIQUE -> parts.add("    CONSTRAINT " + DdlSql.quote(k.name())
                        + " UNIQUE (" + columnList(table, k.columnIds()) + ")");
                case CHECK -> {
                    if (k.expression() != null) {
                        parts.add("    CONSTRAINT " + DdlSql.quote(k.name())
                                + " CHECK " + wrapExpression(k.expression()));
                    }
                }
                case FOREIGN_KEY -> { /* deferred to addForeignKey */ }
            }
        }

        sb.append(String.join(",\n", parts)).append("\n)");
        return sb.toString();
    }

    /** A single column definition, as it appears inside CREATE TABLE. */
    public static String columnClause(ColumnDef c) {
        StringBuilder sb = new StringBuilder(DdlSql.quote(c.name()))
                .append(' ').append(c.type().sql());

        if (c.identity() != null) {
            // Identity implies its own default; emitting both would be a syntax error.
            sb.append(" GENERATED ").append(c.identity()).append(" AS IDENTITY");
        } else if (c.defaultExpr() != null) {
            sb.append(" DEFAULT ").append(c.defaultExpr());
        }
        if (!c.nullable()) {
            sb.append(" NOT NULL");
        }
        return sb.toString();
    }

    public static String addForeignKey(String schema, TableDef table, ConstraintDef fk,
                                       SchemaSnapshot snapshot) {
        TableDef referenced = snapshot.tables().get(fk.references().tableId());
        if (referenced == null) {
            throw new IllegalStateException(
                    "foreign key " + fk.name() + " references a table not present in the snapshot");
        }
        StringBuilder sb = new StringBuilder("ALTER TABLE ")
                .append(DdlSql.qualify(schema, table.name()))
                .append(" ADD CONSTRAINT ").append(DdlSql.quote(fk.name()))
                .append(" FOREIGN KEY (").append(columnList(table, fk.columnIds()))
                .append(") REFERENCES ").append(DdlSql.qualify(schema, referenced.name()))
                .append(" (").append(columnList(referenced, fk.references().columnIds())).append(")");

        if (!"NO ACTION".equals(fk.references().onDelete())) {
            sb.append(" ON DELETE ").append(fk.references().onDelete());
        }
        if (!"NO ACTION".equals(fk.references().onUpdate())) {
            sb.append(" ON UPDATE ").append(fk.references().onUpdate());
        }
        return sb.toString();
    }

    public static String createIndex(String schema, TableDef table, IndexDef index) {
        return createIndex(schema, table, index, false);
    }

    public static String createIndex(String schema, TableDef table, IndexDef index, boolean concurrently) {
        StringBuilder sb = new StringBuilder("CREATE ");
        if (index.unique()) {
            sb.append("UNIQUE ");
        }
        sb.append("INDEX ");
        if (concurrently) {
            sb.append("CONCURRENTLY ");
        }
        sb.append(DdlSql.quote(index.name()))
          .append(" ON ").append(DdlSql.qualify(schema, table.name()))
          .append(" USING ").append(index.method())
          .append(" (");

        List<String> cols = new ArrayList<>();
        for (IndexDef.IndexColumn c : index.columns()) {
            ColumnDef column = table.columns().get(c.columnId());
            if (column == null) {
                throw new IllegalStateException(
                        "index " + index.name() + " references a column not present in the table");
            }
            cols.add(DdlSql.quote(column.name()) + " " + c.direction() + " NULLS " + c.nulls());
        }
        sb.append(String.join(", ", cols)).append(")");

        if (index.predicate() != null) {
            sb.append(" WHERE ").append(stripOuterParens(index.predicate()));
        }
        return sb.toString();
    }

    /**
     * The full statement list to recreate a snapshot in an empty schema, in execution order.
     *
     * <p>Foreign keys are returned separately from table creation so a caller materialising a
     * branch can copy sampled rows in between: rows have to land before the constraints that
     * validate them, or a sampled child row whose parent was not sampled would abort the copy.
     */
    public static Materialisation materialise(String schema, SchemaSnapshot snapshot) {
        List<String> tables = new ArrayList<>();
        List<String> foreignKeys = new ArrayList<>();
        List<String> indexes = new ArrayList<>();

        for (TableDef t : sortedTables(snapshot)) {
            tables.add(createTable(schema, t));
            for (ConstraintDef k : sortedConstraints(t)) {
                if (k.kind() == ConstraintDef.Kind.FOREIGN_KEY) {
                    foreignKeys.add(addForeignKey(schema, t, k, snapshot));
                }
            }
            for (IndexDef i : sortedIndexes(t)) {
                // An index that exists only to back a PK or UNIQUE constraint was already created
                // by that constraint. Creating it again would fail on a duplicate name.
                if (!i.constraintBacked()) {
                    indexes.add(createIndex(schema, t, i));
                }
            }
        }
        return new Materialisation(tables, foreignKeys, indexes);
    }

    public record Materialisation(List<String> createTables,
                                  List<String> addForeignKeys,
                                  List<String> createIndexes) {
        public List<String> allInOrder() {
            List<String> all = new ArrayList<>(createTables);
            all.addAll(addForeignKeys);
            all.addAll(createIndexes);
            return all;
        }
    }

    /**
     * Tables ordered so a parent precedes the children that reference it.
     *
     * <p>Not needed for CREATE TABLE (foreign keys are deferred), but it is exactly the order the
     * row copy needs, so the sort lives here and both callers use it. Cycles cannot deadlock the
     * sort: any table not resolvable is appended once progress stalls.
     */
    public static List<TableDef> sortedTables(SchemaSnapshot snapshot) {
        Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
        for (TableDef t : snapshot.tables().values()) {
            Set<String> parents = new LinkedHashSet<>();
            for (ConstraintDef k : t.constraints().values()) {
                if (k.kind() == ConstraintDef.Kind.FOREIGN_KEY
                        && k.references() != null
                        && k.references().tableId() != null
                        && !k.references().tableId().equals(t.id())) {
                    parents.add(k.references().tableId());
                }
            }
            dependsOn.put(t.id(), parents);
        }

        List<TableDef> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();

        // Deterministic starting order, so two runs over the same snapshot emit identical SQL.
        List<TableDef> remaining = new ArrayList<>(snapshot.tables().values());
        remaining.sort(Comparator.comparing(TableDef::name));

        while (!remaining.isEmpty()) {
            boolean progressed = false;
            Iterator<TableDef> it = remaining.iterator();
            while (it.hasNext()) {
                TableDef t = it.next();
                if (placed.containsAll(dependsOn.getOrDefault(t.id(), Set.of()))) {
                    ordered.add(t);
                    placed.add(t.id());
                    it.remove();
                    progressed = true;
                }
            }
            if (!progressed) {
                // A cycle. Emit the rest in name order; correctness is unaffected because the
                // foreign keys that form the cycle are added in a later pass anyway.
                remaining.forEach(t -> {
                    ordered.add(t);
                    placed.add(t.id());
                });
                break;
            }
        }
        return ordered;
    }

    private static List<ColumnDef> orderedColumns(TableDef table) {
        List<ColumnDef> columns = new ArrayList<>(table.columns().values());
        columns.sort(Comparator.comparingInt(ColumnDef::position));
        return columns;
    }

    private static List<ConstraintDef> sortedConstraints(TableDef table) {
        List<ConstraintDef> out = new ArrayList<>(table.constraints().values());
        out.sort(Comparator.comparing(ConstraintDef::name));
        return out;
    }

    private static List<IndexDef> sortedIndexes(TableDef table) {
        List<IndexDef> out = new ArrayList<>(table.indexes().values());
        out.sort(Comparator.comparing(IndexDef::name));
        return out;
    }

    private static String columnList(TableDef table, List<String> columnIds) {
        List<String> names = new ArrayList<>();
        for (String id : columnIds) {
            ColumnDef c = table.columns().get(id);
            if (c == null) {
                throw new IllegalStateException("constraint references unknown column id " + id);
            }
            names.add(DdlSql.quote(c.name()));
        }
        return String.join(", ", names);
    }

    /** pg_get_expr already parenthesises check expressions; avoid doubling them up. */
    private static String wrapExpression(String expression) {
        String e = expression.trim();
        return e.startsWith("(") ? e : "(" + e + ")";
    }

    private static String stripOuterParens(String expression) {
        String e = expression.trim();
        if (!e.startsWith("(") || !e.endsWith(")")) {
            return e;
        }
        // Only strip when the leading paren actually matches the trailing one, so
        // "(a) AND (b)" is left intact.
        int depth = 0;
        for (int i = 0; i < e.length(); i++) {
            if (e.charAt(i) == '(') depth++;
            else if (e.charAt(i) == ')') {
                depth--;
                if (depth == 0 && i < e.length() - 1) {
                    return e;
                }
            }
        }
        return e.substring(1, e.length() - 1).trim();
    }
}
