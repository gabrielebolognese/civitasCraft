package dev.civitas.core.events;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The eight events of SPEC 13.5.
 *
 * <p>Each is a key into the {@code events.definitions} block of {@code events.yml}, where its
 * duration and its effect live. Nothing about what an event <em>does</em> is written here:
 * that is {@link EventEffects}, reading config. This enum only says which events exist, which
 * is the part a server operator cannot change.
 */
public enum ServerEventType {

    /** Market sell prices up, SPEC 13.5. */
    MARKET_BOOM("market-boom"),

    /** Market sell and buy prices down. */
    MARKET_CRASH("market-crash"),

    /** Crops grow faster and farming quests pay more. */
    HARVEST_FESTIVAL("harvest-festival"),

    /** Ore yields more and mining quests pay more. */
    GOLD_RUSH("gold-rush"),

    /** Waves of hostile mobs near city borders, paying treasuries for kills. */
    INVASION("invasion"),

    /** Cheaper claims and free city creation. */
    FOUNDERS_WEEK("founders-week"),

    /** Upkeep doubled, announced further ahead than the rest. */
    DOUBLE_UPKEEP("double-upkeep"),

    /** No market tax. */
    TAX_HOLIDAY("tax-holiday");

    private final String key;

    ServerEventType(String key) {
        this.key = key;
    }

    /** The name in {@code events.yml}, in the command, and in the database. */
    public String key() {
        return key;
    }

    /** Where this event's settings live in {@code events.yml}. */
    public String configPath() {
        return "events.definitions." + key;
    }

    /** {@code event.<key>.name} in the language files. */
    public String nameKey() {
        return "event." + key + ".name";
    }

    /** The one-line description shown when it is announced. */
    public String descriptionKey() {
        return "event." + key + ".description";
    }

    public static List<ServerEventType> all() {
        return List.of(values());
    }

    /** Resolves what an operator or an admin command typed. */
    public static Optional<ServerEventType> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalised = input.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return all().stream().filter(type -> type.key.equals(normalised)).findFirst();
    }
}
