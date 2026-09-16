package com.schemasync.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemasync.branch.BranchService;
import com.schemasync.catalog.SchemaIntrospector;
import com.schemasync.core.diff.SchemaChange;
import com.schemasync.core.diff.SchemaDiff;
import com.schemasync.core.model.SchemaSnapshot;
import com.schemasync.ops.OperationService;
import com.schemasync.ops.SchemaOperation;
import com.schemasync.store.ControlPlaneStore;
import com.schemasync.store.Records;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class SchemaSyncController {

    private final ControlPlaneStore store;
    private final BranchService branches;
    private final OperationService operations;
    private final SchemaIntrospector introspector;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SchemaSyncController(ControlPlaneStore store, BranchService branches,
                                OperationService operations, SchemaIntrospector introspector,
                                JdbcTemplate jdbc, ObjectMapper mapper) {
        this.store = store;
        this.branches = branches;
        this.operations = operations;
        this.introspector = introspector;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/projects")
    public List<Dtos.ProjectView> projects() {
        return store.listProjects().stream().map(Dtos.ProjectView::from).toList();
    }

    @GetMapping("/projects/{projectId}/branches")
    public List<Dtos.BranchView> branches(@PathVariable UUID projectId) {
        return store.listBranches(projectId).stream().map(Dtos.BranchView::from).toList();
    }

    @PostMapping("/projects/{projectId}/branches")
    public Dtos.BranchView createBranch(@PathVariable UUID projectId,
                                        @RequestBody Dtos.CreateBranchRequest req) {
        String from = req.from() == null ? "main" : req.from();
        String author = req.author() == null ? "anonymous" : req.author();
        return Dtos.BranchView.from(branches.createBranch(projectId, from, req.name(), author));
    }

    @DeleteMapping("/branches/{branchId}")
    public ResponseEntity<Void> deleteBranch(@PathVariable UUID branchId) {
        branches.deleteBranch(branchId);
        return ResponseEntity.noContent().build();
    }

    /** The branch's schema as recorded. Table sizes come from the live database. */
    @GetMapping("/branches/{branchId}/schema")
    public Dtos.SchemaView schema(@PathVariable UUID branchId) {
        Records.Branch branch = store.findBranch(branchId).orElseThrow();
        SchemaSnapshot snapshot = store.headSnapshot(branch);
        return Dtos.toSchemaView(snapshot, tableStats(branch.pgSchemaName()));
    }

    /**
     * What this branch changed relative to the point it forked from.
     *
     * <p>Computed from snapshots, not from the operation log: an add-then-drop within a branch
     * correctly collapses to nothing. The operation log answers a different question ("how did it
     * get here") and is a separate endpoint.
     */
    @GetMapping("/branches/{branchId}/diff")
    public Dtos.DiffView diff(@PathVariable UUID branchId) {
        Records.Branch branch = store.findBranch(branchId).orElseThrow();
        boolean drifted = branches.checkDrift(branchId);

        SchemaSnapshot base = branch.baseCommitId() == null
                ? SchemaSnapshot.empty()
                : store.loadSnapshot(store.findCommit(branch.baseCommitId()).orElseThrow().snapshotId());
        SchemaSnapshot head = store.headSnapshot(branch);

        List<SchemaChange> changes = SchemaDiff.diff(base, head);
        Records.Branch parent = branch.parentBranchId() == null ? null
                : store.findBranch(branch.parentBranchId()).orElse(null);

        return new Dtos.DiffView(branch.name(), parent == null ? "-" : parent.name(),
                changes.stream().map(Dtos.ChangeView::from).toList(), drifted);
    }

    @GetMapping("/branches/{branchId}/history")
    public List<Dtos.CommitView> history(@PathVariable UUID branchId) {
        return store.history(branchId).stream().map(c -> new Dtos.CommitView(
                c.id(), c.message(), c.author(), c.seq(),
                c.createdAt() == null ? null : DateTimeFormatter.ISO_INSTANT.format(c.createdAt()),
                store.opsForCommit(c.id()).stream()
                        .map(o -> new Dtos.OpView(o.opType(), o.targetKind(), o.renderedSql()))
                        .toList()
        )).toList();
    }

    /**
     * Re-records the branch's live schema, clearing drift.
     *
     * <p>The counterpart to drift detection: detecting a problem you give the user no way to fix
     * is not a safety feature, it is a dead end.
     */
    @PostMapping("/branches/{branchId}/refresh")
    public Dtos.CommitView refresh(@PathVariable UUID branchId,
                                   @RequestBody(required = false) Dtos.CreateBranchRequest req) {
        String author = req == null || req.author() == null ? "anonymous" : req.author();
        Records.Commit commit = branches.refresh(branchId, author);
        return new Dtos.CommitView(commit.id(), commit.message(), commit.author(), commit.seq(),
                DateTimeFormatter.ISO_INSTANT.format(commit.createdAt()), List.of());
    }

    @PostMapping("/branches/{branchId}/operations")
    public Dtos.CommitView apply(@PathVariable UUID branchId, @RequestBody Dtos.ApplyRequest req) {
        List<SchemaOperation> ops = req.operations().stream()
                .map(m -> mapper.convertValue(m, SchemaOperation.class))
                .toList();
        String message = req.message() == null ? describe(ops) : req.message();
        String author = req.author() == null ? "anonymous" : req.author();

        Records.Commit commit = operations.applyAll(branchId, ops, message, author);
        return new Dtos.CommitView(commit.id(), commit.message(), commit.author(), commit.seq(),
                DateTimeFormatter.ISO_INSTANT.format(commit.createdAt()),
                store.opsForCommit(commit.id()).stream()
                        .map(o -> new Dtos.OpView(o.opType(), o.targetKind(), o.renderedSql()))
                        .toList());
    }

    /** Live row counts and sizes, so the UI can show what a migration would actually touch. */
    private Map<String, Dtos.TableStats> tableStats(String schema) {
        Map<String, Dtos.TableStats> out = new HashMap<>();
        jdbc.query("""
                SELECT c.relname                                   AS name,
                       greatest(c.reltuples, 0)::bigint            AS approx_rows,
                       pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind = 'r'
                """, rs -> {
            out.put(rs.getString("name"),
                    new Dtos.TableStats(rs.getLong("approx_rows"), rs.getString("total_size")));
        }, schema);
        return out;
    }

    private static String describe(List<SchemaOperation> ops) {
        return ops.size() == 1 ? ops.get(0).kind() : ops.size() + " schema changes";
    }

    // ----- error handling ---------------------------------------------------
    // Errors here are things a user did, not server faults, so they come back as 400 with the
    // actual reason. "Something went wrong" would make the product unusable.

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class,
            com.schemasync.ops.SnapshotMutator.InvalidOperationException.class,
            com.schemasync.exec.DdlSql.InvalidIdentifierException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, String>> databaseError(org.springframework.dao.DataAccessException e) {
        Throwable root = e.getMostSpecificCause();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", root.getMessage() == null ? e.toString() : root.getMessage()));
    }
}
