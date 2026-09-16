package com.schemasync.core.model;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises column default expressions returned by {@code pg_get_expr()}.
 *
 * <p>Postgres rewrites defaults on the way in: you write {@code DEFAULT 'pending'} on a
 * {@code varchar(32)} column and it stores {@code 'pending'::character varying}. Diffing the raw
 * strings would report a change every time a schema is re-imported.
 *
 * <p>This is deliberately conservative. It removes a trailing cast only when that cast is the
 * column's own type -- i.e. only the rewrite Postgres itself performed. An expression it does not
 * recognise is left exactly as-is, because a wrong "normalisation" of a default is worse than no
 * normalisation: it would make two genuinely different defaults compare equal.
 */
public final class DefaultExprCanonicalizer {

    /** Trailing cast, e.g. {@code 'pending'::character varying}. */
    private static final Pattern TRAILING_CAST =
            Pattern.compile("^(.*?)::\\s*([a-z0-9_ ]+(?:\\(\\d+(?:,\\d+)?\\))?)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * Spellings Postgres treats as identical. These are keyword forms that {@code pg_get_expr}
     * may render either way depending on how the column was declared.
     */
    private static final Map<String, String> FUNCTION_ALIASES = Map.of(
            "current_timestamp", "now()",
            "current_timestamp()", "now()",
            "\"current_timestamp\"()", "now()",
            "current_date", "CURRENT_DATE",
            "current_user", "CURRENT_USER"
    );

    private DefaultExprCanonicalizer() {
    }

    /**
     * @param raw        the expression from {@code pg_get_expr(adbin, adrelid)}, or null
     * @param columnType the column's canonical type, used to recognise a redundant cast
     * @return the canonical expression, or null if there is no default
     */
    public static String canonicalize(String raw, DataType columnType) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();

        // An identity/serial column's default is a nextval() over a sequence whose name is
        // derived from the table and column. That name changes under a rename even though the
        // semantics do not, so identity is modelled as a column attribute and the default is
        // dropped here rather than being compared as text.
        if (s.toLowerCase(Locale.ROOT).startsWith("nextval(")) {
            return null;
        }

        String aliased = FUNCTION_ALIASES.get(s.toLowerCase(Locale.ROOT));
        if (aliased != null) {
            return aliased;
        }

        Matcher m = TRAILING_CAST.matcher(s);
        if (m.matches()) {
            String value = m.group(1).trim();
            String castType = m.group(2).trim();
            // Strip the cast only when it is the column's own type -- exactly the rewrite
            // Postgres performed. An explicit cast to some other type is meaningful and stays.
            if (sameType(castType, columnType)) {
                return value;
            }
        }
        return s;
    }

    private static boolean sameType(String castType, DataType columnType) {
        if (columnType == null) {
            return false;
        }
        try {
            DataType parsed = TypeCanonicalizer.parse(castType);
            // Compare base names only. Postgres commonly drops the length from the cast it adds
            // ('pending'::character varying on a varchar(32) column), so requiring the length to
            // match too would leave the redundant cast in place.
            return parsed.base().equals(columnType.base());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
