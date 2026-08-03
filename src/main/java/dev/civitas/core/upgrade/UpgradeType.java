package dev.civitas.core.upgrade;

import java.util.Locale;
import java.util.Optional;

/**
 * The six upgrade tracks, SPEC 5.7.
 *
 * <p>Each is five levels, bought in order, permanent, and paid from the treasury. The config
 * key is the enum name lowercased and hyphenated, which is how {@code cities.yml} already
 * writes them, so a track and its costs cannot drift apart by renaming one of them.
 *
 * <p>What each level <em>does</em> is not here. An upgrade's effect belongs to the system it
 * affects: Population is a member cap, Treasury Interest is a discount on a bill, Market
 * Access is a tax rate. Putting the arithmetic in this enum would mean six unrelated systems
 * importing it to ask what their own numbers are.
 */
public enum UpgradeType {

    /** +5 member cap per level, on top of {@code members.base-cap}. */
    POPULATION("population"),

    /** +1 shared vault page of 27 slots per level. */
    VAULT("vault"),

    /** -4% upkeep per level. */
    TREASURY_INTEREST("treasury-interest"),

    /** +1 maximum outpost per level, to the SPEC 7.2 ceiling of six. */
    OUTPOST_RANGE("outpost-range"),

    /** +5% defense unit health and more units per level, SPEC 12.4. */
    FORTIFICATION("fortification"),

    /** -0.8 percentage points of market tax per level. */
    MARKET_ACCESS("market-access");

    /** SPEC 5.7: every track has five levels. */
    public static final int MAX_LEVEL = 5;

    private final String key;

    UpgradeType(String key) {
        this.key = key;
    }

    /** How this track is written in {@code cities.yml} and stored in {@code city_upgrades}. */
    public String key() {
        return key;
    }

    /** The config path to this track's block. */
    public String configPath() {
        return "upgrades." + key;
    }

    /** The language key for its name. */
    public String messageKey() {
        return "upgrade." + key;
    }

    /** @param key a stored {@code upgrade_key}, or a name a player typed */
    public static Optional<UpgradeType> parse(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String normalised = key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (UpgradeType type : values()) {
            if (type.key.equals(normalised)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
