package com.schemasync.branch;

import com.schemasync.store.ControlPlaneStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Imports the demo schema as a project on first start, if it exists and has not been imported.
 *
 * <p>This is a product decision, not a convenience: the first thing anyone sees should be a real
 * schema they can branch, not an empty state asking them to configure a connection. Idempotent, so
 * restarting never duplicates or clobbers anything.
 */
@Component
public class DemoBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoBootstrap.class);
    private static final String DEMO_PROJECT = "demo-shop";

    private final JdbcTemplate jdbc;
    private final ControlPlaneStore store;
    private final BranchService branches;
    private final SchemaSyncProperties props;

    public DemoBootstrap(JdbcTemplate jdbc, ControlPlaneStore store,
                         BranchService branches, SchemaSyncProperties props) {
        this.jdbc = jdbc;
        this.store = store;
        this.branches = branches;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (store.findProjectByName(DEMO_PROJECT).isPresent()) {
            return;
        }
        Integer hasSchema = jdbc.queryForObject(
                "SELECT count(*)::int FROM pg_namespace WHERE nspname = ?",
                Integer.class, props.mainSchema());
        if (hasSchema == null || hasSchema == 0) {
            log.info("Schema '{}' not found; skipping demo import. Run ./scripts/seed.sh to create it.",
                    props.mainSchema());
            return;
        }
        Integer tables = jdbc.queryForObject("""
                SELECT count(*)::int FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind = 'r'
                """, Integer.class, props.mainSchema());
        if (tables == null || tables == 0) {
            log.info("Schema '{}' has no tables; skipping demo import.", props.mainSchema());
            return;
        }

        branches.importProject(DEMO_PROJECT, props.mainSchema());
        log.info("Imported demo project '{}' from schema '{}'", DEMO_PROJECT, props.mainSchema());
    }
}
