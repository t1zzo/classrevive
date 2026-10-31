package dev.classrevive.bytecode;

import java.util.*;

/**
 * Phase G6: who calls a method, then who calls those, up to a depth, from a {@link ReferenceIndex}. A caller is a member
 * holding a call, a lambda body or method reference, or a method handle to the method. A method already on the path is
 * shown once more, marked as a cycle, and not expanded again. A node budget bounds the whole tree.
 */
public final class CallHierarchy {
    private CallHierarchy() {}

    /**
     * One method in the tree. {@code sites}: its references to the parent (empty for the root). {@code cycle}: it is
     * already on the path above. {@code cut}: callers exist but were not expanded (depth or budget reached).
     */
    public record Node(ReferenceIndex.Ref method, List<ReferenceIndex.Usage> sites, boolean cycle, boolean cut, List<Node> callers) {}

    /** The tree of callers of {@code method}; {@code depth} 1 lists direct callers only. */
    public static Node callers(ReferenceIndex index, ReferenceIndex.Ref method, int depth, int maxNodes) {
        int[] budget = {Math.max(1, maxNodes) - 1};
        return expand(index, method, List.of(), new ArrayDeque<>(List.of(method)), depth, budget);
    }

    private static Node expand(ReferenceIndex index, ReferenceIndex.Ref method, List<ReferenceIndex.Usage> sites, Deque<ReferenceIndex.Ref> path,
                               int depth, int[] budget) {
        var grouped = direct(index, method);
        if (depth <= 0 || grouped.isEmpty()) return new Node(method, sites, false, !grouped.isEmpty(), List.of());
        var children = new ArrayList<Node>();
        boolean cut = false;
        for (var entry : grouped.entrySet()) {
            if (budget[0] <= 0) { cut = true; break; }
            budget[0]--;
            var caller = entry.getKey();
            if (path.contains(caller)) { children.add(new Node(caller, entry.getValue(), true, false, List.of())); continue; }
            path.push(caller);
            children.add(expand(index, caller, entry.getValue(), path, depth - 1, budget));
            path.pop();
        }
        return new Node(method, sites, false, cut, List.copyOf(children));
    }

    /** The direct callers of {@code method}, each with its sites, in the index's order. */
    public static Map<ReferenceIndex.Ref,List<ReferenceIndex.Usage>> direct(ReferenceIndex index, ReferenceIndex.Ref method) {
        var grouped = new LinkedHashMap<ReferenceIndex.Ref,List<ReferenceIndex.Usage>>();
        for (var usage : index.usages(method))
            if (ReferenceIndex.calls(usage.site().kind()) && !usage.site().from().isClass())
                grouped.computeIfAbsent(usage.site().from(), k -> new ArrayList<>()).add(usage);
        return grouped;
    }
}
