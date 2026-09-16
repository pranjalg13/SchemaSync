package com.schemasync.core.model;

import java.util.Objects;

/**
 * A column type in canonical form.
 *
 * <p>Postgres does not hand back the type you wrote. {@code varchar(32)} comes out of
 * {@code format_type()} as {@code character varying(32)}, {@code timestamptz} as
 * {@code timestamp with time zone}, {@code serial} as {@code integer} plus a sequence default.
 * If we hashed those strings as-is we would get a phantom diff every time a schema was
 * re-imported, and the content hash -- which drift detection and no-op detection both rely on --
 * would be meaningless.
 *
 * <p>So every type is parsed into (base, length, precision, scale) with a single canonical
 * spelling for the base, and rendered back out deterministically.
 */
public record DataType(String base, Integer length, Integer precision, Integer scale) {

    public DataType {
        Objects.requireNonNull(base, "base");
    }

    public static DataType of(String base) {
        return new DataType(base, null, null, null);
    }

    public static DataType varchar(int length) {
        return new DataType("varchar", length, null, null);
    }

    public static DataType numeric(int precision, int scale) {
        return new DataType("numeric", null, precision, scale);
    }

    /** The canonical SQL spelling, e.g. {@code varchar(32)} or {@code numeric(14,2)}. */
    public String sql() {
        if (length != null) {
            return base + "(" + length + ")";
        }
        if (precision != null) {
            return scale != null ? base + "(" + precision + "," + scale + ")"
                                 : base + "(" + precision + ")";
        }
        return base;
    }

    @Override
    public String toString() {
        return sql();
    }
}
