package com.schemasync.core.plan;

/**
 * What one operation will cost when applied to a real table.
 *
 * <p>Drives both the execution strategy and the risk badge shown before anything runs. The
 * {@code rationale} is user-facing: telling someone "this rewrites 40 million rows" is only useful
 * if you also say why.
 */
public record Classification(
        Verdict verdict,
        LockMode lock,
        Blocks blocks,
        String rationale
) {
    public enum Verdict {
        /** Catalog-only. Safe at any table size. */
        INSTANT,
        /** Reads every row but does not rewrite them. Needs an online strategy on a large table. */
        SCAN,
        /** Rewrites the whole table and all its indexes. Never issued directly on a large table. */
        REWRITE
    }

    /**
     * The lock Postgres takes. Only ACCESS EXCLUSIVE blocks a plain SELECT, which is why it is the
     * one that turns a slow migration into an outage.
     */
    public enum LockMode {
        ACCESS_EXCLUSIVE, SHARE, SHARE_ROW_EXCLUSIVE, SHARE_UPDATE_EXCLUSIVE
    }

    /** What concurrent traffic loses while the step runs. This is what users actually care about. */
    public enum Blocks {
        NOTHING, WRITES, READS_AND_WRITES
    }

    public boolean isSafeAtAnySize() {
        return verdict == Verdict.INSTANT;
    }

    public static Classification instant(String rationale) {
        // An ACCESS EXCLUSIVE lock held for microseconds is fine; the danger is holding it for
        // minutes, or queueing behind someone else while holding the front of the lock queue.
        return new Classification(Verdict.INSTANT, LockMode.ACCESS_EXCLUSIVE,
                Blocks.READS_AND_WRITES, rationale);
    }

    public static Classification scan(LockMode lock, Blocks blocks, String rationale) {
        return new Classification(Verdict.SCAN, lock, blocks, rationale);
    }

    public static Classification rewrite(String rationale) {
        return new Classification(Verdict.REWRITE, LockMode.ACCESS_EXCLUSIVE,
                Blocks.READS_AND_WRITES, rationale);
    }
}
