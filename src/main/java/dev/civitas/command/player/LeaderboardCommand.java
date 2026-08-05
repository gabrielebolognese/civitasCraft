package dev.civitas.command.player;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.economy.Money;
import dev.civitas.core.progression.LeaderboardEntry;
import dev.civitas.core.progression.LeaderboardService;
import dev.civitas.core.progression.LeaderboardType;
import dev.civitas.lang.LangManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;

/**
 * {@code /leaderboard [type] [page]}, SPEC 9.1 and SPEC 13.3.
 *
 * <p>With no argument it prints every board with its leader. SPEC 13.3 requires all of them
 * to be shown "with equal prominence", which is a design instruction as much as a layout one:
 * the index gives Wealth exactly the same line as Farmer, because the whole reason there are
 * nine boards is that a server with one ladder has one way to matter.
 *
 * <p>Available to the console as well as to players. Nothing here needs a location or an
 * inventory, and an admin reading standings from the server log is a reasonable thing to want.
 */
public final class LeaderboardCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;

    public LeaderboardCommand(Supplier<CivitasServices> services, LangManager lang) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("leaderboard")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> {
                    index(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (LeaderboardType type : LeaderboardType.values()) {
                                if (type.key().startsWith(
                                        builder.getRemaining().toLowerCase(Locale.ROOT))) {
                                    builder.suggest(type.key());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            show(context.getSource().getSender(),
                                    StringArgumentType.getString(context, "type"), 1);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    show(context.getSource().getSender(),
                                            StringArgumentType.getString(context, "type"),
                                            IntegerArgumentType.getInteger(context, "page"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .build();
    }

    // ==================================================================================
    // The index
    // ==================================================================================

    private void index(Audience audience) {
        LeaderboardService boards = boardsOrNull(audience);
        if (boards == null) {
            return;
        }

        lang.sendRaw(audience, "leaderboard.index.header");
        for (LeaderboardType type : LeaderboardType.all()) {
            lang.sendRaw(audience, "leaderboard.index.entry",
                    Replies.p("key", type.key()),
                    Replies.p("name", plain(type.nameKey())),
                    Replies.p("metric", plain(type.metricKey())),
                    Replies.p("leader", leaderOf(boards, type)));
        }
        lang.sendRaw(audience, "leaderboard.index.footer");
    }

    /** The top line of a board, condensed to one placeholder for the index. */
    private String leaderOf(LeaderboardService boards, LeaderboardType type) {
        if (!boards.isAvailable(type)) {
            return plain("leaderboard.unavailable-short");
        }
        List<LeaderboardEntry> page = boards.board(type).orElse(List.of());
        if (!boards.isReady()) {
            return plain("leaderboard.not-ready-short");
        }
        if (page.isEmpty()) {
            return plain("leaderboard.empty-short");
        }
        LeaderboardEntry top = page.get(0);
        return top.name() + " " + value(type, top);
    }

    // ==================================================================================
    // One board
    // ==================================================================================

    private void show(Audience audience, String typeName, int page) {
        LeaderboardService boards = boardsOrNull(audience);
        if (boards == null) {
            return;
        }

        Optional<LeaderboardType> parsed = LeaderboardType.parse(typeName);
        if (parsed.isEmpty()) {
            lang.send(audience, "leaderboard.unknown-type", Replies.p("type", typeName));
            return;
        }
        LeaderboardType type = parsed.get();

        if (!boards.isAvailable(type)) {
            lang.send(audience, "leaderboard.unavailable",
                    Replies.p("name", plain(type.nameKey())));
            return;
        }
        if (!boards.isReady()) {
            lang.send(audience, "leaderboard.not-ready");
            return;
        }

        List<LeaderboardEntry> entries = boards.page(type, page);
        int pages = boards.pageCount(type);

        lang.sendRaw(audience, "leaderboard.header",
                Replies.p("name", plain(type.nameKey())),
                Replies.p("metric", plain(type.metricKey())),
                Replies.p("page", String.valueOf(page)),
                Replies.p("pages", String.valueOf(pages)));

        if (entries.isEmpty()) {
            lang.sendRaw(audience, "leaderboard.empty");
            return;
        }

        for (LeaderboardEntry entry : entries) {
            lang.sendRaw(audience, type.entryKey(),
                    Replies.p("rank", String.valueOf(entry.rank())),
                    Replies.p("name", entry.name()),
                    Replies.p("value", value(type, entry)),
                    Replies.p("secondary", secondary(entry)));
        }

        if (page < pages) {
            lang.sendRaw(audience, "leaderboard.next-page",
                    Replies.p("key", type.key()),
                    Replies.p("page", String.valueOf(page + 1)));
        }
    }

    // ==================================================================================
    // Formatting
    // ==================================================================================

    /**
     * Renders a board's figure the way that board measures things.
     *
     * <p>A count prints as a whole number and money through {@link Money#format}, so the
     * currency symbol and decimal places stay config-driven rather than being spelled out
     * here.
     */
    private String value(LeaderboardType type, LeaderboardEntry entry) {
        return switch (type.format()) {
            case MONEY -> Money.format(entry.value(), services.get().economy().configs());
            case COUNT, RECORD -> entry.value().toBigInteger().toString();
        };
    }

    private String secondary(LeaderboardEntry entry) {
        BigDecimal value = entry.secondary();
        return value == null ? "0" : value.toBigInteger().toString();
    }

    /** A language string as literal text, for embedding in another message's placeholder. */
    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(lang.get(key));
    }

    /** @return null, having already explained why, if storage is not open yet */
    private LeaderboardService boardsOrNull(Audience audience) {
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(audience, "plugin.starting");
            return null;
        }
        if (!current.leaderboards().isEnabled()) {
            lang.send(audience, "leaderboard.disabled");
            return null;
        }
        return current.leaderboards();
    }
}
