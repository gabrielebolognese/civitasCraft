package dev.civitas.core.travel;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.Money;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.lang.LangManager;
import dev.civitas.msg.Formats;
import dev.civitas.util.Result;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Warmups, cooldowns and cancellation, once, for every destination in SPEC 32.7.
 *
 * <p>SPEC 32.7: "All teleports are cancelled by movement or damage during warmup, and all are
 * blocked while combat tagged." That is one rule for six destinations, and it had already been
 * written twice — {@code SpawnService} for the city spawn and {@code OutpostTeleport} for
 * outposts — before this milestone added three more. Six copies of a rule is six places for it
 * to drift, so the three new ones share this and the two old ones are noted in
 * {@code OPEN_QUESTIONS.md} for the milestone that rebuilds them.
 *
 * <h2>Charged on arrival, never on request</h2>
 *
 * <p>The rule M10 recorded and this inherits: "A player knocked out of the warmup by damage has
 * not travelled and should not have paid, and charging up front would make interrupting somebody
 * a way to take their money." Affordability is checked before the warmup starts, so a player is
 * refused rather than made to wait five seconds to learn they are poor, and the money moves only
 * once they arrive.
 */
public final class TeleportService {

    /** SPEC 10: skips warmups and cooldowns, not fares. */
    public static final String BYPASS_COOLDOWN = "civitas.bypass.cooldown";

    private final Plugin plugin;
    private final ConfigManager configs;
    private final EconomyService economy;
    private final LangManager lang;
    private final Logger logger;

    /** A warmup in flight, and where the player was standing when it started. */
    private record Warmup(BukkitTask task, Location origin) {
    }

    private final Map<UUID, Warmup> warmups = new ConcurrentHashMap<>();
    private final Map<UUID, Map<TravelKind, Long>> lastTravel = new ConcurrentHashMap<>();

    /**
     * SPEC 33.8's combat tag. Answers "not tagged" until the war-PvP milestone builds it.
     *
     * <p>SPEC 33.8 blocks every teleport while tagged: "Escaping by moving is always
     * legitimate. Escaping by menu is not."
     */
    private CombatTag combatTag = player -> false;

    /** Whether a player is combat tagged, SPEC 33.8. */
    @FunctionalInterface
    public interface CombatTag {

        boolean isTagged(UUID player);
    }

    /**
     * @param plugin needed only to schedule a warmup; the cooldown and configuration readers
     *               are pure, so a test that only exercises those may pass null
     */
    public TeleportService(Plugin plugin, ConfigManager configs, EconomyService economy,
                           LangManager lang, Logger logger) {
        this.plugin = plugin;
        this.configs = Objects.requireNonNull(configs, "configs");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Hands the service SPEC 33.8's combat tag. The war milestone's only touchpoint here. */
    public void useCombatTag(CombatTag tag) {
        this.combatTag = Objects.requireNonNull(tag, "tag");
    }

    // ==================================================================================
    // Travelling
    // ==================================================================================

    /**
     * Refuses, teleports, or starts a warmup.
     *
     * @return the warmup in seconds, zero if the player arrived at once
     */
    public Result<Long> begin(Player player, TravelKind kind, Location destination) {
        return begin(player, kind, destination, cost(kind));
    }

    /**
     * The same, with the fare supplied by the caller.
     *
     * <p>For SPEC 39.5's outposts, whose fare is {@code 100 * D(d)} and therefore depends on
     * which outpost is being travelled to. Every other destination in SPEC 32.7 has a fixed
     * number and uses the overload above.
     */
    public Result<Long> begin(Player player, TravelKind kind, Location destination,
                              BigDecimal fare) {
        return begin(player, kind, destination, fare, null);
    }

    /**
     * The same, naming the destination in the warmup and arrival messages.
     *
     * <p>An outpost has a name its city chose and a player recognises, so "Travelling to North"
     * beats "Travelling to your outpost". Everything else falls back to the destination's own
     * label, which is what {@code label == null} selects.
     */
    public Result<Long> begin(Player player, TravelKind kind, Location destination,
                              BigDecimal fare, String label) {
        UUID uuid = player.getUniqueId();
        String named = label == null ? lang.plain(kind.messageKey()) : label;

        if (warmups.containsKey(uuid)) {
            return Result.failure("ALREADY_WARMING", "travel.already-warming");
        }
        if (combatTag.isTagged(uuid)) {
            return Result.failure("COMBAT_TAGGED", "travel.combat-tagged");
        }
        long remaining = player.hasPermission(BYPASS_COOLDOWN) ? 0L : cooldownRemaining(uuid, kind);
        if (remaining > 0) {
            return Result.failure("COOLDOWN", "travel.cooldown", Map.of(
                    "time", Formats.duration(remaining),
                    "destination", named));
        }
        if (destination == null || destination.getWorld() == null) {
            return Result.failure("NO_DESTINATION", "travel.no-destination");
        }

        BigDecimal cost = fare == null ? BigDecimal.ZERO : fare;
        if (cost.signum() > 0 && economy.balanceOrZero(uuid).compareTo(cost) < 0) {
            // Refused now rather than after the warmup: making somebody wait five seconds to
            // be told they are poor is a worse message than telling them at once.
            return Result.failure("CANNOT_AFFORD", "travel.cannot-afford", Map.of(
                    "cost", Money.format(cost, configs),
                    "balance", Money.format(economy.balanceOrZero(uuid), configs)));
        }

        long seconds = player.hasPermission(BYPASS_COOLDOWN) ? 0L : warmupSeconds(kind);
        if (plugin == null) {
            // Only reachable from a test that built the service without one. Refusing beats
            // teleporting with no way to cancel.
            return Result.failure("NO_SCHEDULER", "command.error");
        }
        if (seconds <= 0) {
            arrive(player, kind, destination, cost, named);
            return Result.success(0L);
        }

        Location origin = player.getLocation().clone();
        BukkitTask task = org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            warmups.remove(uuid);
            if (player.isOnline()) {
                arrive(player, kind, destination, cost, named);
            }
        }, seconds * 20L);

        warmups.put(uuid, new Warmup(task, origin));
        lang.send(player, "travel.warmup",
                LangManager.placeholder("seconds", String.valueOf(seconds)),
                LangManager.placeholder("destination", named));
        return Result.success(seconds);
    }

    private void arrive(Player player, TravelKind kind, Location destination,
                        BigDecimal cost, String named) {
        player.teleport(destination);
        lastTravel.computeIfAbsent(player.getUniqueId(), key -> new EnumMap<>(TravelKind.class))
                .put(kind, System.currentTimeMillis());

        if (cost.signum() > 0) {
            charge(player, kind, cost);
        }
        lang.send(player, "travel.arrived",
                LangManager.placeholder("destination", named));
    }

    /**
     * Takes the fare, after the player has arrived.
     *
     * <p>A failed charge is logged and the journey stands. The alternative is teleporting a
     * player and then teleporting them back because their balance moved in the last five
     * seconds, which is a worse outcome than a fare the server occasionally fails to collect.
     */
    private void charge(Player player, TravelKind kind, BigDecimal cost) {
        try {
            economy.take(player.getUniqueId(), cost, TransactionType.OUTPOST_TELEPORT_FEE, null,
                            "{\"travel\":\"" + kind.key() + "\"}")
                    .exceptionally(error -> {
                        logger.log(Level.WARNING, "Could not charge the fare for "
                                + player.getName(), error);
                        return null;
                    });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not charge the fare for " + player.getName(), e);
        }
    }

    // ==================================================================================
    // Cancellation, SPEC 32.7
    // ==================================================================================

    /**
     * Stops a warmup, if one is running.
     *
     * @param messageKey what to tell the player, or null to cancel silently
     */
    public boolean cancel(Player player, String messageKey) {
        Warmup warmup = warmups.remove(player.getUniqueId());
        if (warmup == null) {
            return false;
        }
        warmup.task().cancel();
        if (messageKey != null) {
            lang.send(player, messageKey);
        }
        return true;
    }

    /**
     * Whether a player has left the block they started their warmup on.
     *
     * <p>Block-level rather than exact, so turning on the spot or being nudged by a mob does
     * not cancel. SPEC 32.7 says "cancelled by movement", and a player who has not left the
     * block they were standing on has not moved in any sense they would recognise.
     */
    public boolean hasMovedAway(Player player, Location to) {
        Warmup warmup = warmups.get(player.getUniqueId());
        if (warmup == null || to == null || to.getWorld() == null) {
            return false;
        }
        Location origin = warmup.origin();
        return !origin.getWorld().equals(to.getWorld())
                || origin.getBlockX() != to.getBlockX()
                || origin.getBlockY() != to.getBlockY()
                || origin.getBlockZ() != to.getBlockZ();
    }

    public boolean isWarmingUp(UUID player) {
        return warmups.containsKey(player);
    }

    /**
     * Cancels every warmup in flight. Called on disable.
     *
     * <p>A warmup outliving the plugin would fire against a scheduler that no longer has a
     * service behind it, so it is cancelled rather than left to be interrupted.
     */
    public void stopAll() {
        warmups.values().forEach(warmup -> warmup.task().cancel());
        warmups.clear();
    }

    /** Drops a player's warmup and cooldowns. Called on quit. */
    public void forget(UUID player) {
        Warmup warmup = warmups.remove(player);
        if (warmup != null) {
            warmup.task().cancel();
        }
        lastTravel.remove(player);
    }

    // ==================================================================================
    // Cooldowns
    // ==================================================================================

    /** How long until this player may use this destination again, in millis. */
    public long cooldownRemaining(UUID player, TravelKind kind) {
        Map<TravelKind, Long> perKind = lastTravel.get(player);
        if (perKind == null || perKind.get(kind) == null) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - perKind.get(kind);
        return Math.max(0, cooldownSeconds(kind) * 1000L - elapsed);
    }

    // ==================================================================================
    // Configuration, SPEC 32.7's table
    // ==================================================================================

    public BigDecimal cost(TravelKind kind) {
        return BigDecimal.valueOf(configs.get(kind.configFile()).getDouble(kind.costKey(), 0));
    }

    public long cooldownSeconds(TravelKind kind) {
        return configs.get(kind.configFile()).getLong(kind.cooldownKey(), 60);
    }

    public long warmupSeconds(TravelKind kind) {
        return configs.get(kind.configFile()).getLong(kind.warmupKey(), 5);
    }
}
