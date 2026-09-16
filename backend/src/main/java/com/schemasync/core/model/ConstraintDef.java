package com.schemasync.core.model;

import java.util.List;

/**
 * A table constraint. Column references are by stable column ID, never by name, so a constraint
 * survives a rename of the column it covers without appearing to change.
 */
public record ConstraintDef(
        String id,
        String name,
        Kind kind,
        List<String> columnIds,
        /** Non-null only for FOREIGN_KEY. */
        ForeignKeyRef references,
        /** Non-null only for CHECK: the canonical expression text. */
        String expression
) {
    public enum Kind { PRIMARY_KEY, UNIQUE, CHECK, FOREIGN_KEY }

    public record ForeignKeyRef(
            String tableId,
            List<String> columnIds,
            String onDelete,
            String onUpdate
    ) {}

    public ConstraintDef withName(String newName) {
        return new ConstraintDef(id, newName, kind, columnIds, references, expression);
    }

    /**
     * A semantic fingerprint, used to recognise that two independently-created constraints express
     * the same intent. Without this, two people adding the same unique constraint under different
     * names would surface as a conflict rather than as an automatic dedupe.
     *
     * <p>Deliberately excludes {@code name} and {@code id}.
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(kind.name()).append('|').append(columnIds);
        if (references != null) {
            sb.append("|ref=").append(references.tableId()).append(references.columnIds())
              .append(':').append(references.onDelete()).append(':').append(references.onUpdate());
        }
        if (expression != null) {
            sb.append("|expr=").append(expression);
        }
        return sb.toString();
    }
}
