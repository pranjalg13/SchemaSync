package com.schemasync.store;

import com.schemasync.core.model.SchemaSnapshot;
import com.schemasync.core.model.SnapshotHasher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the control plane.
 *
 * <p>Plain {@code JdbcTemplate} rather than an ORM. The target-database half of this application
 * cannot use an ORM at all -- you cannot express {@code CREATE INDEX CONCURRENTLY} or a
 * lock-timeout-wrapped {@code ALTER TABLE} through one -- so using JPA here would mean two
 * persistence idioms in one codebase to save very little on ten tables, several of which hold
 * JSONB.
 */
@Repository
public class ControlPlaneStore {

    private final JdbcTemplate jdbc;
    private final SnapshotCodec codec;

    public ControlPlaneStore(JdbcTemplate jdbc, SnapshotCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    // ----- projects ---------------------------------------------------------

    public Records.Project createProject(String name, String mainSchema) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sv.project (id, name, main_schema) VALUES (?, ?, ?)",
                id, name, mainSchema);
        return findProject(id).orElseThrow();
    }

    public Optional<Records.Project> findProject(UUID id) {
        return jdbc.query("SELECT * FROM sv.project WHERE id = ?", PROJECT, id).stream().findFirst();
    }

    public Optional<Records.Project> findProjectByName(String name) {
        return jdbc.query("SELECT * FROM sv.project WHERE name = ?", PROJECT, name).stream().findFirst();
    }

    public List<Records.Project> listProjects() {
        return jdbc.query("SELECT * FROM sv.project ORDER BY created_at", PROJECT);
    }

    // ----- snapshots --------------------------------------------------------

    /**
     * Stores a snapshot, reusing an existing row when the content hash already exists.
     *
     * <p>Deduplication is not an optimisation here so much as a correctness convenience: it makes
     * "did this change anything?" answerable by comparing one id, and it is what lets an empty
     * commit be detected for free.
     */
    public UUID saveSnapshot(SchemaSnapshot snapshot) {
        String hash = SnapshotHasher.hash(snapshot);
        List<UUID> existing = jdbc.queryForList(
                "SELECT id FROM sv.schema_snapshot WHERE content_hash = ?", UUID.class, hash);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sv.schema_snapshot (id, content, content_hash, object_count)
                VALUES (?, ?::jsonb, ?, ?)
                """, id, codec.toJson(snapshot), hash, snapshot.objectCount());
        return id;
    }

    public SchemaSnapshot loadSnapshot(UUID id) {
        String json = jdbc.queryForObject(
                "SELECT content::text FROM sv.schema_snapshot WHERE id = ?", String.class, id);
        return codec.fromJson(json);
    }

    public String snapshotHash(UUID id) {
        return jdbc.queryForObject(
                "SELECT content_hash FROM sv.schema_snapshot WHERE id = ?", String.class, id);
    }

    // ----- branches ---------------------------------------------------------

    public Records.Branch createBranch(UUID projectId, String name, String pgSchemaName,
                                       UUID parentBranchId, UUID baseCommitId, String createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sv.branch
                    (id, project_id, name, pg_schema_name, parent_branch_id, base_commit_id,
                     status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'CREATING', ?)
                """, id, projectId, name, pgSchemaName, parentBranchId, baseCommitId, createdBy);
        return findBranch(id).orElseThrow();
    }

    public Optional<Records.Branch> findBranch(UUID id) {
        return jdbc.query("SELECT * FROM sv.branch WHERE id = ?", BRANCH, id).stream().findFirst();
    }

    public Optional<Records.Branch> findBranchByName(UUID projectId, String name) {
        return jdbc.query("SELECT * FROM sv.branch WHERE project_id = ? AND name = ?",
                BRANCH, projectId, name).stream().findFirst();
    }

    public List<Records.Branch> listBranches(UUID projectId) {
        return jdbc.query("""
                SELECT * FROM sv.branch
                WHERE project_id = ? AND status <> 'ABANDONED'
                ORDER BY (name = 'main') DESC, created_at
                """, BRANCH, projectId);
    }

    public void setBranchHead(UUID branchId, UUID commitId) {
        jdbc.update("UPDATE sv.branch SET head_commit_id = ? WHERE id = ?", commitId, branchId);
    }

    public void setBranchStatus(UUID branchId, String status) {
        jdbc.update("UPDATE sv.branch SET status = ? WHERE id = ?", status, branchId);
    }

    public void deleteBranch(UUID branchId) {
        jdbc.update("DELETE FROM sv.branch WHERE id = ?", branchId);
    }

    // ----- commits ----------------------------------------------------------

    public Records.Commit commit(UUID projectId, UUID branchId, UUID parentCommitId,
                                 UUID secondParentCommitId, UUID snapshotId,
                                 String message, String author) {
        UUID id = UUID.randomUUID();
        Long maxSeq = jdbc.queryForObject(
                "SELECT coalesce(max(seq), 0) FROM sv.schema_commit WHERE project_id = ?",
                Long.class, projectId);
        long seq = (maxSeq == null ? 0 : maxSeq) + 1;

        jdbc.update("""
                INSERT INTO sv.schema_commit
                    (id, project_id, branch_id, parent_commit_id, second_parent_commit_id,
                     snapshot_id, message, author, seq)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, projectId, branchId, parentCommitId, secondParentCommitId,
                snapshotId, message, author, seq);

        setBranchHead(branchId, id);
        return findCommit(id).orElseThrow();
    }

    public Optional<Records.Commit> findCommit(UUID id) {
        return jdbc.query("SELECT * FROM sv.schema_commit WHERE id = ?", COMMIT, id).stream().findFirst();
    }

    public List<Records.Commit> history(UUID branchId) {
        return jdbc.query("SELECT * FROM sv.schema_commit WHERE branch_id = ? ORDER BY seq DESC",
                COMMIT, branchId);
    }

    /** The snapshot at a branch's head, or an empty snapshot if it has no commits yet. */
    public SchemaSnapshot headSnapshot(Records.Branch branch) {
        if (branch.headCommitId() == null) {
            return SchemaSnapshot.empty();
        }
        Records.Commit head = findCommit(branch.headCommitId()).orElseThrow();
        return loadSnapshot(head.snapshotId());
    }

    // ----- operation log ----------------------------------------------------

    public void recordOp(UUID commitId, int ordinal, String opType, String targetKind,
                         String targetStableId, String payloadJson, String renderedSql) {
        jdbc.update("""
                INSERT INTO sv.change_op
                    (commit_id, ordinal, op_type, target_kind, target_stable_id, payload, rendered_sql)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, commitId, ordinal, opType, targetKind, targetStableId, payloadJson, renderedSql);
    }

    public List<Records.ChangeOp> opsForCommit(UUID commitId) {
        return jdbc.query("SELECT * FROM sv.change_op WHERE commit_id = ? ORDER BY ordinal",
                CHANGE_OP, commitId);
    }

    // ----- mappers ----------------------------------------------------------

    private static final RowMapper<Records.Project> PROJECT = (rs, n) -> new Records.Project(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("main_schema"),
            toInstant(rs.getTimestamp("created_at")));

    private static final RowMapper<Records.Branch> BRANCH = (rs, n) -> new Records.Branch(
            rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getString("name"),
            rs.getString("pg_schema_name"),
            rs.getObject("head_commit_id", UUID.class),
            rs.getObject("parent_branch_id", UUID.class),
            rs.getObject("base_commit_id", UUID.class),
            rs.getString("status"),
            rs.getString("created_by"),
            toInstant(rs.getTimestamp("created_at")));

    private static final RowMapper<Records.Commit> COMMIT = (rs, n) -> new Records.Commit(
            rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getObject("branch_id", UUID.class),
            rs.getObject("parent_commit_id", UUID.class),
            rs.getObject("second_parent_commit_id", UUID.class),
            rs.getObject("snapshot_id", UUID.class),
            rs.getString("message"),
            rs.getString("author"),
            rs.getLong("seq"),
            toInstant(rs.getTimestamp("created_at")));

    private static final RowMapper<Records.ChangeOp> CHANGE_OP = (rs, n) -> new Records.ChangeOp(
            rs.getLong("id"),
            rs.getObject("commit_id", UUID.class),
            rs.getInt("ordinal"),
            rs.getString("op_type"),
            rs.getString("target_kind"),
            rs.getString("target_stable_id"),
            rs.getString("payload"),
            rs.getString("rendered_sql"));

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
