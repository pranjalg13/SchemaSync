package com.schemasync.catalog;

import com.schemasync.core.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Reads a real Postgres schema out of {@code pg_catalog} and into a canonical
 * {@link SchemaSnapshot}.
 *
 * <p>Two things make this more than a straight catalog dump:
 *
 * <ol>
 *   <li><b>Canonicalisation.</b> Types and default expressions are normalised (see
 *       {@link TypeCanonicalizer}), so re-importing an unchanged schema produces an identical
 *       content hash instead of a phantom diff on every column.</li>
 *   <li><b>Identity carry-forward.</b> Given a previous snapshot, stable IDs are reused by matching
 *       on name. This matters for drift detection: we re-introspect the live schema against the
 *       recorded head, and want to compare content, not churn identity.</li>
 * </ol>
 *
 * <p>Unsupported object kinds are recorded in {@link SchemaSnapshot#unmanaged()} rather than
 * skipped. Skipping them would be actively dangerous: the merge plan is {@code diff(target,
 * merged)}, so an object missing from the snapshot is indistinguishable from one the user deleted,
 * and the merge would drop it from the real database.
 */
@Component
public class SchemaIntrospector {

    private final JdbcTemplate jdbc;

    public SchemaIntrospector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SchemaSnapshot introspect(String schemaName) {
        return introspect(schemaName, null);
    }

    public SchemaSnapshot introspect(String schemaName, SchemaSnapshot previous) {
        IdSource ids = new IdSource(previous);

        Map<String, TableDef> byId = new LinkedHashMap<>();
        Map<String, String> tableIdByName = new HashMap<>();

        for (String tableName : tableNames(schemaName)) {
            String tableId = ids.tableId(tableName);
            tableIdByName.put(tableName, tableId);
            byId.put(tableId, new TableDef(tableId, tableName,
                    new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>()));
        }

        // attnum -> column stable id, per table. Constraints and indexes reference columns by
        // attnum in the catalog, and we store them by stable id, so this is the bridge.
        Map<String, Map<Integer, String>> columnIdByAttnum = new HashMap<>();

        loadColumns(schemaName, byId, tableIdByName, columnIdByAttnum, ids);
        loadConstraints(schemaName, byId, tableIdByName, columnIdByAttnum, ids);
        loadIndexes(schemaName, byId, tableIdByName, columnIdByAttnum, ids);

        return new SchemaSnapshot(SchemaSnapshot.CURRENT_VERSION, byId, findUnmanaged(schemaName));
    }

    private List<String> tableNames(String schemaName) {
        return jdbc.queryForList("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind = 'r'
                ORDER BY c.relname
                """, String.class, schemaName);
    }

    private void loadColumns(String schemaName,
                             Map<String, TableDef> byId,
                             Map<String, String> tableIdByName,
                             Map<String, Map<Integer, String>> columnIdByAttnum,
                             IdSource ids) {
        jdbc.query("""
                SELECT c.relname                              AS table_name,
                       a.attname                              AS column_name,
                       a.attnum                               AS attnum,
                       format_type(a.atttypid, a.atttypmod)   AS data_type,
                       a.attnotnull                           AS not_null,
                       a.attidentity                          AS identity,
                       pg_get_expr(d.adbin, d.adrelid)        AS default_expr
                FROM pg_attribute a
                JOIN pg_class c      ON c.oid = a.attrelid
                JOIN pg_namespace n  ON n.oid = c.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE n.nspname = ? AND c.relkind = 'r'
                  AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY c.relname, a.attnum
                """, rs -> {
            String tableName = rs.getString("table_name");
            String tableId = tableIdByName.get(tableName);
            if (tableId == null) {
                return;
            }
            String columnName = rs.getString("column_name");
            int attnum = rs.getInt("attnum");

            DataType type = TypeCanonicalizer.parse(rs.getString("data_type"));
            String defaultExpr = DefaultExprCanonicalizer.canonicalize(rs.getString("default_expr"), type);
            String identity = switch (Objects.toString(rs.getString("identity"), "")) {
                case "a" -> "ALWAYS";
                case "d" -> "BY DEFAULT";
                default -> null;
            };

            String columnId = ids.columnId(tableName, columnName);
            columnIdByAttnum.computeIfAbsent(tableId, k -> new HashMap<>()).put(attnum, columnId);

            TableDef table = byId.get(tableId);
            table.columns().put(columnId, new ColumnDef(
                    columnId, columnName, attnum, type, !rs.getBoolean("not_null"), defaultExpr, identity));
        }, schemaName);
    }

    private void loadConstraints(String schemaName,
                                 Map<String, TableDef> byId,
                                 Map<String, String> tableIdByName,
                                 Map<String, Map<Integer, String>> columnIdByAttnum,
                                 IdSource ids) {
        jdbc.query("""
                SELECT c.relname                                   AS table_name,
                       con.conname                                 AS constraint_name,
                       con.contype                                 AS constraint_type,
                       con.conkey::int[]                           AS local_cols,
                       con.confkey::int[]                          AS foreign_cols,
                       fc.relname                                  AS ref_table,
                       con.confdeltype                             AS on_delete,
                       con.confupdtype                             AS on_update,
                       pg_get_expr(con.conbin, con.conrelid)       AS check_expr
                FROM pg_constraint con
                JOIN pg_class c     ON c.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_class fc ON fc.oid = con.confrelid
                WHERE n.nspname = ? AND con.contype IN ('p','u','c','f')
                ORDER BY c.relname, con.conname
                """, rs -> {
            String tableName = rs.getString("table_name");
            String tableId = tableIdByName.get(tableName);
            if (tableId == null) {
                return;
            }
            ConstraintDef.Kind kind = switch (rs.getString("constraint_type")) {
                case "p" -> ConstraintDef.Kind.PRIMARY_KEY;
                case "u" -> ConstraintDef.Kind.UNIQUE;
                case "c" -> ConstraintDef.Kind.CHECK;
                case "f" -> ConstraintDef.Kind.FOREIGN_KEY;
                default -> null;
            };
            if (kind == null) {
                return;
            }

            String constraintName = rs.getString("constraint_name");
            Map<Integer, String> attnumToId = columnIdByAttnum.getOrDefault(tableId, Map.of());
            List<String> columnIds = resolve(rs.getArray("local_cols"), attnumToId);

            ConstraintDef.ForeignKeyRef ref = null;
            if (kind == ConstraintDef.Kind.FOREIGN_KEY) {
                String refTable = rs.getString("ref_table");
                String refTableId = tableIdByName.get(refTable);
                Map<Integer, String> refAttnums =
                        refTableId == null ? Map.of() : columnIdByAttnum.getOrDefault(refTableId, Map.of());
                ref = new ConstraintDef.ForeignKeyRef(
                        refTableId,
                        resolve(rs.getArray("foreign_cols"), refAttnums),
                        actionName(rs.getString("on_delete")),
                        actionName(rs.getString("on_update")));
            }

            String constraintId = ids.constraintId(tableName, constraintName);
            byId.get(tableId).constraints().put(constraintId, new ConstraintDef(
                    constraintId, constraintName, kind, columnIds, ref,
                    kind == ConstraintDef.Kind.CHECK ? rs.getString("check_expr") : null));
        }, schemaName);
    }

    private void loadIndexes(String schemaName,
                             Map<String, TableDef> byId,
                             Map<String, String> tableIdByName,
                             Map<String, Map<Integer, String>> columnIdByAttnum,
                             IdSource ids) {
        jdbc.query("""
                SELECT t.relname                                AS table_name,
                       i.relname                                AS index_name,
                       ix.indisunique                           AS is_unique,
                       am.amname                                AS method,
                       ix.indkey::text                          AS key_attnums,
                       ix.indoption::text                       AS key_options,
                       pg_get_expr(ix.indpred, ix.indrelid)     AS predicate,
                       EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = i.oid)
                                                                AS constraint_backed
                FROM pg_index ix
                JOIN pg_class i     ON i.oid = ix.indexrelid
                JOIN pg_class t     ON t.oid = ix.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_am am       ON am.oid = i.relam
                WHERE n.nspname = ? AND t.relkind = 'r'
                ORDER BY t.relname, i.relname
                """, rs -> {
            String tableName = rs.getString("table_name");
            String tableId = tableIdByName.get(tableName);
            if (tableId == null) {
                return;
            }
            String indexName = rs.getString("index_name");
            Map<Integer, String> attnumToId = columnIdByAttnum.getOrDefault(tableId, Map.of());

            // indkey is an int2vector rendered as space-separated attnums; indoption is a
            // parallel vector of flag bits (1 = DESC, 2 = NULLS FIRST).
            String[] keys = Objects.toString(rs.getString("key_attnums"), "").trim().split("\\s+");
            String[] options = Objects.toString(rs.getString("key_options"), "").trim().split("\\s+");

            List<IndexDef.IndexColumn> columns = new ArrayList<>();
            boolean expressionIndex = false;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i].isEmpty()) {
                    continue;
                }
                int attnum = Integer.parseInt(keys[i]);
                // attnum 0 means an expression index. We do not model those; flag and skip.
                if (attnum == 0) {
                    expressionIndex = true;
                    break;
                }
                String columnId = attnumToId.get(attnum);
                if (columnId == null) {
                    continue;
                }
                int flags = i < options.length && !options[i].isEmpty() ? Integer.parseInt(options[i]) : 0;
                columns.add(new IndexDef.IndexColumn(
                        columnId,
                        (flags & 1) != 0 ? "DESC" : "ASC",
                        (flags & 2) != 0 ? "FIRST" : "LAST"));
            }
            if (expressionIndex || columns.isEmpty()) {
                return;
            }

            String indexId = ids.indexId(tableName, indexName);
            byId.get(tableId).indexes().put(indexId, new IndexDef(
                    indexId, indexName, rs.getString("method"), rs.getBoolean("is_unique"),
                    columns, rs.getString("predicate"), rs.getBoolean("constraint_backed")));
        }, schemaName);
    }

    /**
     * Objects we do not model. Recorded so the schema can be marked partially managed and the
     * relevant operations blocked -- never silently omitted.
     */
    private List<SchemaSnapshot.UnmanagedObject> findUnmanaged(String schemaName) {
        List<SchemaSnapshot.UnmanagedObject> out = new ArrayList<>();

        jdbc.query("""
                SELECT c.relkind AS kind, c.relname AS name
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind IN ('v','m','p','f')
                ORDER BY c.relname
                """, rs -> {
            String kind = switch (rs.getString("kind")) {
                case "v" -> "view";
                case "m" -> "materialized_view";
                case "p" -> "partitioned_table";
                case "f" -> "foreign_table";
                default -> "unknown";
            };
            out.add(new SchemaSnapshot.UnmanagedObject(kind, rs.getString("name"),
                    "SchemaSync does not model " + kind + "s; it is preserved but not versioned"));
        }, schemaName);

        // Triggers, excluding both Postgres' internal FK-enforcement triggers and our own
        // backfill sync triggers (which are transient machinery, not user schema).
        jdbc.query("""
                SELECT tg.tgname AS name
                FROM pg_trigger tg
                JOIN pg_class c     ON c.oid = tg.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND NOT tg.tgisinternal AND tg.tgname NOT LIKE '\\_\\_sv\\_%'
                ORDER BY tg.tgname
                """, rs -> {
            out.add(new SchemaSnapshot.UnmanagedObject("trigger", rs.getString("name"),
                    "SchemaSync does not model triggers; it is preserved but not versioned"));
        }, schemaName);

        jdbc.query("""
                SELECT t.typname AS name
                FROM pg_type t
                JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE n.nspname = ? AND t.typtype = 'e'
                ORDER BY t.typname
                """, rs -> {
            out.add(new SchemaSnapshot.UnmanagedObject("enum", rs.getString("name"),
                    "SchemaSync does not model enum types; columns using it cannot be retyped"));
        }, schemaName);

        return out;
    }

    private static List<String> resolve(java.sql.Array array, Map<Integer, String> attnumToId) {
        if (array == null) {
            return List.of();
        }
        try {
            Integer[] attnums = (Integer[]) array.getArray();
            List<String> out = new ArrayList<>(attnums.length);
            for (Integer attnum : attnums) {
                String id = attnumToId.get(attnum);
                if (id != null) {
                    out.add(id);
                }
            }
            return out;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot read catalog array", e);
        }
    }

    private static String actionName(String code) {
        return switch (Objects.toString(code, "a")) {
            case "c" -> "CASCADE";
            case "n" -> "SET NULL";
            case "d" -> "SET DEFAULT";
            case "r" -> "RESTRICT";
            default -> "NO ACTION";
        };
    }

    /**
     * Hands out stable IDs, reusing the previous snapshot's ID for an object of the same name.
     *
     * <p>Name-matching is correct here and nowhere else. Introspection is the one place we have no
     * intent to work from -- we are reading a database, not receiving an operation -- so name is
     * the only handle available. Every subsequent change goes through the operation API, where the
     * ID is already known and a rename is explicit.
     */
    private static final class IdSource {
        private final Map<String, String> tables = new HashMap<>();
        private final Map<String, String> columns = new HashMap<>();
        private final Map<String, String> constraints = new HashMap<>();
        private final Map<String, String> indexes = new HashMap<>();

        IdSource(SchemaSnapshot previous) {
            if (previous == null) {
                return;
            }
            for (TableDef t : previous.tables().values()) {
                tables.put(t.name(), t.id());
                t.columns().values().forEach(c -> columns.put(t.name() + "." + c.name(), c.id()));
                t.constraints().values().forEach(k -> constraints.put(t.name() + "." + k.name(), k.id()));
                t.indexes().values().forEach(i -> indexes.put(t.name() + "." + i.name(), i.id()));
            }
        }

        String tableId(String table) {
            return tables.computeIfAbsent(table, k -> StableId.mint(StableId.TABLE));
        }

        String columnId(String table, String column) {
            return columns.computeIfAbsent(table + "." + column, k -> StableId.mint(StableId.COLUMN));
        }

        String constraintId(String table, String constraint) {
            return constraints.computeIfAbsent(table + "." + constraint, k -> StableId.mint(StableId.CONSTRAINT));
        }

        String indexId(String table, String index) {
            return indexes.computeIfAbsent(table + "." + index, k -> StableId.mint(StableId.INDEX));
        }
    }
}
