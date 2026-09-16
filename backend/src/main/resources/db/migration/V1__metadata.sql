-- SchemaSync control plane.
--
-- This schema lives in the SAME database as the managed branch schemas. That is deliberate:
-- it lets a merge's DDL and its metadata commit share one transaction, and lets a backfill
-- batch and its cursor update commit atomically together. See decisions.md.

CREATE SCHEMA IF NOT EXISTS sv;

-- ---------------------------------------------------------------------------
-- Projects and branches
-- ---------------------------------------------------------------------------

CREATE TABLE sv.project (
    id                 uuid PRIMARY KEY,
    name               text        NOT NULL UNIQUE,
    -- The Postgres schema holding the project's canonical ("main") data.
    main_schema        text        NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);

-- A commit is a full canonical snapshot, deduplicated by content hash.
-- Snapshots are kilobytes, so storing one per commit is cheap; it makes diff and
-- three-way merge pure functions over immutable documents.
CREATE TABLE sv.schema_snapshot (
    id                 uuid PRIMARY KEY,
    content            jsonb       NOT NULL,
    content_hash       text        NOT NULL UNIQUE,
    object_count       int         NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sv.branch (
    id                 uuid PRIMARY KEY,
    project_id         uuid        NOT NULL REFERENCES sv.project(id) ON DELETE CASCADE,
    name               text        NOT NULL,
    -- The real Postgres schema (namespace) materialising this branch.
    pg_schema_name     text        NOT NULL UNIQUE,
    head_commit_id     uuid,                -- FK added below (circular with schema_commit)
    parent_branch_id   uuid        REFERENCES sv.branch(id) ON DELETE SET NULL,
    base_commit_id     uuid,                -- fork point; cached fast path for merge-base
    status             text        NOT NULL DEFAULT 'ACTIVE',
    created_by         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    merged_at          timestamptz,
    CONSTRAINT branch_name_unique_per_project UNIQUE (project_id, name),
    CONSTRAINT branch_status_valid CHECK (status IN
        ('CREATING','ACTIVE','MERGING','MERGED','ABANDONED','DRIFTED'))
);

-- Named "schema_commit" rather than "commit": COMMIT is a SQL keyword and an
-- unquoted table of that name is a persistent footgun.
CREATE TABLE sv.schema_commit (
    id                    uuid PRIMARY KEY,
    project_id            uuid        NOT NULL REFERENCES sv.project(id) ON DELETE CASCADE,
    branch_id             uuid        NOT NULL REFERENCES sv.branch(id) ON DELETE CASCADE,
    parent_commit_id      uuid        REFERENCES sv.schema_commit(id),
    -- Non-null only for merge commits. The DAG has at most two parents.
    second_parent_commit_id uuid      REFERENCES sv.schema_commit(id),
    snapshot_id           uuid        NOT NULL REFERENCES sv.schema_snapshot(id),
    message               text        NOT NULL DEFAULT '',
    author                text,
    seq                   bigint      NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX schema_commit_branch_seq_idx ON sv.schema_commit (branch_id, seq DESC);
CREATE INDEX schema_commit_parent_idx     ON sv.schema_commit (parent_commit_id);

ALTER TABLE sv.branch
    ADD CONSTRAINT branch_head_commit_fk
    FOREIGN KEY (head_commit_id) REFERENCES sv.schema_commit(id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE sv.branch
    ADD CONSTRAINT branch_base_commit_fk
    FOREIGN KEY (base_commit_id) REFERENCES sv.schema_commit(id) DEFERRABLE INITIALLY DEFERRED;

-- The operation log: what the user did, in order. This is the audit trail and the
-- "how it got there" view. It is NEVER the source of truth for schema state --
-- that is always the snapshot. A bug here can therefore not corrupt a schema.
CREATE TABLE sv.change_op (
    id                 bigserial PRIMARY KEY,
    commit_id          uuid        NOT NULL REFERENCES sv.schema_commit(id) ON DELETE CASCADE,
    ordinal            int         NOT NULL,
    op_type            text        NOT NULL,
    target_kind        text        NOT NULL,
    target_stable_id   text        NOT NULL,
    payload            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    rendered_sql       text,
    applied_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (commit_id, ordinal)
);

-- ---------------------------------------------------------------------------
-- Merge
-- ---------------------------------------------------------------------------

CREATE TABLE sv.merge_request (
    id                    uuid PRIMARY KEY,
    source_branch_id      uuid        NOT NULL REFERENCES sv.branch(id) ON DELETE CASCADE,
    target_branch_id      uuid        NOT NULL REFERENCES sv.branch(id) ON DELETE CASCADE,
    merge_base_commit_id  uuid        REFERENCES sv.schema_commit(id),
    source_head_commit_id uuid        REFERENCES sv.schema_commit(id),
    -- Optimistic concurrency: if the target head moved, the merge must be recomputed.
    expected_target_head_commit_id uuid REFERENCES sv.schema_commit(id),
    state                 text        NOT NULL DEFAULT 'DRAFT',
    merged_snapshot_id    uuid        REFERENCES sv.schema_snapshot(id),
    plan                  jsonb,
    result_commit_id      uuid        REFERENCES sv.schema_commit(id),
    created_at            timestamptz NOT NULL DEFAULT now(),
    applied_at            timestamptz,
    CONSTRAINT merge_state_valid CHECK (state IN
        ('DRAFT','CONFLICTED','RESOLVED','VALIDATING','APPLYING','APPLIED','FAILED','ABANDONED'))
);

CREATE TABLE sv.merge_conflict (
    id                 bigserial PRIMARY KEY,
    merge_request_id   uuid        NOT NULL REFERENCES sv.merge_request(id) ON DELETE CASCADE,
    conflict_type      text        NOT NULL,
    severity           text        NOT NULL,
    object_kind        text        NOT NULL,
    stable_id          text        NOT NULL,
    attribute_path     text,
    base_json          jsonb,
    ours_json          jsonb,
    theirs_json        jsonb,
    resolution         text,            -- OURS | THEIRS | CUSTOM | null when unresolved
    resolved_value     jsonb,
    auto_resolved      boolean     NOT NULL DEFAULT false,
    resolved_at        timestamptz,
    CONSTRAINT conflict_severity_valid CHECK (severity IN
        ('AUTO','SAFE','DESTRUCTIVE','STRUCTURAL')),
    CONSTRAINT conflict_resolution_valid CHECK (resolution IS NULL OR resolution IN
        ('OURS','THEIRS','CUSTOM'))
);

CREATE INDEX merge_conflict_request_idx ON sv.merge_conflict (merge_request_id);

-- ---------------------------------------------------------------------------
-- Migration execution
-- ---------------------------------------------------------------------------

CREATE TABLE sv.migration_run (
    id                 uuid PRIMARY KEY,
    merge_request_id   uuid        REFERENCES sv.merge_request(id) ON DELETE CASCADE,
    target_schema      text        NOT NULL,
    -- ATOMIC: every step is metadata-only, so the whole plan runs in one transaction.
    -- ONLINE: something needs a rewrite or scan, so we stage across transactions and
    -- genuinely give up all-or-nothing. The UI says which.
    mode               text        NOT NULL DEFAULT 'ATOMIC',
    status             text        NOT NULL DEFAULT 'PENDING',
    claimed_by         text,
    heartbeat_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    started_at         timestamptz,
    finished_at        timestamptz,
    error_sqlstate     text,
    error_message      text,
    CONSTRAINT run_mode_valid CHECK (mode IN ('ATOMIC','ONLINE')),
    CONSTRAINT run_status_valid CHECK (status IN
        ('PENDING','RUNNING','AWAITING_CONFIRM','PAUSED','FAILED','SUCCEEDED','CANCELLED'))
);

-- Claim query drives off this: status + created_at, with stale-heartbeat reclaim.
CREATE INDEX migration_run_claim_idx ON sv.migration_run (status, created_at);

CREATE TABLE sv.migration_step (
    id                 bigserial PRIMARY KEY,
    run_id             uuid        NOT NULL REFERENCES sv.migration_run(id) ON DELETE CASCADE,
    seq                int         NOT NULL,
    -- All steps expanded from one logical operation share an op_group, so the UI can
    -- collapse "6 steps" back into "change amount to numeric(14,2)".
    op_group           text        NOT NULL,
    kind               text        NOT NULL,
    description        text        NOT NULL,
    sql_text           text,
    lock_mode          text,
    blocks             text,            -- NOTHING | WRITES | READS_AND_WRITES
    reversible         boolean     NOT NULL DEFAULT true,
    point_of_no_return boolean     NOT NULL DEFAULT false,
    status             text        NOT NULL DEFAULT 'PENDING',
    attempts           int         NOT NULL DEFAULT 0,
    lock_wait_ms       bigint,
    started_at         timestamptz,
    finished_at        timestamptz,
    error_sqlstate     text,
    error_message      text,
    UNIQUE (run_id, seq),
    CONSTRAINT step_kind_valid CHECK (kind IN
        ('DDL','BACKFILL','VALIDATE','INDEX_CONCURRENT','ANALYZE','PREFLIGHT')),
    CONSTRAINT step_status_valid CHECK (status IN
        ('PENDING','RUNNING','SUCCEEDED','FAILED','SKIPPED'))
);

CREATE INDEX migration_step_run_idx ON sv.migration_step (run_id, seq);

CREATE TABLE sv.backfill_cursor (
    step_id            bigint PRIMARY KEY REFERENCES sv.migration_step(id) ON DELETE CASCADE,
    key_column         text        NOT NULL,
    -- text, so one column works for bigint, uuid, timestamptz and tid keys alike.
    last_key           text,
    rows_done          bigint      NOT NULL DEFAULT 0,
    rows_estimated     bigint,
    batch_size         int         NOT NULL DEFAULT 1000,
    batches_done       int         NOT NULL DEFAULT 0,
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sv.backfill_failure (
    id                 bigserial PRIMARY KEY,
    step_id            bigint      NOT NULL REFERENCES sv.migration_step(id) ON DELETE CASCADE,
    low_key            text,
    high_key           text,
    sqlstate           text,
    message            text,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX backfill_failure_step_idx ON sv.backfill_failure (step_id);
