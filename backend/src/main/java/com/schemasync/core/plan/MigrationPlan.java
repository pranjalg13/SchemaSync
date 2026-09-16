package com.schemasync.core.plan;

import java.util.List;

/**
 * An ordered, classified plan.
 *
 * @param mode ATOMIC when every step is metadata-only, so the whole plan can run in one
 *             transaction and a failure leaves no residue. ONLINE when something has to touch
 *             every row, which forces multiple transactions and genuinely gives up
 *             all-or-nothing. The UI states which, rather than implying atomicity we do not have.
 */
public record MigrationPlan(List<MigrationStep> steps, Mode mode, List<String> preflightWarnings) {

    public enum Mode { ATOMIC, ONLINE }

    public boolean hasPointOfNoReturn() {
        return steps.stream().anyMatch(MigrationStep::pointOfNoReturn);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** Steps that read or rewrite every row: what makes a migration slow rather than instant. */
    public List<MigrationStep> expensiveSteps() {
        return steps.stream()
                .filter(s -> s.classification() != null
                        && s.classification().verdict() != Classification.Verdict.INSTANT)
                .toList();
    }
}
