package dev.civitas.core.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import dev.civitas.core.market.craft.RecipeGraph;

/**
 * SPEC 21.10.1's startup validation, and the latch that keeps the market shut when it fails.
 *
 * <p>SPEC: "On enable, the market module runs these assertions and <b>refuses to enable</b>
 * (logging SEVERE with the specific failure) if any fails." Four assertions are listed. This
 * milestone owns the third — "no two items in the buy list are in the same crafting
 * equivalence class" — and the refusal mechanism the other three plug into. M6b fills in the
 * hard blacklist and the villager-disjointness check.
 *
 * <h2>Why a latch rather than a startup exception</h2>
 *
 * <p>Throwing on enable would take the whole plugin down, and the market is one module of
 * many: a server whose buy list has a bad pair should still have its cities, claims,
 * protection and wars. So the check records its failures, {@link #passed} answers false, and
 * {@code MarketService.enabled} consults it — every buy and sell then refuses through the path
 * that already exists for {@code market.enabled: false}.
 *
 * <p>SPEC 21.10.4 is the reason the latch is not itself configurable: "The hard blacklist, the
 * equivalence check, and the villager-disjointness check are code-level and are not readable
 * from config. An admin can change prices, quotas, and elasticity. An admin cannot add
 * emeralds to the buy list, even by editing the yml, even by editing the database." An
 * operator who wants their market back fixes the buy list; there is no key that says "run
 * anyway".
 */
public final class MarketSafetyCheck {

    /** One reason the market may not open, in the words an operator has to act on. */
    public record Failure(String assertionName, String detail) {

        @Override
        public String toString() {
            return assertionName + ": " + detail;
        }
    }

    private final List<Failure> failures = new ArrayList<>();
    private boolean run;

    /**
     * SPEC 21.10.1's third assertion.
     *
     * <p>Pairwise over the buy list rather than by comparing class identifiers, because the
     * relation {@link RecipeGraph#related} defines is not transitive — see that class for why
     * raw iron and iron ore are both listable despite sharing a smelting output. There is no
     * partition into classes to compare.
     *
     * <p>The buy list is a couple of dozen entries, so the pairwise walk is a few hundred
     * reachability searches at startup and nothing afterwards.
     */
    public void checkEquivalenceClasses(List<String> buyList, RecipeGraph graph) {
        Objects.requireNonNull(buyList, "buyList");
        Objects.requireNonNull(graph, "graph");
        run = true;

        for (int i = 0; i < buyList.size(); i++) {
            for (int j = i + 1; j < buyList.size(); j++) {
                String left = buyList.get(i);
                String right = buyList.get(j);
                if (!graph.related(left, right)) {
                    continue;
                }
                List<String> path = graph.pathBetween(left, right);
                failures.add(new Failure("crafting equivalence",
                        left + " and " + right + " are in the same crafting equivalence class"
                                + (path.isEmpty() ? "" : " (" + String.join(" -> ", path) + ")")
                                + ". SPEC 21.3: listing both sides of a recipe lets a player "
                                + "dump one side to its price floor and then run the round "
                                + "trip for profit until the prices converge. Trade only the "
                                + "most raw form."));
            }
        }
    }

    /** Records a failure found by an assertion another milestone owns. */
    public void fail(String assertionName, String detail) {
        run = true;
        failures.add(new Failure(assertionName, detail));
    }

    /** Whether the market may open. False until something has actually been checked. */
    public boolean passed() {
        return run && failures.isEmpty();
    }

    public List<Failure> failures() {
        return List.copyOf(failures);
    }

    /**
     * Writes the verdict to the log.
     *
     * <p>SEVERE per SPEC 21.10.1, and one line per failure rather than a count: an operator
     * reading "3 problems" has to go looking, and the whole point of naming the pair and its
     * path is that the fix is then obvious.
     *
     * @return true if the market may open
     */
    public boolean report(Logger logger) {
        if (passed()) {
            return true;
        }
        logger.severe("The server market will NOT open. SPEC 21.10.1's startup validation "
                + "failed:");
        for (Failure failure : failures) {
            logger.severe("  " + failure);
        }
        logger.severe("Fix economy.yml's buy list and restart. There is no configuration key "
                + "that overrides this check (SPEC 21.10.4).");
        return false;
    }
}
