package com.schemasync.store;

import java.time.Instant;
import java.util.UUID;

/** Row shapes for the control-plane tables. Deliberately dumb carriers, no behaviour. */
public final class Records {

    private Records() {
    }

    public record Project(UUID id, String name, String mainSchema, Instant createdAt) {}

    public record Branch(
            UUID id,
            UUID projectId,
            String name,
            String pgSchemaName,
            UUID headCommitId,
            UUID parentBranchId,
            UUID baseCommitId,
            String status,
            String createdBy,
            Instant createdAt) {

        public boolean isDrifted() {
            return "DRIFTED".equals(status);
        }
    }

    public record Commit(
            UUID id,
            UUID projectId,
            UUID branchId,
            UUID parentCommitId,
            UUID secondParentCommitId,
            UUID snapshotId,
            String message,
            String author,
            long seq,
            Instant createdAt) {}

    public record ChangeOp(
            long id,
            UUID commitId,
            int ordinal,
            String opType,
            String targetKind,
            String targetStableId,
            String payloadJson,
            String renderedSql) {}
}
