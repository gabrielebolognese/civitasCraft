package dev.civitas.command.diplomacy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.diplomacy.Alliance;
import dev.civitas.core.diplomacy.AllianceState;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * {@code /ally} and {@code /truce}, SPEC 9.3.
 *
 * <p>Both trees live here because they are the same shape over the same service, and because
 * a player who types the wrong one of the two should still be told something useful.
 */
public final class AllyCommand {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public AllyCommand(Supplier<CivitasServices> services, LangManager lang,
                       Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // /ally
    // ==================================================================================

    public LiteralCommandNode<CommandSourceStack> buildAlly() {
        return Commands.literal("ally")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> list(context.getSource().getSender()))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource().getSender())))
                .then(Commands.literal("invite").then(city(this::invite)))
                .then(Commands.literal("accept").then(city(this::accept)))
                .then(Commands.literal("break").then(city(this::breakAlliance)))
                .then(Commands.literal("trust")
                        .then(Commands.argument("city", StringArgumentType.word())
                                .suggests(this::suggestCities)
                                .then(Commands.argument("on", BoolArgumentType.bool())
                                        .executes(context -> trust(
                                                context.getSource().getSender(),
                                                StringArgumentType.getString(context, "city"),
                                                BoolArgumentType.getBool(context, "on"))))))
                .build();
    }

    // ==================================================================================
    // /truce
    // ==================================================================================

    public LiteralCommandNode<CommandSourceStack> buildTruce() {
        return Commands.literal("truce")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> listTruces(context.getSource().getSender()))
                .then(Commands.literal("list")
                        .executes(context -> listTruces(context.getSource().getSender())))
                .then(Commands.literal("offer")
                        .then(Commands.argument("city", StringArgumentType.word())
                                .suggests(this::suggestCities)
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 30))
                                        .executes(context -> offerTruce(
                                                context.getSource().getSender(),
                                                StringArgumentType.getString(context, "city"),
                                                IntegerArgumentType.getInteger(context, "days"))))))
                .build();
    }

    // ==================================================================================
    // Actions
    // ==================================================================================

    private int invite(Audience audience, String cityName) {
        Pair pair = pairOf(audience, cityName);
        if (pair == null) {
            return Command.SINGLE_SUCCESS;
        }
        Replies.reply(services.get().diplomacy()
                        .invite(pair.player().getUniqueId(), pair.own(), pair.other()),
                pair.player(), lang, scheduler, logger,
                alliance -> {
                    lang.send(pair.player(), "diplomacy.invited",
                            LangManager.placeholder("city", pair.other().name()));
                    tellCity(pair.other(), "diplomacy.invite-received",
                            LangManager.placeholder("city", pair.own().name()));
                });
        return Command.SINGLE_SUCCESS;
    }

    private int accept(Audience audience, String cityName) {
        Pair pair = pairOf(audience, cityName);
        if (pair == null) {
            return Command.SINGLE_SUCCESS;
        }
        Replies.reply(services.get().diplomacy()
                        .accept(pair.player().getUniqueId(), pair.own(), pair.other()),
                pair.player(), lang, scheduler, logger,
                alliance -> {
                    tellCity(pair.own(), "diplomacy.allied",
                            LangManager.placeholder("city", pair.other().name()));
                    tellCity(pair.other(), "diplomacy.allied",
                            LangManager.placeholder("city", pair.own().name()));
                });
        return Command.SINGLE_SUCCESS;
    }

    private int breakAlliance(Audience audience, String cityName) {
        Pair pair = pairOf(audience, cityName);
        if (pair == null) {
            return Command.SINGLE_SUCCESS;
        }
        Replies.reply(services.get().diplomacy()
                        .breakAlliance(pair.player().getUniqueId(), pair.own(), pair.other()),
                pair.player(), lang, scheduler, logger,
                alliance -> {
                    long hours = services.get().diplomacy().noticeHours();
                    // Both cities are told, because SPEC 14.2's notice exists so the other
                    // side knows. Telling only the city that gave it would defeat the rule.
                    tellCity(pair.own(), "diplomacy.breaking",
                            LangManager.placeholder("city", pair.other().name()),
                            LangManager.placeholder("hours", String.valueOf(hours)));
                    tellCity(pair.other(), "diplomacy.breaking-received",
                            LangManager.placeholder("city", pair.own().name()),
                            LangManager.placeholder("hours", String.valueOf(hours)));
                });
        return Command.SINGLE_SUCCESS;
    }

    private int trust(Audience audience, String cityName, boolean on) {
        Pair pair = pairOf(audience, cityName);
        if (pair == null) {
            return Command.SINGLE_SUCCESS;
        }
        Replies.reply(services.get().diplomacy()
                        .setTrusted(pair.player().getUniqueId(), pair.own(), pair.other(), on),
                pair.player(), lang, scheduler, logger,
                alliance -> {
                    String key = on ? "diplomacy.trusted" : "diplomacy.untrusted";
                    tellCity(pair.own(), key,
                            LangManager.placeholder("city", pair.other().name()));
                    tellCity(pair.other(), key,
                            LangManager.placeholder("city", pair.own().name()));
                });
        return Command.SINGLE_SUCCESS;
    }

    private int offerTruce(Audience audience, String cityName, int days) {
        Pair pair = pairOf(audience, cityName);
        if (pair == null) {
            return Command.SINGLE_SUCCESS;
        }
        Replies.reply(services.get().diplomacy()
                        .offerTruce(pair.player().getUniqueId(), pair.own(), pair.other(), days),
                pair.player(), lang, scheduler, logger,
                expiresAt -> {
                    tellCity(pair.own(), "diplomacy.truce-agreed",
                            LangManager.placeholder("city", pair.other().name()),
                            LangManager.placeholder("when", WHEN.format(
                                    Instant.ofEpochMilli(expiresAt))));
                    tellCity(pair.other(), "diplomacy.truce-agreed",
                            LangManager.placeholder("city", pair.own().name()),
                            LangManager.placeholder("when", WHEN.format(
                                    Instant.ofEpochMilli(expiresAt))));
                });
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // Listing
    // ==================================================================================

    private int list(Audience audience) {
        Context context = contextOf(audience);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        var diplomacy = services.get().diplomacy();
        List<Alliance> mine = diplomacy.registry().allianceOf(context.city().id());

        lang.sendRaw(audience, "diplomacy.ally-list-header",
                LangManager.placeholder("count",
                        String.valueOf(diplomacy.registry().allyCount(context.city().id()))),
                LangManager.placeholder("max", String.valueOf(diplomacy.maxAllies())));

        boolean any = false;
        for (Alliance alliance : mine) {
            if (alliance.state() == AllianceState.BROKEN) {
                continue;
            }
            any = true;
            String otherName = services.get().registry()
                    .city(alliance.otherThan(context.city().id()))
                    .map(City::name).orElse("?");

            String key = switch (alliance.state()) {
                case PENDING -> alliance.proposedBy() == context.city().id()
                        ? "diplomacy.ally-list-sent"
                        : "diplomacy.ally-list-received";
                case BREAKING -> "diplomacy.ally-list-breaking";
                default -> alliance.trusted()
                        ? "diplomacy.ally-list-trusted"
                        : "diplomacy.ally-list-entry";
            };
            lang.sendRaw(audience, key,
                    LangManager.placeholder("city", otherName),
                    LangManager.placeholder("hours",
                            String.valueOf(diplomacy.hoursLeftOfNotice(alliance))));
        }
        if (!any) {
            lang.sendRaw(audience, "diplomacy.ally-list-empty");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int listTruces(Audience audience) {
        Context context = contextOf(audience);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        long now = System.currentTimeMillis();
        var truces = services.get().diplomacy().registry()
                .trucesOf(context.city().id(), now);

        lang.sendRaw(audience, "diplomacy.truce-list-header");
        if (truces.isEmpty()) {
            lang.sendRaw(audience, "diplomacy.truce-list-empty");
            return Command.SINGLE_SUCCESS;
        }
        for (var truce : truces) {
            lang.sendRaw(audience, "diplomacy.truce-list-entry",
                    LangManager.placeholder("city", services.get().registry()
                            .city(truce.otherCityId()).map(City::name).orElse("?")),
                    LangManager.placeholder("when",
                            WHEN.format(Instant.ofEpochMilli(truce.expiresAt()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // Resolving
    // ==================================================================================

    private ArgumentBuilder<CommandSourceStack, ?> city(
            java.util.function.BiFunction<Audience, String, Integer> handler) {
        return Commands.argument("city", StringArgumentType.word())
                .suggests(this::suggestCities)
                .executes(context -> handler.apply(context.getSource().getSender(),
                        StringArgumentType.getString(context, "city")));
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestCities(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                          com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CivitasServices current = services.get();
        if (current != null) {
            String typed = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            current.registry().cities().stream()
                    .map(City::name)
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(typed))
                    .forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    private record Context(Player player, City city) { }

    private record Pair(Player player, City own, City other) { }

    private Context contextOf(Audience audience) {
        if (services.get() == null) {
            lang.send(audience, "plugin.starting");
            return null;
        }
        if (!(audience instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return null;
        }
        Optional<City> city = services.get().registry().cityOf(player.getUniqueId());
        if (city.isEmpty()) {
            lang.send(player, "city.none");
            return null;
        }
        return new Context(player, city.get());
    }

    private Pair pairOf(Audience audience, String cityName) {
        Context context = contextOf(audience);
        if (context == null) {
            return null;
        }
        Optional<City> other = services.get().registry().cityByName(cityName);
        if (other.isEmpty()) {
            lang.send(context.player(), "city.unknown");
            return null;
        }
        return new Pair(context.player(), context.city(), other.get());
    }

    /** Tells everyone online in a city. */
    private void tellCity(City city, String key,
                          net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
        for (var member : city.members()) {
            Player online = org.bukkit.Bukkit.getPlayer(member.uuid());
            if (online != null) {
                lang.send(online, key, extra);
            }
        }
    }
}
