package com.schemasync.ops;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * The nine operations the branch editor can perform.
 *
 * <p>This closed set is the reason the rest of the system is tractable. Because every change
 * arrives as one of these -- rather than as free-form SQL we would have to parse and interpret --
 * three things follow:
 *
 * <ul>
 *   <li><b>A rename is an intent, not an inference.</b> {@link RenameColumn} names the column by
 *       its stable id and supplies a new name. Nothing has to guess whether a drop and an add were
 *       "really" a rename, which is the difference between a free catalog update and destroying a
 *       column of production data.</li>
 *   <li><b>The conflict matrix is finite.</b> Nine verbs over a fixed attribute set enumerate, so
 *       every conflict can be given a predetermined resolution rather than a general-purpose merge
 *       editor.</li>
 *   <li><b>There is no SQL parser.</b> Operations are rendered to SQL, never read from it.</li>
 * </ul>
 *
 * <p>A sealed interface, so adding a tenth operation forces every switch in the planner and
 * classifier to be updated -- the compiler enforces completeness instead of a default branch
 * silently swallowing it.
 */
// The discriminator is "op", not "type": AddColumn already has a field called "type" (its SQL
// data type), and Jackson cannot use one property for both the subtype tag and a real field.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SchemaOperation.AddColumn.class, name = "ADD_COLUMN"),
        @JsonSubTypes.Type(value = SchemaOperation.DropColumn.class, name = "DROP_COLUMN"),
        @JsonSubTypes.Type(value = SchemaOperation.RenameColumn.class, name = "RENAME_COLUMN"),
        @JsonSubTypes.Type(value = SchemaOperation.ChangeColumnType.class, name = "CHANGE_COLUMN_TYPE"),
        @JsonSubTypes.Type(value = SchemaOperation.SetColumnNullable.class, name = "SET_COLUMN_NULLABLE"),
        @JsonSubTypes.Type(value = SchemaOperation.SetColumnDefault.class, name = "SET_COLUMN_DEFAULT"),
        @JsonSubTypes.Type(value = SchemaOperation.CreateTable.class, name = "CREATE_TABLE"),
        @JsonSubTypes.Type(value = SchemaOperation.DropTable.class, name = "DROP_TABLE"),
        @JsonSubTypes.Type(value = SchemaOperation.AddIndex.class, name = "ADD_INDEX"),
        @JsonSubTypes.Type(value = SchemaOperation.DropIndex.class, name = "DROP_INDEX"),
})
public sealed interface SchemaOperation {

    /** The stable id of the object this operation acts on. */
    String targetId();

    String kind();

    record AddColumn(
            String tableId,
            String name,
            String type,
            boolean nullable,
            String defaultExpr
    ) implements SchemaOperation {
        public String targetId() { return tableId; }
        public String kind() { return "ADD_COLUMN"; }
    }

    record DropColumn(String tableId, String columnId) implements SchemaOperation {
        public String targetId() { return columnId; }
        public String kind() { return "DROP_COLUMN"; }
    }

    record RenameColumn(String tableId, String columnId, String newName) implements SchemaOperation {
        public String targetId() { return columnId; }
        public String kind() { return "RENAME_COLUMN"; }
    }

    record ChangeColumnType(String tableId, String columnId, String newType, String usingExpr)
            implements SchemaOperation {
        public String targetId() { return columnId; }
        public String kind() { return "CHANGE_COLUMN_TYPE"; }
    }

    record SetColumnNullable(String tableId, String columnId, boolean nullable, String fillValue)
            implements SchemaOperation {
        public String targetId() { return columnId; }
        public String kind() { return "SET_COLUMN_NULLABLE"; }
    }

    record SetColumnDefault(String tableId, String columnId, String defaultExpr)
            implements SchemaOperation {
        public String targetId() { return columnId; }
        public String kind() { return "SET_COLUMN_DEFAULT"; }
    }

    record CreateTable(String name, List<NewColumn> columns) implements SchemaOperation {
        public String targetId() { return name; }
        public String kind() { return "CREATE_TABLE"; }

        public record NewColumn(String name, String type, boolean nullable,
                                String defaultExpr, boolean primaryKey) {}
    }

    record DropTable(String tableId) implements SchemaOperation {
        public String targetId() { return tableId; }
        public String kind() { return "DROP_TABLE"; }
    }

    record AddIndex(String tableId, String name, List<String> columnIds,
                    boolean unique, String method) implements SchemaOperation {
        public String targetId() { return tableId; }
        public String kind() { return "ADD_INDEX"; }
    }

    record DropIndex(String tableId, String indexId) implements SchemaOperation {
        public String targetId() { return indexId; }
        public String kind() { return "DROP_INDEX"; }
    }
}
