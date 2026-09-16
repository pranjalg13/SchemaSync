package com.schemasync.api;

import com.schemasync.core.diff.SchemaChange;
import com.schemasync.core.model.*;
import com.schemasync.store.Records;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire shapes. Kept separate from the domain records so the API can stay stable as they evolve. */
public final class Dtos {

    private Dtos() {
    }

    public record ProjectView(UUID id, String name, String mainSchema) {
        public static ProjectView from(Records.Project p) {
            return new ProjectView(p.id(), p.name(), p.mainSchema());
        }
    }

    public record BranchView(UUID id, String name, String pgSchema, String status,
                             UUID headCommitId, boolean isMain) {
        public static BranchView from(Records.Branch b) {
            return new BranchView(b.id(), b.name(), b.pgSchemaName(), b.status(),
                    b.headCommitId(), "main".equals(b.name()));
        }
    }

    public record ColumnView(String id, String name, String type, boolean nullable,
                             String defaultExpr, String identity, boolean primaryKey) {}

    public record IndexView(String id, String name, boolean unique, String method,
                            List<String> columns, boolean constraintBacked) {}

    public record ConstraintView(String id, String name, String kind, List<String> columns) {}

    public record TableView(String id, String name, List<ColumnView> columns,
                            List<IndexView> indexes, List<ConstraintView> constraints,
                            Long approxRows, String totalSize) {}

    public record SchemaView(List<TableView> tables,
                             List<SchemaSnapshot.UnmanagedObject> unmanaged,
                             String contentHash) {}

    public record ChangeView(String kind, String table, String object, String description,
                             String from, String to, boolean destructive) {
        public static ChangeView from(SchemaChange c) {
            return new ChangeView(c.kind().name(), c.tableName(), c.objectName(), c.describe(),
                    c.from(), c.to(), c.kind().isDestructive());
        }
    }

    public record DiffView(String sourceBranch, String targetBranch,
                           List<ChangeView> changes, boolean drifted) {}

    public record CommitView(UUID id, String message, String author, long seq, String createdAt,
                             List<OpView> operations) {}

    public record OpView(String type, String targetKind, String sql) {}

    public record CreateBranchRequest(String from, String name, String author) {}

    public record ApplyRequest(List<Map<String, Object>> operations, String message, String author) {}

    /** Renders a snapshot for the UI, resolving stable ids to names for display. */
    public static SchemaView toSchemaView(SchemaSnapshot snapshot, Map<String, TableStats> stats) {
        List<TableView> tables = snapshot.tablesSortedByName().values().stream().map(t -> {
            List<String> pkColumnIds = t.constraints().values().stream()
                    .filter(c -> c.kind() == ConstraintDef.Kind.PRIMARY_KEY)
                    .findFirst().map(ConstraintDef::columnIds).orElse(List.of());

            List<ColumnView> columns = t.columns().values().stream()
                    .sorted(java.util.Comparator.comparingInt(ColumnDef::position))
                    .map(c -> new ColumnView(c.id(), c.name(), c.type().sql(), c.nullable(),
                            c.defaultExpr(), c.identity(), pkColumnIds.contains(c.id())))
                    .toList();

            List<IndexView> indexes = t.indexes().values().stream()
                    .sorted(java.util.Comparator.comparing(IndexDef::name))
                    .map(i -> new IndexView(i.id(), i.name(), i.unique(), i.method(),
                            i.columns().stream()
                                    .map(ic -> {
                                        ColumnDef c = t.columns().get(ic.columnId());
                                        return c == null ? "?" : c.name();
                                    }).toList(),
                            i.constraintBacked()))
                    .toList();

            List<ConstraintView> constraints = t.constraints().values().stream()
                    .sorted(java.util.Comparator.comparing(ConstraintDef::name))
                    .map(k -> new ConstraintView(k.id(), k.name(), k.kind().name(),
                            k.columnIds().stream()
                                    .map(id -> {
                                        ColumnDef c = t.columns().get(id);
                                        return c == null ? "?" : c.name();
                                    }).toList()))
                    .toList();

            TableStats s = stats.get(t.name());
            return new TableView(t.id(), t.name(), columns, indexes, constraints,
                    s == null ? null : s.approxRows(), s == null ? null : s.totalSize());
        }).toList();

        return new SchemaView(tables, snapshot.unmanaged(), SnapshotHasher.hash(snapshot));
    }

    public record TableStats(long approxRows, String totalSize) {}
}
