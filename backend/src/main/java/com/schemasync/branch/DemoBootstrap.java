package com.schemasync.branch;

import com.schemasync.store.ControlPlaneStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Imports the demo schema as a project on first start, if it exists and has not been imported.
 *
 * <p>This is a product decision, not a convenience: the first thing anyone sees should be a real
 * schema they can branch, not an empty state asking them to configure a connection. Idempotent, so
 * restarting never duplicates or clobbers anything.
 *
 * <p>When {@code schemasync.demo.seed-orders} is positive and the schema does not exist yet, it is
 * created and seeded first. That is what makes {@code docker compose up} on a fresh clone -- and a
 * deploy against an empty managed database -- land on a usable demo. The schema is only ever
 * created when absent, so this can never overwrite real data.
 */
@Component
public class DemoBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoBootstrap.class);
    private static final String DEMO_PROJECT = "demo-shop";

    private final JdbcTemplate jdbc;
    private final ControlPlaneStore store;
    private final BranchService branches;
    private final SchemaSyncProperties props;
    private final DataSource dataSource;
    private final long seedOrders;

    public DemoBootstrap(JdbcTemplate jdbc, ControlPlaneStore store, BranchService branches,
                         SchemaSyncProperties props, DataSource dataSource,
                         @Value("${schemasync.demo.seed-orders:0}") long seedOrders) {
        this.jdbc = jdbc;
        this.store = store;
        this.branches = branches;
        this.props = props;
        this.dataSource = dataSource;
        this.seedOrders = seedOrders;
    }

    @Override
    public void run(ApplicationArguments args) {
        importIfMissing();
    }

    /**
     * Imports the demo project if it is not already there.
     *
     * <p>Exposed separately from startup so the end-to-end script can reset the database and have
     * the API pick up the fresh schema without a restart -- a test that needs the server bounced
     * between runs is a test nobody runs.
     */
    public boolean importIfMissing() {
        if (store.findProjectByName(DEMO_PROJECT).isPresent()) {
            return false;
        }
        Integer hasSchema = jdbc.queryForObject(
                "SELECT count(*)::int FROM pg_namespace WHERE nspname = ?",
                Integer.class, props.mainSchema());
        if (hasSchema == null || hasSchema == 0) {
            if (seedOrders <= 0) {
                log.info("Schema '{}' not found and seeding is off; skipping demo import. "
                        + "Run ./scripts/seed.sh, or set SCHEMASYNC_DEMO_SEED_ORDERS.", props.mainSchema());
                return false;
            }
            seedDemo();
        }
        Integer tables = jdbc.queryForObject("""
                SELECT count(*)::int FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind = 'r'
                """, Integer.class, props.mainSchema());
        if (tables == null || tables == 0) {
            log.info("Schema '{}' has no tables; skipping demo import.", props.mainSchema());
            return false;
        }

        branches.importProject(DEMO_PROJECT, props.mainSchema());
        log.info("Imported demo project '{}' from schema '{}'", DEMO_PROJECT, props.mainSchema());
        return true;
    }

    /** Creates and fills the demo schema from the same SQL that scripts/seed.sh uses. */
    private void seedDemo() {
        long started = System.nanoTime();
        log.info("Seeding demo schema '{}' with {} orders", props.mainSchema(), seedOrders);
        long customers = Math.max(1_000, Math.min(50_000, seedOrders / 20));
        String seed = read("demo/seed.sql")
                .replace("{{customers}}", Long.toString(customers))
                .replace("{{products}}", "5000")
                .replace("{{orders}}", Long.toString(seedOrders));

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("demo/schema.sql"));
        populator.addScript(new ByteArrayResource(seed.getBytes(StandardCharsets.UTF_8)));
        populator.execute(dataSource);
        log.info("Seeded demo schema in {}ms", (System.nanoTime() - started) / 1_000_000);
    }

    private static String read(String path) {
        try {
            return new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("missing demo resource " + path, e);
        }
    }
}
