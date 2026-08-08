package dev.civitas.msg;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * SPEC 23.6's notification categories.
 *
 * <p>SPEC 23 introduces a great many messages, and SPEC 22.1 lists {@code /toggle} as a
 * <b>High</b> severity omission from Part I for that reason: "Section 23 adds many messages.
 * Without a toggle, chat becomes unusable."
 *
 * <h2>Four of these cannot be turned off, and that is the interesting part</h2>
 *
 * <p>SPEC 23.6 locks four categories on. Not as a default — as a property of the category, which
 * no command, GUI or database write can change. The reasoning is different for each:
 *
 * <ul>
 *   <li>{@link #TREASURY_WITHDRAW} — SPEC 23.5.6: "Every withdrawal is announced to the whole
 *       city, always, with no toggle. This is deliberate and is <b>the primary anti-fraud
 *       mechanism in the plugin</b>. Social transparency prevents treasury theft far more
 *       effectively than any permission system, because the thief knows everyone will see it
 *       happen in real time." A mutable toggle here is a mute button on the burglar alarm.
 *   <li>{@link #UPKEEP_CRITICAL} — a city that cannot pay upkeep loses chunks. Muting the
 *       warning is how a player loses their own city to a message they chose not to read.
 *   <li>{@link #WAR} — safety. A player who has muted war messages does not know they are in
 *       one.
 *   <li>{@link #ACTIONBAR} and {@link #SOUNDS} are <b>not</b> locked, deliberately: they are
 *       presentation, and SPEC 23.6 lists them unlocked.
 * </ul>
 *
 * <p>The lock lives here rather than in the command that reads it, so every path to changing a
 * preference passes it. A guard in {@code /toggle} alone would be one GUI away from useless.
 */
public enum ToggleCategory {

    /** Own transactions: sales, purchases, payments. */
    ECONOMY_PERSONAL("economy_personal", true, false),
    /** Deposits by other members. */
    ECONOMY_CITY("economy_city", true, false),
    /** Every treasury withdrawal, to the whole city. SPEC 23.5.6. Never mutable. */
    TREASURY_WITHDRAW("treasury_withdraw", true, true),
    /** Own claims. */
    LAND_OWN("land_own", true, false),
    /** Claims by other members. */
    LAND_CITY("land_city", true, false),
    /** Joins, leaves, rank changes. */
    MEMBERSHIP("membership", true, false),
    /** Ordinary upkeep charges. */
    UPKEEP("upkeep", true, false),
    /** The city is about to start losing chunks. Never mutable. */
    UPKEEP_CRITICAL("upkeep_critical", true, true),
    /** War, at every stage. Never mutable. */
    WAR("war", true, true),
    DIPLOMACY("diplomacy", true, false),
    QUESTS("quests", true, false),
    CONTESTS("contests", true, false),
    EVENTS("events", true, false),
    LEADERBOARD("leaderboard", true, false),
    /** Somebody bought from your chest shop. */
    SHOP_SALES("shop_sales", true, false),
    /** Action-bar feedback as a whole. Presentation, so mutable. */
    ACTIONBAR("actionbar", true, false),
    /** Plugin sounds. Presentation, so mutable. */
    SOUNDS("sounds", true, false),
    /** One-line messages instead of multi-line. Off by default, per SPEC 23.6. */
    COMPACT("compact", false, false);

    private final String key;
    private final boolean defaultOn;
    private final boolean locked;

    ToggleCategory(String key, boolean defaultOn, boolean locked) {
        this.key = key;
        this.defaultOn = defaultOn;
        this.locked = locked;
    }

    /** The name SPEC 23.6 gives this category, and the one a player types. */
    public String key() {
        return key;
    }

    /** Whether a player who has never touched it receives these messages. */
    public boolean defaultOn() {
        return defaultOn;
    }

    /**
     * Whether this category is always on, whatever anybody sets.
     *
     * <p>A locked category is not a default. It cannot be changed by a command, by a GUI, or by
     * writing a row into the database by hand.
     */
    public boolean locked() {
        return locked;
    }

    /** The {@code lang/} key describing this category, for {@code /toggle list}. */
    public String messageKey() {
        // A dash, not a dot: Bukkit reads "." as a path separator, so
        // toggle.category.economy-personal would make "category" a section and every
        // lookup on it would return a MemorySection. That is the M8 bug that rendered
        // fifty GUI labels as MemorySection[path=...] to players.
        return "toggle.category-" + key.replace('_', '-');
    }

    /** Resolves what a player typed, however they capitalised it. */
    public static Optional<ToggleCategory> byKey(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String wanted = typed.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(category -> category.key.equals(wanted))
                .findFirst();
    }

    /** Every category a player may actually change. */
    public static java.util.List<ToggleCategory> mutable() {
        return Arrays.stream(values()).filter(category -> !category.locked).toList();
    }
}
