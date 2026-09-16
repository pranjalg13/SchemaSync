package com.schemasync.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemasync.catalog.SchemaIntrospector;
import com.schemasync.core.model.SchemaSnapshot;
import com.schemasync.store.ControlPlaneStore;
import com.schemasync.store.Records;
import com.schemasync.exec.DdlSql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Applies an operation to a branch: real DDL, updated snapshot, and a log entry, atomically.
 *
 * <p>All three happen in one transaction, which is possible only because the control plane lives
 * in the same database as the branch schemas. Postgres has transactional DDL, so a failed
 * operation leaves neither a half-changed schema nor a snapshot that disagrees with it. Splitting
 * the metadata into a second datastore would have turned this into a two-phase commit problem for
 * no benefit.
 */
@Service
public class OperationService {

    private static final Logger log = LoggerFactory.getLogger(OperationService.class);

    private final JdbcTemplate jdbc;
    private final ControlPlaneStore store;
    private final SchemaIntrospector introspector;
    private final ObjectMapper mapper;

    public OperationService(JdbcTemplate jdbc, ControlPlaneStore store,
                            SchemaIntrospector introspector, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.store = store;
        this.introspector = introspector;
        this.mapper = mapper;
    }

    @Transactional
    public Records.Commit apply(UUID branchId, SchemaOperation op, String message, String author) {
        return applyAll(branchId, List.of(op), message, author);
    }

    /**
     * Applies several operations as one commit.
     *
     * <p>Each operation is validated against the snapshot produced by the previous one, so a
     * sequence like "add column, then index it" works, and a sequence that is internally
     * inconsistent fails before any SQL runs.
     */
    @Transactional
    public Records.Commit applyAll(UUID branchId, List<SchemaOperation> ops,
                                   String message, String author) {
        Records.Branch branch = store.findBranch(branchId)
                .orElseThrow(() -> new IllegalArgumentException("no such branch"));
        if (branch.isDrifted()) {
            throw new IllegalStateException(
                    "branch '" + branch.name() + "' has drifted from its recorded schema; "
                            + "re-import or revert it before making further changes");
        }
        if ("main".equals(branch.name())) {
            // Edits land on main only through a merge, which is what gets the safety analysis,
            // the preflight and the online execution path.
            throw new IllegalStateException(
                    "'main' cannot be edited directly; branch it and merge the change back");
        }

        validateNames(ops);

        SchemaSnapshot snapshot = store.headSnapshot(branch);
        record Applied(SchemaOperation op, List<String> sql) {}
        List<Applied> applied = new java.util.ArrayList<>();

        // Validate the whole sequence in memory first. Rendering SQL against the pre-operation
        // snapshot is what resolves stable ids to the names the database currently knows.
        for (SchemaOperation op : ops) {
            List<String> sql = OperationSqlRenderer.render(branch.pgSchemaName(), snapshot, op);
            snapshot = SnapshotMutator.apply(snapshot, op);
            applied.add(new Applied(op, sql));
        }

        for (Applied a : applied) {
            for (String statement : a.sql()) {
                log.debug("branch {}: {}", branch.name(), statement);
                jdbc.execute(statement);
            }
        }

        // Read the schema back rather than trusting the in-memory mutation.
        //
        // The two can legitimately disagree, and not only through bugs. Retyping a column, for
        // instance, does NOT rewrite that column's default: after ALTER COLUMN status TYPE text,
        // Postgres still reports the default as 'pending'::character varying. Any model that
        // predicted what the database would do would drift from it over time, and then drift
        // detection -- which exists to catch exactly that -- would fire on our own changes.
        //
        // Passing the mutated snapshot as the identity source is what makes this safe: names in
        // it are already post-rename, so ids carry across by name. The result has the database's
        // content with our operation log's identity, which is the combination we actually want.
        SchemaSnapshot recorded = introspector.introspect(branch.pgSchemaName(), snapshot);

        UUID snapshotId = store.saveSnapshot(recorded);
        Records.Commit commit = store.commit(branch.projectId(), branchId, branch.headCommitId(),
                null, snapshotId, message, author);

        int ordinal = 0;
        for (Applied a : applied) {
            store.recordOp(commit.id(), ordinal++, a.op().kind(), targetKind(a.op()),
                    a.op().targetId(), toJson(a.op()), String.join(";\n", a.sql()));
        }

        log.info("Applied {} operation(s) to branch '{}'", ops.size(), branch.name());
        return commit;
    }

    /** Names reaching DDL are validated here, at the boundary, before they can reach DdlSql. */
    private void validateNames(List<SchemaOperation> ops) {
        for (SchemaOperation op : ops) {
            switch (op) {
                case SchemaOperation.AddColumn a -> DdlSql.validateUserIdentifier("column", a.name());
                case SchemaOperation.RenameColumn r -> DdlSql.validateUserIdentifier("column", r.newName());
                case SchemaOperation.AddIndex i -> DdlSql.validateUserIdentifier("index", i.name());
                case SchemaOperation.CreateTable t -> {
                    DdlSql.validateUserIdentifier("table", t.name());
                    t.columns().forEach(c -> DdlSql.validateUserIdentifier("column", c.name()));
                }
                default -> { /* operations that only reference existing objects by id */ }
            }
        }
    }

    private static String targetKind(SchemaOperation op) {
        return switch (op) {
            case SchemaOperation.AddColumn ignored -> "COLUMN";
            case SchemaOperation.DropColumn ignored -> "COLUMN";
            case SchemaOperation.RenameColumn ignored -> "COLUMN";
            case SchemaOperation.ChangeColumnType ignored -> "COLUMN";
            case SchemaOperation.SetColumnNullable ignored -> "COLUMN";
            case SchemaOperation.SetColumnDefault ignored -> "COLUMN";
            case SchemaOperation.CreateTable ignored -> "TABLE";
            case SchemaOperation.DropTable ignored -> "TABLE";
            case SchemaOperation.AddIndex ignored -> "INDEX";
            case SchemaOperation.DropIndex ignored -> "INDEX";
        };
    }

    private String toJson(SchemaOperation op) {
        try {
            return mapper.writeValueAsString(op);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise operation", e);
        }
    }
}
