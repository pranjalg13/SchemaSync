package com.schemasync.core.model;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Stable identifiers for schema objects.
 *
 * <p>These are the reason a rename is never mistaken for a drop plus an add. An ID is minted when
 * an object is first seen (at import, or when a branch creates it) and then carried unchanged for
 * the object's lifetime. Diff matches objects by ID, so a rename is a change to the {@code name}
 * attribute of the same object rather than the disappearance of one object and the arrival of
 * another.
 *
 * <p>Format is a ULID-style prefix + timestamp + randomness: lexicographically sortable by
 * creation time, which makes snapshots diff-friendly to read and gives deterministic ordering
 * without a separate sequence column.
 */
public final class StableId {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final String TABLE = "tbl";
    public static final String COLUMN = "col";
    public static final String INDEX = "idx";
    public static final String CONSTRAINT = "con";

    private StableId() {
    }

    public static String mint(String kindPrefix) {
        return kindPrefix + "_" + encode(Instant.now().toEpochMilli(), 10) + randomSuffix(6);
    }

    private static String randomSuffix(int chars) {
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            sb.append(CROCKFORD[RANDOM.nextInt(CROCKFORD.length)]);
        }
        return sb.toString();
    }

    private static String encode(long value, int chars) {
        char[] out = new char[chars];
        for (int i = chars - 1; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (value & 31)];
            value >>>= 5;
        }
        return new String(out);
    }
}
