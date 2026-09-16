package com.schemasync.core.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.schemasync.core.plan.Classification.Verdict.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The classification table as executable specification. No database needed. */
class SafetyClassifierTest {

    @Test
    @DisplayName("now() is STABLE, so a now() default does NOT rewrite the table")
    void nowIsNotVolatile() {
        // The trap. These two lines look almost identical and differ by minutes of downtime on a
        // 5GB table. Postgres evaluates a STABLE default once and stores it in the catalog.
        assertThat(SafetyClassifier.addColumn(false, "now()").verdict()).isEqualTo(INSTANT);
        assertThat(SafetyClassifier.addColumn(false, "clock_timestamp()").verdict()).isEqualTo(REWRITE);
    }

    @ParameterizedTest(name = "DEFAULT {0} -> {1}")
    @DisplayName("column defaults are classified by the volatility of what they call")
    @CsvSource({
            "0,                    INSTANT",
            "'pending',            INSTANT",
            "now(),                INSTANT",
            "CURRENT_TIMESTAMP,    INSTANT",
            "transaction_timestamp(), INSTANT",
            "random(),             REWRITE",
            "gen_random_uuid(),    REWRITE",
            "clock_timestamp(),    REWRITE",
    })
    void classifiesDefaults(String defaultExpr, String expected) {
        assertThat(SafetyClassifier.addColumn(true, defaultExpr).verdict())
                .isEqualTo(Classification.Verdict.valueOf(expected));
    }

    @ParameterizedTest(name = "{0} -> {1} is {2}")
    @DisplayName("only genuine widenings skip the table rewrite")
    @CsvSource({
            // Free: the on-disk representation is unchanged, only the constraint widens.
            "varchar(50),  varchar(100), INSTANT",
            "varchar(50),  text,         INSTANT",
            "varchar(50),  varchar,      INSTANT",
            "'numeric(10,2)', 'numeric(14,2)', INSTANT",
            "timestamp(3), timestamp(6), INSTANT",

            // Not free: narrowing has to check every value.
            "varchar(100), varchar(50),  REWRITE",
            "text,         varchar(50),  REWRITE",
            "'numeric(14,2)', 'numeric(10,2)', REWRITE",

            // Not free: a scale change re-rounds every value.
            "'numeric(14,2)', 'numeric(14,4)', REWRITE",

            // Not free despite feeling like a widening -- int4 and int8 are different on disk.
            // This is the one people are most often surprised by.
            "integer,      bigint,       REWRITE",
            "integer,      'numeric(14,2)', REWRITE",
            "text,         integer,      REWRITE",
    })
    void classifiesTypeChanges(String from, String to, String expected) {
        assertThat(SafetyClassifier.changeColumnType(from, to, null).verdict())
                .isEqualTo(Classification.Verdict.valueOf(expected));
    }

    @Test
    @DisplayName("a USING expression always forces a rewrite")
    void usingForcesRewrite() {
        // Even a no-op-looking USING is per-row computation.
        assertThat(SafetyClassifier.changeColumnType("varchar(50)", "text", "col::text").verdict())
                .isEqualTo(REWRITE);
    }

    @Test
    @DisplayName("catalog-only operations are safe at any table size")
    void catalogOnlyOperations() {
        assertThat(SafetyClassifier.dropColumn().isSafeAtAnySize()).isTrue();
        assertThat(SafetyClassifier.rename("column").isSafeAtAnySize()).isTrue();
        assertThat(SafetyClassifier.setNullable(true).isSafeAtAnySize()).isTrue();
        assertThat(SafetyClassifier.setDefault().isSafeAtAnySize()).isTrue();
        assertThat(SafetyClassifier.dropIndex().isSafeAtAnySize()).isTrue();
        assertThat(SafetyClassifier.createTable().isSafeAtAnySize()).isTrue();
        assertThat(SafetyClassifier.dropTable().isSafeAtAnySize()).isTrue();
    }

    @Test
    @DisplayName("SET NOT NULL scans, and CREATE INDEX blocks writes rather than reads")
    void scanningOperations() {
        assertThat(SafetyClassifier.setNullable(false).verdict()).isEqualTo(SCAN);

        Classification index = SafetyClassifier.createIndex();
        assertThat(index.verdict()).isEqualTo(SCAN);
        // The distinction that matters: a plain CREATE INDEX takes SHARE, which lets reads through
        // but stops every write for the whole build.
        assertThat(index.lock()).isEqualTo(Classification.LockMode.SHARE);
        assertThat(index.blocks()).isEqualTo(Classification.Blocks.WRITES);
    }

    @Test
    @DisplayName("every classification explains itself")
    void rationalesArePresent() {
        // The rationale is shown to users before they approve a migration, so an empty one is a
        // bug rather than a cosmetic omission.
        assertThat(SafetyClassifier.addColumn(true, "now()").rationale()).isNotBlank();
        assertThat(SafetyClassifier.changeColumnType("integer", "bigint", null).rationale())
                .contains("rewritten");
    }
}
