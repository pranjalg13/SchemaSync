package com.schemasync.core.merge;

import com.schemasync.core.model.SchemaSnapshot;

import java.util.List;

/**
 * The outcome of a three-way merge.
 *
 * @param merged    the combined schema, assuming every conflict resolves to the default shown
 * @param conflicts everything the two sides disagreed about, auto-resolved ones included
 */
public record MergeResult(SchemaSnapshot merged, List<MergeConflict> conflicts) {

    /** Conflicts a human still has to answer. Auto-resolved ones are reported, not asked about. */
    public List<MergeConflict> unresolved() {
        return conflicts.stream().filter(c -> !c.isAutoResolved()).toList();
    }

    public boolean isClean() {
        return unresolved().isEmpty();
    }

    public boolean hasDestructiveConflicts() {
        return conflicts.stream()
                .anyMatch(c -> c.severity() == MergeConflict.Severity.DESTRUCTIVE);
    }
}
