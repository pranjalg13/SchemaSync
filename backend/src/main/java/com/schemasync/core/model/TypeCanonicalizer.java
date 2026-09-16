package com.schemasync.core.model;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the output of Postgres' {@code format_type()} into a canonical {@link DataType}.
 *
 * <p>Table-driven on purpose: the alias table is the specification, and
 * {@code TypeCanonicalizerTest} asserts every row of it. Adding a type means adding a row,
 * not editing control flow.
 */
public final class TypeCanonicalizer {

    /**
     * Maps every spelling Postgres might return -- internal names, SQL standard names, and
     * common aliases -- onto one canonical base name.
     *
     * <p>The canonical name chosen is the one an engineer would actually write, so a diff reads
     * naturally: {@code bigint}, not {@code int8}.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            // Integers
            Map.entry("int2", "smallint"),
            Map.entry("smallint", "smallint"),
            Map.entry("int4", "integer"),
            Map.entry("int", "integer"),
            Map.entry("integer", "integer"),
            Map.entry("int8", "bigint"),
            Map.entry("bigint", "bigint"),

            // Exact and approximate numerics
            Map.entry("numeric", "numeric"),
            Map.entry("decimal", "numeric"),
            Map.entry("float4", "real"),
            Map.entry("real", "real"),
            Map.entry("float8", "double precision"),
            Map.entry("double precision", "double precision"),

            // Character
            Map.entry("varchar", "varchar"),
            Map.entry("character varying", "varchar"),
            Map.entry("bpchar", "char"),
            Map.entry("character", "char"),
            Map.entry("char", "char"),
            Map.entry("text", "text"),

            // Boolean
            Map.entry("bool", "boolean"),
            Map.entry("boolean", "boolean"),

            // Date/time. Note the parameterised forms are handled below, because Postgres
            // renders precision in the middle: "timestamp(3) with time zone".
            Map.entry("timestamptz", "timestamptz"),
            Map.entry("timestamp with time zone", "timestamptz"),
            Map.entry("timestamp", "timestamp"),
            Map.entry("timestamp without time zone", "timestamp"),
            Map.entry("timetz", "timetz"),
            Map.entry("time with time zone", "timetz"),
            Map.entry("time", "time"),
            Map.entry("time without time zone", "time"),
            Map.entry("date", "date"),
            Map.entry("interval", "interval"),

            // Misc
            Map.entry("uuid", "uuid"),
            Map.entry("json", "json"),
            Map.entry("jsonb", "jsonb"),
            Map.entry("bytea", "bytea"),
            Map.entry("inet", "inet"),
            Map.entry("cidr", "cidr"),
            Map.entry("macaddr", "macaddr"),
            Map.entry("serial", "integer"),
            Map.entry("bigserial", "bigint"),
            Map.entry("smallserial", "smallint")
    );

    /** Matches a trailing parameter list: {@code (32)} or {@code (14,2)}. */
    private static final Pattern PARAMS = Pattern.compile("^(.*?)\\s*\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)\\s*$");

    /** Matches Postgres' infix precision for time types: {@code timestamp(3) with time zone}. */
    private static final Pattern INFIX_TIME =
            Pattern.compile("^(timestamp|time)\\s*\\((\\d+)\\)\\s*(with(?:out)? time zone)$", Pattern.CASE_INSENSITIVE);

    private TypeCanonicalizer() {
    }

    /**
     * @param formatted the string returned by {@code format_type(atttypid, atttypmod)}
     */
    public static DataType parse(String formatted) {
        if (formatted == null || formatted.isBlank()) {
            throw new IllegalArgumentException("type string must not be blank");
        }
        String s = formatted.trim().toLowerCase(Locale.ROOT);

        // Arrays are carried through verbatim rather than modelled; they are out of scope for
        // the editable palette but must survive a round trip without being mangled.
        if (s.endsWith("[]")) {
            return DataType.of(s);
        }

        // "timestamp(3) with time zone" has to be matched before the generic parameter form,
        // because the parameters are not at the end of the string.
        Matcher infix = INFIX_TIME.matcher(s);
        if (infix.matches()) {
            String canonical = canonicalBase(infix.group(1) + " " + infix.group(3));
            return new DataType(canonical, null, Integer.valueOf(infix.group(2)), null);
        }

        Matcher m = PARAMS.matcher(s);
        if (!m.matches()) {
            return DataType.of(canonicalBase(s));
        }

        String base = canonicalBase(m.group(1));
        int first = Integer.parseInt(m.group(2));
        Integer second = m.group(3) == null ? null : Integer.valueOf(m.group(3));

        // A single parameter means length for character types and precision for everything else.
        boolean lengthTyped = base.equals("varchar") || base.equals("char");
        if (lengthTyped) {
            return new DataType(base, first, null, null);
        }
        return new DataType(base, null, first, second);
    }

    private static String canonicalBase(String raw) {
        String key = raw.trim().replaceAll("\\s+", " ");
        String canonical = ALIASES.get(key);
        // An unknown type (a domain, an enum, an extension type) is kept verbatim rather than
        // guessed at. Introspection separately flags these so the schema is marked partially
        // managed -- silently normalising something we do not understand would be worse.
        return canonical != null ? canonical : key;
    }
}
