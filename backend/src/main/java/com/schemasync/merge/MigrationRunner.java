package com.schemasync.merge;

import com.schemasync.core.model.SchemaSnapshot;
import com.schemasync.core.plan.MigrationPlan;
import com.schemasync.core.plan.MigrationStep;
import com.schemasync.exec.BackfillExecutor;
import com.schemasync.exec.LockSafeExecutor;
import com.schemasync.store.ControlPlaneStore;
import com.schemasync.store.Records;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Executes a migration plan against the target database.
 *
 * <p>Every step's status is persisted before it is reported, so a refresh shows exactly what a
 * reconnecting client sees, and a crash leaves an accurate record of how far things got.
 *
 * <p>Note the asymmetry in how state is written, which is the whole design in miniature: step
 * status is committed in its <b>own</b> transaction, because you must never extend a transaction
 * that is holding ACCESS EXCLUSIVE just to record progress. Backfill cursors commit in the
 * <b>same</b> transaction as their batch, because those transactions are short and the atomicity
 * of data-plus-cursor is exactly what makes resume correct.
 */
@Service
public class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private final JdbcTemplate jdbc;
    private final ControlPlaneStore store;
    private final LockSafeExecutor executor;
    private final BackfillExecutor backfill;
    private final TransactionTemplate tx;
    private final DataSource dataSource;

    public MigrationRunner(JdbcTemplate jdbc, ControlPlaneStore store, LockSafeExecutor executor,
                           BackfillExecutor backfill, PlatformTransactionManager txManager,
                           DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.store = store;
        this.executor = executor;
        this.backfill = backfill;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Persists a plan so it can be executed, resumed and streamed. */
    @Transactional
    public UUID createRun(UUID mergeRequestId, String targetSchema, MigrationPlan plan) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sv.migration_run (id, merge_request_id, target_schema, mode, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, runId, mergeRequestId, targetSchema, plan.mode().name());

        for (MigrationStep step : plan.steps()) {
            jdbc.update("""
                    INSERT INTO sv.migration_step
                        (run_id, seq, op_group, kind, description, sql_text, lock_mode, blocks,
                         reversible, point_of_no_return)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, runId, step.seq(), step.opGroup(), step.kind().name(), step.description(),
                    step.sql(), step.classification().lock().name(),
                    step.classification().blocks().name(),
                    !step.pointOfNoReturn(), step.pointOfNoReturn());
        }
        return runId;
    }

    /**
     * Runs the plan to completion.
     *
     * <p>An advisory lock keeps two migrations off the same schema. Two concurrent
     * SHARE UPDATE EXCLUSIVE operations on one table conflict with each other, so without this a
     * second migration would not corrupt anything but would deadlock or stall unpredictably.
     */
    public void execute(UUID runId, UUID mergeRequestId, MigrationPlan plan,
                        String targetSchema, SchemaSnapshot merged, UUID sourceBranchId,
                        UUID targetBranchId, UUID baseCommitId, String author) {
        // The advisory lock is taken and released on ONE dedicated connection held for the whole
        // run. A session advisory lock belongs to the connection that took it; going through
        // JdbcTemplate, lock and unlock could each borrow a different pooled connection, so the
        // unlock would silently do nothing and the lock would outlive the migration. It only
        // appeared to work because Hikari tends to hand a thread back its last connection.
        try (Connection lockConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(true);
            if (!advisoryLock(lockConnection, "pg_try_advisory_lock", targetSchema)) {
                markRunFailed(runId, "LOCKED",
                        "Another migration is already running against this schema.");
                return;
            }
            try {
                jdbc.update("UPDATE sv.migration_run SET status='RUNNING', started_at=now(), "
                        + "heartbeat_at=now() WHERE id=?", runId);

                for (MigrationStep step : plan.steps()) {
                    if (!runStep(runId, step)) {
                        markRunFailed(runId, "STEP_FAILED", "Step " + step.seq() + " failed.");
                        return;
                    }
                }

                finish(runId, mergeRequestId, merged, sourceBranchId, targetBranchId, baseCommitId, author);
            } catch (RuntimeException e) {
                log.error("Migration {} failed", runId, e);
                markRunFailed(runId, "ERROR", e.getMessage());
            } finally {
                advisoryLock(lockConnection, "pg_advisory_unlock", targetSchema);
            }
        } catch (SQLException e) {
            log.error("Migration {} could not obtain a connection for its lock", runId, e);
            markRunFailed(runId, e.getSQLState(), e.getMessage());
        }
    }

    private static boolean advisoryLock(Connection conn, String function, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + function + "(hashtext(?))")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private boolean runStep(UUID runId, MigrationStep step) {
        Long stepId = jdbc.queryForObject(
                "SELECT id FROM sv.migration_step WHERE run_id=? AND seq=?",
                Long.class, runId, step.seq());
        if (stepId == null) {
            return false;
        }
        setStepStatus(stepId, "RUNNING", null, null, null);
        log.info("run {} step {}: {}", runId, step.seq(), step.description());

        try {
            switch (step.kind()) {
                case BACKFILL -> {
                    backfill.run(stepId, step.backfill(), progress ->
                            jdbc.update("UPDATE sv.migration_run SET heartbeat_at=now() WHERE id=?", runId));
                    setStepStatus(stepId, "SUCCEEDED", null, null, null);
                }
                case INDEX_CONCURRENT -> {
                    // Cannot run in a transaction block, and may leave an INVALID index behind on
                    // failure -- which still costs write overhead and, if unique, keeps enforcing
                    // uniqueness. So clean up before retrying rather than leaving debris.
                    dropInvalidIndexes(step);
                    LockSafeExecutor.Result r = executor.runConcurrently(step.sql(), runId.toString());
                    if (!r.success()) {
                        dropInvalidIndexes(step);
                        setStepStatus(stepId, "FAILED", r.sqlState(), r.error(), r.lockWaitMs());
                        return false;
                    }
                    setStepStatus(stepId, "SUCCEEDED", null, null, r.lockWaitMs());
                }
                case VALIDATE -> {
                    LockSafeExecutor.Result r = executor.runValidation(step.sql(), runId.toString());
                    if (!r.success()) {
                        setStepStatus(stepId, "FAILED", r.sqlState(), r.error(), r.lockWaitMs());
                        return false;
                    }
                    setStepStatus(stepId, "SUCCEEDED", null, null, r.lockWaitMs());
                }
                default -> {
                    // A step may carry several statements that must be atomic together -- the
                    // column swap is the important one, where a partial application would leave
                    // the table with neither the old column nor the new.
                    List<String> statements = List.of(step.sql().split(";\\s*\\n"));
                    LockSafeExecutor.Result r = executor.runDdlBatch(statements, runId.toString());
                    if (!r.success()) {
                        setStepStatus(stepId, "FAILED", r.sqlState(), r.error(), r.lockWaitMs());
                        return false;
                    }
                    setStepStatus(stepId, "SUCCEEDED", null, null, r.lockWaitMs());
                }
            }
            return true;
        } catch (RuntimeException e) {
            setStepStatus(stepId, "FAILED", null, e.getMessage(), null);
            return false;
        }
    }

    /**
     * Removes any INVALID index left by a failed CONCURRENTLY build.
     *
     * <p>Postgres leaves these behind deliberately so you can inspect them, but they are pure cost
     * until removed: ignored by the planner while still being maintained on every write.
     */
    private void dropInvalidIndexes(MigrationStep step) {
        if (step.sql() == null) {
            return;
        }
        String name = extractIndexName(step.sql());
        if (name == null) {
            return;
        }
        jdbc.query("""
                SELECT n.nspname AS schema, c.relname AS name
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relname = ? AND NOT i.indisvalid
                """, rs -> {
            String sql = "DROP INDEX CONCURRENTLY IF EXISTS \"" + rs.getString("schema")
                    + "\".\"" + rs.getString("name") + "\"";
            log.warn("Dropping invalid index left by a failed concurrent build: {}", sql);
            executor.runConcurrently(sql, "cleanup");
        }, name);
    }

    private static String extractIndexName(String sql) {
        var m = java.util.regex.Pattern
                .compile("INDEX\\s+(?:CONCURRENTLY\\s+)?\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Records the merge commit and advances the target branch, atomically.
     *
     * <p>Via TransactionTemplate: this is called on `this` from execute(), and a @Transactional
     * annotation on a self-invoked method is silently ignored by Spring's proxy.
     */
    private void finish(UUID runId, UUID mergeRequestId, SchemaSnapshot merged,
                        UUID sourceBranchId, UUID targetBranchId, UUID baseCommitId, String author) {
        tx.executeWithoutResult(status -> recordMerge(runId, mergeRequestId, merged,
                sourceBranchId, targetBranchId, author));
    }

    private void recordMerge(UUID runId, UUID mergeRequestId, SchemaSnapshot merged,
                             UUID sourceBranchId, UUID targetBranchId, String author) {
        Records.Branch target = store.findBranch(targetBranchId).orElseThrow();
        Records.Branch source = store.findBranch(sourceBranchId).orElseThrow();

        UUID snapshotId = store.saveSnapshot(merged);
        // Two parents: this is what makes the commit graph a DAG and lets a later merge base
        // computation know that the source's history is now part of the target's.
        Records.Commit commit = store.commit(target.projectId(), targetBranchId,
                target.headCommitId(), source.headCommitId(), snapshotId,
                "Merge '" + source.name() + "' into '" + target.name() + "'", author);

        jdbc.update("UPDATE sv.merge_request SET state='APPLIED', applied_at=now(), "
                + "result_commit_id=? WHERE id=?", commit.id(), mergeRequestId);
        jdbc.update("UPDATE sv.branch SET status='MERGED', merged_at=now() WHERE id=?", sourceBranchId);
        jdbc.update("UPDATE sv.migration_run SET status='SUCCEEDED', finished_at=now() WHERE id=?", runId);

        log.info("Merged '{}' into '{}' as commit {}", source.name(), target.name(), commit.id());
    }

    /** Each status write is its own autocommit statement, so it is durable the moment it runs. */
    private void setStepStatus(long stepId, String status, String sqlState, String error,
                                 Long lockWaitMs) {
        jdbc.update("""
                UPDATE sv.migration_step
                SET status = ?,
                    error_sqlstate = ?,
                    error_message = ?,
                    lock_wait_ms = coalesce(?, lock_wait_ms),
                    started_at = CASE WHEN ? = 'RUNNING' THEN now() ELSE started_at END,
                    finished_at = CASE WHEN ? IN ('SUCCEEDED','FAILED','SKIPPED') THEN now() ELSE finished_at END
                WHERE id = ?
                """, status, sqlState, truncate(error), lockWaitMs, status, status, stepId);
    }

    private void markRunFailed(UUID runId, String sqlState, String message) {
        jdbc.update("""
                UPDATE sv.migration_run
                SET status='FAILED', finished_at=now(), error_sqlstate=?, error_message=?
                WHERE id=?
                """, sqlState, truncate(message), runId);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "...";
    }
}
