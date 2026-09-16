-- Deleting a branch must not delete its history.
--
-- Once a branch is merged, its head commit becomes the second parent of the merge commit on the
-- target. Deleting the branch cascades to its commits and orphans that reference, so Postgres
-- rejects it -- correctly. The commit graph is shared history and is not the branch's to take
-- with it.
--
-- So "delete" drops the branch's Postgres schema (which is what actually costs storage) and
-- leaves an ABANDONED tombstone row behind. The name has to be reusable afterwards, which a
-- plain unique constraint would prevent, hence the partial index.

ALTER TABLE sv.branch DROP CONSTRAINT IF EXISTS branch_name_unique_per_project;

CREATE UNIQUE INDEX branch_active_name_unique
    ON sv.branch (project_id, name)
    WHERE status <> 'ABANDONED';

-- Records that the Postgres schema behind this branch has been dropped, so the UI can say
-- "merged, storage reclaimed" rather than pretending the branch is still browsable.
ALTER TABLE sv.branch ADD COLUMN IF NOT EXISTS schema_dropped boolean NOT NULL DEFAULT false;
