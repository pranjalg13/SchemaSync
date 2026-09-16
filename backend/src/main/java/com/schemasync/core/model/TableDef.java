package com.schemasync.core.model;

import java.util.Map;

/**
 * A table. Children are keyed by stable ID rather than held in lists, because every diff and merge
 * operation is a key-set walk -- and keying by ID is precisely what makes a rename show up as an
 * attribute change on a matched pair rather than as an unmatched add and an unmatched drop.
 */
public record TableDef(
        String id,
        String name,
        Map<String, ColumnDef> columns,
        Map<String, ConstraintDef> constraints,
        Map<String, IndexDef> indexes
) {
    public TableDef withName(String newName) {
        return new TableDef(id, newName, columns, constraints, indexes);
    }

    public TableDef withColumns(Map<String, ColumnDef> newColumns) {
        return new TableDef(id, name, newColumns, constraints, indexes);
    }

    public TableDef withIndexes(Map<String, IndexDef> newIndexes) {
        return new TableDef(id, name, columns, constraints, newIndexes);
    }

    public TableDef withConstraints(Map<String, ConstraintDef> newConstraints) {
        return new TableDef(id, name, columns, newConstraints, indexes);
    }

    public ColumnDef columnByName(String columnName) {
        return columns.values().stream()
                .filter(c -> c.name().equals(columnName))
                .findFirst()
                .orElse(null);
    }
}
