package com.schemasync.api;

import com.schemasync.core.merge.MergeConflict;
import com.schemasync.core.plan.MigrationStep;
import com.schemasync.merge.MergeService;
import com.schemasync.merge.MigrationRunner;
import com.schemasync.store.ControlPlaneStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class MergeController {

    private final MergeService merges;
    private final MigrationRunner runner;
    private final ControlPlaneStore store;
    private final JdbcTemplate jdbc;

    /**
     * Migrations run on their own small pool rather than the request thread: a backfill can take
     * minutes, and holding an HTTP connection open for it would tie the two lifetimes together.
     * Bounded at 2 so a stuck migration cannot starve anything else.
     */
    private final ExecutorService migrationPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "schemasync-migration");
        t.setDaemon(true);
        return t;
    });

    public MergeController(MergeService merges, MigrationRunner runner,
                           ControlPlaneStore store, JdbcTemplate jdbc) {
        this.merges = merges;
        this.runner = runner;
        this.store = store;
        this.jdbc = jdbc;
    }

    /** Computes the merge and its plan without touching anything. Safe to call repeatedly. */
    @PostMapping("/branches/{sourceId}/merge/preview")
    public MergePreview preview(@PathVariable UUID sourceId, @RequestBody(required = false) PreviewRequest req) {
        UUID targetId = resolveTarget(sourceId, req == null ? null : req.targetBranchId());
        Map<String, String> resolutions = req == null || req.resolutions() == null
                ? Map.of() : req.resolutions();

        MergeService.Prepared prepared = merges.prepare(sourceId, targetId, resolutions);
        return toPreview(prepared);
    }

    /** Applies the merge. Refuses while anything is unresolved or pre-flight found a blocker. */
    @PostMapping("/branches/{sourceId}/merge/apply")
    public Map<String, Object> apply(@PathVariable UUID sourceId, @RequestBody(required = false) PreviewRequest req) {
        UUID targetId = resolveTarget(sourceId, req == null ? null : req.targetBranchId());
        Map<String, String> resolutions = req == null || req.resolutions() == null
                ? Map.of() : req.resolutions();
        String author = req == null || req.author() == null ? "anonymous" : req.author();

        MergeService.Prepared prepared = merges.prepare(sourceId, targetId, resolutions);

        if (!prepared.unresolved().isEmpty()) {
            throw new IllegalStateException(prepared.unresolved().size()
                    + " conflict(s) still need a decision before this merge can run.");
        }
        if (!prepared.blockers().isEmpty()) {
            throw new IllegalStateException("Pre-flight found blockers: "
                    + String.join(" ", prepared.blockers()));
        }
        if (prepared.plan().isEmpty()) {
            throw new IllegalStateException("Nothing to merge: the target already has these changes.");
        }

        UUID mergeRequestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sv.merge_request
                    (id, source_branch_id, target_branch_id, merge_base_commit_id,
                     source_head_commit_id, expected_target_head_commit_id, state)
                VALUES (?, ?, ?, ?, ?, ?, 'APPLYING')
                """, mergeRequestId, sourceId, targetId, prepared.baseCommitId(),
                prepared.source().headCommitId(), prepared.target().headCommitId());

        UUID runId = runner.createRun(mergeRequestId, prepared.target().pgSchemaName(), prepared.plan());

        migrationPool.submit(() -> runner.execute(runId, mergeRequestId, prepared.plan(),
                prepared.target().pgSchemaName(), prepared.merged(), sourceId, targetId,
                prepared.baseCommitId(), author));

        return Map.of("runId", runId, "mergeRequestId", mergeRequestId,
                "mode", prepared.plan().mode().name(),
                "steps", prepared.plan().steps().size());
    }

    /** Current state of a run. The UI polls this; every field is read from the database. */
    @GetMapping("/runs/{runId}")
    public Map<String, Object> run(@PathVariable UUID runId) {
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT id, status, mode, error_message, started_at, finished_at "
                + "FROM sv.migration_run WHERE id = ?", runId);

        List<Map<String, Object>> steps = jdbc.query("""
                SELECT s.seq, s.op_group, s.kind, s.description, s.sql_text, s.status,
                       s.lock_mode, s.blocks, s.point_of_no_return, s.error_message,
                       s.lock_wait_ms,
                       c.rows_done, c.rows_estimated, c.batch_size
                FROM sv.migration_step s
                LEFT JOIN sv.backfill_cursor c ON c.step_id = s.id
                WHERE s.run_id = ? ORDER BY s.seq
                """, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", rs.getInt("seq"));
            m.put("group", rs.getString("op_group"));
            m.put("kind", rs.getString("kind"));
            m.put("description", rs.getString("description"));
            m.put("sql", rs.getString("sql_text"));
            m.put("status", rs.getString("status"));
            m.put("blocks", rs.getString("blocks"));
            m.put("lockMode", rs.getString("lock_mode"));
            m.put("pointOfNoReturn", rs.getBoolean("point_of_no_return"));
            m.put("error", rs.getString("error_message"));
            m.put("lockWaitMs", rs.getObject("lock_wait_ms"));
            long done = rs.getLong("rows_done");
            if (!rs.wasNull()) {
                m.put("rowsDone", done);
                m.put("rowsEstimated", rs.getLong("rows_estimated"));
                m.put("batchSize", rs.getInt("batch_size"));
            }
            return m;
        }, runId);

        Map<String, Object> out = new LinkedHashMap<>(run);
        out.put("steps", steps);
        return out;
    }

    /**
     * Sessions currently holding a lock on a table.
     *
     * <p>When a migration stalls, "timed out waiting for a lock" is useless on its own. This says
     * who is holding it and for how long, which turns a mystery into a decision.
     */
    @GetMapping("/schemas/{schema}/tables/{table}/blockers")
    public List<Map<String, Object>> blockers(@PathVariable String schema, @PathVariable String table) {
        return jdbc.query("""
                SELECT a.pid, a.state, a.application_name,
                       extract(epoch FROM (now() - a.xact_start))::bigint AS xact_age_seconds,
                       left(a.query, 200) AS query
                FROM pg_locks l
                JOIN pg_stat_activity a ON a.pid = l.pid
                WHERE l.relation = to_regclass(?) AND l.granted AND a.pid <> pg_backend_pid()
                ORDER BY a.xact_start
                """, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pid", rs.getInt("pid"));
            m.put("state", rs.getString("state"));
            m.put("applicationName", rs.getString("application_name"));
            m.put("xactAgeSeconds", rs.getLong("xact_age_seconds"));
            m.put("query", rs.getString("query"));
            return m;
        }, schema + "." + table);
    }

    private UUID resolveTarget(UUID sourceId, UUID explicit) {
        if (explicit != null) {
            return explicit;
        }
        var source = store.findBranch(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("no such branch"));
        if (source.parentBranchId() != null) {
            return source.parentBranchId();
        }
        return store.findBranchByName(source.projectId(), "main")
                .orElseThrow(() -> new IllegalArgumentException("no target branch")).id();
    }

    private MergePreview toPreview(MergeService.Prepared p) {
        List<ConflictView> conflicts = p.conflicts().stream()
                .map(c -> new ConflictView(c.type().name(), c.severity().name(), c.objectKind(),
                        c.stableId(), c.tableName(), c.objectName(), c.attribute(),
                        c.base(), c.ours(), c.theirs(), c.question(), c.isAutoResolved()))
                .toList();

        List<StepView> steps = p.plan().steps().stream()
                .map(s -> new StepView(s.seq(), s.opGroup(), s.kind().name(), s.description(),
                        s.sql(), s.classification().verdict().name(),
                        s.classification().blocks().name(), s.classification().lock().name(),
                        s.classification().rationale(), s.pointOfNoReturn()))
                .toList();

        return new MergePreview(
                p.source().name(), p.target().name(),
                p.plan().mode().name(),
                conflicts.stream().filter(c -> !c.autoResolved()).toList(),
                conflicts.stream().filter(ConflictView::autoResolved).toList(),
                steps, p.plan().preflightWarnings(), p.blockers(), p.canApply());
    }

    public record PreviewRequest(UUID targetBranchId, Map<String, String> resolutions, String author) {}

    public record ConflictView(String type, String severity, String objectKind, String stableId,
                               String table, String object, String attribute,
                               String base, String ours, String theirs, String question,
                               boolean autoResolved) {}

    public record StepView(int seq, String group, String kind, String description, String sql,
                           String verdict, String blocks, String lockMode, String rationale,
                           boolean pointOfNoReturn) {}

    public record MergePreview(String source, String target, String mode,
                               List<ConflictView> conflicts, List<ConflictView> autoResolved,
                               List<StepView> steps, List<String> warnings, List<String> blockers,
                               boolean canApply) {}

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage()));
    }
}
