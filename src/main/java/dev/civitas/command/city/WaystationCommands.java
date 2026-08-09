package dev.civitas.command.city;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.economy.Money;
import dev.civitas.core.travel.TravelKind;
import dev.civitas.core.waystation.Waystation;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * {@code /city waystation}, SPEC 39.11.
 *
 * <p>Five subcommands, and the argument they take is a <b>world</b> rather than a name. SPEC
 * 39.10 allows one per city per resource world, so the world identifies it unambiguously and a
 * name would be a label a player has to invent and then remember in order to use.
 *
 * <p>{@code create}, {@code claim} and {@code delete} take no argument at all: those act on
 * where the player is standing, which is necessarily one resource world.
 */
public final class WaystationCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public WaystationCommands(Supplier<CivitasServices> services, LangManager lang,
                              Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public ArgumentBuilder<CommandSourceStack, ?> build() {
        return Commands.literal("waystation")
                .executes(context -> list(context.getSource().getSender()))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource().getSender())))
                .then(Commands.literal("create").executes(this::create))
                .then(Commands.literal("claim").executes(this::claim))
                .then(Commands.literal("delete").executes(this::delete))
                .then(Commands.literal("tp")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(heldWorlds())
                                .executes(this::teleport)));
    }

    /**
     * Completes against the worlds the sender's city actually has a waystation in.
     *
     * <p>Not against every resource world: SPEC 22.8 calls suggesting values that cannot work
     * "hostile", and a city with one waystation has exactly one valid answer here.
     */
    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> heldWorlds() {
        return (context, builder) -> {
            CivitasServices current = services.get();
            if (current != null && context.getSource().getSender() instanceof Player player) {
                current.registry().cityOf(player.getUniqueId()).ifPresent(city ->
                        current.waystations().registry().of(city.id()).stream()
                                .map(Waystation::world)
                                .filter(world -> world.toLowerCase(java.util.Locale.ROOT)
                                        .startsWith(builder.getRemaining()
                                                .toLowerCase(java.util.Locale.ROOT)))
                                .forEach(builder::suggest));
            }
            return builder.buildFuture();
        };
    }

    // ==================================================================================
    // The subcommands
    // ==================================================================================

    private int list(Audience audience) {
        Context context = contextOf(audience);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        var service = services.get().waystations();
        List<Waystation> held = service.registry().of(context.city().id());

        if (held.isEmpty()) {
            lang.send(context.player(), "waystation.list-empty");
            return Command.SINGLE_SUCCESS;
        }

        lang.sendRaw(context.player(), "waystation.list-header",
                LangManager.placeholder("count", String.valueOf(held.size())));
        for (Waystation waystation : held) {
            lang.sendRaw(context.player(), "waystation.list-entry",
                    LangManager.placeholder("world", waystation.world()),
                    LangManager.placeholder("chunks",
                            String.valueOf(service.registry().chunkCount(waystation.id()))),
                    LangManager.placeholder("max", String.valueOf(service.costs().maxChunks())),
                    LangManager.placeholder("upkeep",
                            money(service.upkeepFor(waystation))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int create(CommandContext<CommandSourceStack> command) {
        Context context = contextOf(command.getSource().getSender());
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = context.player();
        Location at = player.getLocation();
        String world = at.getWorld().getName();

        Replies.reply(services.get().waystations().create(player.getUniqueId(), context.city(),
                        world, at.getBlockX() >> 4, at.getBlockZ() >> 4,
                        at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()),
                player, lang, scheduler, logger,
                waystation -> lang.send(player, "waystation.created",
                        LangManager.placeholder("world", waystation.world()),
                        LangManager.placeholder("upkeep", money(
                                services.get().waystations().upkeepFor(waystation)))));
        return Command.SINGLE_SUCCESS;
    }

    private int claim(CommandContext<CommandSourceStack> command) {
        Context context = contextOf(command.getSource().getSender());
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = context.player();
        Location at = player.getLocation();

        Replies.reply(services.get().waystations().expand(player.getUniqueId(), context.city(),
                        at.getWorld().getName(), at.getBlockX() >> 4, at.getBlockZ() >> 4),
                player, lang, scheduler, logger,
                chunk -> lang.send(player, "waystation.chunk-claimed",
                        LangManager.placeholder("world", chunk.world()),
                        LangManager.placeholder("chunks", String.valueOf(
                                services.get().waystations().registry()
                                        .chunkCount(chunk.waystationId()))),
                        LangManager.placeholder("max", String.valueOf(
                                services.get().waystations().costs().maxChunks()))));
        return Command.SINGLE_SUCCESS;
    }

    private int delete(CommandContext<CommandSourceStack> command) {
        Context context = contextOf(command.getSource().getSender());
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = context.player();

        Replies.reply(services.get().waystations().delete(player.getUniqueId(), context.city(),
                        player.getLocation().getWorld().getName()),
                player, lang, scheduler, logger,
                waystation -> lang.send(player, "waystation.deleted",
                        LangManager.placeholder("world", waystation.world())));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /city waystation tp <world>}, SPEC 39.11.
     *
     * <p>Goes through {@link dev.civitas.core.travel.TeleportService} like every other
     * destination in SPEC 32.7, so the warmup, the cooldown, cancellation on movement or damage
     * and SPEC 33.8's combat-tag block are the same code rather than a sixth copy of them.
     */
    private int teleport(CommandContext<CommandSourceStack> command) {
        Context context = contextOf(command.getSource().getSender());
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = context.player();
        String world = StringArgumentType.getString(command, "world");

        if (!context.city().hasPermission(player.getUniqueId(), CityPermission.OUTPOST_TP)) {
            lang.send(player, "city.no-permission", LangManager.placeholder(
                    "permission", CityPermission.OUTPOST_TP.name()));
            return Command.SINGLE_SUCCESS;
        }

        Optional<Waystation> waystation =
                services.get().waystations().registry().of(context.city().id(), world);
        if (waystation.isEmpty()) {
            lang.send(player, "waystation.none-here", LangManager.placeholder("world", world));
            return Command.SINGLE_SUCCESS;
        }

        Location destination = destinationOf(waystation.get());
        if (destination == null) {
            lang.send(player, "waystation.world-missing");
            return Command.SINGLE_SUCCESS;
        }

        Result<Long> started = services.get().teleports().begin(player,
                TravelKind.WAYSTATION_TP, destination,
                services.get().waystations().costs().teleportCost(),
                lang.plain("waystation.destination-label"));
        if (started instanceof Result.Failure<Long> failure) {
            Replies.sendFailure(player, lang, failure);
        }
        return Command.SINGLE_SUCCESS;
    }

    private Location destinationOf(Waystation waystation) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(waystation.world());
        return world == null ? null : new Location(world, waystation.warpX(), waystation.warpY(),
                waystation.warpZ(), waystation.warpYaw(), waystation.warpPitch());
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private String money(java.math.BigDecimal amount) {
        return Money.format(amount, services.get().economy().configs());
    }

    private record Context(Player player, City city) { }

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
}
