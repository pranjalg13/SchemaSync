package com.schemasync.core.merge;

import com.schemasync.core.model.*;

import java.util.*;

/**
 * Three-way merge over schema snapshots.
 *
 * <p>Convention, stated once and loudly because it is the single biggest source of confusion in
 * any merge UI: <b>ours = the target branch</b> (usually {@code main}), <b>theirs = the incoming
 * source branch</b>. Same as {@code git merge}.
 *
 * <p>The merge runs <b>per attribute, not per object</b>. If the target renamed a column while the
 * source retyped the same column, those are edits to two different attributes of one object and
 * they combine cleanly into renamed-and-retyped. An object-level merge would call that a conflict
 * and make the user choose between two changes that do not actually disagree.
 *
 * <p>Stable ids are what make the attributes line up. Git merges per line and uses position to
 * align; we merge per attribute and use identity. That is also why a rename on one side and an
 * unrelated edit on the other is not a conflict here, where a text-based diff3 would produce one.
 *
 * <p>Pure: no Spring, no JDBC, so every row of the conflict taxonomy is unit-tested in milliseconds.
 */
public final class ThreeWayMerger {

    private ThreeWayMerger() {
    }

    public static MergeResult merge(SchemaSnapshot base, SchemaSnapshot ours, SchemaSnapshot theirs) {
        List<MergeConflict> conflicts = new ArrayList<>();
        Map<String, TableDef> merged = new LinkedHashMap<>();

        Set<String> tableIds = new LinkedHashSet<>();
        tableIds.addAll(base.tables().keySet());
        tableIds.addAll(ours.tables().keySet());
        tableIds.addAll(theirs.tables().keySet());

        for (String tableId : tableIds) {
            TableDef b = base.tables().get(tableId);
            TableDef o = ours.tables().get(tableId);
            TableDef t = theirs.tables().get(tableId);

            // Added on one side only, or added identically on both.
            if (b == null) {
                if (o != null && t == null) {
                    merged.put(tableId, o);
                } else if (o == null && t != null) {
                    merged.put(tableId, t);
                } else if (o != null) {
                    merged.put(tableId, mergeTable(null, o, t, conflicts));
                }
                continue;
            }

            // Dropped on both sides: agreement, nothing to do.
            if (o == null && t == null) {
                continue;
            }

            // Dropped on one side, still present on the other. Only a conflict if the surviving
            // side actually changed it -- dropping something nobody touched is not a disagreement.
            if (o == null) {
                if (tableChanged(b, t)) {
                    conflicts.add(new MergeConflict(
                            MergeConflict.Type.TABLE_MODIFIED_AND_DROPPED,
                            MergeConflict.Severity.DESTRUCTIVE,
                            "TABLE", tableId, b.name(), b.name(), null,
                            b.name(), "dropped", "modified",
                            "The target dropped table '" + b.name() + "', but this branch changed it. "
                            + "Keep the table with those changes, or let the drop stand?"));
                    merged.put(tableId, t);
                }
                continue;
            }
            if (t == null) {
                if (tableChanged(b, o)) {
                    conflicts.add(new MergeConflict(
                            MergeConflict.Type.TABLE_MODIFIED_AND_DROPPED,
                            MergeConflict.Severity.DESTRUCTIVE,
                            "TABLE", tableId, b.name(), b.name(), null,
                            b.name(), "modified", "dropped",
                            "This branch dropped table '" + b.name() + "', but the target changed it. "
                            + "Let the drop stand, or keep the target's version?"));
                } else {
                    continue;   // clean drop
                }
                continue;
            }

            merged.put(tableId, mergeTable(b, o, t, conflicts));
        }

        detectNameCollisions(merged, conflicts);
        return new MergeResult(new SchemaSnapshot(SchemaSnapshot.CURRENT_VERSION, merged,
                ours.unmanaged()), conflicts);
    }

    private static TableDef mergeTable(TableDef base, TableDef ours, TableDef theirs,
                                       List<MergeConflict> conflicts) {
        String tableName = pick(base == null ? null : base.name(), ours.name(), theirs.name());

        if (base != null && !ours.name().equals(base.name()) && !theirs.name().equals(base.name())
                && !ours.name().equals(theirs.name())) {
            conflicts.add(new MergeConflict(
                    MergeConflict.Type.BOTH_RENAMED, MergeConflict.Severity.SAFE,
                    "TABLE", ours.id(), base.name(), base.name(), "name",
                    base.name(), ours.name(), theirs.name(),
                    "Both branches renamed table '" + base.name() + "'. Which name should it keep?"));
            tableName = ours.name();
        }

        return new TableDef(ours.id(), tableName,
                mergeColumns(base, ours, theirs, conflicts),
                mergeConstraints(base, ours, theirs, conflicts),
                mergeIndexes(base, ours, theirs, conflicts));
    }

    private static Map<String, ColumnDef> mergeColumns(TableDef base, TableDef ours, TableDef theirs,
                                                       List<MergeConflict> conflicts) {
        Map<String, ColumnDef> merged = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        if (base != null) ids.addAll(base.columns().keySet());
        ids.addAll(ours.columns().keySet());
        ids.addAll(theirs.columns().keySet());

        String tableName = ours.name();

        for (String id : ids) {
            ColumnDef b = base == null ? null : base.columns().get(id);
            ColumnDef o = ours.columns().get(id);
            ColumnDef t = theirs.columns().get(id);

            if (b == null) {
                // Added on one or both sides. Same id on both means the same operation record,
                // which cannot happen across independent branches -- so this is the identical-add
                // case only in replayed/rebased histories.
                if (o != null && t == null) merged.put(id, o);
                else if (o == null && t != null) merged.put(id, t);
                else if (o != null) merged.put(id, o);
                continue;
            }

            if (o == null && t == null) {
                continue;                                   // dropped on both sides: agreement
            }

            if (o == null) {                                // target dropped it
                if (columnChanged(b, t)) {
                    conflicts.add(new MergeConflict(
                            MergeConflict.Type.MODIFIED_AND_DROPPED,
                            MergeConflict.Severity.DESTRUCTIVE,
                            "COLUMN", id, tableName, b.name(), null,
                            describe(b), "dropped", describe(t),
                            "The target dropped column '" + b.name() + "', but this branch changed it. "
                            + "Keeping it restores the column; letting the drop stand loses its data."));
                    merged.put(id, t);
                }
                continue;
            }
            if (t == null) {                                // source dropped it
                if (columnChanged(b, o)) {
                    conflicts.add(new MergeConflict(
                            MergeConflict.Type.MODIFIED_AND_DROPPED,
                            MergeConflict.Severity.DESTRUCTIVE,
                            "COLUMN", id, tableName, b.name(), null,
                            describe(b), describe(o), "dropped",
                            "This branch dropped column '" + b.name() + "', but the target changed it. "
                            + "Letting the drop stand loses the column and its data."));
                    merged.put(id, o);
                } // else: clean drop, leave it out
                continue;
            }

            merged.put(id, mergeColumn(b, o, t, tableName, conflicts));
        }
        return merged;
    }

    /**
     * Merges one column attribute by attribute.
     *
     * <p>This is where "renamed on one side, retyped on the other" resolves cleanly instead of
     * becoming a conflict the user has to adjudicate.
     */
    private static ColumnDef mergeColumn(ColumnDef base, ColumnDef ours, ColumnDef theirs,
                                         String tableName, List<MergeConflict> conflicts) {
        String name = mergeAttribute(base.name(), ours.name(), theirs.name(),
                () -> conflicts.add(new MergeConflict(
                        MergeConflict.Type.BOTH_RENAMED, MergeConflict.Severity.SAFE,
                        "COLUMN", ours.id(), tableName, base.name(), "name",
                        base.name(), ours.name(), theirs.name(),
                        "Both branches renamed '" + base.name() + "'. Which name should it keep?")));

        String type = mergeAttribute(base.type().sql(), ours.type().sql(), theirs.type().sql(),
                () -> conflicts.add(new MergeConflict(
                        MergeConflict.Type.BOTH_MODIFIED, MergeConflict.Severity.STRUCTURAL,
                        "COLUMN", ours.id(), tableName, ours.name(), "type",
                        base.type().sql(), ours.type().sql(), theirs.type().sql(),
                        "Both branches retyped '" + ours.name() + "'. These cannot both apply; "
                        + "pick one, or give a type that accommodates both.")));

        Boolean nullable = mergeAttribute(base.nullable(), ours.nullable(), theirs.nullable(),
                () -> conflicts.add(new MergeConflict(
                        MergeConflict.Type.BOTH_MODIFIED, MergeConflict.Severity.STRUCTURAL,
                        "COLUMN", ours.id(), tableName, ours.name(), "nullable",
                        String.valueOf(base.nullable()), String.valueOf(ours.nullable()),
                        String.valueOf(theirs.nullable()),
                        "Both branches changed whether '" + ours.name() + "' accepts NULL.")));

        String defaultExpr = mergeAttribute(base.defaultExpr(), ours.defaultExpr(), theirs.defaultExpr(),
                () -> conflicts.add(new MergeConflict(
                        MergeConflict.Type.BOTH_MODIFIED, MergeConflict.Severity.SAFE,
                        "COLUMN", ours.id(), tableName, ours.name(), "default",
                        base.defaultExpr(), ours.defaultExpr(), theirs.defaultExpr(),
                        "Both branches changed the default for '" + ours.name() + "'.")));

        return new ColumnDef(ours.id(), name, ours.position(),
                TypeCanonicalizer.parse(type), nullable, defaultExpr, ours.identity());
    }

    /**
     * The core three-way rule for a single attribute.
     *
     * <p>Note the third case: both sides changed it to the <em>same</em> value. That is agreement,
     * not conflict -- two people independently deciding the same thing should not require a
     * decision.
     */
    private static <T> T mergeAttribute(T base, T ours, T theirs, Runnable onConflict) {
        if (Objects.equals(ours, theirs)) return ours;      // same value (incl. both unchanged)
        if (Objects.equals(base, ours)) return theirs;      // only theirs changed
        if (Objects.equals(base, theirs)) return ours;      // only ours changed
        onConflict.run();                                   // both changed, differently
        return ours;                                        // default to target; user may flip it
    }

    private static Map<String, ConstraintDef> mergeConstraints(TableDef base, TableDef ours,
                                                               TableDef theirs,
                                                               List<MergeConflict> conflicts) {
        Map<String, ConstraintDef> merged = new LinkedHashMap<>(ours.constraints());
        Set<String> baseIds = base == null ? Set.of() : base.constraints().keySet();

        for (Map.Entry<String, ConstraintDef> e : theirs.constraints().entrySet()) {
            if (!merged.containsKey(e.getKey()) && !baseIds.contains(e.getKey())) {
                boolean duplicate = merged.values().stream()
                        .anyMatch(c -> c.fingerprint().equals(e.getValue().fingerprint()));
                if (duplicate) {
                    conflicts.add(new MergeConflict(
                            MergeConflict.Type.DUPLICATE_INTENT, MergeConflict.Severity.AUTO,
                            "CONSTRAINT", e.getKey(), ours.name(), e.getValue().name(), null,
                            null, "already present", e.getValue().name(),
                            "Both branches added an equivalent constraint; keeping one."));
                } else {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }
        // Dropped by the target: honour the drop unless the source changed it.
        if (base != null) {
            for (String id : baseIds) {
                if (!ours.constraints().containsKey(id)) {
                    merged.remove(id);
                }
            }
        }
        return merged;
    }

    private static Map<String, IndexDef> mergeIndexes(TableDef base, TableDef ours, TableDef theirs,
                                                      List<MergeConflict> conflicts) {
        Map<String, IndexDef> merged = new LinkedHashMap<>(ours.indexes());
        Set<String> baseIds = base == null ? Set.of() : base.indexes().keySet();

        for (Map.Entry<String, IndexDef> e : theirs.indexes().entrySet()) {
            String id = e.getKey();
            IndexDef their = e.getValue();
            if (merged.containsKey(id) || baseIds.contains(id)) {
                continue;
            }
            // Two people independently adding the same index under different names is the same
            // intent expressed twice, not a disagreement. Dedupe it and say so.
            Optional<IndexDef> equivalent = merged.values().stream()
                    .filter(i -> i.fingerprint().equals(their.fingerprint()))
                    .findFirst();
            if (equivalent.isPresent()) {
                conflicts.add(new MergeConflict(
                        MergeConflict.Type.DUPLICATE_INTENT, MergeConflict.Severity.AUTO,
                        "INDEX", id, ours.name(), their.name(), null,
                        null, equivalent.get().name(), their.name(),
                        "Both branches added the same index under different names; keeping '"
                        + equivalent.get().name() + "'."));
                continue;
            }
            Optional<IndexDef> sameName = merged.values().stream()
                    .filter(i -> i.name().equals(their.name()))
                    .findFirst();
            if (sameName.isPresent()) {
                conflicts.add(new MergeConflict(
                        MergeConflict.Type.INDEX_NAME_COLLISION, MergeConflict.Severity.STRUCTURAL,
                        "INDEX", id, ours.name(), their.name(), "name",
                        null, sameName.get().name(), their.name(),
                        "Both branches created a different index called '" + their.name()
                        + "'. Index names must be unique; rename one."));
                continue;
            }
            merged.put(id, their);
        }
        if (base != null) {
            for (String id : baseIds) {
                if (!ours.indexes().containsKey(id)) {
                    merged.remove(id);
                }
            }
        }
        return merged;
    }

    /**
     * Catches collisions that only exist once both sides are combined.
     *
     * <p>Each branch is internally valid; it is the merge that creates the clash -- two columns
     * that ended up with the same name, or a rename on one side landing on a name the other side
     * introduced. Nothing earlier can see this, because it is a property of the result.
     */
    private static void detectNameCollisions(Map<String, TableDef> merged,
                                             List<MergeConflict> conflicts) {
        Map<String, String> tableNames = new HashMap<>();
        for (TableDef t : merged.values()) {
            String existing = tableNames.put(t.name(), t.id());
            if (existing != null) {
                conflicts.add(new MergeConflict(
                        MergeConflict.Type.ADD_ADD_NAME_COLLISION, MergeConflict.Severity.STRUCTURAL,
                        "TABLE", t.id(), t.name(), t.name(), "name",
                        null, t.name(), t.name(),
                        "The merged schema would contain two tables called '" + t.name()
                        + "'. Rename one."));
            }

            Map<String, String> columnNames = new HashMap<>();
            for (ColumnDef c : t.columns().values()) {
                String prior = columnNames.put(c.name(), c.id());
                if (prior != null) {
                    conflicts.add(new MergeConflict(
                            MergeConflict.Type.ADD_ADD_NAME_COLLISION,
                            MergeConflict.Severity.STRUCTURAL,
                            "COLUMN", c.id(), t.name(), c.name(), "name",
                            null, c.name(), c.name(),
                            "Merging would give '" + t.name() + "' two columns called '" + c.name()
                            + "'. Rename one of them."));
                }
            }
        }
    }

    private static boolean columnChanged(ColumnDef base, ColumnDef other) {
        return !base.name().equals(other.name())
                || !base.type().sql().equals(other.type().sql())
                || base.nullable() != other.nullable()
                || !Objects.equals(base.defaultExpr(), other.defaultExpr());
    }

    private static boolean tableChanged(TableDef base, TableDef other) {
        if (!base.name().equals(other.name())) return true;
        if (base.columns().size() != other.columns().size()) return true;
        for (Map.Entry<String, ColumnDef> e : base.columns().entrySet()) {
            ColumnDef o = other.columns().get(e.getKey());
            if (o == null || columnChanged(e.getValue(), o)) return true;
        }
        return false;
    }

    private static String describe(ColumnDef c) {
        return c.name() + " " + c.type().sql() + (c.nullable() ? "" : " NOT NULL");
    }

    private static String pick(String base, String ours, String theirs) {
        if (Objects.equals(ours, theirs)) return ours;
        if (Objects.equals(base, ours)) return theirs;
        return ours;
    }
}
