package com.schemasync.merge;

import com.schemasync.store.ControlPlaneStore;
import com.schemasync.store.Records;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Finds the lowest common ancestor of two branch heads in the commit DAG.
 *
 * <p>The merge base is what makes a three-way merge possible: without it you cannot tell "I added
 * this" from "you deleted it". A branch's recorded {@code base_commit_id} is the fork point and is
 * usually the answer -- but it stops being the answer the moment a branch pulls from its target,
 * because the base then moves forward. So it is treated as a fast path and a test oracle, not as
 * the truth.
 *
 * <p>With at most two parents and hundreds of commits, the obvious algorithm is the right one:
 * colour every ancestor of A, then walk B's ancestors newest-first and take the first hit. No
 * cleverness required, and it is worth saying so rather than reaching for something sophisticated.
 */
@Component
public class MergeBaseFinder {

    private final ControlPlaneStore store;

    public MergeBaseFinder(ControlPlaneStore store) {
        this.store = store;
    }

    public Optional<UUID> find(UUID headA, UUID headB) {
        if (headA == null || headB == null) {
            return Optional.empty();
        }
        if (headA.equals(headB)) {
            return Optional.of(headA);
        }

        Set<UUID> ancestorsOfA = ancestors(headA);

        // Walk B's ancestors newest-first, so the FIRST common commit found is the LOWEST common
        // ancestor. Taking any common ancestor would still merge, but against a base further back
        // than necessary -- which manufactures conflicts out of changes both sides already share.
        PriorityQueue<Records.Commit> frontier =
                new PriorityQueue<>(Comparator.comparingLong(Records.Commit::seq).reversed());
        Set<UUID> seen = new HashSet<>();
        store.findCommit(headB).ifPresent(frontier::add);

        while (!frontier.isEmpty()) {
            Records.Commit commit = frontier.poll();
            if (!seen.add(commit.id())) {
                continue;
            }
            if (ancestorsOfA.contains(commit.id())) {
                return Optional.of(commit.id());
            }
            addParent(frontier, commit.parentCommitId());
            addParent(frontier, commit.secondParentCommitId());
        }
        return Optional.empty();
    }

    private void addParent(PriorityQueue<Records.Commit> frontier, UUID parentId) {
        if (parentId != null) {
            store.findCommit(parentId).ifPresent(frontier::add);
        }
    }

    private Set<UUID> ancestors(UUID head) {
        Set<UUID> out = new HashSet<>();
        Deque<UUID> stack = new ArrayDeque<>();
        stack.push(head);
        while (!stack.isEmpty()) {
            UUID id = stack.pop();
            if (!out.add(id)) {
                continue;
            }
            store.findCommit(id).ifPresent(c -> {
                if (c.parentCommitId() != null) stack.push(c.parentCommitId());
                if (c.secondParentCommitId() != null) stack.push(c.secondParentCommitId());
            });
        }
        return out;
    }
}
