package dev.civitas.command.admin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.command.Suggest;
import dev.civitas.core.economy.Money;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.StaffNoteRow;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * SPEC 22.7.1 and 22.7.2, the commands an admin reaches for when somebody says "player X has too
 * much money".
 *
 * <h2>Why these are separate from SPEC 9.4</h2>
 *
 * <p>SPEC 22.7 makes the distinction itself: Part I's admin section "covered management. These add
 * the <b>investigative</b> commands an admin actually needs." Management changes the world; these
 * only read it, and the difference matters when the question is whether an intervention is
 * warranted at all.
 *
 * <p>{@code /ca history} is the priority SPEC 22.1 names — "This is the command for 'what did this
 * player sell'" — and it is deliberately <b>market activity only</b>. The full ledger already has
 * {@code /ca ledger}, and an admin chasing an exploit does not want a player's rent payments
 * interleaved with the eight thousand diamonds they sold in an hour.
 */
public final class AdminInvestigationCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    /** Who is watching city chat, SPEC 22.7.2. Memory only; a restart clears it. */
    private final Set<UUID> spying = ConcurrentHashMap.newKeySet();

    public AdminInvestigationCommands(Supplier<CivitasServices> services, LangManager lang,
                                      Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public List<LiteralArgumentBuilder<CommandSourceStack>> build() {
        return List.of(history(), notes(), spy(), whoami(), stats());
    }

    /** Whether this admin has {@code /ca spy} on, for the chat listener to consult. */
    public boolean isSpying(UUID admin) {
        return spying.contains(admin);
    }

    // ==================================================================================
    // /ca history, SPEC 22.7.1's priority
    // ==================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> history() {
        return Commands.literal("history")
                .requires(source -> source.getSender().hasPermission("civitas.admin.audit"))
                .then(Commands.literal("item")
                        .then(Commands.argument("material", StringArgumentType.word())
                                .suggests(this::suggestMarketItems)
                                .executes(context -> {
                                    itemHistory(context.getSource().getSender(),
                                            StringArgumentType.getString(context, "material"), 7);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                        .executes(context -> {
                                            itemHistory(context.getSource().getSender(),
                                                    StringArgumentType.getString(context,
                                                            "material"),
                                                    IntegerArgumentType.getInteger(context,
                                                            "days"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Suggest.onlinePlayers())
                        .executes(context -> {
                            playerHistory(context.getSource().getSender(),
                                    StringArgumentType.getString(context, "player"), 7);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                .executes(context -> {
                                    playerHistory(context.getSource().getSender(),
                                            StringArgumentType.getString(context, "player"),
                                            IntegerArgumentType.getInteger(context, "days"));
                                    return Command.SINGLE_SUCCESS;
                                })));
    }

    private void playerHistory(Audience audience, String name, int days) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        resolve(current, audience, name, found -> {
            long since = System.currentTimeMillis() - (long) days * 86_400_000L;
            then(current.daos().ledger().findByPlayer(found.uuid(), since, 200), audience, rows -> {
                List<LedgerRow> market = rows.stream().filter(AdminInvestigationCommands::isMarket)
                        .toList();
                lang.send(audience, "admin.history.header",
                        Replies.p("player", found.name()),
                        Replies.p("days", String.valueOf(days)),
                        Replies.p("count", String.valueOf(market.size())));
                if (market.isEmpty()) {
                    lang.send(audience, "admin.history.none");
                    return;
                }
                BigDecimal running = BigDecimal.ZERO;
                for (LedgerRow row : market) {
                    running = running.add(row.amount());
                    lang.send(audience, "admin.history.entry",
                            Replies.p("type", row.type()),
                            Replies.p("item", itemOf(row.metadata())),
                            Replies.p("amount", Money.format(row.amount(),
                                    current.economy().configs())),
                            Replies.p("balance", Money.format(row.balanceAfter(),
                                    current.economy().configs())));
                }
                lang.send(audience, "admin.history.net",
                        Replies.p("amount", Money.format(running,
                                current.economy().configs())));
            });
        });
    }

    /**
     * SPEC 22.7.1: "Every transaction of one item server-wide, sorted by volume. <b>Finds the
     * exploit.</b>"
     */
    private void itemHistory(Audience audience, String material, int days) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        String wanted = material.toUpperCase(Locale.ROOT);
        long since = System.currentTimeMillis() - (long) days * 86_400_000L;

        then(current.daos().ledger().findByType("MARKET_SELL", since, 5000), audience, rows -> {
            List<LedgerRow> forItem = rows.stream()
                    .filter(row -> wanted.equalsIgnoreCase(itemOf(row.metadata())))
                    .toList();
            lang.send(audience, "admin.history.item-header",
                    Replies.p("item", wanted),
                    Replies.p("days", String.valueOf(days)),
                    Replies.p("count", String.valueOf(forItem.size())));
            if (forItem.isEmpty()) {
                lang.send(audience, "admin.history.none");
                return;
            }
            // Grouped by seller and sorted by value, because the question this answers is "who",
            // not "when": a list of four thousand sales in timestamp order finds nothing.
            java.util.Map<UUID, BigDecimal> bySeller = new java.util.LinkedHashMap<>();
            for (LedgerRow row : forItem) {
                if (row.actorUuid() != null) {
                    bySeller.merge(row.actorUuid(), row.amount(), BigDecimal::add);
                }
            }
            bySeller.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(15)
                    .forEach(entry -> lang.send(audience, "admin.history.item-entry",
                            Replies.p("player", String.valueOf(entry.getKey())),
                            Replies.p("amount", Money.format(entry.getValue(),
                                    current.economy().configs()))));
        });
    }

    private static boolean isMarket(LedgerRow row) {
        return row.type().startsWith("MARKET_") || "PLAYER_SHOP".equals(row.type());
    }

    /** Pulls the material out of a metadata blob, the same shape {@code MarketService} writes. */
    private static String itemOf(String metadata) {
        if (metadata == null) {
            return "-";
        }
        int key = metadata.indexOf("\"item\"");
        if (key < 0) {
            return "-";
        }
        int open = metadata.indexOf('"', metadata.indexOf(':', key) + 1);
        int close = open < 0 ? -1 : metadata.indexOf('"', open + 1);
        return open < 0 || close < 0 ? "-" : metadata.substring(open + 1, close);
    }

    /**
     * {@code /ca stats server}, SPEC 36.6.
     *
     * <p>The ratio of active-in-7-days to active-in-30 is the line worth reading: a server bleeding
     * regulars while its registration count still climbs is exactly the failure SPEC 36.6 calls
     * "slow and confusing to diagnose", and no other number here shows it.
     */
    private LiteralArgumentBuilder<CommandSourceStack> stats() {
        return Commands.literal("stats")
                .requires(source -> source.getSender().hasPermission("civitas.admin.info"))
                .then(Commands.literal("server")
                        .executes(context -> {
                            serverStats(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private void serverStats(Audience audience) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        then(current.serverStats().record(System.currentTimeMillis()), audience, today -> {
            lang.send(audience, "admin.stats.header");
            lang.send(audience, "admin.stats.players",
                    Replies.p("registered", String.valueOf(today.registered())),
                    Replies.p("week", String.valueOf(today.active7d())),
                    Replies.p("month", String.valueOf(today.active30d())));
            lang.send(audience, "admin.stats.cities",
                    Replies.p("cities", String.valueOf(today.cities())),
                    Replies.p("claims", String.valueOf(today.claims())),
                    Replies.p("average", String.format(Locale.ROOT, "%.1f",
                            today.averageCitySize())));

            // The retention line, computed rather than stored: a ratio is what an owner acts on
            // and a count is not.
            if (today.active30d() > 0) {
                lang.send(audience, "admin.stats.retention",
                        Replies.p("percent", String.format(Locale.ROOT, "%.0f",
                                100.0 * today.active7d() / today.active30d())));
            }
        });
    }

    // ==================================================================================
    // /ca notes, SPEC 22.7.2
    // ==================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> notes() {
        return Commands.literal("notes")
                .requires(source -> source.getSender().hasPermission("civitas.admin.info"))
                .then(Commands.literal("add")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(Suggest.onlinePlayers())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            addNote(context.getSource().getSender(),
                                                    StringArgumentType.getString(context,
                                                            "player"),
                                                    StringArgumentType.getString(context, "text"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Suggest.onlinePlayers())
                        .executes(context -> {
                            listNotes(context.getSource().getSender(),
                                    StringArgumentType.getString(context, "player"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private void listNotes(Audience audience, String name) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        resolve(current, audience, name, found ->
                then(current.daos().staffNotes().findAbout(found.uuid(), 25), audience, notes -> {
                    lang.send(audience, "admin.notes.header",
                            Replies.p("player", found.name()),
                            Replies.p("count", String.valueOf(notes.size())));
                    if (notes.isEmpty()) {
                        lang.send(audience, "admin.notes.none");
                        return;
                    }
                    for (StaffNoteRow note : notes) {
                        lang.send(audience, "admin.notes.entry",
                                Replies.p("note", note.note()),
                                Replies.p("when", stamp(note.createdAt())));
                    }
                }));
    }

    private void addNote(Audience audience, String name, String text) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        resolve(current, audience, name, found -> {
            current.daos().staffNotes().insert(new StaffNoteRow(0, found.uuid(),
                    actorOf(audience), text, System.currentTimeMillis()));
            // Audited as well as stored: SPEC 17.6 case 80 wants every admin action on the
            // record, and a note is an admin forming a view about a player.
            current.audit().record(actorOf(audience), "STAFF_NOTE", found.name(), null);
            lang.send(audience, "admin.notes.added", Replies.p("player", found.name()));
        });
    }

    // ==================================================================================
    // /ca spy, SPEC 22.7.2
    // ==================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> spy() {
        return Commands.literal("spy")
                .requires(source -> source.getSender().hasPermission("civitas.admin.info"))
                .executes(context -> {
                    toggleSpy(context.getSource().getSender(), null);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("state", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("on");
                            builder.suggest("off");
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            toggleSpy(context.getSource().getSender(),
                                    StringArgumentType.getString(context, "state"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private void toggleSpy(Audience audience, String state) {
        if (!(audience instanceof Player admin)) {
            lang.send(audience, dev.civitas.lang.Msg.COMMAND_PLAYER_ONLY);
            return;
        }
        boolean on = state == null
                ? !spying.contains(admin.getUniqueId())
                : "on".equalsIgnoreCase(state);
        if (on) {
            spying.add(admin.getUniqueId());
        } else {
            spying.remove(admin.getUniqueId());
        }
        lang.send(admin, on ? "admin.spy.on" : "admin.spy.off");
    }

    // ==================================================================================
    // /ca whoami, SPEC 22.7.3
    // ==================================================================================

    /**
     * SPEC 22.7.3: "Lists which admin permissions you hold. Useful on a staff team with tiers."
     *
     * <p>Read from the sender rather than from a table, so it answers the only question worth
     * asking — what this account can actually do right now, whatever a permissions plugin has
     * been told about it.
     */
    private LiteralArgumentBuilder<CommandSourceStack> whoami() {
        return Commands.literal("whoami")
                .requires(source -> source.getSender().hasPermission("civitas.admin"))
                .executes(context -> {
                    Audience audience = context.getSource().getSender();
                    lang.send(audience, "admin.whoami.header");
                    for (String node : ADMIN_NODES) {
                        boolean held = context.getSource().getSender().hasPermission(node);
                        lang.send(audience, held ? "admin.whoami.held" : "admin.whoami.missing",
                                Replies.p("node", node));
                    }
                    return Command.SINGLE_SUCCESS;
                });
    }

    /** SPEC 10's admin nodes, in the order that section lists them. */
    private static final List<String> ADMIN_NODES = List.of(
            "civitas.admin", "civitas.admin.info", "civitas.admin.audit",
            "civitas.admin.inspect", "civitas.admin.city", "civitas.admin.claim",
            "civitas.admin.economy", "civitas.admin.war", "civitas.admin.event",
            "civitas.admin.contest", "civitas.admin.system");

    // ==================================================================================

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestMarketItems(
                    com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                    com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CivitasServices current = services.get();
        if (current != null) {
            // SPEC 22.8: complete against the market list, not every Minecraft material.
            // "Suggesting 1,200 materials when 14 are sellable is hostile."
            String prefix = builder.getRemaining().toUpperCase(Locale.ROOT);
            current.market().registry().buyList().stream()
                    .filter(material -> material.startsWith(prefix))
                    .limit(40)
                    .forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    private static String stamp(long at) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(at));
    }

    private CivitasServices ready(Audience audience) {
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(audience, "plugin.starting");
        }
        return current;
    }

    private void resolve(CivitasServices current, Audience audience, String name,
                         java.util.function.Consumer<PlayerLookup.Resolved> then) {
        current.lookup().resolve(name).whenComplete((resolved, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                lang.send(audience, "command.error");
                return;
            }
            if (resolved == null || resolved.isEmpty()) {
                lang.send(audience, "player.unknown", Replies.p("player", name));
                return;
            }
            then.accept(resolved.get());
        }));
    }

    private <T> void then(CompletableFuture<T> future, Audience audience,
                          java.util.function.Consumer<T> print) {
        future.whenComplete((value, error) -> scheduler.runOnMain(() -> {
            if (error != null || value == null) {
                logger.log(Level.WARNING, "An investigation query failed", error);
                lang.send(audience, "command.error");
                return;
            }
            print.accept(value);
        }));
    }

    private static UUID actorOf(Audience audience) {
        return audience instanceof Player player ? player.getUniqueId() : null;
    }
}
