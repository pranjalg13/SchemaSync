package com.schemasync.core.plan;

import com.schemasync.core.model.*;
import com.schemasync.ops.SchemaOperation;
import com.schemasync.ops.SnapshotMutator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planner, tested without a database.
 *
 * <p>The theme running through these: the same logical change must produce a <em>different plan</em>
 * depending on how much data is underneath it. Five steps to retype 200 rows would be ceremony;
 * one step to retype 40 million would be an outage.
 */
class MigrationPlannerTest {

    private static final long THRESHOLD = 100_000;

    private static SchemaSnapshot ordersSchema() {
        Map<String, ColumnDef> cols = new LinkedHashMap<>();
        cols.put("col_id", new ColumnDef("col_id", "id", 1, DataType.of("bigint"), false, null, "BY DEFAULT"));
        cols.put("col_amt", new ColumnDef("col_amt", "amount", 2, DataType.of("integer"), false, null, null));
        cols.put("col_notes", new ColumnDef("col_notes", "notes", 3, DataType.of("text"), true, null, null));

        Map<String, ConstraintDef> cons = new LinkedHashMap<>();
        cons.put("con_pk", new ConstraintDef("con_pk", "orders_pkey",
                ConstraintDef.Kind.PRIMARY_KEY, List.of("col_id"), null, null));

        return SchemaSnapshot.of(Map.of("tbl_orders",
                new TableDef("tbl_orders", "orders", cols, cons, new LinkedHashMap<>())));
    }

    private static MigrationPlan planFor(SchemaSnapshot target, SchemaSnapshot merged, long rows) {
        return new MigrationPlanner("main", Map.of("orders", rows), THRESHOLD).plan(target, merged);
    }

    @Test
    @DisplayName("a small table takes the direct path; a large one is expanded online")
    void strategyDependsOnTableSize() {
        SchemaSnapshot target = ordersSchema();
        SchemaSnapshot merged = SnapshotMutator.apply(target,
                new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "numeric(14,2)", null));

        MigrationPlan small = planFor(target, merged, 500);
        assertThat(small.mode()).isEqualTo(MigrationPlan.Mode.ATOMIC);
        assertThat(small.steps()).hasSize(1);
        assertThat(small.steps().get(0).sql()).contains("ALTER COLUMN", "TYPE numeric(14,2)");

        MigrationPlan large = planFor(target, merged, 40_000_000);
        assertThat(large.mode()).isEqualTo(MigrationPlan.Mode.ONLINE);
        assertThat(large.steps()).hasSizeGreaterThan(5);
        // Crucially, the naive statement is never issued on the big table.
        assertThat(large.steps())
                .noneMatch(s -> s.sql() != null
                        && s.sql().contains("ALTER COLUMN \"amount\" TYPE"));
    }

    @Test
    @DisplayName("the online retype follows expand, sync, backfill, swap in that order")
    void onlineRetypeSequence() {
        SchemaSnapshot target = ordersSchema();
        SchemaSnapshot merged = SnapshotMutator.apply(target,
                new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "numeric(14,2)", null));

        List<MigrationStep> steps = planFor(target, merged, 40_000_000).steps();

        int shadow = indexOfSql(steps, "ADD COLUMN \"__sv_amount__new\"");
        int trigger = indexOfSql(steps, "CREATE TRIGGER");
        int backfill = indexOfKind(steps, MigrationStep.Kind.BACKFILL);
        int swap = indexOfSql(steps, "RENAME COLUMN \"__sv_amount__new\"");

        assertThat(shadow).isGreaterThanOrEqualTo(0);
        assertThat(trigger).isGreaterThan(shadow);
        // The correctness argument for the whole pattern: the trigger must be in place before the
        // first batch, so every row is covered by either the trigger or the backfill.
        assertThat(backfill).isGreaterThan(trigger);
        assertThat(swap).isGreaterThan(backfill);
        assertThat(steps.get(swap).pointOfNoReturn()).isTrue();
    }

    @Test
    @DisplayName("ANALYZE follows the swap, because the swap drops the column's statistics")
    void analyzeAfterSwap() {
        SchemaSnapshot target = ordersSchema();
        SchemaSnapshot merged = SnapshotMutator.apply(target,
                new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "numeric(14,2)", null));
        List<MigrationStep> steps = planFor(target, merged, 40_000_000).steps();

        assertThat(indexOfKind(steps, MigrationStep.Kind.ANALYZE))
                .isGreaterThan(indexOfSql(steps, "RENAME COLUMN \"__sv_amount__new\""));
    }

    @Test
    @DisplayName("SET NOT NULL validates a check first so the scan is skipped")
    void setNotNullAvoidsTheScan() {
        SchemaSnapshot target = ordersSchema();
        SchemaSnapshot merged = SnapshotMutator.apply(target,
                new SchemaOperation.SetColumnNullable("tbl_orders", "col_notes", false, null));

        List<MigrationStep> steps = planFor(target, merged, 40_000_000).steps();

        int notValid = indexOfSql(steps, "NOT VALID");
        int validate = indexOfKind(steps, MigrationStep.Kind.VALIDATE);
        int setNotNull = indexOfSql(steps, "SET NOT NULL");

        assertThat(notValid).isGreaterThanOrEqualTo(0);
        assertThat(validate).isGreaterThan(notValid);
        assertThat(setNotNull).isGreaterThan(validate);
        // The payoff: by the time SET NOT NULL runs, a validated CHECK already proves the
        // property, so PG 12+ skips the full scan it would otherwise do under ACCESS EXCLUSIVE.
        assertThat(steps.get(setNotNull).classification().verdict())
                .isEqualTo(Classification.Verdict.INSTANT);
    }

    @Test
    @DisplayName("a big index build goes CONCURRENTLY, which forces the plan out of ATOMIC")
    void largeIndexUsesConcurrently() {
        SchemaSnapshot target = ordersSchema();
        SchemaSnapshot merged = SnapshotMutator.apply(target, new SchemaOperation.AddIndex(
                "tbl_orders", "orders_notes_idx", List.of("col_notes"), false, "btree"));

        MigrationPlan small = planFor(target, merged, 500);
        assertThat(small.mode()).isEqualTo(MigrationPlan.Mode.ATOMIC);
        assertThat(small.steps().get(0).sql()).doesNotContain("CONCURRENTLY");

        MigrationPlan large = planFor(target, merged, 40_000_000);
        assertThat(large.steps().get(0).sql()).contains("CONCURRENTLY");
        // CREATE INDEX CONCURRENTLY cannot run inside a transaction block, and that single fact
        // is what costs the whole plan its all-or-nothing guarantee.
        assertThat(large.steps().get(0).transactional()).isFalse();
        assertThat(large.mode()).isEqualTo(MigrationPlan.Mode.ONLINE);
    }

    @Test
    @DisplayName("a rename is one instant step at any table size")
    void renameIsAlwaysInstant() {
        SchemaSnapshot target = ordersSchema();
        SchemaSnapshot merged = SnapshotMutator.apply(target,
                new SchemaOperation.RenameColumn("tbl_orders", "col_amt", "amount_cents"));

        for (long rows : new long[]{0, 500, 40_000_000}) {
            MigrationPlan plan = planFor(target, merged, rows);
            assertThat(plan.steps()).hasSize(1);
            assertThat(plan.steps().get(0).sql()).contains("RENAME COLUMN");
            assertThat(plan.steps().get(0).classification().verdict())
                    .isEqualTo(Classification.Verdict.INSTANT);
            assertThat(plan.mode()).isEqualTo(MigrationPlan.Mode.ATOMIC);
            // And never a DROP: that is the whole point of tracking identity.
            assertThat(plan.steps().get(0).sql()).doesNotContain("DROP COLUMN");
        }
    }

    @Test
    @DisplayName("drops precede creates, so a freed name can be reused in one merge")
    void dropsBeforeCreates() {
        SchemaSnapshot target = ordersSchema();
        SchemaSnapshot merged = SnapshotMutator.apply(
                SnapshotMutator.apply(target, new SchemaOperation.DropColumn("tbl_orders", "col_notes")),
                new SchemaOperation.AddColumn("tbl_orders", "memo", "text", true, null));

        List<MigrationStep> steps = planFor(target, merged, 1000).steps();
        assertThat(indexOfSql(steps, "DROP COLUMN"))
                .isLessThan(indexOfSql(steps, "ADD COLUMN"));
    }

    @Test
    @DisplayName("destructive changes produce a warning naming the row count")
    void destructiveChangesWarn() {
        SchemaSnapshot target = ordersSchema();
        SchemaSnapshot merged = SnapshotMutator.apply(target,
                new SchemaOperation.DropColumn("tbl_orders", "col_notes"));

        MigrationPlan plan = planFor(target, merged, 40_000_000);
        assertThat(plan.preflightWarnings()).isNotEmpty();
        assertThat(plan.preflightWarnings().get(0)).contains("40.0M");
        assertThat(plan.hasPointOfNoReturn()).isTrue();
    }

    @Test
    @DisplayName("no changes means no plan")
    void emptyPlan() {
        SchemaSnapshot s = ordersSchema();
        assertThat(planFor(s, s, 40_000_000).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("every step explains itself in plain English")
    void everyStepIsDescribed() {
        SchemaSnapshot target = ordersSchema();
        SchemaSnapshot merged = SnapshotMutator.apply(target,
                new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "numeric(14,2)", null));

        // The plan is shown to a human before they approve it; a step with no description or an
        // empty rationale is a bug, not a cosmetic gap.
        assertThat(planFor(target, merged, 40_000_000).steps()).allSatisfy(s -> {
            assertThat(s.description()).isNotBlank();
            assertThat(s.classification().rationale()).isNotBlank();
        });
    }

    @Test
    @DisplayName("attaching the sync trigger is instant and blocks writes, never reads")
    void triggerIsNotAScan() {
        // Found in the UI: this step was badged "reads every row" while its own rationale said
        // "no table scan". CREATE TRIGGER reads no rows; it only waits for in-flight writers.
        SchemaSnapshot target = ordersSchema();
        SchemaSnapshot merged = SnapshotMutator.apply(target,
                new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "numeric(14,2)", null));
        MigrationStep trigger = planFor(target, merged, 40_000_000).steps()
                .get(indexOfSql(planFor(target, merged, 40_000_000).steps(), "CREATE TRIGGER"));

        assertThat(trigger.classification().verdict()).isEqualTo(Classification.Verdict.INSTANT);
        assertThat(trigger.classification().blocks()).isEqualTo(Classification.Blocks.WRITES);
        assertThat(trigger.classification().lock()).isEqualTo(Classification.LockMode.SHARE_ROW_EXCLUSIVE);
    }

    private static int indexOfSql(List<MigrationStep> steps, String fragment) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).sql() != null && steps.get(i).sql().contains(fragment)) return i;
        }
        return -1;
    }

    private static int indexOfKind(List<MigrationStep> steps, MigrationStep.Kind kind) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).kind() == kind) return i;
        }
        return -1;
    }
}
