package dev.civitas.command.player;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.command.Suggest;
import dev.civitas.core.economy.Money;
import dev.civitas.core.mining.MiningClaimService;
import dev.civitas.core.travel.TravelKind;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.msg.Formats;
import dev.civitas.storage.row.MiningClaimRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * {@code /mine}, SPEC 32.6.
 *
 * <p>{@code claim}, {@code unclaim}, {@code info}, {@code trust}, {@code untrust} and {@code tp},
 * which is the list SPEC 32.6 gives. The teleport goes through {@code TeleportService} like every
 * other destination in SPEC 32.7's table.
 */
public final class MineCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;

    public MineCommand(Supplier<CivitasServices> services, LangManager lang,
                       Scheduler scheduler) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("mine")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> {
                    lang.send(context.getSource().getSender(), "mine.usage");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("claim")
                        .executes(context -> withPlayer(context, this::claim)))
                .then(Commands.literal("unclaim")
                        .executes(context -> withPlayer(context, this::unclaim)))
                .then(Commands.literal("info")
                        .executes(context -> withPlayer(context, this::info)))
                .then(Commands.literal("tp")
                        .executes(context -> withPlayer(context, this::teleport)))
                .then(Commands.literal("trust")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(Suggest.onlinePlayers())
                                .executes(context -> withPlayer(context, player -> trust(player,
                                        StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("untrust")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(Suggest.onlinePlayers())
                                .executes(context -> withPlayer(context, player -> untrust(player,
                                        StringArgumentType.getString(context, "player"))))))
                .build();
    }

    // ==================================================================================
    // Claiming
    // ==================================================================================

    private void claim(Player player) {
        MiningClaimService mines = services.get().miningClaims();
        var chunk = player.getLocation().getChunk();

        mines.claim(player.getUniqueId(), player.getWorld().getName(), chunk.getX(),
                        chunk.getZ(), limitFor(player), System.currentTimeMillis())
                .whenComplete((result, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(player, "command.error");
                        return;
                    }
                    if (result instanceof Result.Failure<MiningClaimRow> failure) {
                        Replies.sendFailure(player, lang, failure);
                        return;
                    }
                    MiningClaimRow row = result.orElseThrow();
                    lang.send(player, "mine.claimed",
                            Replies.p("chunk", Formats.chunk(row.chunkX(), row.chunkZ())),
                            Replies.p("cost", money(mines.cost())),
                            Replies.p("upkeep", money(mines.upkeepPerDay())));
                }));
    }

    private void unclaim(Player player) {
        MiningClaimService mines = services.get().miningClaims();
        var chunk = player.getLocation().getChunk();

        mines.unclaim(player.getUniqueId(), player.getWorld().getName(), chunk.getX(),
                        chunk.getZ())
                .whenComplete((result, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(player, "command.error");
                        return;
                    }
                    if (result instanceof Result.Failure<MiningClaimRow> failure) {
                        Replies.sendFailure(player, lang, failure);
                        return;
                    }
                    MiningClaimRow row = result.orElseThrow();
                    lang.send(player, "mine.unclaimed",
                            Replies.p("chunk", Formats.chunk(row.chunkX(), row.chunkZ())),
                            Replies.p("refund", money(row.costPaid()
                                    .multiply(java.math.BigDecimal.valueOf(
                                            mines.refundPercent()))
                                    .divide(java.math.BigDecimal.valueOf(100),
                                            java.math.RoundingMode.DOWN))));
                }));
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    private void info(Player player) {
        MiningClaimService mines = services.get().miningClaims();
        var owned = mines.registry().ownedBy(player.getUniqueId());
        if (owned.isEmpty()) {
            lang.send(player, "mine.info-none");
            return;
        }

        lang.sendRaw(player, "mine.info-header");
        long now = System.currentTimeMillis();
        for (MiningClaimRow row : owned) {
            if (row.isDelinquent()) {
                long left = mines.graceMillis() - (now - row.delinquentSince());
                lang.sendRaw(player, "mine.info-delinquent",
                        Replies.p("chunk", Formats.chunk(row.chunkX(), row.chunkZ())),
                        Replies.p("days", String.valueOf(Math.max(0,
                                left / 86_400_000L))));
                continue;
            }
            lang.sendRaw(player, "mine.info-entry",
                    Replies.p("chunk", Formats.chunk(row.chunkX(), row.chunkZ())),
                    Replies.p("world", row.world()),
                    Replies.p("upkeep", money(mines.upkeepPerDay())));
        }

        var trusted = mines.registry().trustedBy(player.getUniqueId());
        if (!trusted.isEmpty()) {
            // Names would mean a database read per trusted player for one line of /mine info.
            // Online players are named; anyone offline shows as their id, which is still enough
            // to run /mine untrust against.
            lang.sendRaw(player, "mine.info-trusted",
                    Replies.p("players", trusted.stream()
                            .map(uuid -> {
                                Player online = player.getServer().getPlayer(uuid);
                                return online == null ? uuid.toString() : online.getName();
                            })
                            .reduce((a, b) -> a + ", " + b).orElse("")));
        }
    }

    // ==================================================================================
    // Trust
    // ==================================================================================

    private void trust(Player owner, String typed) {
        resolveThen(owner, typed, target -> services.get().miningClaims()
                .trust(owner.getUniqueId(), target, System.currentTimeMillis())
                .whenComplete((result, error) -> scheduler.runOnMain(() ->
                        report(owner, result, error, "mine.trusted", typed))));
    }

    private void untrust(Player owner, String typed) {
        resolveThen(owner, typed, target -> services.get().miningClaims()
                .untrust(owner.getUniqueId(), target)
                .whenComplete((result, error) -> scheduler.runOnMain(() ->
                        report(owner, result, error, "mine.untrusted", typed))));
    }

    /**
     * Resolves a typed name, then acts.
     *
     * <p>Asynchronous because the name may belong to somebody offline, which is a database read
     * — and SPEC 32.6's trust list is exactly the case where that matters, since a player grants
     * access to a friend who is not currently online.
     */
    private void resolveThen(Player asker, String typed,
                             java.util.function.Consumer<UUID> action) {
        services.get().lookup().resolve(typed)
                .whenComplete((resolved, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(asker, "command.error");
                        return;
                    }
                    if (resolved == null || resolved.isEmpty()) {
                        lang.send(asker, "player.unknown", Replies.p("player", typed));
                        return;
                    }
                    action.accept(resolved.get().uuid());
                }));
    }

    private void report(Player owner, Result<UUID> result, Throwable error, String key,
                        String typed) {
        if (error != null) {
            lang.send(owner, "command.error");
            return;
        }
        if (result instanceof Result.Failure<UUID> failure) {
            Replies.sendFailure(owner, lang, failure);
            return;
        }
        lang.send(owner, key, Replies.p("player", typed));
    }

    // ==================================================================================
    // Travel
    // ==================================================================================

    private void teleport(Player player) {
        var owned = services.get().miningClaims().registry().ownedBy(player.getUniqueId());
        if (owned.isEmpty()) {
            lang.send(player, "mine.no-claim-to-tp");
            return;
        }
        MiningClaimRow row = owned.get(0);
        org.bukkit.World world = player.getServer().getWorld(row.world());
        if (world == null) {
            lang.send(player, "travel.rtp-world-missing");
            return;
        }
        // The centre of the claimed chunk, at whatever height is solid there. A mine entrance
        // is usually at the surface, and the alternative is storing a warp point per claim for
        // a feature SPEC 32.6 does not give one.
        int x = (row.chunkX() << 4) + 8;
        int z = (row.chunkZ() << 4) + 8;
        Result<Long> result = services.get().teleports().begin(player, TravelKind.MINE_TP,
                new org.bukkit.Location(world, x + 0.5,
                        world.getHighestBlockYAt(x, z) + 1.0, z + 0.5));
        if (result instanceof Result.Failure<Long> failure) {
            Replies.sendFailure(player, lang, failure);
        }
    }

    // ==================================================================================
    // Plumbing
    // ==================================================================================

    /**
     * How many claims this player may hold, SPEC 32.6.
     *
     * <p>"1 per player, 2 with {@code civitas.limit.miningclaims.2}." Read as a scaling node in
     * the SPEC 10 style, so a donor rank can be given more without a code change.
     */
    private int limitFor(Player player) {
        int base = services.get().miningClaims().baseLimit();
        for (int n = 10; n > base; n--) {
            if (player.hasPermission("civitas.limit.miningclaims." + n)) {
                return n;
            }
        }
        return base;
    }

    private String money(java.math.BigDecimal amount) {
        return Money.format(amount, services.get().economy().configs());
    }

    private int withPlayer(CommandContext<CommandSourceStack> context,
                           java.util.function.Consumer<Player> action) {
        Audience audience = context.getSource().getSender();
        CivitasServices ready = services.get();
        if (ready == null) {
            lang.send(audience, "plugin.starting");
            return Command.SINGLE_SUCCESS;
        }
        if (!ready.miningClaims().enabled()) {
            lang.send(audience, "mine.disabled");
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
