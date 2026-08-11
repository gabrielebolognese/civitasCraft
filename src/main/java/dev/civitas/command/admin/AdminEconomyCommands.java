package dev.civitas.command.admin;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.command.Suggest;
import dev.civitas.core.city.City;
import dev.civitas.core.economy.Money;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.lang.LangManager;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * SPEC 9.4.4, economy administration.
 *
 * <h2>The section where a mistake is worth real money</h2>
 * Every command here moves currency, and SPEC 1.5 makes every movement auditable for exactly
 * this reason. So each one writes to the audit log before it acts, and {@code economy.yml}'s
 * {@code audit.strict-admin-reasons} can require a stated reason on the three that create or
 * destroy money outright. An admin grant with no explanation is the thing SPEC 17.6 case 80
 * exists to surface.
 *
 * <h2>Give, take and set are not the same operation</h2>
 * {@code give} and {@code take} are relative and {@code set} is absolute, which matters when
 * two admins act at once: two gives of 1,000 leave the player 2,000 better off, and two sets
 * to 1,000 leave them at 1,000 however many ran. That is why SPEC lists all three rather than
 * only one.
 */
public final class AdminEconomyCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public AdminEconomyCommands(Supplier<CivitasServices> services, LangManager lang,
                                Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("eco")
                .requires(source -> source.getSender().hasPermission("civitas.admin.economy"))
                .then(walletCommand("give"))
                .then(walletCommand("take"))
                .then(walletCommand("set"))
                .then(Commands.literal("freeze")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(Suggest.onlinePlayers())
                                .executes(context -> {
                                    freeze(context.getSource().getSender(),
                                            StringArgumentType.getString(context, "player"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("rollback")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .then(Commands.argument("reason",
                                                StringArgumentType.greedyString())
                                        .executes(context -> {
                                            rollback(context.getSource().getSender(),
                                                    LongArgumentType.getLong(context, "id"),
                                                    StringArgumentType.getString(context,
                                                            "reason"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("treasury")
                        .then(Commands.argument("city", StringArgumentType.word())
                                .suggests(this::suggestCities)
                                .then(Commands.argument("operation", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            builder.suggest("give");
                                            builder.suggest("take");
                                            builder.suggest("set");
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("amount",
                                                        StringArgumentType.word())
                                                .executes(context -> {
                                                    treasury(context.getSource().getSender(),
                                                            StringArgumentType.getString(context, "city"),
                                                            StringArgumentType.getString(context, "operation"),
                                                            StringArgumentType.getString(context, "amount"));
                                                    return Command.SINGLE_SUCCESS;
                                                })))))
                .then(supply())
                .then(sources())
                .then(top());
    }

    // ==================================================================================
    // SPEC 22.7.1's money supply reports, M14a
    // ==================================================================================

    /**
     * {@code /ca eco supply [days]} — SPEC 22.7.1's inflation dashboard.
     *
     * <p>"Money supply over time: created, destroyed, net, by ledger type." It is the instrument
     * SPEC 21.4 Class G exists for: "Without this you cannot detect an exploit you did not
     * predict." An unknown exploit still shows up as money appearing under some type faster than
     * it should, and this is where that is visible.
     */
    private LiteralArgumentBuilder<CommandSourceStack> supply() {
        return Commands.literal("supply")
                .executes(context -> {
                    supply(context.getSource().getSender(), 7);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("days",
                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 365))
                        .executes(context -> {
                            supply(context.getSource().getSender(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType
                                            .getInteger(context, "days"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private void supply(Audience audience, int days) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        then(current.moneySupply().supplyOver(days, System.currentTimeMillis()), audience,
                report -> {
                    lang.send(audience, "admin.supply.header",
                            Replies.p("days", String.valueOf(days)),
                            Replies.p("readings", String.valueOf(report.readings())));

                    report.now().ifPresentOrElse(now -> lang.send(audience, "admin.supply.now",
                                    Replies.p("total", money(current, now.circulation())),
                                    Replies.p("wallets", money(current, now.playerTotal())),
                                    Replies.p("treasuries", money(current, now.treasuryTotal())),
                                    Replies.p("escrow", money(current, now.escrowTotal()))),
                            () -> lang.send(audience, "admin.supply.no-readings"));

                    report.percentChange().ifPresent(percent -> lang.send(audience,
                            "admin.supply.change",
                            Replies.p("delta", money(current,
                                    report.change().orElse(BigDecimal.ZERO))),
                            Replies.p("percent", percent.toPlainString())));

                    lang.send(audience, "admin.supply.flows",
                            Replies.p("created", money(current, report.created())),
                            Replies.p("destroyed", money(current, report.destroyed())));

                    for (var flow : report.topSources(5)) {
                        lang.send(audience, "admin.supply.source",
                                Replies.p("type", flow.type()),
                                Replies.p("amount", money(current, flow.in())));
                    }
                    for (var flow : report.topSinks(5)) {
                        lang.send(audience, "admin.supply.sink",
                                Replies.p("type", flow.type()),
                                Replies.p("amount", money(current, flow.out())));
                    }
                });
    }

    /** {@code /ca eco sources <player> [days]} — SPEC 22.7.1: "where did this come from". */
    private LiteralArgumentBuilder<CommandSourceStack> sources() {
        return Commands.literal("sources")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Suggest.onlinePlayers())
                        .executes(context -> {
                            sources(context.getSource().getSender(),
                                    StringArgumentType.getString(context, "player"), 7);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("days",
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .integer(1, 365))
                                .executes(context -> {
                                    sources(context.getSource().getSender(),
                                            StringArgumentType.getString(context, "player"),
                                            com.mojang.brigadier.arguments.IntegerArgumentType
                                                    .getInteger(context, "days"));
                                    return Command.SINGLE_SUCCESS;
                                })));
    }

    private void sources(Audience audience, String name, int days) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        resolve(current, audience, name, found -> then(
                current.moneySupply().sourcesFor(found.uuid(), days, System.currentTimeMillis()),
                audience, flows -> {
                    lang.send(audience, "admin.supply.sources-header",
                            Replies.p("player", found.name()),
                            Replies.p("days", String.valueOf(days)));
                    if (flows.isEmpty()) {
                        lang.send(audience, "admin.supply.sources-none");
                        return;
                    }
                    for (var flow : flows) {
                        lang.send(audience, "admin.supply.source",
                                Replies.p("type", flow.type()),
                                Replies.p("amount", money(current, flow.in())));
                    }
                }));
    }

    /**
     * {@code /ca eco top [count]} — SPEC 22.7.1's wealth concentration view.
     *
     * <p>SPEC 22.1 says why it is worth a command of its own: "Wealth concentration is the first
     * thing to check for an exploit."
     */
    private LiteralArgumentBuilder<CommandSourceStack> top() {
        return Commands.literal("top")
                .executes(context -> {
                    top(context.getSource().getSender(), 10);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("count",
                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 50))
                        .executes(context -> {
                            top(context.getSource().getSender(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType
                                            .getInteger(context, "count"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private void top(Audience audience, int count) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        then(current.moneySupply().topPlayers(count), audience, players -> {
            lang.send(audience, "admin.supply.top-players");
            for (var holder : players) {
                lang.send(audience, "admin.supply.top-entry",
                        Replies.p("name", holder.name() == null ? "?" : holder.name()),
                        Replies.p("amount", money(current, holder.balance())),
                        Replies.p("percent", holder.percentOfCirculation().toPlainString()));
            }
            then(current.moneySupply().topCities(count), audience, cities -> {
                lang.send(audience, "admin.supply.top-cities");
                for (var holder : cities) {
                    lang.send(audience, "admin.supply.top-entry",
                            Replies.p("name", holder.name()),
                            Replies.p("amount", money(current, holder.balance())),
                            Replies.p("percent", holder.percentOfCirculation().toPlainString()));
                }
            });
        });
    }

    /**
     * Runs a read on the storage pool and prints its result on the server thread.
     *
     * <p>Every one of these reports is a plain query with no {@code Result} to unwrap, so
     * {@link Replies#reply} does not fit. A failure is logged and told to the admin rather than
     * swallowed: a report that silently prints nothing looks the same as a healthy economy.
     */
    private <T> void then(java.util.concurrent.CompletableFuture<T> future, Audience audience,
                          java.util.function.Consumer<T> print) {
        future.whenComplete((value, error) -> scheduler.runOnMain(() -> {
            if (error != null || value == null) {
                logger.log(java.util.logging.Level.WARNING, "Money supply report failed", error);
                lang.send(audience, "command.error");
                return;
            }
            print.accept(value);
        }));
    }

    /** The {@code market} branch, a sibling of {@code eco} under {@code /ca}. */
    public LiteralArgumentBuilder<CommandSourceStack> buildMarket() {
        return Commands.literal("market")
                .requires(source -> source.getSender().hasPermission("civitas.admin.economy"))
                .then(Commands.literal("setprice")
                        .then(Commands.argument("material", StringArgumentType.word())
                                .suggests(this::suggestMaterials)
                                .then(Commands.argument("base", StringArgumentType.word())
                                        .executes(context -> {
                                            setPrice(context.getSource().getSender(),
                                                    StringArgumentType.getString(context, "material"),
                                                    StringArgumentType.getString(context, "base"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("setstock")
                        .then(Commands.argument("material", StringArgumentType.word())
                                .suggests(this::suggestMaterials)
                                .then(Commands.argument("stock",
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .integer(0))
                                        .executes(context -> {
                                            setStock(context.getSource().getSender(),
                                                    StringArgumentType.getString(context, "material"),
                                                    com.mojang.brigadier.arguments
                                                            .IntegerArgumentType.getInteger(context, "stock"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("reload")
                        .executes(context -> {
                            reloadMarket(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    // ==================================================================================
    // Wallets
    // ==================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> walletCommand(String operation) {
        return Commands.literal(operation)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Suggest.onlinePlayers())
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(context -> wallet(context, operation, null))
                                .then(Commands.argument("reason",
                                                StringArgumentType.greedyString())
                                        .executes(context -> wallet(context, operation,
                                                StringArgumentType.getString(context,
                                                        "reason"))))));
    }

    private int wallet(CommandContext<CommandSourceStack> context, String operation,
                       String reason) {
        Audience audience = context.getSource().getSender();
        CivitasServices current = ready(audience);
        if (current == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (reason == null && current.fraud().strictReasons()) {
            // SPEC 9.4.4: "reason is mandatory in strict mode". An admin grant with no
            // explanation is exactly what SPEC 17.6 case 80's log exists to surface, and a
            // blank reason makes the log useless at the moment somebody asks about it.
            lang.send(audience, "admin.eco.reason-required");
            return Command.SINGLE_SUCCESS;
        }

        Result<BigDecimal> parsed = Money.parse(
                StringArgumentType.getString(context, "amount"));
        if (parsed instanceof Result.Failure<BigDecimal> failure) {
            Replies.sendFailure(audience, lang, failure);
            return Command.SINGLE_SUCCESS;
        }
        BigDecimal amount = parsed.orElseThrow();
        String name = StringArgumentType.getString(context, "player");

        resolve(current, audience, name, target -> {
            current.audit().record(actorOf(audience), "ECO_" + operation.toUpperCase(Locale.ROOT),
                    target.name(), reason, Map.of("amount", amount.toPlainString()));

            java.util.concurrent.CompletableFuture<Result<BigDecimal>> action = switch (operation) {
                case "give" -> current.economy().give(target.uuid(), amount,
                        TransactionType.ADMIN_GIVE, null, reason);
                case "take" -> current.economy().take(target.uuid(), amount,
                        TransactionType.ADMIN_TAKE, null, reason);
                default -> current.economy().setBalance(target.uuid(), amount,
                        TransactionType.ADMIN_SET, reason);
            };

            // Literal keys rather than "admin.eco." + operation, so LangKeyUsageTest can see
            // them: a key the scanner cannot find is a key nobody notices is missing until a
            // player triggers that exact branch.
            String key = switch (operation) {
                case "give" -> "admin.eco.give";
                case "take" -> "admin.eco.take";
                default -> "admin.eco.set";
            };
            Replies.reply(action, audience, lang, scheduler, logger,
                    balance -> lang.send(audience, key,
                            Replies.p("player", target.name()),
                            Replies.p("amount", money(current, amount)),
                            Replies.p("balance", money(current, balance))));
        });
        return Command.SINGLE_SUCCESS;
    }

    /** SPEC 9.4.4: "Player cannot send or receive money." */
    private void freeze(Audience audience, String name) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        resolve(current, audience, name, target -> {
            current.audit().record(actorOf(audience), "ECO_FREEZE", target.name(), null);
            Replies.reply(current.economy().toggleFrozen(target.uuid()),
                    audience, lang, scheduler, logger,
                    frozen -> lang.send(audience, frozen ? "admin.eco.frozen"
                                    : "admin.eco.unfrozen",
                            Replies.p("player", target.name())));
        });
    }

    /** SPEC 9.4.4's rollback, whose hard parts live in {@code LedgerRollback}. */
    private void rollback(Audience audience, long id, String reason) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.audit().record(actorOf(audience), "ECO_ROLLBACK", String.valueOf(id), reason);

        Replies.reply(current.ledgerRollback().reverse(actorOf(audience), id, reason),
                audience, lang, scheduler, logger,
                reversal -> {
                    lang.send(audience, "admin.eco.rolled-back",
                            Replies.p("id", String.valueOf(reversal.originalId())),
                            Replies.p("recovered", money(current, reversal.recovered())));
                    if (!reversal.isComplete()) {
                        // SPEC 17.3 case 35. The admin has to know the job is unfinished.
                        lang.send(audience, "admin.eco.rollback-debt",
                                Replies.p("debt", money(current, reversal.debt())));
                    }
                    if (reversal.downstream() > 0) {
                        lang.send(audience, "admin.eco.rollback-downstream",
                                Replies.p("count", String.valueOf(reversal.downstream())));
                    }
                });
    }

    /** SPEC 9.4.4: the same three operations against a city treasury. */
    private void treasury(Audience audience, String cityName, String operation, String rawAmount) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        Result<BigDecimal> parsed = Money.parse(rawAmount);
        if (parsed instanceof Result.Failure<BigDecimal> failure) {
            Replies.sendFailure(audience, lang, failure);
            return;
        }
        BigDecimal amount = parsed.orElseThrow();

        current.registry().cityByName(cityName).ifPresentOrElse(city -> {
            current.audit().record(actorOf(audience),
                    "ECO_TREASURY_" + operation.toUpperCase(Locale.ROOT), city.name(), null,
                    Map.of("amount", amount.toPlainString()));

            BigDecimal delta = switch (operation.toLowerCase(Locale.ROOT)) {
                case "give" -> amount;
                case "take" -> amount.negate();
                default -> amount.subtract(city.treasury());
            };

            Replies.reply(current.treasury().adminAdjust(city, delta),
                    audience, lang, scheduler, logger,
                    balance -> lang.send(audience, "admin.eco.treasury",
                            Replies.p("city", city.name()),
                            Replies.p("treasury", money(current, balance))));
        }, () -> lang.send(audience, "city.unknown"));
    }

    // ==================================================================================
    // Market
    // ==================================================================================

    private void setPrice(Audience audience, String material, String rawPrice) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        Result<BigDecimal> parsed = Money.parse(rawPrice);
        if (parsed instanceof Result.Failure<BigDecimal> failure) {
            Replies.sendFailure(audience, lang, failure);
            return;
        }
        String key = material.toUpperCase(Locale.ROOT);
        if (!current.market().registry().trades(key)) {
            lang.send(audience, "market.not-traded", Replies.p("item", key));
            return;
        }

        current.audit().record(actorOf(audience), "MARKET_SETPRICE", key, null,
                Map.of("base", parsed.orElseThrow().toPlainString()));
        current.market().registry().setBasePrice(key, parsed.orElseThrow());
        lang.send(audience, "admin.market.price-set",
                Replies.p("item", key),
                Replies.p("price", money(current, parsed.orElseThrow())));
    }

    private void setStock(Audience audience, String material, int stock) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        String key = material.toUpperCase(Locale.ROOT);
        if (!current.market().registry().trades(key)) {
            lang.send(audience, "market.not-traded", Replies.p("item", key));
            return;
        }

        current.audit().record(actorOf(audience), "MARKET_SETSTOCK", key, null,
                Map.of("stock", String.valueOf(stock)));
        current.market().registry().setStock(key, stock)
                .whenComplete((written, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(audience, "command.error");
                        return;
                    }
                    lang.send(audience, "admin.market.stock-set",
                            Replies.p("item", key),
                            Replies.p("stock", String.valueOf(stock)));
                }));
    }

    private void reloadMarket(Audience audience) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.audit().record(actorOf(audience), "MARKET_RELOAD", null, null);
        current.market().registry().loadAll()
                .whenComplete((loaded, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(audience, "command.error");
                        return;
                    }
                    lang.send(audience, "admin.market.reloaded",
                            Replies.p("count", String.valueOf(loaded)));
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

    private static java.util.UUID actorOf(Audience audience) {
        return audience instanceof Player player ? player.getUniqueId() : null;
    }

    private String money(CivitasServices current, BigDecimal amount) {
        return Money.format(amount, current.economy().configs());
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
            suggestMaterials(CommandContext<CommandSourceStack> context,
                             com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CivitasServices current = services.get();
        if (current != null) {
            String prefix = builder.getRemaining().toUpperCase(Locale.ROOT);
            current.market().registry().catalogue().stream()
                    .map(item -> item.material())
                    .filter(name -> name.startsWith(prefix))
                    .forEach(builder::suggest);
        }
        return builder.buildFuture();
    }
}
