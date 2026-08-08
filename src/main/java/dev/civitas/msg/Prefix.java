package dev.civitas.msg;

/**
 * SPEC 23.3's message prefixes.
 *
 * <p>Ten of them, one per subject, so a player can tell at a glance whether a line is about
 * their money, their land or a war before reading a word of it.
 *
 * <h2>Compact mode</h2>
 *
 * <p>SPEC 23.3: "Compact mode ({@code /toggle compact}) replaces word prefixes with single
 * characters: {@code $}, {@code C}, {@code L}, {@code W}, {@code Q}, {@code D}, {@code !},
 * {@code +}." That is eight characters for ten prefixes; SPEC omits the server and success
 * pairing. {@link #SERVER} takes {@code S}, which is the only unused initial left.
 *
 * <h2>Why these are not nested under the existing {@code prefix} key</h2>
 *
 * <p>{@code lang/} already holds a top-level {@code prefix:} string, used by
 * {@code LangManager.send} since M0. Adding {@code prefix.economy} beside it would make
 * {@code prefix} both a string and a section, and Bukkit reads {@code .} as a path separator —
 * which is the bug that rendered fifty GUI labels as {@code MemorySection[path=...]} to players
 * and was invisible to every test. So these live under {@code prefixes:} instead.
 */
public enum Prefix {

    ECONOMY("economy", "$"),
    CITY("city", "C"),
    LAND("land", "L"),
    WAR("war", "W"),
    QUEST("quest", "Q"),
    ALLY("ally", "D"),
    SERVER("server", "S"),
    ADMIN("admin", "A"),
    ERROR("error", "!"),
    SUCCESS("success", "+");

    private final String key;
    private final String compact;

    Prefix(String key, String compact) {
        this.key = key;
        this.compact = compact;
    }

    /** The {@code lang/} key holding the full form. */
    public String messageKey() {
        return "prefixes." + key;
    }

    /** The {@code lang/} key holding the compact form. */
    public String compactMessageKey() {
        return "prefixes.compact-" + key;
    }

    /** SPEC 23.3's single character, for the tests and as a fallback. */
    public String compactCharacter() {
        return compact;
    }

    /** The key to render, given whether this player has compact mode on. */
    public String keyFor(boolean compactMode) {
        return compactMode ? compactMessageKey() : messageKey();
    }

    public String key() {
        return key;
    }
}
