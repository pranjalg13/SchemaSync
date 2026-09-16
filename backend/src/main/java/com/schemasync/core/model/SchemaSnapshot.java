package com.schemasync.core.model;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The complete state of a schema at one commit.
 *
 * <p>This is the unit of storage and the unit of comparison. Diff and three-way merge are pure
 * functions over snapshots -- no database access, no replaying an operation log -- which is what
 * lets the whole version-control engine be unit-tested without Postgres.
 *
 * @param unmanaged objects present in the real schema that SchemaSync does not model (views,
 *                  triggers, functions, partitioned tables, enums, ...). They are recorded rather
 *                  than dropped, because the merge plan is computed as {@code diff(target, merged)}
 *                  -- so an object missing from the snapshot looks exactly like an object the user
 *                  deleted, and the merge would drop it from the real database.
 */
public record SchemaSnapshot(
        int schemaVersion,
        Map<String, TableDef> tables,
        List<UnmanagedObject> unmanaged
) {
    public static final int CURRENT_VERSION = 1;

    public record UnmanagedObject(String kind, String name, String reason) {}

    public static SchemaSnapshot of(Map<String, TableDef> tables) {
        return new SchemaSnapshot(CURRENT_VERSION, tables, List.of());
    }

    public static SchemaSnapshot empty() {
        return new SchemaSnapshot(CURRENT_VERSION, Map.of(), List.of());
    }

    public SchemaSnapshot withTables(Map<String, TableDef> newTables) {
        return new SchemaSnapshot(schemaVersion, newTables, unmanaged);
    }

    public boolean isPartiallyManaged() {
        return !unmanaged.isEmpty();
    }

    public TableDef tableByName(String name) {
        return tables.values().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    public int objectCount() {
        return tables.values().stream()
                .mapToInt(t -> 1 + t.columns().size() + t.constraints().size() + t.indexes().size())
                .sum();
    }

    /** Tables in a stable, human-friendly order. Used for rendering, never for comparison. */
    public Map<String, TableDef> tablesSortedByName() {
        Map<String, TableDef> sorted = new TreeMap<>();
        tables.values().forEach(t -> sorted.put(t.name(), t));
        return sorted;
    }
}
