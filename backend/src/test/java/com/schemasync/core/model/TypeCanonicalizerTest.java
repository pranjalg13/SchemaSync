package com.schemasync.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The alias table is the specification; this test asserts every row of it.
 *
 * <p>These are not trivial tests. Without canonicalisation, re-importing an unchanged schema
 * produces a phantom diff on every single column, and the content hash -- which drift detection
 * and no-op detection both depend on -- becomes meaningless.
 */
class TypeCanonicalizerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("Postgres spellings normalise to one canonical form")
    @CsvSource({
            // The exact strings format_type() returns for our demo schema.
            "character varying(32),   varchar(32)",
            "character varying,       varchar",
            "timestamp with time zone,    timestamptz",
            "timestamp without time zone, timestamp",
            "time with time zone,     timetz",
            "time without time zone,  time",
            "integer,                 integer",
            "bigint,                  bigint",
            "text,                    text",

            // Internal names, which appear in casts and in some catalog paths.
            "int2,    smallint",
            "int4,    integer",
            "int8,    bigint",
            "bool,    boolean",
            "float4,  real",
            "float8,  double precision",
            "bpchar(10), char(10)",

            // serial is not a type: it is integer + sequence + default. Canonicalising it to
            // integer is what stops a serial column diffing against a plain integer column.
            "serial,      integer",
            "bigserial,   bigint",
            "smallserial, smallint",

            // Numerics keep precision and scale; character types keep length.
            "'numeric(14,2)', 'numeric(14,2)'",
            "numeric(10),   numeric(10)",
            "numeric,       numeric",
            "'decimal(5,3)',  'numeric(5,3)'",

            // Whitespace and case must not matter.
            "  CHARACTER  VARYING(50)  , varchar(50)",
            "INTEGER,                    integer"
    })
    void canonicalises(String input, String expected) {
        assertThat(TypeCanonicalizer.parse(input).sql()).isEqualTo(expected);
    }

    @Test
    @DisplayName("infix precision on time types is parsed, not mistaken for a suffix")
    void infixTimePrecision() {
        // Postgres renders this with the parameters in the MIDDLE of the type name, which defeats
        // a naive "match trailing (n)" parser.
        assertThat(TypeCanonicalizer.parse("timestamp(3) with time zone").base()).isEqualTo("timestamptz");
        assertThat(TypeCanonicalizer.parse("timestamp(3) with time zone").precision()).isEqualTo(3);
        assertThat(TypeCanonicalizer.parse("time(6) without time zone").base()).isEqualTo("time");
    }

    @Test
    @DisplayName("a single parameter is length for character types and precision for numerics")
    void singleParameterMeaning() {
        DataType vc = TypeCanonicalizer.parse("character varying(32)");
        assertThat(vc.length()).isEqualTo(32);
        assertThat(vc.precision()).isNull();

        DataType num = TypeCanonicalizer.parse("numeric(10)");
        assertThat(num.length()).isNull();
        assertThat(num.precision()).isEqualTo(10);
    }

    @Test
    @DisplayName("unknown types are preserved verbatim rather than guessed at")
    void unknownTypesPreserved() {
        // A domain, enum or extension type. Introspection separately flags these so the schema is
        // marked partially managed; silently normalising something we do not understand would be
        // worse than leaving it alone.
        assertThat(TypeCanonicalizer.parse("my_enum_status").sql()).isEqualTo("my_enum_status");
        assertThat(TypeCanonicalizer.parse("public.citext").sql()).isEqualTo("public.citext");
    }

    @Test
    @DisplayName("array types survive a round trip unmangled")
    void arrayTypes() {
        assertThat(TypeCanonicalizer.parse("integer[]").sql()).isEqualTo("integer[]");
        assertThat(TypeCanonicalizer.parse("text[]").sql()).isEqualTo("text[]");
    }
}
