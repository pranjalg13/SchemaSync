package com.schemasync.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultExprCanonicalizerTest {

    @Test
    @DisplayName("Postgres' own redundant cast is stripped")
    void stripsRedundantCast() {
        // Exactly what our demo schema produces: you write DEFAULT 'pending' on a varchar(32)
        // and Postgres stores 'pending'::character varying.
        String result = DefaultExprCanonicalizer.canonicalize(
                "'pending'::character varying", DataType.varchar(32));
        assertThat(result).isEqualTo("'pending'");
    }

    @Test
    @DisplayName("a cast to a DIFFERENT type is meaningful and must be kept")
    void keepsMeaningfulCast() {
        // Stripping this would make two genuinely different defaults compare equal -- a much
        // worse failure than leaving a redundant cast in place.
        String result = DefaultExprCanonicalizer.canonicalize("'42'::integer", DataType.varchar(32));
        assertThat(result).isEqualTo("'42'::integer");
    }

    @Test
    @DisplayName("CURRENT_TIMESTAMP and now() are the same default")
    void normalisesTimestampAliases() {
        DataType ts = DataType.of("timestamptz");
        assertThat(DefaultExprCanonicalizer.canonicalize("CURRENT_TIMESTAMP", ts)).isEqualTo("now()");
        assertThat(DefaultExprCanonicalizer.canonicalize("now()", ts)).isEqualTo("now()");
    }

    @Test
    @DisplayName("identity/serial sequence defaults are dropped, not compared as text")
    void dropsNextval() {
        // The sequence name is derived from table and column, so it changes under a rename even
        // though the semantics do not. Identity is modelled as a column attribute instead.
        String result = DefaultExprCanonicalizer.canonicalize(
                "nextval('main.orders_id_seq'::regclass)", DataType.of("bigint"));
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("no default stays no default")
    void handlesNull() {
        assertThat(DefaultExprCanonicalizer.canonicalize(null, DataType.of("text"))).isNull();
        assertThat(DefaultExprCanonicalizer.canonicalize("   ", DataType.of("text"))).isNull();
    }
}
