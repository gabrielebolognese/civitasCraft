package dev.civitas.command.admin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.command.Suggest;
import dev.civitas.core.admin.FraudHeuristics;
import dev.civitas.core.city.City;
import dev.civitas.core.economy.Money;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * SPEC 9.4.1, inspection and audit.
 *
 * <h2>Everything here reads and nothing writes</h2>
 * That is what makes this the section a server can hand to a moderator. {@code civitas.admin.info}
 * and {@code civitas.admin.audit} together let somebody investigate a dispute completely and
 * change nothing, which is the shape most moderation actually takes.
 *
 * <p>SPEC 1.5 is the reason this section exists at all: "every coin movement, every claim, every
 * war action is logged… admins can reconstruct any dispute. This is not optional." Until these
 * commands existed the ledger was written and unreadable, which satisfied the letter of that
 * and none of its purpose.
 */
public final class AdminInspectCommands {

    private static final long MILLIS_PER_DAY = TimeUnit.DAYS.toMillis(1);

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public AdminInspectCommands(Supplier<CivitasServices> services, LangManager lang,
                                Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Every 9.4.1 branch, added to the {@code /ca} root. */
    public List<LiteralArgumentBuilder<CommandSourceStack>> build() {
        return List.of(info(), player(), ledger(), audit(), inspect(), alts(), economy());
    }

    // ==================================================================================
    // /ca info and /ca player
    // ==================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> info() {
        return Commands.literal("info")
                .requires(source -> source.getSender().hasPermission("civitas.admin.info"))
                .then(Commands.argument("city", StringArgumentType.word())
                        .suggests(this::suggestCities)
                        .executes(context -> {
                            cityInfo(context.getSource().getSender(),
                                    StringArgumentType.getString(context, "city"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    /** SPEC 9.4.1: "Full city dump: all fields, all claims, all members, all upgrades." */
    private void cityInfo(Audience audience, String name) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.registry().cityByName(name).ifPresentOrElse(city -> {
            lang.sendRaw(audience, "admin.info.header", Replies.p("city", city.name()));
            lang.sendRaw(audience, "admin.info.identity",
                    Replies.p("id", String.valueOf(city.id())),
                    Replies.p("tag", city.tag()),
                    Replies.p("mayor", nameOf(city.mayorUuid())),
                    Replies.p("founded", stamp(city.foundedAt())));
            lang.sendRaw(audience, "admin.info.money",
                    Replies.p("treasury", money(current, city.treasury())),
                    Replies.p("upkeep", money(current, current.upkeepTask().amountFor(city))),
                    Replies.p("delinquent", city.isDelinquent() ? "yes" : "no"));
            lang.sendRaw(audience, "admin.info.land",
                    Replies.p("claims", String.valueOf(current.claimRegistry()
                            .claimsOf(city.id()).size())),
                    Replies.p("outposts", String.valueOf(current.outposts().registry()
                            .countOf(city.id()))),
                    Replies.p("core", city.coreWorld() + " " + city.coreChunkX()
                            + "," + city.coreChunkZ()));
            lang.sendRaw(audience, "admin.info.people",
                    Replies.p("members", String.valueOf(city.memberCount())),
                    Replies.p("cap", String.valueOf(current.cities().memberCap(city))));
            lang.sendRaw(audience, "admin.info.state",
                    Replies.p("frozen", city.isFrozen() ? "yes" : "no"),
                    Replies.p("deleted", city.isDeleted() ? "yes" : "no"),
                    Replies.p("immunity", city.warProtectionUntil() > System.currentTimeMillis()
                            ? stamp(city.warProtectionUntil()) : "-"));
            lang.sendRaw(audience, "admin.info.upgrades",
                    Replies.p("bought", String.valueOf(current.upgrades().totalLevels(city.id()))),
                    // Points, not a count: an admin looking for a city that is over budget
                    // cannot see it from how many units are standing.
                    Replies.p("defense", current.defense().pointsSpent(city.id())
                            + "/" + current.defense().capacity(city)));
        }, () -> lang.send(audience, "city.unknown"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> player() {
        return Commands.literal("player")
                .requires(source -> source.getSender().hasPermission("civitas.admin.info"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Suggest.onlinePlayers())
                        .executes(context -> {
                            playerInfo(context.getSource().getSender(),
                                    StringArgumentType.getString(context, "player"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    /** SPEC 9.4.1: "Full player dump: balance, city, rank, playtime, IP-shared accounts." */
    private void playerInfo(Audience audience, String name) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        resolve(current, audience, name, resolved ->
                current.daos().players().findByUuid(resolved.uuid())
                        .whenComplete((row, error) -> scheduler.runOnMain(() -> {
                            if (error != null || row == null || row.isEmpty()) {
                                lang.send(audience, "player.unknown",
                                        Replies.p("player", name));
                                return;
                            }
                            printPlayer(audience, current, resolved.name(), row.get());
                        })));
    }

    private void printPlayer(Audience audience, CivitasServices current, String name,
                             PlayerRow row) {
        lang.sendRaw(audience, "admin.player.header", Replies.p("player", name));
        lang.sendRaw(audience, "admin.player.money",
                Replies.p("balance", money(current, row.balance())),
                Replies.p("frozen", row.frozen() ? "yes" : "no"));
        lang.sendRaw(audience, "admin.player.city",
                Replies.p("city", row.cityId() == null ? "-"
                        : current.registry().city(row.cityId()).map(City::name)
                                .orElse("#" + row.cityId())),
                Replies.p("uuid", row.uuid().toString()));
        lang.sendRaw(audience, "admin.player.time",
                Replies.p("playtime", hours(row.totalPlaytimeMs())),
                Replies.p("active", hours(row.activePlaytimeMs())),
                Replies.p("first", stamp(row.firstJoin())),
                Replies.p("seen", stamp(row.lastSeen())));
    }

    // ==================================================================================
    // /ca ledger
    // ==================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> ledger() {
        return Commands.literal("ledger")
                .requires(source -> source.getSender().hasPermission("civitas.admin.audit"))
                .then(Commands.literal("player")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(Suggest.onlinePlayers())
                                .executes(context -> ledgerPlayer(context, 7))
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                        .executes(context -> ledgerPlayer(context,
                                                IntegerArgumentType.getInteger(context, "days"))))))
                .then(Commands.literal("city")
                        .then(Commands.argument("city", StringArgumentType.word())
                                .suggests(this::suggestCities)
                                .executes(context -> ledgerCity(context, 7))
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                        .executes(context -> ledgerCity(context,
                                                IntegerArgumentType.getInteger(context, "days"))))))
                .then(Commands.literal("type")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests(this::suggestTypes)
                                .executes(context -> ledgerType(context, 7))
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                        .executes(context -> ledgerType(context,
                                                IntegerArgumentType.getInteger(context, "days"))))))
                .then(Commands.literal("export")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(this::suggestExportTargets)
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                        .executes(context -> {
                                            export(context.getSource().getSender(),
                                                    StringArgumentType.getString(context, "target"),
                                                    IntegerArgumentType.getInteger(context, "days"));
                                            return Command.SINGLE_SUCCESS;
                                        }))));
    }

    private int ledgerPlayer(CommandContext<CommandSourceStack> context, int days) {
        Audience audience = context.getSource().getSender();
        CivitasServices current = ready(audience);
        if (current == null) {
            return Command.SINGLE_SUCCESS;
        }
        String name = StringArgumentType.getString(context, "player");
        long since = System.currentTimeMillis() - days * MILLIS_PER_DAY;

        resolve(current, audience, name, resolved ->
                show(audience, current, "admin.ledger.header-player", name,
                        current.daos().ledger().findByPlayer(resolved.uuid(), since, 100)));
        return Command.SINGLE_SUCCESS;
    }

    private int ledgerCity(CommandContext<CommandSourceStack> context, int days) {
        Audience audience = context.getSource().getSender();
        CivitasServices current = ready(audience);
        if (current == null) {
            return Command.SINGLE_SUCCESS;
        }
        String name = StringArgumentType.getString(context, "city");
        long since = System.currentTimeMillis() - days * MILLIS_PER_DAY;

        current.registry().cityByName(name).ifPresentOrElse(city ->
                        show(audience, current, "admin.ledger.header-city", city.name(),
                                current.daos().ledger().findByCity(city.id(), since, 100)),
                () -> lang.send(audience, "city.unknown"));
        return Command.SINGLE_SUCCESS;
    }

    private int ledgerType(CommandContext<CommandSourceStack> context, int days) {
        Audience audience = context.getSource().getSender();
        CivitasServices current = ready(audience);
        if (current == null) {
            return Command.SINGLE_SUCCESS;
        }
        String type = StringArgumentType.getString(context, "type").toUpperCase(Locale.ROOT);
        long since = System.currentTimeMillis() - days * MILLIS_PER_DAY;

        show(audience, current, "admin.ledger.header-type", type,
                current.daos().ledger().findByType(type, since, 100));
        return Command.SINGLE_SUCCESS;
    }

    private void show(Audience audience, CivitasServices current, String headerKey,
                      String subject, java.util.concurrent.CompletableFuture<List<LedgerRow>> query) {
        query.whenComplete((rows, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Ledger query failed", error);
                lang.send(audience, "command.error");
                return;
            }
            if (rows.isEmpty()) {
                lang.send(audience, "admin.ledger.empty", Replies.p("subject", subject));
                return;
            }
            lang.sendRaw(audience, headerKey,
                    Replies.p("subject", subject),
                    Replies.p("count", String.valueOf(rows.size())));
            for (LedgerRow row : rows) {
                lang.sendRaw(audience, "admin.ledger.entry",
                        Replies.p("when", stamp(row.timestamp())),
                        Replies.p("type", row.type()),
                        Replies.p("amount", money(current, row.amount())),
                        Replies.p("after", money(current, row.balanceAfter())),
                        Replies.p("actor", nameOf(row.actorUuid())));
            }
        }));
    }

    /** SPEC 9.4.1's CSV export. */
    private void export(Audience audience, String target, int days) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        long since = System.currentTimeMillis() - days * MILLIS_PER_DAY;

        // A target may be a player, a city or a transaction type. Rather than a fourth
        // argument telling the command which, it tries each: an admin exporting "Roma" should
        // not have to say whether Roma is a city.
        current.registry().cityByName(target)
                .ifPresentOrElse(
                        city -> exportRows(audience, current, city.name(),
                                current.daos().ledger().findByCity(city.id(), since, 100_000)),
                        () -> current.lookup().resolve(target).whenComplete(
                                (resolved, error) -> scheduler.runOnMain(() -> {
                                    if (resolved != null && resolved.isPresent()) {
                                        exportRows(audience, current, resolved.get().name(),
                                                current.daos().ledger().findByPlayer(
                                                        resolved.get().uuid(), since, 100_000));
                                    } else {
                                        exportRows(audience, current, target,
                                                current.daos().ledger().findByType(
                                                        target.toUpperCase(Locale.ROOT),
                                                        since, 100_000));
                                    }
                                })));
    }

    private void exportRows(Audience audience, CivitasServices current, String label,
                            java.util.concurrent.CompletableFuture<List<LedgerRow>> query) {
        query.thenAccept(rows -> {
            // Still off the server thread: writing a file is I/O, and SPEC 2.1's reasoning
            // about the database applies to a disk write just as well.
            try {
                java.io.File written = current.ledgerExport().write(label, rows,
                        System.currentTimeMillis());
                scheduler.runOnMain(() -> lang.send(audience, "admin.ledger.exported",
                        Replies.p("file", written.getName()),
                        Replies.p("count", String.valueOf(rows.size()))));
            } catch (java.io.IOException e) {
                logger.log(Level.SEVERE, "Could not write the ledger export", e);
                scheduler.runOnMain(() -> lang.send(audience, "admin.ledger.export-failed"));
            }
        }).exceptionally(error -> {
            logger.log(Level.SEVERE, "Ledger export query failed", error);
            scheduler.runOnMain(() -> lang.send(audience, "command.error"));
            return null;
        });
    }

    // ==================================================================================
    // /ca audit suspicious
    // ==================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> audit() {
        return Commands.literal("audit")
                .requires(source -> source.getSender().hasPermission("civitas.admin.audit"))
                .then(Commands.literal("suspicious")
                        .executes(context -> {
                            suspicious(context.getSource().getSender(), 7);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                .executes(context -> {
                                    suspicious(context.getSource().getSender(),
                                            IntegerArgumentType.getInteger(context, "days"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("log")
                        .executes(context -> {
                            auditLog(context.getSource().getSender(), 7);
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    /**
     * SPEC 17.6 case 79's heuristics over the recent ledger.
     *
     * <p>Every line is worded as something to look at rather than something proved. A heuristic
     * presented as a verdict gets somebody banned for playing well.
     */
    private void suspicious(Audience audience, int days) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long since = now - days * MILLIS_PER_DAY;
        lang.send(audience, "admin.audit.running", Replies.p("days", String.valueOf(days)));

        current.daos().ledger().findByType(TransactionType.TREASURY_WITHDRAW.name(), since, 5000)
                .thenCombine(current.daos().ledger().findByType(
                                TransactionType.PLAYER_PAY.name(), since, 5000),
                        (withdrawals, transfers) -> {
                            FraudHeuristics rules = current.fraud();
                            List<FraudHeuristics.Hit> hits = new ArrayList<>();
                            hits.addAll(rules.largeWithdrawals(withdrawals));
                            hits.addAll(rules.repeatedTransfers(transfers, now));
                            return hits;
                        })
                .whenComplete((hits, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        logger.log(Level.SEVERE, "The audit sweep failed", error);
                        lang.send(audience, "command.error");
                        return;
                    }
                    if (hits.isEmpty()) {
                        lang.send(audience, "admin.audit.nothing");
                        return;
                    }
                    lang.sendRaw(audience, "admin.audit.header",
                            Replies.p("count", String.valueOf(hits.size())));
                    for (FraudHeuristics.Hit hit : hits) {
                        lang.sendRaw(audience, "admin.audit.hit",
                                Replies.p("rule", hit.rule()),
                                Replies.p("subject", hit.subject()),
                                Replies.p("detail", describe(hit.detail())));
                    }
                    lang.send(audience, "admin.audit.disclaimer");
                }));
    }

    /** The admin action log itself, SPEC 17.6 case 80. */
    private void auditLog(Audience audience, int days) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        long since = System.currentTimeMillis() - days * MILLIS_PER_DAY;

        current.audit().recent(since, 50).whenComplete((rows, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                lang.send(audience, "command.error");
                return;
            }
            if (rows.isEmpty()) {
                lang.send(audience, "admin.audit.log-empty");
                return;
            }
            lang.sendRaw(audience, "admin.audit.log-header",
                    Replies.p("count", String.valueOf(rows.size())));
            rows.forEach(row -> lang.sendRaw(audience, "admin.audit.log-entry",
                    Replies.p("when", stamp(row.timestamp())),
                    Replies.p("actor", nameOf(row.actorUuid())),
                    Replies.p("action", row.action()),
                    Replies.p("target", row.target() == null ? "-" : row.target()),
                    Replies.p("reason", row.reason() == null ? "-" : row.reason())));
        }));
    }

    // ==================================================================================
    // /ca inspect and /ca alts
    // ==================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> inspect() {
        return Commands.literal("inspect")
                .requires(source -> source.getSender().hasPermission("civitas.admin.inspect"))
                .executes(context -> {
                    CivitasServices current = ready(context.getSource().getSender());
                    if (current == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    if (!(context.getSource().getSender() instanceof Player player)) {
                        lang.send(context.getSource().getSender(),
                                dev.civitas.lang.Msg.COMMAND_PLAYER_ONLY);
                        return Command.SINGLE_SUCCESS;
                    }
                    boolean on = current.inspect().toggle(player.getUniqueId());
                    lang.send(player, on ? "admin.inspect.on" : "admin.inspect.off");
                    return Command.SINGLE_SUCCESS;
                });
    }

    private LiteralArgumentBuilder<CommandSourceStack> alts() {
        return Commands.literal("alts")
                .requires(source -> source.getSender().hasPermission("civitas.admin.audit"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Suggest.onlinePlayers())
                        .executes(context -> {
                            alts(context.getSource().getSender(),
                                    StringArgumentType.getString(context, "player"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    /**
     * SPEC 9.4.1: "Accounts sharing an IP or login fingerprint."
     *
     * <p>M15 stores a salted hash of the address and never the address itself, which is enough
     * to answer "do these two connect from the same place" and not enough to answer "where".
     * That is the whole question this command needs, and the narrower answer is the one worth
     * having: an admin investigating alt accounts has no business learning where somebody
     * lives.
     */
    private void alts(Audience audience, String name) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        resolve(current, audience, name, resolved ->
                current.daos().playerLogins().find(resolved.uuid())
                        .thenCompose(mine -> {
                            if (mine.isEmpty()) {
                                return java.util.concurrent.CompletableFuture.completedFuture(
                                        List.<UUID>of());
                            }
                            return current.daos().playerLogins().findAll().thenApply(all ->
                                    all.stream()
                                            .filter(row -> row.loginHash()
                                                    .equals(mine.get().loginHash()))
                                            .map(dev.civitas.storage.row.PlayerLoginRow::uuid)
                                            .filter(uuid -> !uuid.equals(resolved.uuid()))
                                            .toList());
                        })
                        .whenComplete((shared, error) -> scheduler.runOnMain(() -> {
                            if (error != null) {
                                lang.send(audience, "command.error");
                                return;
                            }
                            if (shared.isEmpty()) {
                                lang.send(audience, "admin.alts.none",
                                        Replies.p("player", resolved.name()));
                                return;
                            }
                            lang.sendRaw(audience, "admin.alts.header",
                                    Replies.p("player", resolved.name()),
                                    Replies.p("count", String.valueOf(shared.size())));
                            shared.forEach(uuid -> lang.sendRaw(audience, "admin.alts.entry",
                                    Replies.p("player", nameOf(uuid))));
                        })));
    }

    // ==================================================================================
    // /ca economy stats
    // ==================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> economy() {
        return Commands.literal("economy")
                .requires(source -> source.getSender().hasPermission("civitas.admin.economy"))
                .then(Commands.literal("stats")
                        .executes(context -> {
                            economyStats(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    /** SPEC 9.4.1: "Total circulation, top 20 balances, weekly inflation delta." */
    private void economyStats(Audience audience) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.daos().players().findTopByBalance(20)
                .thenCombine(current.daos().players().totalCirculation(),
                        (top, circulation) -> Map.entry(top, circulation))
                .whenComplete((data, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        logger.log(Level.SEVERE, "Economy stats failed", error);
                        lang.send(audience, "command.error");
                        return;
                    }
                    lang.sendRaw(audience, "admin.economy.header",
                            Replies.p("circulation", money(current, data.getValue())));
                    int rank = 1;
                    for (PlayerRow row : data.getKey()) {
                        lang.sendRaw(audience, "admin.economy.entry",
                                Replies.p("rank", String.valueOf(rank++)),
                                Replies.p("player", row.lastKnownName()),
                                Replies.p("balance", money(current, row.balance())));
                    }
                }));
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

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
                logger.log(Level.SEVERE, "Player lookup failed", error);
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

    private static String describe(Map<String, String> detail) {
        return detail.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static String stamp(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochMilli(millis));
    }

    private static String hours(long millis) {
        return String.valueOf(TimeUnit.MILLISECONDS.toHours(millis));
    }

    private static String nameOf(UUID uuid) {
        if (uuid == null) {
            return "console";
        }
        String name = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private String money(CivitasServices current, BigDecimal amount) {
        return amount == null ? "-" : Money.format(amount, current.economy().configs());
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestCities(CommandContext<CommandSourceStack> context,
                          com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CivitasServices current = services.get();
        if (current != null) {
            String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
            current.registry().cities().stream()
                    .map(City::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestTypes(CommandContext<CommandSourceStack> context,
                         com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String prefix = builder.getRemaining().toUpperCase(Locale.ROOT);
        for (TransactionType type : TransactionType.values()) {
            if (type.name().startsWith(prefix)) {
                builder.suggest(type.name());
            }
        }
        return builder.buildFuture();
    }

    /**
     * Everything {@code /ca ledger export} will accept: a player, a city, or a ledger type.
     *
     * <p>All three namespaces at once, because M21 decided this argument tries each in turn
     * rather than taking a fourth argument to say which. An admin exporting "Roma" should not
     * have to tell the plugin that Roma is a city, and should not have to remember that it
     * could equally have been a player.
     */
    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestExportTargets(CommandContext<CommandSourceStack> context,
                                 com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (org.bukkit.entity.Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(player.getName());
            }
        }
        CivitasServices current = services.get();
        if (current != null) {
            current.registry().cities().stream()
                    .map(dev.civitas.core.city.City::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                    .forEach(builder::suggest);
        }
        return suggestTypes(context, builder);
    }
}
