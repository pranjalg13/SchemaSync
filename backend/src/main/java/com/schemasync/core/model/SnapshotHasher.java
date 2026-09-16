package com.schemasync.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes a content hash for a snapshot.
 *
 * <p>Two snapshots describing the same schema must hash identically regardless of the order
 * Postgres happened to return rows in, so everything is sorted before it is fed to the digest.
 * The hash drives three things: snapshot deduplication, free no-op-commit detection, and drift
 * detection (re-introspect the live schema and compare against the recorded head).
 *
 * <p>Deliberately excluded from the hash:
 * <ul>
 *   <li>{@code position} -- Postgres column order is cosmetic; including it would make an
 *       unrelated DROP COLUMN look like a change to every column after it.</li>
 *   <li>stable IDs -- so that a schema introspected into two different projects, with different
 *       minted IDs, still compares equal by content.</li>
 * </ul>
 *
 * <p>A hand-rolled canonical serialisation rather than a JSON library, because we need total
 * control over ordering and over exactly which fields participate. Jackson's output ordering is a
 * configuration detail, and a content hash that silently depends on one is a trap.
 */
public final class SnapshotHasher {

    private SnapshotHasher() {
    }

    public static String hash(SchemaSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("v").append(snapshot.schemaVersion()).append('\n');

        List<TableDef> tables = new ArrayList<>(snapshot.tables().values());
        tables.sort(Comparator.comparing(TableDef::name));
        for (TableDef t : tables) {
            sb.append("T:").append(t.name()).append('\n');

            List<ColumnDef> columns = new ArrayList<>(t.columns().values());
            columns.sort(Comparator.comparing(ColumnDef::name));
            for (ColumnDef c : columns) {
                sb.append("  C:").append(c.name())
                  .append('|').append(c.type().sql())
                  .append('|').append(c.nullable() ? "NULL" : "NOT NULL")
                  .append('|').append(c.defaultExpr() == null ? "-" : c.defaultExpr())
                  .append('|').append(c.identity() == null ? "-" : c.identity())
                  .append('\n');
            }

            // Constraints and indexes hash by semantic fingerprint plus name. The fingerprint
            // catches "same thing, different name"; the name is included because renaming a
            // constraint IS a real change to the schema, just a safe one.
            List<ConstraintDef> constraints = new ArrayList<>(t.constraints().values());
            constraints.sort(Comparator.comparing(ConstraintDef::name));
            for (ConstraintDef k : constraints) {
                sb.append("  K:").append(k.name()).append('|')
                  .append(fingerprintWithColumnNames(k, t)).append('\n');
            }

            List<IndexDef> indexes = new ArrayList<>(t.indexes().values());
            indexes.sort(Comparator.comparing(IndexDef::name));
            for (IndexDef i : indexes) {
                sb.append("  I:").append(i.name()).append('|')
                  .append(fingerprintWithColumnNames(i, t)).append('\n');
            }
        }

        List<SchemaSnapshot.UnmanagedObject> unmanaged = new ArrayList<>(snapshot.unmanaged());
        unmanaged.sort(Comparator.comparing(SchemaSnapshot.UnmanagedObject::kind)
                .thenComparing(SchemaSnapshot.UnmanagedObject::name));
        for (SchemaSnapshot.UnmanagedObject u : unmanaged) {
            sb.append("U:").append(u.kind()).append(':').append(u.name()).append('\n');
        }

        return sha256(sb.toString());
    }

    /**
     * Fingerprints reference columns by stable ID, but the hash must be ID-independent, so
     * resolve IDs back to names first.
     */
    private static String fingerprintWithColumnNames(ConstraintDef k, TableDef t) {
        String cols = k.columnIds().stream().map(id -> columnName(t, id)).sorted().toList().toString();
        StringBuilder sb = new StringBuilder(k.kind().name()).append('|').append(cols);
        if (k.references() != null) {
            sb.append("|ref=").append(k.references().columnIds())
              .append(':').append(k.references().onDelete())
              .append(':').append(k.references().onUpdate());
        }
        if (k.expression() != null) {
            sb.append("|expr=").append(k.expression());
        }
        return sb.toString();
    }

    private static String fingerprintWithColumnNames(IndexDef i, TableDef t) {
        StringBuilder cols = new StringBuilder();
        for (IndexDef.IndexColumn c : i.columns()) {
            // Index column ORDER is semantic -- (a, b) is a different index from (b, a) -- so
            // unlike constraint columns these are not sorted.
            cols.append(columnName(t, c.columnId())).append(':')
                .append(c.direction()).append(':').append(c.nulls()).append(',');
        }
        return i.method() + "|" + i.unique() + "|" + cols + "|" + (i.predicate() == null ? "" : i.predicate());
    }

    private static String columnName(TableDef t, String columnId) {
        ColumnDef c = t.columns().get(columnId);
        return c == null ? "?" + columnId : c.name();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
