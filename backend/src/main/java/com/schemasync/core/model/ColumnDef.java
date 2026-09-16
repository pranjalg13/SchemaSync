package com.schemasync.core.model;

/**
 * A column, addressed by its stable {@code id}. {@code name} is an ordinary attribute -- that is
 * the whole trick that makes renames free.
 *
 * @param position ordinal position in the table. Stored so we can render a table in a familiar
 *                 order, but deliberately EXCLUDED from diff and hashing: Postgres column order is
 *                 cosmetic, and comparing it manufactures conflicts out of nothing.
 * @param identity null, or {@code "ALWAYS"} / {@code "BY DEFAULT"}. Modelled as an attribute
 *                 rather than left in the default expression, because the underlying sequence name
 *                 changes under a rename while the semantics do not.
 */
public record ColumnDef(
        String id,
        String name,
        int position,
        DataType type,
        boolean nullable,
        String defaultExpr,
        String identity
) {
    public ColumnDef withName(String newName) {
        return new ColumnDef(id, newName, position, type, nullable, defaultExpr, identity);
    }

    public ColumnDef withType(DataType newType) {
        return new ColumnDef(id, name, position, newType, nullable, defaultExpr, identity);
    }

    public ColumnDef withNullable(boolean newNullable) {
        return new ColumnDef(id, name, position, type, newNullable, defaultExpr, identity);
    }

    public ColumnDef withDefault(String newDefault) {
        return new ColumnDef(id, name, position, type, nullable, newDefault, identity);
    }
}
