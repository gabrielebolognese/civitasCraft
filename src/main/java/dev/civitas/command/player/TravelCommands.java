package dev.civitas.command.player;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.travel.RandomTeleport;
import dev.civitas.core.travel.TravelKind;
import dev.civitas.core.travel.WarpService;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.msg.Formats;
import dev.civitas.storage.row.WarpRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code /spawn}, {@code /rtp} and {@code /warp}, SPEC 32.7.
 *
 * <p>Three commands in one class because they are the same shape: resolve a destination, hand it
 * to {@code TeleportService}, and let that enforce the cooldown, the warmup and the fare. Only
 * {@code /rtp} does anything interesting, and what it does is described in
 * {@code RandomTeleport}.
 */
public final class TravelCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;

    public TravelCommands(Supplier<CivitasServices> services, LangManager lang,
                          Scheduler scheduler) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    // ==================================================================================
    // /spawn
    // ==================================================================================

    public LiteralCommandNode<CommandSourceStack> buildSpawn() {
        return Commands.literal("spawn")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> withPlayer(context, player -> {
                    World main = mainWorld();
                    if (main == null) {
                        lang.send(player, "travel.spawn-missing");
                        return;
                    }
                    begin(player, TravelKind.SPAWN, main.getSpawnLocation());
                }))
                .build();
    }

    // ==================================================================================
    // /rtp
    // ==================================================================================

    public LiteralCommandNode<CommandSourceStack> buildRtp() {
        return Commands.literal("rtp")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> withPlayer(context, player ->
                        randomTeleport(player, TravelKind.RTP, mainWorld())))
                .then(Commands.literal("resource")
                        .executes(context -> withPlayer(context, player -> randomTeleport(
                                player, TravelKind.RTP_RESOURCE,
                                world("worlds.resource", "resource")))))
                .then(Commands.literal("nether")
                        .executes(context -> withPlayer(context, player -> randomTeleport(
                                player, TravelKind.RTP_RESOURCE,
                                world("worlds.resource-nether", "resource_nether")))))
                .build();
    }

    /**
     * SPEC 32.4's search, then the ordinary warmup.
     *
     * <p>The search runs before the warmup rather than during it, so a player is never told
     * "travelling in five seconds" and then that there was nowhere to go. Nothing is charged
     * until they arrive, which is what makes the SPEC 32.4 failure case a refund by
     * construction: there was never a payment to reverse.
     */
    private void randomTeleport(Player player, TravelKind kind, World world) {
        if (world == null) {
            lang.send(player, "travel.rtp-world-missing");
            return;
        }
        CivitasServices ready = services.get();
        // The cheap refusals first, so a player on cooldown is not made to wait for a search
        // whose result will be thrown away.
        long remaining = ready.teleports().cooldownRemaining(player.getUniqueId(), kind);
        if (remaining > 0) {
            lang.send(player, "travel.cooldown",
                    Replies.p("time", Formats.duration(remaining)),
                    Replies.p("destination", lang.plain(kind.messageKey())));
            return;
        }

        RandomTeleport rtp = ready.randomTeleport();
        int radius = kind == TravelKind.RTP ? rtp.maxRadius() : rtp.resourceMaxRadius();
        lang.send(player, "travel.rtp-searching");

        rtp.find(world, radius,
                        RandomTeleport.positionsOf(player.getServer().getOnlinePlayers(), player))
                .whenComplete((found, error) -> scheduler.runOnMain(() -> {
                    if (error != null || found == null) {
                        lang.send(player, "command.error");
                        return;
                    }
                    if (found.isEmpty()) {
                        lang.send(player, "travel.rtp-failed",
                                Replies.p("attempts", String.valueOf(rtp.maxAttempts())));
                        return;
                    }
                    Location at = found.get();
                    begin(player, kind, at);
                    lang.send(player, "travel.rtp-arrived", Replies.p("distance",
                            Formats.count(Math.round(Math.hypot(at.getX(), at.getZ())))));
                }));
    }

    // ==================================================================================
    // /warp
    // ==================================================================================

    public LiteralCommandNode<CommandSourceStack> buildWarp() {
        return Commands.literal("warp")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> {
                    lang.send(context.getSource().getSender(), "warp.usage");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("list")
                        .executes(context -> withPlayer(context, this::listWarps)))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            CivitasServices ready = services.get();
                            if (ready != null) {
                                ready.warps().names(System.currentTimeMillis()).stream()
                                        .filter(name -> name.toLowerCase(java.util.Locale.ROOT)
                                                .startsWith(builder.getRemaining()
                                                        .toLowerCase(java.util.Locale.ROOT)))
                                        .forEach(builder::suggest);
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> withPlayer(context, player -> warpTo(player,
                                StringArgumentType.getString(context, "name")))))
                .build();
    }

    private void listWarps(Player player) {
        List<WarpRow> warps = services.get().warps().all(System.currentTimeMillis());
        if (warps.isEmpty()) {
            lang.send(player, "warp.none");
            return;
        }
        lang.sendRaw(player, "warp.header");
        for (WarpRow warp : warps) {
            lang.sendRaw(player, "warp.entry",
                    Replies.p("name", warp.name()),
                    Replies.p("world", warp.world()),
                    Replies.p("x", String.valueOf((int) warp.x())),
                    Replies.p("z", String.valueOf((int) warp.z())));
        }
    }

    private void warpTo(Player player, String name) {
        WarpService warps = services.get().warps();
        Optional<WarpRow> warp = warps.find(name, System.currentTimeMillis());
        if (warp.isEmpty()) {
            lang.send(player, "warp.unknown", Replies.p("name", name));
            return;
        }
        World world = player.getServer().getWorld(warp.get().world());
        if (world == null) {
            lang.send(player, "travel.rtp-world-missing");
            return;
        }
        begin(player, TravelKind.WARP, new Location(world, warp.get().x(), warp.get().y(),
                warp.get().z(), warp.get().yaw(), warp.get().pitch()));
    }

    // ==================================================================================
    // Plumbing
    // ==================================================================================

    private void begin(Player player, TravelKind kind, Location destination) {
        Result<Long> result = services.get().teleports().begin(player, kind, destination);
        if (result instanceof Result.Failure<Long> failure) {
            Replies.sendFailure(player, lang, failure);
        }
    }

    private World mainWorld() {
        return world("worlds.main", "world");
    }

    private World world(String key, String fallback) {
        return org.bukkit.Bukkit.getWorld(services.get().economy().configs()
                .get(dev.civitas.config.ConfigFile.WORLD).getString(key, fallback));
    }

    private int withPlayer(CommandContext<CommandSourceStack> context,
                           java.util.function.Consumer<Player> action) {
        Audience audience = context.getSource().getSender();
        if (services.get() == null) {
            lang.send(audience, "plugin.starting");
            return Command.SINGLE_SUCCESS;
        }
        if (!(context.getSource().getSender() instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
