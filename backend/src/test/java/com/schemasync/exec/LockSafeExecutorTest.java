package com.schemasync.exec;

import com.schemasync.PostgresTestBase;
import com.schemasync.branch.SchemaSyncProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The executor against a real Postgres.
 *
 * <p>The pool here has exactly ONE connection, so every call reuses the same session. That is
 * deliberate: the settings-leak bug only showed up when the API happened to borrow a connection a
 * migration had used, and with a large pool a test could pass by luck.
 */
class LockSafeExecutorTest extends PostgresTestBase {

    private HikariDataSource pool;
    private JdbcTemplate pooled;
    private LockSafeExecutor executor;
    private String schema;

    @BeforeEach
    void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername(postgres.getUsername());
        cfg.setPassword(postgres.getPassword());
        cfg.setMaximumPoolSize(1);
        pool = new HikariDataSource(cfg);
        pooled = new JdbcTemplate(pool);

        SchemaSyncProperties props = new SchemaSyncProperties("main", "br_", 1000,
                new SchemaSyncProperties.Migration(
                        Duration.ofMillis(300),   // lock timeout
                        Duration.ofSeconds(15),   // ddl statement timeout
                        Duration.ofMinutes(30),   // validate statement timeout
                        Duration.ofSeconds(30),   // batch statement timeout
                        3,                        // lock retries
                        Duration.ofMillis(200),   // max backoff
                        Duration.ofMillis(250), 1000, 100, 50_000, 0.25, 100_000,
                        Duration.ofSeconds(10), Duration.ofSeconds(60)));
        executor = new LockSafeExecutor(pool, props);

        schema = freshSchema("exec");
        jdbc.execute("CREATE TABLE " + schema + ".t (id int PRIMARY KEY)");
        jdbc.execute("INSERT INTO " + schema + ".t SELECT generate_series(1, 1000)");
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    @Test
    @DisplayName("migration timeouts never leak onto the pooled connection the API reuses")
    void sessionSettingsDoNotLeak() {
        assertThat(executor.runDdl("ALTER TABLE " + schema + ".t ADD COLUMN a int", "run-1").success()).isTrue();
        assertThat(executor.runConcurrently(
                "CREATE INDEX CONCURRENTLY t_a_idx ON " + schema + ".t (a)", "run-1").success()).isTrue();

        // Same physical session the migration just used. Before the fix these read 15s, 2s and
        // 'schemasync-migrator/run-1': an ordinary API query would inherit a 15-second timeout.
        assertThat(pooled.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("0");
        assertThat(pooled.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("0");
        assertThat(pooled.queryForObject("SHOW application_name", String.class))
                .doesNotStartWith("schemasync-migrator");
    }

    @Test
    @DisplayName("a batch is all-or-nothing: a failing statement rolls back the ones before it")
    void batchIsAtomic() {
        LockSafeExecutor.Result r = executor.runDdlBatch(List.of(
                "ALTER TABLE " + schema + ".t ADD COLUMN b int",
                "ALTER TABLE " + schema + ".t ADD COLUMN b int"), "run-2");   // duplicate: fails

        assertThat(r.success()).isFalse();
        // Postgres has transactional DDL, so the first ADD COLUMN must be gone too. This is what
        // makes an ATOMIC plan's "all or nothing" badge true.
        Integer columns = jdbc.queryForObject("""
                SELECT count(*)::int FROM information_schema.columns
                WHERE table_schema = ? AND table_name = 't' AND column_name = 'b'
                """, Integer.class, schema);
        assertThat(columns).isZero();
    }

    @Test
    @DisplayName("a DDL blocked by a long reader gives up instead of queueing forever")
    void waitingDdlGivesUp() throws Exception {
        try (Connection reader = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            reader.setAutoCommit(false);
            try (Statement st = reader.createStatement()) {
                st.executeQuery("SELECT * FROM " + schema + ".t").close();   // holds ACCESS SHARE
            }

            long started = System.nanoTime();
            LockSafeExecutor.Result r = executor.runDdl(
                    "ALTER TABLE " + schema + ".t ADD COLUMN c int", "run-3");
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            // Without lock_timeout this ALTER would wait for as long as the reader lives, and every
            // query arriving after it would queue behind it -- the classic migration outage.
            assertThat(r.success()).isFalse();
            assertThat(r.sqlState()).isEqualTo("55P03");
            assertThat(elapsedMs).isLessThan(5_000);
            reader.rollback();
        }
    }

    @Test
    @DisplayName("real errors fail at once; only lock failures are retried")
    void realErrorsAreNotRetried() {
        long started = System.nanoTime();
        LockSafeExecutor.Result r = executor.runDdl(
                "ALTER TABLE " + schema + ".does_not_exist ADD COLUMN x int", "run-4");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(r.success()).isFalse();
        assertThat(r.sqlState()).isEqualTo("42P01");
        // Three lock retries with backoff would take visibly longer than this.
        assertThat(elapsedMs).isLessThan(1_000);
    }
}
