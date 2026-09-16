package com.schemasync.core.merge;

import com.schemasync.core.model.*;
import com.schemasync.ops.SchemaOperation;
import com.schemasync.ops.SnapshotMutator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The merge algebra and the conflict taxonomy, tested without a database. */
class ThreeWayMergerTest {

    /** orders(id, amount integer NOT NULL, notes text NULL) */
    private static SchemaSnapshot base() {
        Map<String, ColumnDef> cols = new LinkedHashMap<>();
        cols.put("col_id", new ColumnDef("col_id", "id", 1, DataType.of("bigint"), false, null, "BY DEFAULT"));
        cols.put("col_amt", new ColumnDef("col_amt", "amount", 2, DataType.of("integer"), false, null, null));
        cols.put("col_notes", new ColumnDef("col_notes", "notes", 3, DataType.of("text"), true, null, null));
        return SchemaSnapshot.of(Map.of("tbl_orders",
                new TableDef("tbl_orders", "orders", cols, new LinkedHashMap<>(), new LinkedHashMap<>())));
    }

    private static ColumnDef col(MergeResult r, String id) {
        return r.merged().tables().get("tbl_orders").columns().get(id);
    }

    @Nested
    @DisplayName("algebraic properties")
    class Algebra {

        @Test
        @DisplayName("merging a branch with itself changes nothing")
        void mergeWithSelfIsIdentity() {
            SchemaSnapshot b = base();
            SchemaSnapshot x = SnapshotMutator.apply(b,
                    new SchemaOperation.RenameColumn("tbl_orders", "col_amt", "total"));

            MergeResult r = ThreeWayMerger.merge(b, x, x);

            assertThat(r.isClean()).isTrue();
            assertThat(SnapshotHasher.hash(r.merged())).isEqualTo(SnapshotHasher.hash(x));
        }

        @Test
        @DisplayName("merging an untouched branch takes the other side's changes")
        void mergeWithUnchangedTakesTheChange() {
            SchemaSnapshot b = base();
            SchemaSnapshot x = SnapshotMutator.apply(b,
                    new SchemaOperation.AddColumn("tbl_orders", "currency", "varchar(3)", true, null));

            // Whichever side is the unchanged one, the result is the same.
            assertThat(SnapshotHasher.hash(ThreeWayMerger.merge(b, b, x).merged()))
                    .isEqualTo(SnapshotHasher.hash(x));
            assertThat(SnapshotHasher.hash(ThreeWayMerger.merge(b, x, b).merged()))
                    .isEqualTo(SnapshotHasher.hash(x));
        }

        @Test
        @DisplayName("swapping the two sides finds the same conflicts")
        void symmetry() {
            // Asymmetry here would mean merging main<-feature and feature<-main disagree about
            // whether there is a problem, which is the kind of bug that is invisible until it
            // corrupts something.
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b,
                    new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "bigint", null));
            SchemaSnapshot theirs = SnapshotMutator.apply(b,
                    new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "numeric(14,2)", null));

            MergeResult forward = ThreeWayMerger.merge(b, ours, theirs);
            MergeResult reverse = ThreeWayMerger.merge(b, theirs, ours);

            assertThat(forward.unresolved()).hasSameSizeAs(reverse.unresolved());
            assertThat(forward.unresolved().get(0).type())
                    .isEqualTo(reverse.unresolved().get(0).type());
            // Only the sides are swapped.
            assertThat(forward.unresolved().get(0).ours())
                    .isEqualTo(reverse.unresolved().get(0).theirs());
        }
    }

    @Nested
    @DisplayName("clean merges")
    class Clean {

        @Test
        @DisplayName("edits to different columns combine without a conflict")
        void disjointEdits() {
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b,
                    new SchemaOperation.RenameColumn("tbl_orders", "col_amt", "amount_cents"));
            SchemaSnapshot theirs = SnapshotMutator.apply(b,
                    new SchemaOperation.DropColumn("tbl_orders", "col_notes"));

            MergeResult r = ThreeWayMerger.merge(b, ours, theirs);

            assertThat(r.isClean()).isTrue();
            assertThat(col(r, "col_amt").name()).isEqualTo("amount_cents");
            assertThat(r.merged().tables().get("tbl_orders").columns()).doesNotContainKey("col_notes");
        }

        @Test
        @DisplayName("a rename on one side and a retype on the other combine on one column")
        void renameAndRetypeCombine() {
            // The case an object-level merge would wrongly call a conflict, forcing the user to
            // choose between two changes that do not actually disagree. Merging per attribute
            // resolves it, and stable ids are what make the attributes line up.
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b,
                    new SchemaOperation.RenameColumn("tbl_orders", "col_amt", "amount_cents"));
            SchemaSnapshot theirs = SnapshotMutator.apply(b,
                    new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "bigint", null));

            MergeResult r = ThreeWayMerger.merge(b, ours, theirs);

            assertThat(r.isClean()).isTrue();
            assertThat(col(r, "col_amt").name()).isEqualTo("amount_cents");
            assertThat(col(r, "col_amt").type().sql()).isEqualTo("bigint");
        }

        @Test
        @DisplayName("both sides making the identical change is agreement, not conflict")
        void identicalChangesAgree() {
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b,
                    new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "bigint", null));
            SchemaSnapshot theirs = SnapshotMutator.apply(b,
                    new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "bigint", null));

            MergeResult r = ThreeWayMerger.merge(b, ours, theirs);

            assertThat(r.isClean()).isTrue();
            assertThat(col(r, "col_amt").type().sql()).isEqualTo("bigint");
        }

        @Test
        @DisplayName("dropping something the other side never touched is not a conflict")
        void uncontestedDrop() {
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b,
                    new SchemaOperation.DropColumn("tbl_orders", "col_notes"));
            SchemaSnapshot theirs = SnapshotMutator.apply(b,
                    new SchemaOperation.AddColumn("tbl_orders", "currency", "varchar(3)", true, null));

            assertThat(ThreeWayMerger.merge(b, ours, theirs).isClean()).isTrue();
        }
    }

    @Nested
    @DisplayName("conflict taxonomy")
    class Conflicts {

        @Test
        @DisplayName("both sides retyping the same column differently is structural")
        void bothRetyped() {
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b,
                    new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "bigint", null));
            SchemaSnapshot theirs = SnapshotMutator.apply(b,
                    new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "numeric(14,2)", null));

            MergeResult r = ThreeWayMerger.merge(b, ours, theirs);

            assertThat(r.isClean()).isFalse();
            MergeConflict c = r.unresolved().get(0);
            assertThat(c.type()).isEqualTo(MergeConflict.Type.BOTH_MODIFIED);
            assertThat(c.severity()).isEqualTo(MergeConflict.Severity.STRUCTURAL);
            assertThat(c.attribute()).isEqualTo("type");
            assertThat(c.ours()).isEqualTo("bigint");
            assertThat(c.theirs()).isEqualTo("numeric(14,2)");
        }

        @Test
        @DisplayName("one side editing what the other dropped is DESTRUCTIVE")
        void modifiedAndDropped() {
            // The severity matters: resolving this the wrong way silently deletes a column that
            // someone was actively working on.
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b,
                    new SchemaOperation.DropColumn("tbl_orders", "col_amt"));
            SchemaSnapshot theirs = SnapshotMutator.apply(b,
                    new SchemaOperation.RenameColumn("tbl_orders", "col_amt", "amount_cents"));

            MergeResult r = ThreeWayMerger.merge(b, ours, theirs);

            MergeConflict c = r.unresolved().get(0);
            assertThat(c.type()).isEqualTo(MergeConflict.Type.MODIFIED_AND_DROPPED);
            assertThat(c.severity()).isEqualTo(MergeConflict.Severity.DESTRUCTIVE);
            assertThat(r.hasDestructiveConflicts()).isTrue();
        }

        @Test
        @DisplayName("both sides renaming the same column is only SAFE")
        void bothRenamed() {
            // Nothing is lost whichever way this goes, so it should not carry the same weight in
            // the UI as a conflict that can destroy data.
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b,
                    new SchemaOperation.RenameColumn("tbl_orders", "col_amt", "amount_cents"));
            SchemaSnapshot theirs = SnapshotMutator.apply(b,
                    new SchemaOperation.RenameColumn("tbl_orders", "col_amt", "total"));

            MergeConflict c = ThreeWayMerger.merge(b, ours, theirs).unresolved().get(0);
            assertThat(c.type()).isEqualTo(MergeConflict.Type.BOTH_RENAMED);
            assertThat(c.severity()).isEqualTo(MergeConflict.Severity.SAFE);
        }

        @Test
        @DisplayName("two new columns that collide on name are caught in the merged result")
        void addAddNameCollision() {
            // Neither branch is invalid on its own; the clash exists only once combined, so it
            // can only be found by inspecting the merge output.
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b,
                    new SchemaOperation.AddColumn("tbl_orders", "status", "varchar(20)", true, null));
            SchemaSnapshot theirs = SnapshotMutator.apply(b,
                    new SchemaOperation.AddColumn("tbl_orders", "status", "integer", true, null));

            MergeResult r = ThreeWayMerger.merge(b, ours, theirs);

            assertThat(r.unresolved()).anyMatch(c ->
                    c.type() == MergeConflict.Type.ADD_ADD_NAME_COLLISION
                            && c.severity() == MergeConflict.Severity.STRUCTURAL);
        }

        @Test
        @DisplayName("the same index added on both sides is auto-resolved, not asked about")
        void duplicateIndexIntent() {
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b, new SchemaOperation.AddIndex(
                    "tbl_orders", "orders_notes_idx", java.util.List.of("col_notes"), false, "btree"));
            SchemaSnapshot theirs = SnapshotMutator.apply(b, new SchemaOperation.AddIndex(
                    "tbl_orders", "idx_orders_notes", java.util.List.of("col_notes"), false, "btree"));

            MergeResult r = ThreeWayMerger.merge(b, ours, theirs);

            // Same intent under two names. Deduped silently, but still reported so the user can
            // audit what was decided for them.
            assertThat(r.isClean()).isTrue();
            assertThat(r.conflicts()).anyMatch(c ->
                    c.type() == MergeConflict.Type.DUPLICATE_INTENT
                            && c.severity() == MergeConflict.Severity.AUTO);
            assertThat(r.merged().tables().get("tbl_orders").indexes()).hasSize(1);
        }

        @Test
        @DisplayName("different indexes sharing a name are a structural conflict")
        void indexNameCollision() {
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b, new SchemaOperation.AddIndex(
                    "tbl_orders", "orders_idx", java.util.List.of("col_notes"), false, "btree"));
            SchemaSnapshot theirs = SnapshotMutator.apply(b, new SchemaOperation.AddIndex(
                    "tbl_orders", "orders_idx", java.util.List.of("col_amt"), false, "btree"));

            assertThat(ThreeWayMerger.merge(b, ours, theirs).unresolved())
                    .anyMatch(c -> c.type() == MergeConflict.Type.INDEX_NAME_COLLISION);
        }

        @Test
        @DisplayName("every conflict asks the user an actual question")
        void conflictsAreExplained() {
            SchemaSnapshot b = base();
            SchemaSnapshot ours = SnapshotMutator.apply(b,
                    new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "bigint", null));
            SchemaSnapshot theirs = SnapshotMutator.apply(b,
                    new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "text", null));

            assertThat(ThreeWayMerger.merge(b, ours, theirs).unresolved())
                    .allSatisfy(c -> assertThat(c.question()).isNotBlank());
        }
    }
}
