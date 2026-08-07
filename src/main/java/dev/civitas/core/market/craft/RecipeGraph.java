package dev.civitas.core.market.craft;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which items can be turned into which, SPEC 21.10.2.
 *
 * <p>Built so that SPEC 21.3's fix can be enforced: "the market trades exactly one item per
 * crafting equivalence class, always the most raw form… With only one side listed, no loop
 * exists in the graph, and arbitrage is impossible by construction rather than by careful
 * price tuning."
 *
 * <h2>The flaw this exists to close</h2>
 *
 * <p>SPEC 21.3 found that the 1.35x spread stops a player wash-trading the <i>same</i> item
 * but does nothing across a recipe, because the two sides carry independent prices that move
 * independently. Nine ingots craft into a block and a block craft back into nine ingots, so
 * once one side is dumped to its price floor the round trip pays — and stays paying until the
 * prices converge. Three of the four market states in SPEC's table contain an infinite money
 * loop, and a player can <b>create</b> the profitable state deliberately by dumping one side
 * first. The dynamic pricing that was meant to protect the economy is the mechanism that
 * breaks it.
 *
 * <h2>Directed, and why that matters</h2>
 *
 * <p>Edges are one-way, because most of Minecraft's recipes are: raw iron smelts into an
 * ingot and nothing turns an ingot back into raw iron. SPEC 21.10.2 defines the relation as
 * "either is reachable from the other", so {@link #related} is the <b>or</b> of reachability
 * in the two directions rather than connectivity in an undirected graph.
 *
 * <p>That distinction is not pedantry. Raw iron and iron ore both smelt to an ingot, so an
 * undirected reading would put them in one class and forbid listing both — but there is no
 * loop between them, because neither converts into the other. Treating the graph as
 * undirected would refuse a safe pair; treating it as direct-edge only would allow an unsafe
 * one. This does neither.
 *
 * <p><b>The relation is deliberately not transitive</b>, which is why this class does not
 * partition items into classes despite SPEC's name for it. If A smelts to B and C smelts to
 * B, then A relates to B and C relates to B, but A and C are unrelated and both may be
 * listed. The safety check is therefore pairwise over the buy list rather than a comparison
 * of class identifiers.
 *
 * <h2>Reachability is transitive even though the relation is not</h2>
 *
 * <p>SPEC 21.10.2: "The check is transitive reachability, not direct-edge, so multi-step
 * laundering (A to B to C) is caught." A player who cannot trade A for C directly but can
 * craft A into B and B into C has the same loop with an extra step in it, and a direct-edge
 * check would miss it entirely.
 *
 * <p>Not thread-safe while being built. Built once at startup, read many times after.
 */
public final class RecipeGraph {

    /** item to everything it converts into in one step. */
    private final Map<String, Set<String>> edges = new LinkedHashMap<>();

    /** Uppercased and trimmed, so {@code iron_ingot} and {@code IRON_INGOT} are one node. */
    static String normalise(String material) {
        return Objects.requireNonNull(material, "material").trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Records that {@code from} can be turned into {@code to} in one step.
     *
     * <p>Self-edges are dropped rather than rejected: Bukkit's recipe list contains a few
     * recipes whose output material equals an input (dyeing an already-dyed item, for one),
     * and a self-edge says nothing about arbitrage while making every item trivially related
     * to itself.
     */
    public void addEdge(String from, String to) {
        String source = normalise(from);
        String target = normalise(to);
        if (source.equals(target)) {
            return;
        }
        edges.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
        // The target must exist as a node even with no outgoing edges, so reachableFrom can
        // be asked about a leaf without a special case.
        edges.computeIfAbsent(target, ignored -> new LinkedHashSet<>());
    }

    /** Records that each item converts into the other, for a genuinely reversible recipe. */
    public void addReversible(String a, String b) {
        addEdge(a, b);
        addEdge(b, a);
    }

    /** How many distinct items the graph knows about. */
    public int size() {
        return edges.size();
    }

    public boolean knows(String material) {
        return edges.containsKey(normalise(material));
    }

    /** One step only, for tests and diagnostics. */
    public Set<String> directlyFrom(String material) {
        return Collections.unmodifiableSet(
                edges.getOrDefault(normalise(material), Set.of()));
    }

    /**
     * Everything reachable from {@code material} by any number of steps, excluding itself
     * unless a cycle genuinely returns to it.
     *
     * <p>Breadth-first rather than a precomputed closure. The graph has a few thousand nodes
     * and this is asked once per buy-list entry at startup, so the closure would cost more to
     * build than the twenty searches it would save.
     */
    public Set<String> reachableFrom(String material) {
        String start = normalise(material);
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(edges.getOrDefault(start, Set.of()));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (!seen.add(next)) {
                continue;
            }
            queue.addAll(edges.getOrDefault(next, Set.of()));
        }
        return Collections.unmodifiableSet(seen);
    }

    /** Whether {@code to} can be produced from {@code from} by any sequence of steps. */
    public boolean reaches(String from, String to) {
        return reachableFrom(from).contains(normalise(to));
    }

    /**
     * SPEC 21.10.2's relation: two items the market must not both trade.
     *
     * <p>True when either converts into the other, however many steps it takes. A pair for
     * which this is true is a pair a player can move value around, so listing both re-opens
     * SPEC 21.3's loop.
     */
    public boolean related(String a, String b) {
        String left = normalise(a);
        String right = normalise(b);
        if (left.equals(right)) {
            return false;   // one item is not a loop with itself; the spread covers that case
        }
        return reaches(left, right) || reaches(right, left);
    }

    /**
     * The chain proving {@link #related}, for the message an operator has to act on.
     *
     * <p>"IRON_INGOT and IRON_BLOCK are related" is a fact; "IRON_INGOT to IRON_BLOCK" is
     * something they can look at and understand. Returns an empty list when unrelated.
     */
    public java.util.List<String> pathBetween(String a, String b) {
        java.util.List<String> forward = path(normalise(a), normalise(b));
        return forward.isEmpty() ? path(normalise(b), normalise(a)) : forward;
    }

    private java.util.List<String> path(String from, String to) {
        if (from.equals(to)) {
            return java.util.List.of();
        }
        Map<String, String> cameFrom = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        Set<String> seen = new LinkedHashSet<>();
        seen.add(from);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : edges.getOrDefault(current, Set.of())) {
                if (!seen.add(next)) {
                    continue;
                }
                cameFrom.put(next, current);
                if (next.equals(to)) {
                    java.util.LinkedList<String> chain = new java.util.LinkedList<>();
                    for (String step = to; step != null; step = cameFrom.get(step)) {
                        chain.addFirst(step);
                    }
                    return java.util.List.copyOf(chain);
                }
                queue.add(next);
            }
        }
        return java.util.List.of();
    }
}
