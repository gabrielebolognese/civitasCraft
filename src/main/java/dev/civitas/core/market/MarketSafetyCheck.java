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

    /**
     * SPEC 21.10.1's first assertion: nothing in the buy list is hard-blacklisted.
     *
     * <p>SPEC 21.8 puts this list in code precisely so that this check cannot be talked out
     * of: "config cannot override, because a well-meaning admin editing a yml is exactly how
     * a server dies." The failure message names the item rather than the rule, because the
     * operator's next action is to delete a line.
     */
    public void checkHardBlacklist(List<String> buyList) {
        run = true;
        if (!HardBlacklist.tagsResolved()) {
            // The named materials are still enforced, but SPEC 21.8's category entries — all
            // meat, all fish, carpet, rails, ice — are not. A partially enforced blacklist is
            // more dangerous than an absent one, because it looks complete.
            failures.add(new Failure("hard blacklist",
                    "the tag-backed categories of SPEC 21.8 could not be read from the "
                            + "server, so \"all meat\", \"all fish\", \"carpet\", "
                            + "\"rails\" and \"ice of all kinds\" are NOT being enforced. "
                            + "The market will not open on a partially enforced blacklist."));
        }
        for (String material : buyList) {
            if (HardBlacklist.forbids(material)) {
                failures.add(new Failure("hard blacklist",
                        material + " may never be bought by the server (SPEC 21.8). It is "
                                + "either automatable without a player present, a mob drop, or "
                                + "one of the two metals SPEC says \"would have ended the "
                                + "server's economy within a week of launch\". Remove it from "
                                + "market.buy; the server may still sell it."));
            }
        }
    }

    /**
     * SPEC 21.10.1's second assertion: nothing in the buy list comes out of a villager.
     *
     * <p>A villager converts emeralds into goods at a fixed rate and restocks forever with no
     * player present. If the server buys one of those goods the loop closes, and unlike SPEC
     * 21.3's crafting arbitrage it needs no favourable price swing — the rate is fixed, so it
     * either pays or it does not, and if it pays it pays forever.
     */
    public void checkVillagerDisjointness(List<String> buyList) {
        run = true;
        for (String material : buyList) {
            if (VillagerTrades.sells(material)) {
                failures.add(new Failure("villager trade",
                        material + " is obtainable from a villager trade (SPEC 21.10.1). A "
                                + "trading hall would turn emeralds into it and the market "
                                + "would turn it back into money, forever."));
            }
        }
    }

    /**
     * SPEC 21.10.1's fourth assertion: every buy entry declares whether it can be automated.
     *
     * <p>"Every buy-list entry has the required {@code # automatable: no|semi} comment parsed
     * from config." A forcing function rather than a value anything reads: SPEC 21.1's second
     * governing principle is that "automation is the enemy of a priced economy", and requiring
     * the answer in writing makes an operator adding an item confront the question that
     * actually matters about it.
     *
     * @param declared material to the value of its comment, absent where there was none
     */
    public void checkAutomatableDeclared(List<String> buyList,
                                         java.util.Map<String, String> declared) {
        run = true;
        for (String material : buyList) {
            String value = declared.get(material);
            if (value == null) {
                failures.add(new Failure("automatable comment",
                        material + " has no \"# automatable: no|semi\" comment (SPEC "
                                + "21.10.1). Every buy entry must say whether a machine can "
                                + "produce it without a player present."));
            } else if (!value.equals("no") && !value.equals("semi")) {
                failures.add(new Failure("automatable comment",
                        material + " declares \"automatable: " + value + "\", which is not "
                                + "\"no\" or \"semi\". An item that is fully automatable "
                                + "must not be in the buy list at all (SPEC 21.1)."));
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
