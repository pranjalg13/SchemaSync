package com.schemasync.exec;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Identifier quoting and validation.
 *
 * <p>SQL identifiers cannot be passed as bind parameters -- you cannot write
 * {@code ALTER TABLE ? ADD COLUMN ?}. Every DDL statement therefore has to interpolate names
 * directly into SQL, which is exactly where injection lives.
 *
 * <p>So the whole product funnels identifier handling through this one class: names are validated
 * at the API boundary against a strict pattern, then quoted here. The entire SQL-injection surface
 * of SchemaSync is this file, which makes it reviewable in one sitting.
 */
public final class DdlSql {

    /**
     * Postgres truncates identifiers at 63 bytes. Accepting a longer one would mean the name we
     * record and the name the database actually created could differ -- and every later statement
     * addressing that object would silently miss.
     */
    public static final int MAX_IDENTIFIER_BYTES = 63;

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*$");

    /**
     * Names SchemaSync reserves for its own migration machinery. A user-supplied object with one
     * of these prefixes would collide with shadow columns and sync triggers mid-migration.
     */
    private static final String RESERVED_PREFIX = "__sv_";

    private static final Set<String> RESERVED_WORDS = Set.of(
            "select", "from", "where", "table", "column", "index", "constraint", "primary",
            "foreign", "key", "unique", "check", "default", "null", "not", "and", "or",
            "create", "drop", "alter", "insert", "update", "delete", "order", "group", "user",
            "grant", "revoke", "all", "any", "as", "on", "in", "is", "into", "values");

    private DdlSql() {
    }

    /**
     * Quotes an identifier for interpolation into DDL.
     *
     * <p>Applied to names that are already in the database and may not satisfy our stricter input
     * rules (a schema imported from elsewhere can legitimately contain mixed case or spaces), so
     * this quotes rather than rejects. Use {@link #validateUserIdentifier} on the way in.
     */
    public static String quote(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("identifier must not be empty");
        }
        if (identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("identifier must not contain a null byte");
        }
        // Doubling embedded quotes is the only escaping Postgres defines for quoted identifiers.
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /** Quotes a schema-qualified name, e.g. {@code "main"."orders"}. */
    public static String qualify(String schema, String object) {
        return quote(schema) + "." + quote(object);
    }

    /**
     * Validates a name supplied by a user before it is ever used to build DDL.
     *
     * <p>Deliberately stricter than Postgres allows. Postgres would happily accept
     * {@code "weird name; DROP TABLE x"} as a quoted identifier, and quoting alone would make it
     * safe -- but allowing it means every error message, every generated shadow-column name and
     * every log line has to stay correct in the presence of arbitrary text. Restricting input to
     * plain identifiers costs users nothing real and removes a whole class of problem.
     */
    public static void validateUserIdentifier(String kind, String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidIdentifierException(kind + " name must not be empty");
        }
        if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_IDENTIFIER_BYTES) {
            throw new InvalidIdentifierException(
                    kind + " name is longer than Postgres' " + MAX_IDENTIFIER_BYTES
                            + "-byte limit and would be silently truncated: " + name);
        }
        if (!SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new InvalidIdentifierException(
                    kind + " name must start with a letter or underscore and contain only "
                            + "letters, digits, underscores or $: " + name);
        }
        if (name.toLowerCase(Locale.ROOT).startsWith(RESERVED_PREFIX)) {
            throw new InvalidIdentifierException(
                    kind + " name must not start with '" + RESERVED_PREFIX
                            + "': that prefix is reserved for SchemaSync's migration machinery");
        }
        if (RESERVED_WORDS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new InvalidIdentifierException(
                    "'" + name + "' is a SQL reserved word; pick another " + kind + " name");
        }
    }

    /** Name of the shadow column used while retyping {@code column} online. */
    public static String shadowColumn(String column) {
        return RESERVED_PREFIX + column + "__new";
    }

    /** Name of the trigger that keeps a shadow column in sync during a backfill. */
    public static String syncTrigger(String column) {
        return RESERVED_PREFIX + column + "__sync";
    }

    public static String syncFunction(String table, String column) {
        return RESERVED_PREFIX + table + "_" + column + "__sync";
    }

    /** Name of the transient NOT NULL check used to let SET NOT NULL skip its table scan. */
    public static String notNullCheck(String column) {
        return RESERVED_PREFIX + column + "__nn";
    }

    public static boolean isReserved(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).startsWith(RESERVED_PREFIX);
    }

    public static class InvalidIdentifierException extends RuntimeException {
        public InvalidIdentifierException(String message) {
            super(message);
        }
    }
}
