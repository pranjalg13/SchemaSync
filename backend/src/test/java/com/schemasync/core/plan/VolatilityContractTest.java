package com.schemasync.core.plan;

import com.schemasync.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the one assumption {@link SafetyClassifier} cannot settle in Java: which functions
 * Postgres actually considers volatile.
 *
 * <p>The classifier hardcodes a list, because it must stay a pure function. That list is a claim
 * about Postgres, and an unverified claim about Postgres is exactly the kind of thing that is
 * quietly wrong for a year. This test makes the database adjudicate.
 */
class VolatilityContractTest extends PostgresTestBase {

    private String volatilityOf(String function) {
        return jdbc.queryForObject("""
                SELECT CASE p.provolatile WHEN 'i' THEN 'IMMUTABLE'
                                          WHEN 's' THEN 'STABLE'
                                          WHEN 'v' THEN 'VOLATILE' END
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'pg_catalog' AND p.proname = ? AND p.pronargs = 0
                LIMIT 1
                """, String.class, function);
    }

    @Test
    @DisplayName("every function we call volatile really is volatile in Postgres")
    void volatileListMatchesPostgres() {
        for (String fn : List.of("random", "clock_timestamp", "gen_random_uuid")) {
            assertThat(volatilityOf(fn))
                    .as("%s must be VOLATILE for our REWRITE classification to be correct", fn)
                    .isEqualTo("VOLATILE");
        }
    }

    @Test
    @DisplayName("now() really is STABLE, so classifying it as instant is correct")
    void nowIsStable() {
        // If Postgres ever changed this, our "instant" classification would become a promise we
        // break by locking the user's table for minutes. Worth asserting rather than assuming.
        assertThat(volatilityOf("now")).isEqualTo("STABLE");
        assertThat(volatilityOf("transaction_timestamp")).isEqualTo("STABLE");
        assertThat(SafetyClassifier.isVolatile("now()")).isFalse();
    }

    @Test
    @DisplayName("the classifier's claims hold against a real table")
    void claimsHoldAgainstRealPostgres() {
        // Proving the classification is TRUE, not merely self-consistent: compare the table's
        // physical file identity before and after. A rewrite gives the table a new relfilenode.
        String schema = freshSchema("rewrite");
        jdbc.execute("CREATE TABLE %s.t (id bigint PRIMARY KEY, v varchar(50))".formatted(schema));
        jdbc.execute("INSERT INTO %s.t SELECT i, 'x' || i FROM generate_series(1, 5000) i".formatted(schema));

        long before = relfilenode(schema, "t");
        jdbc.execute("ALTER TABLE %s.t ADD COLUMN created_at timestamptz DEFAULT now()".formatted(schema));
        assertThat(relfilenode(schema, "t"))
                .as("DEFAULT now() must not rewrite the table")
                .isEqualTo(before);

        jdbc.execute("ALTER TABLE %s.t ALTER COLUMN v TYPE text".formatted(schema));
        assertThat(relfilenode(schema, "t"))
                .as("varchar(50) -> text must not rewrite the table")
                .isEqualTo(before);

        jdbc.execute("ALTER TABLE %s.t ADD COLUMN token uuid DEFAULT gen_random_uuid()".formatted(schema));
        assertThat(relfilenode(schema, "t"))
                .as("a volatile default MUST rewrite the table")
                .isNotEqualTo(before);

        long afterVolatile = relfilenode(schema, "t");
        jdbc.execute("ALTER TABLE %s.t ALTER COLUMN id TYPE numeric".formatted(schema));
        assertThat(relfilenode(schema, "t"))
                .as("bigint -> numeric MUST rewrite the table")
                .isNotEqualTo(afterVolatile);
    }

    private long relfilenode(String schema, String table) {
        Long node = jdbc.queryForObject("""
                SELECT c.relfilenode FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ?
                """, Long.class, schema, table);
        return node == null ? -1 : node;
    }
}
