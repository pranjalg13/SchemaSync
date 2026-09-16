package com.schemasync.core.model;

import java.util.List;
import java.util.stream.Collectors;

/** An index. Like constraints, column references are by stable ID. */
public record IndexDef(
        String id,
        String name,
        String method,
        boolean unique,
        List<IndexColumn> columns,
        /** Non-null for a partial index: the canonical WHERE expression. */
        String predicate,
        /**
         * True when this index exists only to back a constraint. Such an index cannot be dropped
         * with DROP INDEX -- the constraint has to go instead -- so the executor needs to know.
         */
        boolean constraintBacked
) {
    public record IndexColumn(String columnId, String direction, String nulls) {}

    public IndexDef withName(String newName) {
        return new IndexDef(id, newName, method, unique, columns, predicate, constraintBacked);
    }

    /** @see ConstraintDef#fingerprint() -- same purpose, same exclusion of name and id. */
    public String fingerprint() {
        String cols = columns.stream()
                .map(c -> c.columnId() + ":" + c.direction() + ":" + c.nulls())
                .collect(Collectors.joining(","));
        return method + "|" + unique + "|" + cols + "|" + (predicate == null ? "" : predicate);
    }
}
