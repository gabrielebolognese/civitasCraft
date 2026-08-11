package dev.civitas.command.player;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.season.Season;
import dev.civitas.core.season.SeasonService;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;

/**
 * {@code /season}, SPEC 35.5.
 *
 * <p>Every screen here repeats SPEC 35.2's one non-negotiable line, because SPEC says to: "That
 * distinction must be stated explicitly and repeatedly in-game, because 'season' on most servers
 * means 'your stuff is deleted' and players will assume the worst."
 */
public final class SeasonCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public SeasonCommand(Supplier<CivitasServices> services, LangManager lang, Scheduler scheduler,
                         Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("season")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> {
                    status(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("history")
                        .executes(context -> {
                            history(context.getSource().getSender(), 5);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 25))
                                .executes(context -> {
                                    history(context.getSource().getSender(),
                                            IntegerArgumentType.getInteger(context, "count"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("rewards")
                        .executes(context -> {
                            rewards(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void status(Audience audience) {
        SeasonService seasons = seasonsOrNull(audience);
        if (seasons == null) {
            return;
        }
        long now = System.currentTimeMillis();
        seasons.current().ifPresentOrElse(season -> {
            lang.send(audience, "season.header",
                    Replies.p("name", season.name()),
                    Replies.p("theme", season.theme()),
                    Replies.p("day", String.valueOf(season.dayOf(now))),
                    Replies.p("length", String.valueOf(seasons.lengthDays())));
            lang.send(audience, "season.remaining",
                    Replies.p("days", String.valueOf(
                            season.millisRemaining(now) / (24L * 60 * 60 * 1000))));
            // SPEC 35.2's line, on every screen that mentions a season.
            lang.send(audience, "season.not-a-wipe");

            seasons.standingsFor(season).whenComplete((standings, error) ->
                    scheduler.runOnMain(() -> {
                        if (error != null || standings == null) {
                            logger.log(Level.WARNING, "Season standings failed", error);
                            return;
                        }
                        standings.byBoard().forEach((board, entries) -> {
                            if (entries.isEmpty()) {
                                return;
                            }
                            lang.send(audience, "season.board",
                                    Replies.p("board", lang.plain(board.nameKey())),
                                    Replies.p("leader", entries.get(0).name()),
                                    Replies.p("value", entries.get(0).value().toPlainString()));
                        });
                    }));
        }, () -> lang.send(audience, "season.none-running"));
    }

    private void history(Audience audience, int count) {
        SeasonService seasons = seasonsOrNull(audience);
        if (seasons == null) {
            return;
        }
        seasons.history(count).whenComplete((past, error) -> scheduler.runOnMain(() -> {
            if (error != null || past == null) {
                lang.send(audience, "command.error");
                return;
            }
            if (past.isEmpty()) {
                lang.send(audience, "season.history-empty");
                return;
            }
            lang.send(audience, "season.history-header");
            for (Season season : past) {
                lang.send(audience, "season.history-entry",
                        Replies.p("name", season.name()),
                        Replies.p("theme", season.theme()));
            }
        }));
    }

    private void rewards(Audience audience) {
        SeasonService seasons = seasonsOrNull(audience);
        if (seasons == null) {
            return;
        }
        lang.send(audience, "season.rewards-header");
        lang.send(audience, "season.rewards-first");
        lang.send(audience, "season.rewards-top3");
        lang.send(audience, "season.rewards-top10");
        lang.send(audience, "season.rewards-participation");
        lang.send(audience, "season.rewards-cap",
                Replies.p("amount", seasons.maxCurrencyPrize().toPlainString()));
        lang.send(audience, "season.not-a-wipe");
    }

    private SeasonService seasonsOrNull(Audience audience) {
        CivitasServices current = services.get();
        if (current == null || current.seasons() == null) {
            lang.send(audience, "plugin.starting");
            return null;
        }
        if (!current.seasons().enabled()) {
            lang.send(audience, "season.disabled");
            return null;
        }
        return current.seasons();
    }
}
