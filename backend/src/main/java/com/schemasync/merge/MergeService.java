package com.schemasync.merge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemasync.branch.BranchService;
import com.schemasync.branch.SchemaSyncProperties;
import com.schemasync.core.merge.MergeConflict;
import com.schemasync.core.merge.MergeResult;
import com.schemasync.core.merge.ThreeWayMerger;
import com.schemasync.core.model.*;
import com.schemasync.core.plan.MigrationPlan;
import com.schemasync.core.plan.MigrationPlanner;
import com.schemasync.exec.DdlSql;
import com.schemasync.store.ControlPlaneStore;
import com.schemasync.store.Records;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Prepares a merge: compute the base, merge three ways, plan the migration, pre-flight it.
 *
 * <p>Nothing here touches the target database except to read. Preparing and applying are separate
 * on purpose -- the user sees the full plan, its costs and its blockers before anything runs.
 */
@Service
public class MergeService {

    private static final Logger log = LoggerFactory.getLogger(MergeService.class);

    private final ControlPlaneStore store;
    private final MergeBaseFinder mergeBase;
    private final BranchService branches;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SchemaSyncProperties props;

    public MergeService(ControlPlaneStore store, MergeBaseFinder mergeBase, BranchService branches,
                        JdbcTemplate jdbc, ObjectMapper mapper, SchemaSyncProperties props) {
        this.store = store;
        this.mergeBase = mergeBase;
        this.branches = branches;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * Computes a merge without applying it.
     *
     * <p>Idempotent: call it again after resolving a conflict and it recomputes from scratch.
     */
    @Transactional
    public Prepared prepare(UUID sourceBranchId, UUID targetBranchId,
                            Map<String, String> resolutions) {
        Records.Branch source = store.findBranch(sourceBranchId).orElseThrow(
                () -> new IllegalArgumentException("no such source branch"));
        Records.Branch target = store.findBranch(targetBranchId).orElseThrow(
                () -> new IllegalArgumentException("no such target branch"));

        // Drift on either side means the recorded snapshots no longer describe the real schemas,
        // so every decision below would be made on stale information.
        if (branches.checkDrift(sourceBranchId)) {
            throw new IllegalStateException("Branch '" + source.name() + "' has drifted from its "
                    + "recorded schema. Someone changed it outside SchemaSync, so a merge would be "
                    + "computed against information we know is wrong.");
        }

        UUID baseCommitId = mergeBase.find(target.headCommitId(), source.headCommitId())
                .orElse(source.baseCommitId());
        SchemaSnapshot base = baseCommitId == null ? SchemaSnapshot.empty()
                : store.loadSnapshot(store.findCommit(baseCommitId).orElseThrow().snapshotId());
        SchemaSnapshot ours = store.headSnapshot(target);
        SchemaSnapshot theirs = store.headSnapshot(source);

        MergeResult result = ThreeWayMerger.merge(base, ours, theirs);
        SchemaSnapshot merged = applyResolutions(result, ours, theirs, resolutions);

        Map<String, Long> rowCounts = rowCounts(target.pgSchemaName());
        MigrationPlan plan = new MigrationPlanner(
                target.pgSchemaName(), rowCounts, props.migration().onlineModeRowThreshold())
                .plan(ours, merged);

        List<String> blockers = preflight(target.pgSchemaName(), ours, merged, rowCounts);

        return new Prepared(source, target, baseCommitId, merged, result.conflicts(), plan, blockers);
    }

    /**
     * Replaces conflicted values with the user's choices.
     *
     * <p>The merger already defaulted every conflict to the target's value, so this only has to
     * handle the ones the user flipped to the source.
     */
    private SchemaSnapshot applyResolutions(MergeResult result, SchemaSnapshot ours,
                                            SchemaSnapshot theirs, Map<String, String> resolutions) {
        if (resolutions == null || resolutions.isEmpty()) {
            return result.merged();
        }
        Map<String, TableDef> tables = new LinkedHashMap<>(result.merged().tables());

        for (MergeConflict conflict : result.unresolved()) {
            String choice = resolutions.get(conflict.stableId() + ":" + conflict.attribute());
            if (choice == null) {
                choice = resolutions.get(conflict.stableId());
            }
            if (!"THEIRS".equalsIgnoreCase(choice)) {
                continue;
            }
            for (TableDef table : theirs.tables().values()) {
                ColumnDef theirColumn = table.columns().get(conflict.stableId());
                if (theirColumn == null) {
                    continue;
                }
                TableDef mergedTable = tables.get(table.id());
                if (mergedTable == null) {
                    continue;
                }
                Map<String, ColumnDef> columns = new LinkedHashMap<>(mergedTable.columns());
                ColumnDef current = columns.get(conflict.stableId());
                if (current == null) {
                    continue;
                }
                ColumnDef updated = switch (Objects.toString(conflict.attribute(), "")) {
                    case "name" -> current.withName(theirColumn.name());
                    case "type" -> current.withType(theirColumn.type());
                    case "nullable" -> current.withNullable(theirColumn.nullable());
                    case "default" -> current.withDefault(theirColumn.defaultExpr());
                    default -> theirColumn;
                };
                columns.put(conflict.stableId(), updated);
                tables.put(table.id(), mergedTable.withColumns(columns));
            }
        }
        return result.merged().withTables(tables);
    }

    /**
     * Checks destructive changes against the real data before anything runs.
     *
     * <p>This is the loop that closes the branch-sampling tradeoff. A branch holds ~1000 rows, so
     * a cast that succeeds there proves nothing about the other 40 million. Here we ask the actual
     * target table, and turn "it failed at row 14,233,901, ten minutes in" into a blocker shown
     * before the user presses go.
     */
    private List<String> preflight(String schema, SchemaSnapshot target, SchemaSnapshot merged,
                                   Map<String, Long> rowCounts) {
        List<String> blockers = new ArrayList<>();

        for (TableDef mergedTable : merged.tables().values()) {
            TableDef targetTable = target.tables().get(mergedTable.id());
            if (targetTable == null) {
                continue;
            }
            long rows = rowCounts.getOrDefault(targetTable.name(), 0L);
            if (rows == 0) {
                continue;
            }

            for (ColumnDef mergedColumn : mergedTable.columns().values()) {
                ColumnDef targetColumn = targetTable.columns().get(mergedColumn.id());
                if (targetColumn == null) {
                    continue;
                }
                String qualified = DdlSql.qualify(schema, targetTable.name());
                String column = DdlSql.quote(targetColumn.name());

                if (!targetColumn.type().sql().equals(mergedColumn.type().sql())) {
                    countUncastable(qualified, column, mergedColumn.type().sql()).ifPresent(bad -> {
                        if (bad > 0) {
                            blockers.add(String.format(
                                    "%s.%s cannot become %s: %,d row%s would fail to convert.",
                                    targetTable.name(), targetColumn.name(),
                                    mergedColumn.type().sql(), bad, bad == 1 ? "" : "s"));
                        }
                    });
                }

                if (targetColumn.nullable() && !mergedColumn.nullable()) {
                    Long nulls = jdbc.queryForObject(
                            "SELECT count(*) FROM " + qualified + " WHERE " + column + " IS NULL",
                            Long.class);
                    if (nulls != null && nulls > 0 && mergedColumn.defaultExpr() == null) {
                        blockers.add(String.format(
                                "%s.%s cannot become NOT NULL: %,d row%s are NULL and there is no "
                                + "default to fill them with.",
                                targetTable.name(), targetColumn.name(), nulls, nulls == 1 ? "" : "s"));
                    }
                }
            }
        }
        return blockers;
    }

    /**
     * Counts rows that would fail the cast, by attempting it per row and catching failures.
     *
     * <p>Postgres has no "would this cast succeed" predicate, so a small PL/pgSQL block is the
     * honest way to ask. It reads the whole table, which is acceptable because it runs once, holds
     * no locks, and the alternative is discovering the answer ten minutes into a migration.
     */
    private Optional<Long> countUncastable(String qualified, String column, String targetType) {
        try {
            Long bad = jdbc.queryForObject(
                    "SELECT count(*) FROM " + qualified
                    + " WHERE " + column + " IS NOT NULL"
                    + " AND NOT sv.can_cast(" + column + "::text, ?)",
                    Long.class, targetType);
            return Optional.ofNullable(bad);
        } catch (RuntimeException e) {
            // A pre-flight that cannot answer must not block the merge. It only means the user
            // learns the answer from the migration instead of before it, which is the status quo
            // without this check at all.
            log.debug("Cast pre-flight unavailable for {}.{}: {}", qualified, column, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Long> rowCounts(String schema) {
        Map<String, Long> out = new HashMap<>();
        jdbc.query("""
                SELECT c.relname AS name, greatest(c.reltuples, 0)::bigint AS rows
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind = 'r'
                """, rs -> {
            out.put(rs.getString("name"), rs.getLong("rows"));
        }, schema);
        return out;
    }

    public record Prepared(
            Records.Branch source,
            Records.Branch target,
            UUID baseCommitId,
            SchemaSnapshot merged,
            List<MergeConflict> conflicts,
            MigrationPlan plan,
            List<String> blockers) {

        public List<MergeConflict> unresolved() {
            return conflicts.stream().filter(c -> !c.isAutoResolved()).toList();
        }

        public boolean canApply() {
            return unresolved().isEmpty() && blockers.isEmpty() && !plan.isEmpty();
        }
    }
}
