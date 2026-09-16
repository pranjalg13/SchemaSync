package com.schemasync.core.plan;

/**
 * One statement in a migration plan.
 *
 * @param opGroup            all steps expanded from one logical operation share this, so the UI can
 *                           collapse "6 steps" back into "change amount to numeric(14,2)"
 * @param description        plain English, shown to the user instead of the SQL
 * @param transactional      false for steps Postgres forbids inside a transaction block, notably
 *                           CREATE INDEX CONCURRENTLY
 * @param pointOfNoReturn    after this step the original data is gone and the plan cannot be
 *                           rolled back by dropping shadow objects
 */
public record MigrationStep(
        int seq,
        String opGroup,
        Kind kind,
        String description,
        String sql,
        Classification classification,
        boolean transactional,
        boolean pointOfNoReturn,
        BackfillSpec backfill
) {
    public enum Kind { DDL, BACKFILL, VALIDATE, INDEX_CONCURRENT, ANALYZE, PREFLIGHT }

    /**
     * Everything the batched backfill executor needs.
     *
     * @param keyColumn the column paged over; must be orderable and unique for the paging to
     *                  terminate and cover every row
     */
    public record BackfillSpec(
            String schema,
            String table,
            String keyColumn,
            String targetColumn,
            String sourceExpression
    ) {}

    public boolean isBackfill() {
        return kind == Kind.BACKFILL;
    }
}
