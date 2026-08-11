package dev.civitas.core.onboarding;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * SPEC 34.3's starter chain. Five steps, once per account, each teaching exactly one system.
 *
 * <h2>Why step five branches</h2>
 *
 * <p>SPEC 34.3's last step is "Either found a city <b>or</b> claim a mining claim", and SPEC 34.1
 * says why: "A player may play indefinitely without a city… Roughly 30% of players on any server
 * will never join a group, and designing them out is designing out 30% of the server."
 *
 * <p>So the branch is the point rather than a convenience. It is the moment the game tells a new
 * player that not joining a city is a real choice and not a failure state, and it is the only
 * place in the plugin that says so.
 *
 * <h2>The rewards are SPEC's, and they add up to something specific</h2>
 *
 * <p>5,000 C across the chain, on top of SPEC 4.2's 2,000 starting balance, is 70% of a city
 * founding fee — so a motivated new player can found one in their first session or two, which is
 * the pace SPEC 34.3 is aiming at.
 */
public enum StarterStep {

    /** Income and {@code /sell}: the first thing a player has to be able to do. */
    SELL_SOMETHING("500", "onboarding.step-sell-something", "onboarding.name-sell-something",
            "onboarding.starter.rewards.sell-something"),
    /** Travel, and how to find ground nobody owns. */
    TRAVEL("500", "onboarding.step-travel", "onboarding.name-travel",
            "onboarding.starter.rewards.travel"),
    /** That cities exist and their claims are visible from outside. */
    VISIT_CITY("750", "onboarding.step-visit-city", "onboarding.name-visit-city",
            "onboarding.starter.rewards.visit-city"),
    /** Where mining happens, which is not the world cities are built in. */
    MINE_IRON("750", "onboarding.step-mine-iron", "onboarding.name-mine-iron",
            "onboarding.starter.rewards.mine-iron"),
    /** SPEC 34.3's branch: a city, or a mining claim. Either finishes the chain. */
    SETTLE("2500", "onboarding.step-settle", "onboarding.name-settle",
            "onboarding.starter.rewards.settle");

    private final BigDecimal reward;
    private final String messageKey;
    private final String nameKey;
    private final String rewardKey;

    StarterStep(String reward, String messageKey, String nameKey, String rewardKey) {
        this.reward = new BigDecimal(reward);
        this.messageKey = messageKey;
        this.nameKey = nameKey;
        this.rewardKey = rewardKey;
    }

    /**
     * Where this step's reward is configured.
     *
     * <p>A literal for the same reason the message key is one, and the sweep that catches it is
     * different: {@code ConfigKeyUsageTest} scans for key-shaped literals, so a path built by
     * concatenation reads as a key nothing consults — and an operator who edited it would see no
     * effect and no explanation.
     */
    public String rewardKey() {
        return rewardKey;
    }

    /** The default from SPEC 34.3's table. The shipped value is a config key. */
    public BigDecimal defaultReward() {
        return reward;
    }

    public String configKey() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /**
     * The message shown when this step is completed.
     *
     * <p>A literal on the constant rather than a name built from {@code configKey()}. Two
     * reasons, and the second is the real one: a concatenated key is invisible to
     * {@code LangKeyUsageTest}'s orphan sweep, so a typo in it would ship as "Missing message" to
     * a player — which is the exact defect M23's localisation pass was written to catch.
     */
    public String messageKey() {
        return messageKey;
    }

    /** The step's own name, for {@code /guide progress}. */
    public String nameKey() {
        return nameKey;
    }

    public static Optional<StarterStep> byKey(String key) {
        for (StarterStep step : values()) {
            if (step.name().equalsIgnoreCase(key) || step.configKey().equalsIgnoreCase(key)) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    /** How many iron ore SPEC 34.3 asks for at step four. */
    public static final int IRON_TARGET = 32;

    /** How far from spawn SPEC 34.3's travel step counts, in blocks. */
    public static final int TRAVEL_BLOCKS = 500;
}
