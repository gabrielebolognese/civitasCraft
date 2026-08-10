package dev.civitas.command.war;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.economy.Money;
import dev.civitas.core.war.War;
import dev.civitas.core.war.WarState;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * {@code /war}, SPEC 9.3.
 *
 * <p>Six subcommands over the war system: declare one, see one, join an ally's, offer or accept
 * peace, and read the history. Every one of them is a thin shell over a service that does the
 * checking, because SPEC 2.3 puts validation in the service layer and a command that decided
 * anything itself would be a second place for the rules to live.
 */
public final class WarCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public WarCommand(Supplier<CivitasServices> services, LangManager lang, Scheduler scheduler,
                      Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("war")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> {
                    status(context.getSource().getSender(), null);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("declare")
                        .then(Commands.argument("city", StringArgumentType.word())
                                .suggests(this::suggestCities)
                                .then(Commands.argument("wager", StringArgumentType.word())
                                        .executes(context -> {
                                            declare(context.getSource().getSender(),
                                                    StringArgumentType.getString(context, "city"),
                                                    StringArgumentType.getString(context, "wager"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("status")
                        .executes(context -> {
                            status(context.getSource().getSender(), null);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("city", StringArgumentType.word())
                                .suggests(this::suggestCities)
                                .executes(context -> {
                                    status(context.getSource().getSender(),
                                            StringArgumentType.getString(context, "city"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("decline")
                        .executes(context -> {
                            decline(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("join")
                        .then(Commands.argument("war", IntegerArgumentType.integer(1))
                                .then(Commands.argument("side", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            builder.suggest("attacker");
                                            builder.suggest("defender");
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            join(context.getSource().getSender(),
                                                    IntegerArgumentType.getInteger(context, "war"),
                                                    StringArgumentType.getString(context, "side"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("peace")
                        .executes(context -> {
                            peace(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("accept")
                        .executes(context -> {
                            acceptPeace(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(new SiegeCommands(services, lang, scheduler, logger).build())
                .then(Commands.literal("scoreboard")
                        .executes(context -> {
                            scoreboard(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("history")
                        .executes(context -> {
                            history(context.getSource().getSender(), null);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("city", StringArgumentType.word())
                                .suggests(this::suggestCities)
                                .executes(context -> {
                                    history(context.getSource().getSender(),
                                            StringArgumentType.getString(context, "city"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .build();
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestCities(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                          com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CivitasServices current = services.get();
        if (current != null) {
            String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (City city : current.registry().cities()) {
                if (city.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    builder.suggest(city.name());
                }
            }
        }
        return builder.buildFuture();
    }

    // ==================================================================================
    // Declaring, SPEC 11.3
    // ==================================================================================

    private void declare(Audience audience, String targetName, String rawWager) {
        Player player = playerOrNull(audience);
        if (player == null) {
            return;
        }
        City attacker = cityOrNull(player);
        if (attacker == null) {
            return;
        }
        Optional<City> defender = services.get().registry().cityByName(targetName);
        if (defender.isEmpty()) {
            lang.send(player, "city.unknown");
            return;
        }
        Result<BigDecimal> wager = Money.parse(rawWager);
        if (wager instanceof Result.Failure<BigDecimal> failure) {
            Replies.sendFailure(player, lang, failure);
            return;
        }

        Replies.reply(services.get().wars().declare(player.getUniqueId(), attacker,
                        defender.get(), wager.orElseThrow(), System.currentTimeMillis()),
                player, lang, scheduler, logger,
                war -> lang.send(player, "war.declared",
                        Replies.p("city", defender.get().name()),
                        Replies.p("wager", wager.orElseThrow().toPlainString()),
                        Replies.p("hours", String.valueOf(services.get().wars().prepHours()))));
    }

    private void decline(Audience audience) {
        Player player = playerOrNull(audience);
        if (player == null) {
            return;
        }
        City city = cityOrNull(player);
        if (city == null) {
            return;
        }
        Optional<War> war = services.get().wars().registry().engagedWarOf(city.id());
        if (war.isEmpty()) {
            lang.send(player, "war.none");
            return;
        }

        Replies.reply(services.get().wars().decline(player.getUniqueId(), war.get(),
                        System.currentTimeMillis()),
                player, lang, scheduler, logger,
                declined -> lang.send(player, "war.declined"));
    }

    // ==================================================================================
    // Looking at one
    // ==================================================================================

    private void status(Audience audience, String cityName) {
        CivitasServices current = servicesOrNull(audience);
        if (current == null) {
            return;
        }

        Optional<City> city = cityName == null
                ? (audience instanceof Player player
                        ? current.registry().cityOf(player.getUniqueId())
                        : Optional.empty())
                : current.registry().cityByName(cityName);
        if (city.isEmpty()) {
            lang.send(audience, cityName == null ? "city.none" : "city.unknown");
            return;
        }

        Optional<War> war = current.wars().registry().engagedWarOf(city.get().id());
        if (war.isEmpty()) {
            lang.send(audience, "war.at-peace", Replies.p("city", city.get().name()));
            return;
        }

        War active = war.get();
        long now = System.currentTimeMillis();
        lang.sendRaw(audience, "war.status",
                Replies.p("attacker", nameOf(current, active.attackerCityId())),
                Replies.p("defender", nameOf(current, active.defenderCityId())),
                Replies.p("state", plain(active.state().messageKey())),
                Replies.p("attacker-score", String.valueOf(active.attackerScore())),
                Replies.p("defender-score", String.valueOf(active.defenderScore())),
                Replies.p("remaining", describe(active.millisUntilNextPhase(now))));
    }

    private void history(Audience audience, String cityName) {
        CivitasServices current = servicesOrNull(audience);
        if (current == null) {
            return;
        }
        Optional<City> city = cityName == null
                ? (audience instanceof Player player
                        ? current.registry().cityOf(player.getUniqueId())
                        : Optional.empty())
                : current.registry().cityByName(cityName);
        if (city.isEmpty()) {
            lang.send(audience, cityName == null ? "city.none" : "city.unknown");
            return;
        }

        current.wars().historyOf(city.get().id(), 10)
                .whenComplete((rows, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(audience, "command.error");
                        return;
                    }
                    if (rows.isEmpty()) {
                        lang.send(audience, "war.history.empty",
                                Replies.p("city", city.get().name()));
                        return;
                    }
                    lang.sendRaw(audience, "war.history.header",
                            Replies.p("city", city.get().name()));
                    for (var row : rows) {
                        String result = row.winnerCityId() == null ? "-"
                                : nameOf(current, row.winnerCityId());
                        lang.sendRaw(audience, "war.history.entry",
                                Replies.p("attacker", nameOf(current, row.attackerCityId())),
                                Replies.p("defender", nameOf(current, row.defenderCityId())),
                                Replies.p("winner", result),
                                Replies.p("state", row.state()));
                    }
                }));
    }

    /**
     * SPEC 9.3's {@code /war scoreboard}, a toggle rather than something imposed.
     *
     * <p>Turning it on for a player with no war to watch is allowed: it takes effect the
     * moment their city is in one, which is less surprising than being told no and having to
     * remember to ask again later.
     */
    private void scoreboard(Audience audience) {
        Player player = playerOrNull(audience);
        if (player == null) {
            return;
        }
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(audience, "plugin.starting");
            return;
        }
        boolean on = current.warScoreboard().toggle(player);
        lang.send(player, on ? "war.board.on" : "war.board.off");
    }

    // ==================================================================================
    // Allies and peace
    // ==================================================================================

    private void join(Audience audience, int warId, String side) {
        Player player = playerOrNull(audience);
        if (player == null) {
            return;
        }
        City ally = cityOrNull(player);
        if (ally == null) {
            return;
        }
        Optional<War> war = services.get().wars().registry().war(warId);
        if (war.isEmpty()) {
            lang.send(player, "war.unknown");
            return;
        }
        boolean attackerSide = side.toLowerCase(Locale.ROOT).startsWith("a");

        Replies.reply(services.get().warAllies().join(player.getUniqueId(), ally, war.get(),
                        attackerSide, System.currentTimeMillis()),
                player, lang, scheduler, logger,
                joined -> lang.send(player, "war.joined",
                        Replies.p("side", attackerSide ? "attacker" : "defender")));
    }

    private void peace(Audience audience) {
        Player player = playerOrNull(audience);
        if (player == null) {
            return;
        }
        City city = cityOrNull(player);
        if (city == null) {
            return;
        }
        Optional<War> war = services.get().wars().registry().engagedWarOf(city.id());
        if (war.isEmpty()) {
            lang.send(player, "war.none");
            return;
        }

        Result<Void> offered = services.get().peaceOffers()
                .offer(player.getUniqueId(), city, war.get());
        if (offered instanceof Result.Failure<Void> failure) {
            Replies.sendFailure(player, lang, failure);
            return;
        }
        lang.send(player, "war.peace.offered",
                Replies.p("forfeit", services.get().peaceOffers()
                        .forfeitOf(war.get()).toPlainString()));
    }

    private void acceptPeace(Audience audience) {
        Player player = playerOrNull(audience);
        if (player == null) {
            return;
        }
        City city = cityOrNull(player);
        if (city == null) {
            return;
        }
        Optional<War> war = services.get().wars().registry().engagedWarOf(city.id());
        if (war.isEmpty()) {
            lang.send(player, "war.none");
            return;
        }

        Replies.reply(services.get().peaceOffers().accept(player.getUniqueId(), city, war.get(),
                        System.currentTimeMillis()),
                player, lang, scheduler, logger,
                ended -> lang.send(player, "war.peace.agreed"));
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private String nameOf(CivitasServices current, int cityId) {
        return current.registry().city(cityId).map(City::name).orElse("?");
    }

    private String describe(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        if (hours >= 24) {
            return TimeUnit.MILLISECONDS.toDays(millis) + "d";
        }
        if (hours >= 1) {
            return hours + "h";
        }
        return Math.max(1, TimeUnit.MILLISECONDS.toMinutes(millis)) + "m";
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(lang.get(key));
    }

    private CivitasServices servicesOrNull(Audience audience) {
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(audience, "plugin.starting");
        }
        return current;
    }

    private Player playerOrNull(Audience audience) {
        if (servicesOrNull(audience) == null) {
            return null;
        }
        if (!(audience instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return null;
        }
        return player;
    }

    private City cityOrNull(Player player) {
        Optional<City> city = services.get().registry().cityOf(player.getUniqueId());
        if (city.isEmpty()) {
            lang.send(player, "city.none");
            return null;
        }
        return city.get();
    }
}
