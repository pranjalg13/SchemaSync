package com.schemasync;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres container shared across the whole integration suite.
 *
 * <p>Started once and reused deliberately: container startup dominates the runtime of these tests,
 * and each test isolates itself with its own schema rather than its own database. Real Postgres
 * rather than a mock or H2 is non-negotiable here -- the entire product is claims about what
 * specific DDL does to lock levels and rewrite behaviour, and only Postgres can adjudicate those.
 */
public abstract class PostgresTestBase {

    protected static PostgreSQLContainer<?> postgres;
    protected static JdbcTemplate jdbc;

    @BeforeAll
    static void startContainer() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("schemasync_test")
                    .withUsername("test")
                    .withPassword("test");
            postgres.start();

            DriverManagerDataSource ds = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            ds.setDriverClassName("org.postgresql.Driver");
            jdbc = new JdbcTemplate(ds);
        }
    }

    /** Creates an isolated schema for one test and returns its name. */
    protected static String freshSchema(String label) {
        String name = ("t_" + label + "_" + Long.toHexString(System.nanoTime())).toLowerCase();
        jdbc.execute("CREATE SCHEMA " + name);
        return name;
    }
}
