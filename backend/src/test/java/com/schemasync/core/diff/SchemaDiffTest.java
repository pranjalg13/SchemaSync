package com.schemasync.core.diff;

import com.schemasync.core.model.*;
import com.schemasync.ops.SchemaOperation;
import com.schemasync.ops.SnapshotMutator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diff engine, tested without a database.
 *
 * <p>These run in milliseconds because {@link SchemaDiff} and {@link SnapshotMutator} are pure
 * functions -- which is the practical payoff of keeping the version-control core free of Spring
 * and JDBC.
 */
class SchemaDiffTest {

    /** orders(id bigint NOT NULL, amount integer NOT NULL, notes text NULL) */
    private static SchemaSnapshot ordersSchema() {
        Map<String, ColumnDef> columns = new LinkedHashMap<>();
        columns.put("col_id", new ColumnDef("col_id", "id", 1, DataType.of("bigint"), false, null, "BY DEFAULT"));
        columns.put("col_amt", new ColumnDef("col_amt", "amount", 2, DataType.of("integer"), false, null, null));
        columns.put("col_notes", new ColumnDef("col_notes", "notes", 3, DataType.of("text"), true, null, null));
        TableDef orders = new TableDef("tbl_orders", "orders", columns, new LinkedHashMap<>(), new LinkedHashMap<>());
        return SchemaSnapshot.of(Map.of("tbl_orders", orders));
    }

    @Test
    @DisplayName("a rename is a rename, not a drop plus an add")
    void renameIsNotDropAndAdd() {
        SchemaSnapshot before = ordersSchema();
        SchemaSnapshot after = SnapshotMutator.apply(before,
                new SchemaOperation.RenameColumn("tbl_orders", "col_amt", "amount_cents"));

        List<SchemaChange> changes = SchemaDiff.diff(before, after);

        // This assertion IS the product. If the diff ever reports a drop here, the executor would
        // faithfully carry it out and destroy a column of production data where the user asked
        // for a free catalog update.
        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(SchemaChange.Kind.COLUMN_RENAMED);
            assertThat(c.from()).isEqualTo("amount");
            assertThat(c.to()).isEqualTo("amount_cents");
        });
        assertThat(changes).noneMatch(c -> c.kind() == SchemaChange.Kind.COLUMN_DROPPED);
        assertThat(changes).noneMatch(c -> c.kind() == SchemaChange.Kind.COLUMN_ADDED);
    }

    @Test
    @DisplayName("renaming AND retyping the same column reports both, on one column")
    void renameAndRetypeCompose() {
        // The case that defeats every heuristic rename detector: name and type both change at
        // once, so there is no similarity left to match on. Stable ids do not care.
        SchemaSnapshot before = ordersSchema();
        SchemaSnapshot after = SnapshotMutator.apply(
                SnapshotMutator.apply(before,
                        new SchemaOperation.RenameColumn("tbl_orders", "col_amt", "total")),
                new SchemaOperation.ChangeColumnType("tbl_orders", "col_amt", "numeric(14,2)", null));

        List<SchemaChange> changes = SchemaDiff.diff(before, after);

        assertThat(changes).hasSize(2);
        assertThat(changes).allMatch(c -> c.objectId().equals("col_amt"));
        assertThat(changes).anyMatch(c -> c.kind() == SchemaChange.Kind.COLUMN_RENAMED);
        assertThat(changes).anyMatch(c -> c.kind() == SchemaChange.Kind.COLUMN_TYPE_CHANGED);
        assertThat(changes).noneMatch(c -> c.kind() == SchemaChange.Kind.COLUMN_DROPPED);
    }

    @Test
    @DisplayName("a genuine drop is still reported as a drop")
    void dropIsStillADrop() {
        // The mirror of the rename test: identity must not make everything look like a rename.
        SchemaSnapshot before = ordersSchema();
        SchemaSnapshot after = SnapshotMutator.apply(before,
                new SchemaOperation.DropColumn("tbl_orders", "col_notes"));

        assertThat(SchemaDiff.diff(before, after)).singleElement()
                .satisfies(c -> {
                    assertThat(c.kind()).isEqualTo(SchemaChange.Kind.COLUMN_DROPPED);
                    assertThat(c.kind().isDestructive()).isTrue();
                });
    }

    @Test
    @DisplayName("add then drop within a branch collapses to no change")
    void addThenDropCollapses() {
        // Snapshots describe state, so intermediate churn vanishes. Replaying an operation log
        // would instead report two changes that cancel out -- which is why diff reads snapshots
        // and the history view reads the log.
        SchemaSnapshot before = ordersSchema();
        SchemaSnapshot withColumn = SnapshotMutator.apply(before,
                new SchemaOperation.AddColumn("tbl_orders", "temp", "text", true, null));
        String tempId = withColumn.tables().get("tbl_orders").columnByName("temp").id();
        SchemaSnapshot after = SnapshotMutator.apply(withColumn,
                new SchemaOperation.DropColumn("tbl_orders", tempId));

        assertThat(SchemaDiff.diff(before, after)).isEmpty();
    }

    @Test
    @DisplayName("dropping a column takes its dependent indexes with it")
    void dropColumnCascadesToIndexes() {
        // Postgres does this implicitly. If the snapshot did not mirror it, the recorded schema
        // would claim an index that no longer exists and the next merge would try to act on it.
        SchemaSnapshot before = SnapshotMutator.apply(ordersSchema(),
                new SchemaOperation.AddIndex("tbl_orders", "orders_notes_idx",
                        List.of("col_notes"), false, "btree"));
        assertThat(before.tables().get("tbl_orders").indexes()).hasSize(1);

        SchemaSnapshot after = SnapshotMutator.apply(before,
                new SchemaOperation.DropColumn("tbl_orders", "col_notes"));

        assertThat(after.tables().get("tbl_orders").indexes()).isEmpty();
        assertThat(SchemaDiff.diff(before, after))
                .anyMatch(c -> c.kind() == SchemaChange.Kind.INDEX_DROPPED);
    }

    @Test
    @DisplayName("column position is ignored, so one drop does not churn every later column")
    void positionIsNotDiffed() {
        SchemaSnapshot before = ordersSchema();
        Map<String, ColumnDef> shifted = new LinkedHashMap<>();
        before.tables().get("tbl_orders").columns().forEach((id, c) ->
                shifted.put(id, new ColumnDef(c.id(), c.name(), c.position() + 10, c.type(),
                        c.nullable(), c.defaultExpr(), c.identity())));
        SchemaSnapshot after = before.withTables(
                Map.of("tbl_orders", before.tables().get("tbl_orders").withColumns(shifted)));

        assertThat(SchemaDiff.diff(before, after)).isEmpty();
    }

    @Test
    @DisplayName("an unchanged snapshot diffs to nothing")
    void identityDiff() {
        SchemaSnapshot s = ordersSchema();
        assertThat(SchemaDiff.diff(s, s)).isEmpty();
        assertThat(SnapshotHasher.hash(s)).isEqualTo(SnapshotHasher.hash(ordersSchema()));
    }
}
