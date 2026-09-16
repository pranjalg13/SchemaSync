package com.schemasync.core.diff;

/**
 * One difference between two snapshots.
 *
 * <p>Note what is NOT here: there is no "column replaced" or "column moved". A rename is
 * {@link Kind#COLUMN_RENAMED}, carrying both names, because the two snapshots agree on the
 * column's stable id. That is the entire point of the identity model, expressed as a type.
 *
 * @param objectId the stable id the change is about
 * @param from     previous value, for a modification
 * @param to       new value, for a modification
 */
public record SchemaChange(
        Kind kind,
        String tableName,
        String objectName,
        String objectId,
        String from,
        String to
) {
    public enum Kind {
        TABLE_ADDED,
        TABLE_DROPPED,
        TABLE_RENAMED,

        COLUMN_ADDED,
        COLUMN_DROPPED,
        COLUMN_RENAMED,
        COLUMN_TYPE_CHANGED,
        COLUMN_NULLABILITY_CHANGED,
        COLUMN_DEFAULT_CHANGED,

        INDEX_ADDED,
        INDEX_DROPPED,
        INDEX_CHANGED,

        CONSTRAINT_ADDED,
        CONSTRAINT_DROPPED,
        CONSTRAINT_CHANGED;

        /**
         * Whether applying this change can destroy data.
         *
         * <p>Drives the warnings in the UI. A rename is conspicuously absent: renaming a column is
         * a catalog-only change and loses nothing, which is exactly why mistaking one for a
         * drop-and-add matters so much.
         */
        public boolean isDestructive() {
            return this == TABLE_DROPPED || this == COLUMN_DROPPED || this == COLUMN_TYPE_CHANGED;
        }
    }

    public static SchemaChange of(Kind kind, String tableName, String objectName, String objectId) {
        return new SchemaChange(kind, tableName, objectName, objectId, null, null);
    }

    public static SchemaChange modified(Kind kind, String tableName, String objectName,
                                        String objectId, String from, String to) {
        return new SchemaChange(kind, tableName, objectName, objectId, from, to);
    }

    /** A one-line human description, used directly in the diff UI. */
    public String describe() {
        return switch (kind) {
            case TABLE_ADDED -> "Added table " + objectName;
            case TABLE_DROPPED -> "Dropped table " + objectName;
            case TABLE_RENAMED -> "Renamed table " + from + " to " + to;
            case COLUMN_ADDED -> "Added column " + objectName;
            case COLUMN_DROPPED -> "Dropped column " + objectName;
            case COLUMN_RENAMED -> "Renamed " + from + " to " + to;
            case COLUMN_TYPE_CHANGED -> "Changed " + objectName + " from " + from + " to " + to;
            case COLUMN_NULLABILITY_CHANGED -> objectName + " is now " + to;
            case COLUMN_DEFAULT_CHANGED -> "Default for " + objectName + " is now "
                    + (to == null ? "none" : to);
            case INDEX_ADDED -> "Added index " + objectName;
            case INDEX_DROPPED -> "Dropped index " + objectName;
            case INDEX_CHANGED -> "Changed index " + objectName;
            case CONSTRAINT_ADDED -> "Added constraint " + objectName;
            case CONSTRAINT_DROPPED -> "Dropped constraint " + objectName;
            case CONSTRAINT_CHANGED -> "Changed constraint " + objectName;
        };
    }
}
