package com.schemasync.branch;

import com.schemasync.catalog.SchemaIntrospector;
import com.schemasync.core.model.*;
import com.schemasync.core.plan.DdlRenderer;
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
 * Branch lifecycle: import a project, fork a branch, drop a branch.
 *
 * <p>The central decision lives in {@link #materialise}. A branch is a real Postgres schema
 * containing the same tables as its parent, seeded with a bounded row sample -- not a copy of the
 * data. Branch creation is therefore O(schema), not O(data): forking a 5GB table takes the same
 * few hundred milliseconds as forking an empty one, because the volume of data copied is capped by
 * configuration rather than by the size of the source.
 *
 * <p>The honest cost: a branch is structurally real but not data-real, so you cannot prove a
 * destructive cast is safe from inside it. That loop is closed at merge time by pre-flight
 * validation against the actual target table.
 */
@Service
public class BranchService {

    private static final Logger log = LoggerFactory.getLogger(BranchService.class);

    private final JdbcTemplate jdbc;
    private final SchemaIntrospector introspector;
    private final ControlPlaneStore store;
    private final SchemaSyncProperties props;

    public BranchService(JdbcTemplate jdbc, SchemaIntrospector introspector,
                         ControlPlaneStore store, SchemaSyncProperties props) {
        this.jdbc = jdbc;
        this.introspector = introspector;
        this.store = store;
        this.props = props;
    }

    /**
     * Imports an existing Postgres schema as a project, creating its {@code main} branch.
     *
     * <p>"main is just a branch" is load-bearing, not a slogan: main is an ordinary row in
     * {@code sv.branch} that happens to point at the pre-existing schema. Nothing downstream
     * special-cases it.
     */
    @Transactional
    public Records.Project importProject(String projectName, String mainSchema) {
        if (store.findProjectByName(projectName).isPresent()) {
            throw new IllegalArgumentException("project '" + projectName + "' already exists");
        }
        requireSchemaExists(mainSchema);

        Records.Project project = store.createProject(projectName, mainSchema);
        Records.Branch main = store.createBranch(
                project.id(), "main", mainSchema, null, null, "system");

        SchemaSnapshot snapshot = introspector.introspect(mainSchema);
        UUID snapshotId = store.saveSnapshot(snapshot);
        Records.Commit initial = store.commit(project.id(), main.id(), null, null, snapshotId,
                "Import " + mainSchema, "system");

        jdbc.update("UPDATE sv.branch SET base_commit_id = ?, status = 'ACTIVE' WHERE id = ?",
                initial.id(), main.id());

        log.info("Imported project '{}' from schema '{}': {} tables, {} unmanaged objects",
                projectName, mainSchema, snapshot.tables().size(), snapshot.unmanaged().size());
        return project;
    }

    /** Forks {@code sourceBranch} into a new branch backed by its own Postgres schema. */
    @Transactional
    public Records.Branch createBranch(UUID projectId, String sourceBranchName,
                                       String newBranchName, String author) {
        DdlSql.validateUserIdentifier("branch", newBranchName);

        Records.Project project = store.findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("no such project"));
        Records.Branch source = store.findBranchByName(projectId, sourceBranchName)
                .orElseThrow(() -> new IllegalArgumentException("no such branch: " + sourceBranchName));
        if (store.findBranchByName(projectId, newBranchName).isPresent()) {
            throw new IllegalArgumentException("branch '" + newBranchName + "' already exists");
        }

        SchemaSnapshot snapshot = store.headSnapshot(source);

        // Schema name is derived from a fresh id, not from the branch name: branch names are
        // user-facing and may be reused after a delete, while a Postgres schema lingering from a
        // half-dropped branch must never collide with a new one.
        String pgSchema = props.branchSchemaPrefix()
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        Records.Branch branch = store.createBranch(projectId, newBranchName, pgSchema,
                source.id(), source.headCommitId(), author);

        long startedAt = System.nanoTime();
        materialise(pgSchema, snapshot, project.mainSchema());
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        // The branch starts at the same snapshot as its parent, so an untouched branch has an
        // empty diff and its content hash matches. saveSnapshot dedupes, so this reuses the row.
        UUID snapshotId = store.saveSnapshot(snapshot);
        store.commit(projectId, branch.id(), source.headCommitId(), null, snapshotId,
                "Branch from " + sourceBranchName, author);
        store.setBranchStatus(branch.id(), "ACTIVE");

        log.info("Created branch '{}' as schema '{}' in {}ms ({} tables)",
                newBranchName, pgSchema, elapsedMs, snapshot.tables().size());
        return store.findBranch(branch.id()).orElseThrow();
    }

    /**
     * Builds the branch's Postgres schema and seeds it.
     *
     * <p>Order matters and is the same order the merge planner uses: tables without their foreign
     * keys, then rows, then foreign keys, then indexes.
     *
     * <ul>
     *   <li>Foreign keys come after the rows because a sampled child row whose parent was not
     *       sampled would abort the copy. Filtering handles most of this, but adding the
     *       constraints afterwards means the copy cannot fail on ordering at all.</li>
     *   <li>Indexes come last because building them on an empty table and then inserting is
     *       strictly more work than inserting and then building.</li>
     * </ul>
     */
    private void materialise(String pgSchema, SchemaSnapshot snapshot, String sourceSchema) {
        jdbc.execute("CREATE SCHEMA " + DdlSql.quote(pgSchema));

        DdlRenderer.Materialisation ddl = DdlRenderer.materialise(pgSchema, snapshot);
        ddl.createTables().forEach(jdbc::execute);
        copySample(pgSchema, snapshot, sourceSchema);
        ddl.addForeignKeys().forEach(jdbc::execute);
        ddl.createIndexes().forEach(jdbc::execute);
    }

    /**
     * Copies a bounded sample into the branch, parents before children.
     *
     * <p>Each child table is filtered to rows whose referenced parents were themselves sampled.
     * Without that filter the sample is a random subset, so most child rows would point at parents
     * that are not present and the foreign keys added afterwards would fail to validate -- the
     * branch would be structurally correct but unusable.
     */
    private void copySample(String pgSchema, SchemaSnapshot snapshot, String sourceSchema) {
        int limit = props.branchSampleRows();
        if (limit <= 0) {
            return;
        }

        for (TableDef table : DdlRenderer.sortedTables(snapshot)) {
            List<String> columnNames = new ArrayList<>();
            List<ColumnDef> columns = new ArrayList<>(table.columns().values());
            columns.sort(Comparator.comparingInt(ColumnDef::position));
            columns.forEach(c -> columnNames.add(DdlSql.quote(c.name())));
            String columnList = String.join(", ", columnNames);

            StringBuilder where = new StringBuilder();
            for (ConstraintDef fk : table.constraints().values()) {
                if (fk.kind() != ConstraintDef.Kind.FOREIGN_KEY || fk.references() == null) {
                    continue;
                }
                TableDef parent = snapshot.tables().get(fk.references().tableId());
                if (parent == null || parent.id().equals(table.id())) {
                    continue;
                }
                ColumnDef localCol = table.columns().get(fk.columnIds().get(0));
                ColumnDef parentCol = parent.columns().get(fk.references().columnIds().get(0));
                if (localCol == null || parentCol == null) {
                    continue;
                }
                where.append(where.isEmpty() ? " WHERE " : " AND ")
                     .append(DdlSql.quote(localCol.name()))
                     .append(" IN (SELECT ").append(DdlSql.quote(parentCol.name()))
                     .append(" FROM ").append(DdlSql.qualify(pgSchema, parent.name())).append(")");
            }

            String sql = "INSERT INTO " + DdlSql.qualify(pgSchema, table.name())
                    + " (" + columnList + ") SELECT " + columnList
                    + " FROM " + DdlSql.qualify(sourceSchema, table.name())
                    + where + " LIMIT " + limit;
            jdbc.update(sql);
        }

        // Identity sequences are created fresh at 1 by CREATE TABLE, so after copying explicit ids
        // the next INSERT would collide. Fast-forward each sequence past what we copied.
        for (TableDef table : snapshot.tables().values()) {
            for (ColumnDef c : table.columns().values()) {
                if (c.identity() == null) {
                    continue;
                }
                // queryForObject, not update: setval() returns a row, and jdbc.update rejects
                // any statement that produces a result set.
                jdbc.queryForObject("""
                        SELECT setval(
                            pg_get_serial_sequence(?, ?),
                            coalesce((SELECT max(%s) FROM %s), 0) + 1,
                            false)
                        """.formatted(DdlSql.quote(c.name()), DdlSql.qualify(pgSchema, table.name())),
                        Long.class, pgSchema + "." + table.name(), c.name());
            }
        }
    }

    /**
     * Drops a branch and its Postgres schema.
     *
     * <p>{@code main} is refused: it is backed by the schema the project was imported from, so
     * dropping it would destroy the data the whole project describes.
     */
    @Transactional
    public void deleteBranch(UUID branchId) {
        Records.Branch branch = store.findBranch(branchId)
                .orElseThrow(() -> new IllegalArgumentException("no such branch"));
        if ("main".equals(branch.name())) {
            throw new IllegalArgumentException(
                    "refusing to delete 'main': it is backed by the project's own schema");
        }
        jdbc.execute("DROP SCHEMA IF EXISTS " + DdlSql.quote(branch.pgSchemaName()) + " CASCADE");
        store.deleteBranch(branchId);
        log.info("Deleted branch '{}' and schema '{}'", branch.name(), branch.pgSchemaName());
    }

    /**
     * Re-introspects a branch's live schema and compares it against the recorded head.
     *
     * <p>Object identity only holds while every change comes through SchemaSync. Someone running
     * DDL directly against a branch schema silently breaks rename tracking -- so rather than let
     * that corrupt a later merge, we detect it and mark the branch DRIFTED.
     */
    @Transactional
    public boolean checkDrift(UUID branchId) {
        Records.Branch branch = store.findBranch(branchId).orElseThrow();
        SchemaSnapshot recorded = store.headSnapshot(branch);
        SchemaSnapshot live = introspector.introspect(branch.pgSchemaName(), recorded);

        boolean drifted = !SnapshotHasher.hash(live).equals(SnapshotHasher.hash(recorded));
        if (drifted && !branch.isDrifted()) {
            store.setBranchStatus(branchId, "DRIFTED");
            log.warn("Branch '{}' has drifted: the live schema no longer matches its recorded head",
                    branch.name());
        } else if (!drifted && branch.isDrifted()) {
            // Clear the flag when the schema matches again, so a branch that was reverted by hand
            // is not stuck unusable forever.
            store.setBranchStatus(branchId, "ACTIVE");
            log.info("Branch '{}' no longer drifts; back to ACTIVE", branch.name());
        }
        return drifted;
    }

    private void requireSchemaExists(String schema) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*)::int FROM pg_namespace WHERE nspname = ?", Integer.class, schema);
        if (found == null || found == 0) {
            throw new IllegalArgumentException("schema '" + schema + "' does not exist");
        }
    }
}
