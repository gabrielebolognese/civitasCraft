package dev.civitas.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import dev.civitas.lang.LangManager;
import net.kyori.adventure.audience.Audience;

/**
 * {@code /city help [page]}, SPEC 9.1.
 *
 * <h2>Why the table is here and not in the language file</h2>
 *
 * <p>The obvious implementation is a block of prose in {@code en.yml} listing the commands.
 * It is also the one that rots: a milestone adds a command, nobody remembers the help text,
 * and the file still describes the plugin as it was two milestones ago. Nothing fails, which
 * is the problem — a stale help page looks exactly like a current one.
 *
 * <p>So the <i>set</i> of commands is declared here, in Java, and only the wording of each
 * line lives in {@code lang/}. That split buys the thing that matters:
 * {@code HelpPagesTest} walks the real Brigadier trees and fails the build if a command
 * exists with no entry, or an entry names a command that no longer exists. Help cannot drift
 * out of date without breaking the build.
 *
 * <h2>Why the usage line is a message key rather than a format string</h2>
 *
 * <p>An entry renders one whole message key, with no placeholders passed at all. The
 * alternative — a shared {@code <usage> - <description>} template with the usage inserted —
 * looks tidier and is a trap: a usage line such as {@code /city info <name>} contains
 * {@code <name>}, and passing a resolver of that name to the very message that displays it
 * substitutes the argument placeholder away. Passing no resolvers means an unrecognised tag
 * stays literal text, which is how {@code market.sell-usage} has rendered since M6.
 *
 * <h2>What a player is shown</h2>
 *
 * <p>Filtered by <b>Bukkit</b> permission, so an ordinary player is not offered the admin
 * tree. Deliberately <b>not</b> filtered by city rank: a Recruit reading what a Mayor can do
 * is how they learn what to aim at, and SPEC 5.4 lets a city hand out any flag it likes. The
 * city permission each command needs is part of the line's wording instead.
 */
public final class HelpPages {

    /** SPEC 9.1, commands that need no city. */
    /**
     * Every root command the plugin registers.
     *
     * <p>Declared here rather than in the test, because the test's copy was a hardcoded literal
     * and two commands — {@code /quota} at M6c and {@code /toggle} at M7a — shipped with no help
     * entry without failing it. A list that has to be edited in a test whenever a command is
     * added is a list that will not be. Same fix as {@code ConfigKeyUsageTest} deriving its file
     * list from {@code ConfigFile}.
     *
     * <p>Adding a command means adding it here, and {@code HelpPagesTest} then fails until it is
     * documented — which is the whole point.
     */
    public static final java.util.List<String> ROOT_COMMANDS = java.util.List.of(
            "city", "money", "pay", "shop", "sell", "worth", "quota", "quests", "challenges",
            "ally", "truce", "ac", "citychat", "leaderboard", "contest", "war", "bounty",
            "report", "civitas", "toggle", "spawn", "rtp", "warp", "mine", "guide", "season", "transactions", "playtime",
            "cityadmin");

    public static final String GENERAL = "help.category-general";

    /** SPEC 9.2, commands for members of a city. */
    public static final String CITY = "help.category-city";

    /** SPEC 9.3, war and diplomacy. */
    public static final String WAR = "help.category-war";

    /** SPEC 9.4, the admin tree. */
    public static final String ADMIN = "help.category-admin";

    /**
     * One line of help.
     *
     * @param key        the message key holding the whole line, usage and description together
     * @param permission the Bukkit node a sender needs before the line is shown to them
     * @param category   which of SPEC 9.1 to 9.4 the command belongs to
     * @param command    the root command and subcommand path this line documents, used by
     *                   {@code HelpPagesTest} to match entries against the real command tree
     */
    public record Entry(String key, String permission, String category, List<String> command) {

        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(category, "category");
            command = List.copyOf(command);
        }

        static Entry of(String key, String permission, String category, String... command) {
            return new Entry(key, permission, category, List.of(command));
        }
    }

    /**
     * Every command in SPEC 9, in the order SPEC lists them.
     *
     * <p>Ordered as the specification orders them rather than alphabetically, because SPEC 9's
     * grouping is itself information: the commands a player needs on their first day are the
     * ones at the top of SPEC 9.1.
     */
    private static final List<Entry> ENTRIES = List.of(
            // ---- SPEC 9.1, player commands, no city required ------------------------------
            Entry.of("help.city", "civitas.use", GENERAL, "city"),
            Entry.of("help.city-help", "civitas.use", GENERAL, "city", "help"),
            Entry.of("help.city-create", "civitas.city.create", GENERAL, "city", "create"),
            Entry.of("help.city-info", "civitas.use", GENERAL, "city", "info"),
            Entry.of("help.city-list", "civitas.use", GENERAL, "city", "list"),
            Entry.of("help.city-join", "civitas.use", GENERAL, "city", "join"),
            Entry.of("help.city-accept", "civitas.use", GENERAL, "city", "accept"),
            Entry.of("help.city-deny", "civitas.use", GENERAL, "city", "deny"),
            Entry.of("help.city-map", "civitas.use", GENERAL, "city", "map"),
            Entry.of("help.city-here", "civitas.use", GENERAL, "city", "here"),
            Entry.of("help.money", "civitas.economy.balance", GENERAL, "money"),
            Entry.of("help.pay", "civitas.economy.pay", GENERAL, "pay"),
            Entry.of("help.shop", "civitas.market.use", GENERAL, "shop"),
            Entry.of("help.sell", "civitas.market.use", GENERAL, "sell"),
            Entry.of("help.worth", "civitas.market.use", GENERAL, "worth"),
            Entry.of("help.quests", "civitas.quests.use", GENERAL, "quests"),
            Entry.of("help.challenges", "civitas.quests.use", GENERAL, "challenges"),
            Entry.of("help.leaderboard", "civitas.use", GENERAL, "leaderboard"),
            Entry.of("help.contest", "civitas.contest.use", GENERAL, "contest"),
            Entry.of("help.bounty", "civitas.bounty.use", GENERAL, "bounty"),
            Entry.of("help.report", "civitas.use", GENERAL, "report"),
            Entry.of("help.civitas", "civitas.use", GENERAL, "civitas"),
            Entry.of("help.guide", "civitas.use", GENERAL, "guide"),
            Entry.of("help.season", "civitas.use", GENERAL, "season"),
            Entry.of("help.transactions", "civitas.economy.balance", GENERAL,
                    "transactions"),
            Entry.of("help.playtime", "civitas.use", GENERAL, "playtime"),
            // Added at M3b. /quota shipped at M6c and /toggle at M7a with no help entry, and
            // nothing caught it because rootCommands() in the test was a hardcoded literal
            // that did not know they existed. Both are player-facing and both were invisible
            // in /city help.
            Entry.of("help.quota", "civitas.market.use", GENERAL, "quota"),
            Entry.of("help.toggle", "civitas.use", GENERAL, "toggle"),
            Entry.of("help.spawn", "civitas.use", GENERAL, "spawn"),
            Entry.of("help.rtp", "civitas.use", GENERAL, "rtp"),
            Entry.of("help.warp", "civitas.use", GENERAL, "warp"),
            Entry.of("help.mine", "civitas.use", GENERAL, "mine"),

            // ---- SPEC 9.2, city member commands -------------------------------------------
            Entry.of("help.city-spawn", "civitas.use", CITY, "city", "spawn"),
            Entry.of("help.city-setspawn", "civitas.use", CITY, "city", "setspawn"),
            Entry.of("help.city-leave", "civitas.use", CITY, "city", "leave"),
            Entry.of("help.city-deposit", "civitas.use", CITY, "city", "deposit"),
            Entry.of("help.city-withdraw", "civitas.use", CITY, "city", "withdraw"),
            Entry.of("help.city-vault", "civitas.use", CITY, "city", "vault"),
            Entry.of("help.citychat", "civitas.use", CITY, "citychat"),
            Entry.of("help.allychat", "civitas.use", CITY, "ac"),
            Entry.of("help.city-claim", "civitas.use", CITY, "city", "claim"),
            Entry.of("help.city-unclaim", "civitas.use", CITY, "city", "unclaim"),
            Entry.of("help.city-border", "civitas.use", CITY, "city", "border"),
            Entry.of("help.city-invite", "civitas.use", CITY, "city", "invite"),
            Entry.of("help.city-kick", "civitas.use", CITY, "city", "kick"),
            Entry.of("help.city-rank", "civitas.use", CITY, "city", "rank"),
            Entry.of("help.city-outpost", "civitas.use", CITY, "city", "outpost"),
            Entry.of("help.city-upgrade", "civitas.use", CITY, "city", "upgrade"),
            Entry.of("help.city-defense", "civitas.use", CITY, "city", "defense"),
            Entry.of("help.city-setmotd", "civitas.use", CITY, "city", "setmotd"),
            Entry.of("help.city-open", "civitas.use", CITY, "city", "open"),
            Entry.of("help.city-rename", "civitas.use", CITY, "city", "rename"),
            Entry.of("help.city-transfer", "civitas.use", CITY, "city", "transfer"),
            Entry.of("help.city-disband", "civitas.use", CITY, "city", "disband"),
            Entry.of("help.city-hall", "civitas.use", CITY, "city", "hall"),

            // ---- SPEC 9.3, war and diplomacy ----------------------------------------------
            Entry.of("help.war-declare", "civitas.use", WAR, "war", "declare"),
            Entry.of("help.war-status", "civitas.use", WAR, "war", "status"),
            Entry.of("help.war-peace", "civitas.use", WAR, "war", "peace"),
            Entry.of("help.war-accept", "civitas.use", WAR, "war", "accept"),
            Entry.of("help.war-join", "civitas.use", WAR, "war", "join"),
            Entry.of("help.war-history", "civitas.use", WAR, "war", "history"),
            Entry.of("help.war-scoreboard", "civitas.use", WAR, "war", "scoreboard"),
            Entry.of("help.ally-invite", "civitas.use", WAR, "ally", "invite"),
            Entry.of("help.ally-accept", "civitas.use", WAR, "ally", "accept"),
            Entry.of("help.ally-break", "civitas.use", WAR, "ally", "break"),
            Entry.of("help.ally-trust", "civitas.use", WAR, "ally", "trust"),
            Entry.of("help.ally-list", "civitas.use", WAR, "ally", "list"),
            Entry.of("help.truce-offer", "civitas.use", WAR, "truce", "offer"),

            // ---- SPEC 9.4, admin ----------------------------------------------------------
            Entry.of("help.admin", "civitas.admin", ADMIN, "cityadmin"));

    /** SPEC 8.2 pages a GUI; a chat page is smaller, because chat scrolls away. */
    private final int perPage;

    private final LangManager lang;

    public HelpPages(LangManager lang) {
        this(lang, 10);
    }

    public HelpPages(LangManager lang, int perPage) {
        this.lang = Objects.requireNonNull(lang, "lang");
        requirePageSize(perPage);
        this.perPage = perPage;
    }

    /** Every declared entry, in SPEC order. Exposed so the drift test can walk it. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /**
     * The entries a sender may see.
     *
     * <p>Static, and taking a predicate rather than a {@code CommandSender}, so the filtering
     * and paging are testable with neither a server nor a language file — the same reason
     * {@code WarScoreboard.lines} is a pure function. Only {@link #send} needs anything else.
     */
    public static List<Entry> visibleTo(Predicate<String> hasPermission) {
        Objects.requireNonNull(hasPermission, "hasPermission");
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (hasPermission.test(entry.permission())) {
                visible.add(entry);
            }
        }
        return List.copyOf(visible);
    }

    /** How many pages a sender with these permissions would see. Never zero. */
    public static int pageCount(Predicate<String> hasPermission, int perPage) {
        requirePageSize(perPage);
        int visible = visibleTo(hasPermission).size();
        return Math.max(1, (visible + perPage - 1) / perPage);
    }

    /** Rejects a page size that would divide by zero or page backwards. */
    private static void requirePageSize(int perPage) {
        if (perPage < 1) {
            throw new IllegalArgumentException("perPage must be positive, was " + perPage);
        }
    }

    /** The entries on one page, clamping a page number that is out of range. */
    public static List<Entry> page(Predicate<String> hasPermission, int page, int perPage) {
        List<Entry> visible = visibleTo(hasPermission);
        int from = (clamp(page, pageCount(hasPermission, perPage)) - 1) * perPage;
        if (from >= visible.size()) {
            return List.of();
        }
        return visible.subList(from, Math.min(from + perPage, visible.size()));
    }

    /** How many pages this instance's page size gives. */
    public int pageCount(Predicate<String> hasPermission) {
        return pageCount(hasPermission, perPage);
    }

    /** One page at this instance's page size. */
    public List<Entry> page(Predicate<String> hasPermission, int page) {
        return page(hasPermission, page, perPage);
    }

    /** The page size, so a caller can report it. */
    public int perPage() {
        return perPage;
    }

    /** Clamps into {@code [1, pages]}, so a typo shows a page rather than an error. */
    public static int clamp(int page, int pages) {
        return Math.max(1, Math.min(page, pages));
    }

    /**
     * Writes one page to the sender.
     *
     * <p>The category header is repeated whenever the category changes, including at the top
     * of a page that continues one, so a player on page 3 is never reading a list of commands
     * with no idea which section they belong to.
     */
    public void send(Audience audience, Predicate<String> hasPermission, int page) {
        Objects.requireNonNull(audience, "audience");
        int pages = pageCount(hasPermission);
        int clamped = clamp(page, pages);

        lang.sendRaw(audience, "help.header",
                LangManager.placeholder("page", String.valueOf(clamped)),
                LangManager.placeholder("pages", String.valueOf(pages)));

        String category = null;
        for (Entry entry : page(hasPermission, clamped)) {
            if (!entry.category().equals(category)) {
                category = entry.category();
                lang.sendRaw(audience, category);
            }
            // No resolvers, deliberately: a usage line contains <name>, <amount> and the like,
            // and a resolver of that name would substitute the placeholder being documented.
            lang.sendRaw(audience, entry.key());
        }

        if (clamped < pages) {
            lang.sendRaw(audience, "help.more",
                    LangManager.placeholder("next", String.valueOf(clamped + 1)));
        }
    }

    /** The distinct categories in declaration order, for tests and for the rules book. */
    public static Set<String> categories() {
        Set<String> categories = new LinkedHashSet<>();
        ENTRIES.forEach(entry -> categories.add(entry.category()));
        return categories;
    }
}
