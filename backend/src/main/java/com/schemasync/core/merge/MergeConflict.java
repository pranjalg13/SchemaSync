package com.schemasync.core.merge;

/**
 * One place the two branches disagree.
 *
 * <p>Severity drives how the UI treats it, and the distinction between DESTRUCTIVE and the rest is
 * the one that matters: those are the resolutions that can lose data, and they are the ones that
 * get a typed confirmation rather than a button.
 */
public record MergeConflict(
        Type type,
        Severity severity,
        String objectKind,
        String stableId,
        String tableName,
        String objectName,
        String attribute,
        String base,
        String ours,
        String theirs,
        String question
) {
    public enum Severity {
        /** Both sides did the same thing. Resolved silently, but still reported. */
        AUTO,
        /** Names only. Reversible, loses nothing. */
        SAFE,
        /** A resolution here can destroy data. Requires explicit confirmation. */
        DESTRUCTIVE,
        /** The merged schema would be illegal. Must be resolved before anything can run. */
        STRUCTURAL
    }

    public enum Type {
        BOTH_MODIFIED,          // same attribute, two different new values
        BOTH_RENAMED,
        MODIFIED_AND_DROPPED,   // one side edited what the other deleted
        TABLE_MODIFIED_AND_DROPPED,
        ADD_ADD_NAME_COLLISION, // two different new objects that want the same name
        INDEX_NAME_COLLISION,
        DUPLICATE_INTENT        // same thing added on both sides: auto-resolved
    }

    /** The two ways a user may resolve. A third value is offered only where it makes sense. */
    public enum Resolution { OURS, THEIRS, CUSTOM }

    public boolean isAutoResolved() {
        return severity == Severity.AUTO;
    }
}
