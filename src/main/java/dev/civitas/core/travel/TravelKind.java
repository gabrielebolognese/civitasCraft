package dev.civitas.core.travel;

import dev.civitas.config.ConfigFile;

/**
 * The destinations SPEC 32.7 tabulates, each with its own cost, cooldown and warmup.
 *
 * <p>Per destination rather than one global cooldown, because SPEC 32.7 gives them different
 * numbers on purpose: {@code /spawn} is a minute and {@code /rtp} is five, since one takes a
 * player out of trouble and the other is how they find land.
 */
public enum TravelKind {

    /** The server hub, SPEC 32.7. Free, 60s cooldown, 5s warmup. */
    SPAWN("spawn"),

    /** SPEC 32.4's random teleport in the main overworld. 500 C, 5 min, 5s. */
    RTP("rtp"),

    /** The same into a resource world. Free, 2 min, 5s, and a wider radius. */
    RTP_RESOURCE("rtp-resource"),

    /** An admin-defined public warp, SPEC 32.7. Free, 30s, 5s. */
    WARP("warp"),

    /**
     * A player's own mining claim, SPEC 32.7. 100 C, 3 min, 8s.
     *
     * <p>Its numbers live under {@code mining-claims.teleport} rather than {@code travel.mine-tp},
     * because they belong with the feature they gate — an operator turning mining claims off or
     * retuning them should find every number in one block.
     */
    MINE_TP("mine-tp") {
        @Override
        public String configPath() {
            return "mining-claims.teleport";
        }
    },

    /**
     * A city outpost, SPEC 39.5. Distance-scaled fare, 8s warmup, 3 min cooldown.
     *
     * <p>The only destination whose fare is not a fixed number: SPEC 39.5 charges
     * {@code 100 * D(d)}, so the outpost service prices each journey and hands the figure in.
     * The key below is the base that multiplier applies to.
     *
     * <p>Its numbers live in {@code cities.yml} beside the rest of the outpost block, under
     * SPEC 39.15's own names, for the same reason {@link #MINE_TP}'s live with mining claims.
     */
    OUTPOST_TP("outpost-tp") {
        @Override
        public ConfigFile configFile() {
            return ConfigFile.CITIES;
        }

        @Override
        public String configPath() {
            return "outposts.teleport";
        }

        @Override
        public String costKey() {
            return configPath() + ".base-cost";
        }

        @Override
        public String cooldownKey() {
            return configPath() + ".cooldown-seconds";
        }

        @Override
        public String warmupKey() {
            return configPath() + ".warmup-seconds";
        }
    };

    private final String key;

    TravelKind(String key) {
        this.key = key;
    }

    /** Which configuration file holds this destination's numbers. */
    public ConfigFile configFile() {
        return ConfigFile.WORLD;
    }

    /** The section holding this destination's numbers. */
    public String configPath() {
        return "travel." + key;
    }

    public String costKey() {
        return configPath() + ".cost";
    }

    public String cooldownKey() {
        return configPath() + ".cooldown";
    }

    public String warmupKey() {
        return configPath() + ".warmup";
    }

    /** The {@code lang/} key for a cooldown refusal that names the destination. */
    public String messageKey() {
        return "travel.kind-" + key;
    }

    public String key() {
        return key;
    }
}
