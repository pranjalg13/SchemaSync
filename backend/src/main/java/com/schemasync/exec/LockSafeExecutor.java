package com.schemasync.exec;

import com.schemasync.branch.SchemaSyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs a DDL statement without letting it take the table offline.
 *
 * <p>The failure this exists to prevent is not a slow migration -- it is a <b>lock queue pileup</b>.
 * Postgres grants table locks in arrival order, so a statement <em>waiting</em> for ACCESS
 * EXCLUSIVE also blocks every later request that conflicts with it, even ones that are perfectly
 * compatible with the locks currently held:
 *
 * <pre>
 *   t0  a report:    SELECT ... FROM orders     ACCESS SHARE, granted, runs 4 minutes
 *   t1  migration:   ALTER TABLE orders ...     ACCESS EXCLUSIVE, conflicts, queued
 *   t2  the app:     SELECT ... FROM orders     compatible with t0, conflicts with t1, QUEUED
 *   t3+ everything else on that table           QUEUED
 * </pre>
 *
 * <p>One forgotten analytics query is enough, and by the time the report finishes the connection
 * pool is exhausted, so the outage outlives the lock. A short {@code lock_timeout} converts that
 * into a failed statement we can simply retry.
 *
 * <p>Two deliberate departures from the {@code DO $$ ... $$} retry loop found in most write-ups:
 * <ul>
 *   <li><b>Retry in Java, one fresh transaction per attempt.</b> A PL/pgSQL loop with
 *       {@code pg_sleep} stays inside one transaction, so between attempts the session sits idle
 *       in transaction, pinning the xmin horizon and blocking VACUUM cluster-wide.</li>
 *   <li><b>Retry only lock failures.</b> SQLSTATE 55P03 (lock_not_available) and 40P01 (deadlock)
 *       are transient. A constraint violation or a bad cast is a real answer and must fail the
 *       run, not be attempted 25 times.</li>
 * </ul>
 */
@Component
public class LockSafeExecutor {

    private static final Logger log = LoggerFactory.getLogger(LockSafeExecutor.class);

    /** Postgres could not obtain the lock within lock_timeout. Transient: retry. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    /** Deadlock detected. Transient, and likely on the two-table FK operations. */
    private static final String DEADLOCK_DETECTED = "40P01";
    /** statement_timeout fired -- our tripwire for a misclassified operation. NOT retryable. */
    private static final String QUERY_CANCELED = "57014";

    private final DataSource dataSource;
    private final SchemaSyncProperties.Migration config;

    public LockSafeExecutor(DataSource dataSource, SchemaSyncProperties props) {
        this.dataSource = dataSource;
        this.config = props.migration();
    }

    public Result runDdl(String sql, String runId) {
        return run(List.of(sql), runId, config.ddlStatementTimeout(), true);
    }

    /** Several statements in ONE transaction, for the swap step where atomicity is the point. */
    public Result runDdlBatch(List<String> statements, String runId) {
        return run(statements, runId, config.ddlStatementTimeout(), true);
    }

    /** A long scan that takes only weak locks, so it needs a generous timeout and no lock timeout. */
    public Result runValidation(String sql, String runId) {
        return run(List.of(sql), runId, config.validateStatementTimeout(), false);
    }

    /**
     * Runs outside any transaction, for CREATE INDEX CONCURRENTLY.
     *
     * <p>Note that {@code lock_timeout} does not help here. CONCURRENTLY is not blocked on a lock;
     * it waits for existing transactions that have touched the table to <em>finish</em>. A single
     * long-lived transaction elsewhere can stall it indefinitely, which is why the runner surfaces
     * the blocking session rather than just reporting a timeout.
     */
    public Result runConcurrently(String sql, String runId) {
        long startedAt = System.nanoTime();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            applySessionSettings(conn, runId, null, Duration.ZERO);
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            return Result.ok(elapsedMs(startedAt), 1);
        } catch (SQLException e) {
            return Result.failed(e.getSQLState(), e.getMessage(), elapsedMs(startedAt));
        }
    }

    private Result run(List<String> statements, String runId, Duration statementTimeout,
                       boolean bounded) {
        long totalLockWaitMs = 0;

        for (int attempt = 1; attempt <= config.maxLockRetries(); attempt++) {
            long startedAt = System.nanoTime();
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                applySessionSettings(conn, runId,
                        bounded ? config.lockTimeout() : null, statementTimeout);
                try (Statement st = conn.createStatement()) {
                    for (String sql : statements) {
                        st.execute(sql);
                    }
                }
                conn.commit();
                return Result.ok(elapsedMs(startedAt), attempt);
            } catch (SQLException e) {
                long waited = elapsedMs(startedAt);
                totalLockWaitMs += waited;
                String state = e.getSQLState();

                if (!isRetryable(state) || attempt == config.maxLockRetries()) {
                    if (QUERY_CANCELED.equals(state)) {
                        // The tripwire fired: something classified as metadata-only took longer
                        // than a metadata change possibly could. Say so plainly rather than
                        // reporting a bare timeout.
                        return Result.failed(state,
                                "Statement exceeded " + statementTimeout
                                + ", which means it was not the metadata-only change it was "
                                + "classified as. Aborted rather than allowed to rewrite the table "
                                + "while holding ACCESS EXCLUSIVE. Original error: " + e.getMessage(),
                                totalLockWaitMs);
                    }
                    return Result.failed(state, e.getMessage(), totalLockWaitMs);
                }

                long backoff = backoffMillis(attempt);
                log.info("Lock unavailable (attempt {}/{}), retrying in {}ms: {}",
                        attempt, config.maxLockRetries(), backoff, statements.get(0));
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Result.failed("INTERRUPTED", "Migration interrupted", totalLockWaitMs);
                }
            }
        }
        return Result.failed(LOCK_NOT_AVAILABLE,
                "Could not acquire the lock after " + config.maxLockRetries() + " attempts. "
                + "Something is holding a conflicting lock on this table.", totalLockWaitMs);
    }

    private void applySessionSettings(Connection conn, String runId, Duration lockTimeout,
                                      Duration statementTimeout) throws SQLException {
        try (Statement st = conn.createStatement()) {
            // Identifies our sessions in pg_stat_activity, so a DBA watching a migration can see
            // exactly who is doing what.
            st.execute("SET application_name = 'schemasync-migrator/" + runId + "'");
            if (lockTimeout != null) {
                st.execute("SET lock_timeout = '" + lockTimeout.toMillis() + "ms'");
            }
            if (statementTimeout != null) {
                st.execute("SET statement_timeout = '" + statementTimeout.toMillis() + "ms'");
            }
            // A crashed executor must not be able to hold a lock indefinitely.
            st.execute("SET idle_in_transaction_session_timeout = '60s'");
        }
    }

    /**
     * Exponential backoff with full jitter.
     *
     * <p>The jitter is not decoration. Without it, retries land at the same offsets every time and
     * keep colliding with whatever periodic job caused the first failure -- a cron report, a
     * checkpoint -- so the migration fails all 25 attempts against a blocker it could have simply
     * waited out.
     */
    private long backoffMillis(int attempt) {
        long ceiling = Math.min(config.maxBackoff().toMillis(), 100L * (1L << Math.min(attempt, 20)));
        return ThreadLocalRandom.current().nextLong(0, Math.max(1, ceiling));
    }

    private static boolean isRetryable(String sqlState) {
        return LOCK_NOT_AVAILABLE.equals(sqlState) || DEADLOCK_DETECTED.equals(sqlState);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public record Result(boolean success, String sqlState, String error, long lockWaitMs, int attempts) {
        static Result ok(long ms, int attempts) {
            return new Result(true, null, null, ms, attempts);
        }
        static Result failed(String sqlState, String error, long ms) {
            return new Result(false, sqlState, error, ms, 0);
        }
    }
}
