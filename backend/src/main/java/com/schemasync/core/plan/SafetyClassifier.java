package com.schemasync.core.plan;

import com.schemasync.core.model.DataType;
import com.schemasync.core.model.TypeCanonicalizer;

import java.util.Locale;
import java.util.Set;

/**
 * Decides what an operation will cost Postgres.
 *
 * <p>A pure function: no database access, no Spring. That is deliberate and is what makes the
 * whole table of claims below testable in milliseconds rather than behind a container. It is also
 * the artifact that makes every "blocks reads / blocks writes / blocks nothing" badge in the UI
 * honest rather than decorative.
 *
 * <p>Verified against Postgres 16 behaviour; {@code SafetyClassifierTest} asserts the pure logic
 * and {@code VolatilityContractTest} checks the one assumption that cannot be settled in Java --
 * which functions Postgres considers volatile.
 */
public final class SafetyClassifier {

    /**
     * Functions whose result differs per row, forcing {@code ADD COLUMN ... DEFAULT} to rewrite
     * the entire table.
     *
     * <p>The subtle one is {@code now()}: it <b>is not</b> volatile. It is STABLE -- constant
     * within a transaction -- so {@code ADD COLUMN created_at timestamptz DEFAULT now()} is
     * metadata-only and instant even on a 5GB table, while the near-identical-looking
     * {@code DEFAULT clock_timestamp()} rewrites every row. Getting this backwards means either
     * scaring users off a free operation or cheerfully locking their table for minutes.
     *
     * <p>{@code VolatilityContractTest} asserts this list against {@code pg_proc.provolatile}, so
     * the assumption is checked against the database rather than trusted.
     */
    public static final Set<String> VOLATILE_DEFAULT_FUNCTIONS = Set.of(
            "random", "clock_timestamp", "gen_random_uuid", "uuid_generate_v4",
            "nextval", "timeofday");

    private SafetyClassifier() {
    }

    // ----- ADD COLUMN -------------------------------------------------------

    public static Classification addColumn(boolean nullable, String defaultExpr) {
        if (defaultExpr == null) {
            return Classification.instant(
                    nullable
                            ? "Adds a nullable column. Catalog change only, no rows are touched."
                            : "Adds a NOT NULL column with no default; Postgres only permits this "
                              + "on an empty table.");
        }
        if (isVolatile(defaultExpr)) {
            return Classification.rewrite(
                    "The default calls a volatile function, so every row needs its own value. "
                    + "Postgres rewrites the whole table.");
        }
        // Since PG 11 a non-volatile default is evaluated once and stored in the catalog
        // (pg_attribute.attmissingval); existing rows are never touched.
        return Classification.instant(
                "The default is constant for all rows, so Postgres stores it in the catalog "
                + "rather than writing it to every row.");
    }

    // ----- DROP COLUMN ------------------------------------------------------

    public static Classification dropColumn() {
        // Postgres marks the column attisdropped and leaves the data in place; the space comes
        // back gradually as rows are updated, or via VACUUM FULL.
        return Classification.instant(
                "Marks the column dropped in the catalog. Instant, though the disk space is not "
                + "reclaimed until the table is rewritten or vacuumed.");
    }

    // ----- RENAME -----------------------------------------------------------

    public static Classification rename(String what) {
        return Classification.instant(
                "Renames the " + what + " in the catalog. No data is read or written -- this is "
                + "why a rename must never be applied as a drop plus an add.");
    }

    // ----- ALTER COLUMN TYPE ------------------------------------------------

    public static Classification changeColumnType(String fromType, String toType, String usingExpr) {
        DataType from = TypeCanonicalizer.parse(fromType);
        DataType to = TypeCanonicalizer.parse(toType);

        if (usingExpr != null) {
            // A USING expression is arbitrary per-row computation by definition.
            return Classification.rewrite(
                    "A USING expression has to be evaluated for every row, so the table is rewritten.");
        }
        if (isBinaryCoercible(from, to)) {
            return Classification.instant(
                    "Widening " + from.sql() + " to " + to.sql() + " does not change how values are "
                    + "stored, so Postgres only updates the catalog.");
        }
        return Classification.rewrite(
                "Converting " + from.sql() + " to " + to.sql() + " changes the on-disk representation, "
                + "so every row and every index on the table is rewritten.");
    }

    /**
     * Type changes Postgres performs without a rewrite.
     *
     * <p>All of these widen a constraint on a representation that is already identical on disk.
     * Narrowing is never free -- it has to check every value -- and neither is anything that
     * changes the representation itself, which is why {@code integer -> bigint} rewrites despite
     * "feeling" like a widening.
     */
    static boolean isBinaryCoercible(DataType from, DataType to) {
        String f = from.base();
        String t = to.base();

        // varchar(n) -> varchar(m>n), varchar(n) -> varchar, varchar/char -> text
        if ((f.equals("varchar") || f.equals("char")) && t.equals("text")) {
            return true;
        }
        if (f.equals("varchar") && t.equals("varchar")) {
            if (to.length() == null) {
                return true;                       // dropping the limit entirely
            }
            return from.length() != null && to.length() >= from.length();
        }
        // numeric: more precision at the SAME scale is free; any scale change is not, because
        // values have to be re-rounded.
        if (f.equals("numeric") && t.equals("numeric")) {
            if (to.precision() == null) {
                return true;
            }
            if (from.precision() == null) {
                return false;                      // unconstrained -> constrained must be checked
            }
            boolean sameScale = java.util.Objects.equals(
                    from.scale() == null ? 0 : from.scale(),
                    to.scale() == null ? 0 : to.scale());
            return sameScale && to.precision() >= from.precision();
        }
        // Time types: increasing precision is free, decreasing truncates and so rewrites.
        if (f.equals(t) && Set.of("timestamptz", "timestamp", "time", "timetz", "interval").contains(f)) {
            if (to.precision() == null) {
                return true;
            }
            return from.precision() != null && to.precision() >= from.precision();
        }
        return f.equals(t) && java.util.Objects.equals(from.length(), to.length())
                && java.util.Objects.equals(from.precision(), to.precision())
                && java.util.Objects.equals(from.scale(), to.scale());
    }

    // ----- NULLABILITY ------------------------------------------------------

    public static Classification setNullable(boolean nullable) {
        if (nullable) {
            return Classification.instant("Dropping NOT NULL is a catalog change.");
        }
        // The scan is unavoidable in principle, but PG 12+ skips it when a validated
        // CHECK (col IS NOT NULL) already exists -- which is what the online plan sets up.
        return Classification.scan(Classification.LockMode.ACCESS_EXCLUSIVE,
                Classification.Blocks.READS_AND_WRITES,
                "Postgres must confirm no row is NULL. Done directly it scans the whole table "
                + "holding ACCESS EXCLUSIVE; the online plan proves it with a NOT VALID check "
                + "first so this step becomes instant.");
    }

    public static Classification setDefault() {
        return Classification.instant(
                "Changes the default for future rows only. Existing rows are untouched.");
    }

    // ----- INDEXES AND TABLES -----------------------------------------------

    public static Classification createIndex() {
        // Plain CREATE INDEX takes SHARE, which does not block reads but blocks every write for
        // the whole build. On a large table that is minutes of write downtime.
        return Classification.scan(Classification.LockMode.SHARE,
                Classification.Blocks.WRITES,
                "Building an index reads the whole table. A plain build blocks writes for its "
                + "entire duration; CONCURRENTLY avoids that at the cost of two passes.");
    }

    public static Classification dropIndex() {
        return Classification.instant("Drops the index. Brief ACCESS EXCLUSIVE, no table scan.");
    }

    public static Classification createTable() {
        return Classification.instant("Creates an empty table. Nothing existing is touched.");
    }

    public static Classification dropTable() {
        return Classification.instant("Drops the table and its indexes. Instant regardless of size.");
    }

    // ----- helpers ----------------------------------------------------------

    static boolean isVolatile(String defaultExpr) {
        if (defaultExpr == null) {
            return false;
        }
        String lower = defaultExpr.toLowerCase(Locale.ROOT);
        for (String fn : VOLATILE_DEFAULT_FUNCTIONS) {
            if (lower.contains(fn + "(")) {
                return true;
            }
        }
        return false;
    }
}
