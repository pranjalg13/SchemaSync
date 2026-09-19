package com.schemasync.exec;

import com.schemasync.branch.SchemaSyncProperties;
import com.schemasync.core.plan.MigrationStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Fills a column across a large table without a long transaction.
 *
 * <p>Keyset pagination over the primary key, one committed transaction per batch. Both parts
 * matter:
 *
 * <ul>
 *   <li><b>Keyset, not OFFSET.</b> {@code WHERE id > :cursor ORDER BY id LIMIT n} is an index
 *       range scan, so every batch costs the same regardless of how far in it is. OFFSET re-reads
 *       everything it skips and gets quadratically slower.</li>
 *   <li><b>One transaction per batch.</b> A single UPDATE over 40M rows holds 40M row locks, writes
 *       gigabytes of WAL in one go, and -- worst of all -- pins the cluster-wide xmin horizon for
 *       its whole duration, so VACUUM cannot reclaim dead tuples in <em>any</em> table meanwhile.
 *       It also loses everything on a crash at 95%.</li>
 * </ul>
 *
 * <p>The cursor is committed <b>in the same transaction as the batch</b>, so recorded progress can
 * never disagree with the data. That is what makes resume correct rather than approximate.
 */
@Component
public class BackfillExecutor {

    private static final Logger log = LoggerFactory.getLogger(BackfillExecutor.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SchemaSyncProperties.Migration config;

    public BackfillExecutor(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                            SchemaSyncProperties props) {
        this.jdbc = jdbc;
        // A TransactionTemplate rather than @Transactional on a method of this class: the batch
        // loop calls it on `this`, and self-invocation bypasses Spring's proxy, so an annotation
        // there is silently ignored. That is exactly what the first version did -- the batch and
        // its cursor were committed separately while the comments claimed otherwise.
        this.tx = new TransactionTemplate(txManager);
        this.config = props.migration();
    }

    /**
     * @param stepId   the migration_step this backfill belongs to; also the cursor's key
     * @param progress called after each batch so the UI can stream real numbers
     */
    public void run(long stepId, MigrationStep.BackfillSpec spec, Consumer<Progress> progress) {
        String qualified = DdlSql.qualify(spec.schema(), spec.table());
        String key = DdlSql.quote(spec.keyColumn());
        String target = DdlSql.quote(spec.targetColumn());

        Cursor cursor = loadOrCreateCursor(stepId, spec, qualified);
        int batchSize = cursor.batchSize();
        long rowsDone = cursor.rowsDone();
        String lastKey = cursor.lastKey();

        while (true) {
            long startedAt = System.nanoTime();

            // The cursor advances over every key SCANNED, not just those updated. If it only
            // advanced over updated rows, a batch where everything was already in sync (because
            // the trigger got there first) would return zero and loop on the same range forever.
            String sql = """
                    WITH batch AS (
                        SELECT %s AS k FROM %s
                        %s
                        ORDER BY %s
                        LIMIT %d
                    ), upd AS (
                        UPDATE %s t SET %s = %s
                        FROM batch b WHERE t.%s = b.k
                          AND t.%s IS DISTINCT FROM %s
                        RETURNING 1
                    )
                    SELECT (SELECT max(k)::text FROM batch)  AS next_cursor,
                           (SELECT count(*) FROM batch)      AS scanned,
                           (SELECT count(*) FROM upd)        AS updated
                    """.formatted(
                    key, qualified,
                    lastKey == null ? "" : "WHERE " + key + " > " + quoteLiteral(lastKey),
                    key, batchSize,
                    qualified, target, spec.sourceExpression(),
                    key,
                    target, spec.sourceExpression());

            final String cursorBefore = lastKey;
            final long doneBefore = rowsDone;
            final int size = batchSize;
            Map<String, Object> result;
            try {
                // The batch UPDATE and the cursor that records it commit in ONE transaction, so
                // recorded progress can never run ahead of, or fall behind, the data.
                result = tx.execute(status -> {
                    jdbc.execute("SET LOCAL statement_timeout = '"
                            + config.batchStatementTimeout().toMillis() + "ms'");
                    Map<String, Object> r = jdbc.queryForMap(sql);
                    String next = (String) r.get("next_cursor");
                    long updated = ((Number) r.get("updated")).longValue();
                    persistCursor(stepId, next != null ? next : cursorBefore, doneBefore + updated, size);
                    return r;
                });
            } catch (RuntimeException e) {
                // A cast can fail deep into a backfill -- text to integer meets one bad row at
                // 14 million. Record the range and stop WITHOUT advancing, so the operator gets
                // an actionable message and the work already done is not lost.
                recordFailure(stepId, lastKey, e);
                throw new BackfillFailedException(
                        "Backfill of " + spec.table() + "." + spec.targetColumn()
                        + " failed after " + rowsDone + " rows, at " + spec.keyColumn()
                        + " > " + lastKey + ": " + rootMessage(e), e);
            }

            long scanned = ((Number) result.get("scanned")).longValue();
            long updated = ((Number) result.get("updated")).longValue();
            String nextCursor = (String) result.get("next_cursor");
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            rowsDone += updated;
            if (nextCursor != null) {
                lastKey = nextCursor;
            }

            progress.accept(new Progress(rowsDone, cursor.rowsEstimated(), batchSize, elapsedMs));

            if (scanned < batchSize) {
                log.info("Backfill of {}.{} complete: {} rows updated",
                        spec.table(), spec.targetColumn(), rowsDone);
                return;
            }

            batchSize = adapt(batchSize, elapsedMs);
            throttle(elapsedMs);
        }
    }

    /**
     * Converges on a batch size rather than using a fixed one.
     *
     * <p>A good size depends on row width, index count and how busy the disk is -- none of which
     * we can know in advance. Additive-increase / multiplicative-decrease finds it in a few
     * batches and keeps finding it as conditions change.
     */
    private int adapt(int current, long elapsedMs) {
        long target = config.targetBatchDuration().toMillis();
        if (elapsedMs < target / 2) {
            return Math.min(current * 2, config.maxBatchSize());
        }
        if (elapsedMs > target) {
            return Math.max(current / 2, config.minBatchSize());
        }
        return current;
    }

    /**
     * Sleeps between batches in proportion to how long the last one took.
     *
     * <p>One line, and it is the difference between a migration and a migration that takes the
     * site down by saturating disk IO. It also gives autovacuum room to keep up with the dead
     * tuples the backfill is generating.
     */
    private void throttle(long lastBatchMs) {
        long pause = (long) (lastBatchMs * config.throttleRatio());
        if (pause <= 0) {
            return;
        }
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Cursor loadOrCreateCursor(long stepId, MigrationStep.BackfillSpec spec, String qualified) {
        return jdbc.query("SELECT * FROM sv.backfill_cursor WHERE step_id = ?",
                rs -> {
                    if (rs.next()) {
                        return new Cursor(rs.getString("last_key"), rs.getLong("rows_done"),
                                rs.getLong("rows_estimated"), rs.getInt("batch_size"));
                    }
                    Long estimate = jdbc.queryForObject(
                            "SELECT greatest(reltuples, 0)::bigint FROM pg_class c "
                            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE n.nspname = ? AND c.relname = ?",
                            Long.class, spec.schema(), spec.table());
                    jdbc.update("""
                            INSERT INTO sv.backfill_cursor
                                (step_id, key_column, rows_estimated, batch_size)
                            VALUES (?, ?, ?, ?)
                            """, stepId, spec.keyColumn(), estimate == null ? 0 : estimate,
                            config.initialBatchSize());
                    return new Cursor(null, 0, estimate == null ? 0 : estimate,
                            config.initialBatchSize());
                }, stepId);
    }

    private void persistCursor(long stepId, String lastKey, long rowsDone, int batchSize) {
        jdbc.update("""
                UPDATE sv.backfill_cursor
                SET last_key = ?, rows_done = ?, batch_size = ?,
                    batches_done = batches_done + 1, updated_at = now()
                WHERE step_id = ?
                """, lastKey, rowsDone, batchSize, stepId);
    }

    private void recordFailure(long stepId, String lowKey, RuntimeException e) {
        String sqlState = null;
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.sql.SQLException sqle) {
                sqlState = sqle.getSQLState();
                break;
            }
            t = t.getCause();
        }
        jdbc.update("""
                INSERT INTO sv.backfill_failure (step_id, low_key, sqlstate, message)
                VALUES (?, ?, ?, ?)
                """, stepId, lowKey, sqlState, rootMessage(e));
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage();
    }

    /**
     * Cursor values are read back from the database and re-interpolated, so they are quoted as
     * literals here. They originate from a primary key column we chose, never from user input,
     * but interpolating anything unquoted into SQL is a habit worth not having.
     */
    private static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    public record Progress(long rowsDone, long rowsEstimated, int batchSize, long lastBatchMs) {
        public double fraction() {
            return rowsEstimated <= 0 ? 0 : Math.min(1.0, (double) rowsDone / rowsEstimated);
        }
    }

    private record Cursor(String lastKey, long rowsDone, long rowsEstimated, int batchSize) {}

    public static class BackfillFailedException extends RuntimeException {
        public BackfillFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
