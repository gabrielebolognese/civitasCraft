package dev.civitas.listener;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.core.events.EventEffects;
import dev.civitas.core.events.InvasionWaves;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.util.Result;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * The three SPEC 13.5 effects that are events rather than multipliers.
 *
 * <p>Harvest Festival's faster crops, Gold Rush's richer ore, and the Invasion payout. Each
 * asks {@link EventEffects} at the moment it fires rather than holding a flag, so nothing here
 * can be left switched on after its event ends.
 */
public final class EventListener implements Listener {

    private final EventEffects effects;
    private final InvasionWaves invasion;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final TreasuryService treasury;
    private final DatabaseManager db;
    private final Logger logger;

    public EventListener(EventEffects effects, InvasionWaves invasion, CityRegistry cities,
                         ClaimRegistry claims, TreasuryService treasury, DatabaseManager db,
                         Logger logger) {
        this.effects = Objects.requireNonNull(effects, "effects");
        this.invasion = Objects.requireNonNull(invasion, "invasion");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.db = Objects.requireNonNull(db, "db");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Harvest Festival, SPEC 13.5
    // ==================================================================================

    /**
     * Crops grow faster.
     *
     * <p>Minecraft grows a crop one stage per random tick, and there is no supported way to
     * change the tick rate for one block type. What there is, is this event: when a crop grows
     * naturally, the extra stages the multiplier buys are applied on top. A multiplier of 2
     * therefore means each natural growth advances the crop twice, which is what a player
     * experiences as double growth.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        double multiplier = effects.cropGrowthMultiplier();
        if (multiplier <= 1.0) {
            return;
        }

        Block block = event.getBlock();
        if (!Tag.CROPS.isTagged(event.getNewState().getType())) {
            return;
        }
        if (!(event.getNewState().getBlockData() instanceof org.bukkit.block.data.Ageable ageable)) {
            return;
        }

        int extra = (int) Math.floor(multiplier) - 1;
        if (extra <= 0) {
            return;
        }
        int age = Math.min(ageable.getMaximumAge(), ageable.getAge() + extra);
        ageable.setAge(age);
        event.getNewState().setBlockData(ageable);
        // Suppress physics for the same reason SPEC 11.8.2 gives about rollback: a growth that
        // cascades into neighbouring blocks is not what anybody asked for.
        block.setBlockData(ageable, false);
    }

    // ==================================================================================
    // Gold Rush, SPEC 13.5
    // ==================================================================================

    /**
     * Ore drops more.
     *
     * <p>SPEC 13.5 words this as an "ore generation bonus via a temporary loot modifier".
     * Generation cannot be what changes: the chunk was generated long before the event, and
     * Paper exposes no supported loot-table hook without NMS, which SPEC 2.1 forbids unless
     * unavoidable. Multiplying the drop is the observable effect, and it is the honest version
     * of what can be delivered. Recorded in OPEN_QUESTIONS.md.
     *
     * <p>Deliberately on the drop event rather than on the break: this way a silk-touch pick
     * that yields the ore block itself is multiplied too, and a block broken by anything other
     * than a player is not.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onOreDrop(BlockDropItemEvent event) {
        double multiplier = effects.oreDropMultiplier();
        if (multiplier <= 1.0 || !isOre(event.getBlockState().getType())) {
            return;
        }

        int extra = (int) Math.floor(multiplier) - 1;
        if (extra <= 0) {
            return;
        }
        for (var item : List.copyOf(event.getItems())) {
            ItemStack stack = item.getItemStack();
            ItemStack bonus = stack.clone();
            bonus.setAmount(stack.getAmount() * extra);
            event.getBlock().getWorld().dropItemNaturally(item.getLocation(), bonus);
        }
    }

    // ==================================================================================
    // Invasion, SPEC 13.5
    // ==================================================================================

    /**
     * An invasion mob killed inside a city's claims pays that city's treasury.
     *
     * <p>Only a tagged mob pays, so a city with a dark room or one built over a cave cannot
     * farm the reward, and only a kill inside claims pays, so the wilderness earns nobody
     * anything. Both are what SPEC 13.5 says; neither is what would happen by default.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (!invasion.isInvasionMob(event.getEntity())) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }

        Location at = event.getEntity().getLocation();
        Optional<Integer> owner = claims.ownerOf(at.getWorld().getName(),
                at.getBlockX() >> 4, at.getBlockZ() >> 4);
        if (owner.isEmpty()) {
            return;
        }
        Optional<City> city = cities.city(owner.get());
        if (city.isEmpty()) {
            return;
        }

        BigDecimal reward = effects.invasionRewardPerMob();
        if (reward.signum() <= 0) {
            return;
        }

        db.transaction(connection -> treasury.adjust(connection, city.get(), reward,
                        TransactionType.EVENT_REWARD, killer.getUniqueId(),
                        "{\"event\":\"invasion\"}"))
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not pay an invasion reward to "
                            + city.get().name(), error);
                    return Result.failure("PAYOUT_FAILED", "event.none");
                });
    }

    /** Ore blocks, from Bukkit's own tags so a new ore counts the day it ships. */
    private static boolean isOre(Material material) {
        return Tag.COAL_ORES.isTagged(material)
                || Tag.IRON_ORES.isTagged(material)
                || Tag.GOLD_ORES.isTagged(material)
                || Tag.COPPER_ORES.isTagged(material)
                || Tag.DIAMOND_ORES.isTagged(material)
                || Tag.EMERALD_ORES.isTagged(material)
                || Tag.LAPIS_ORES.isTagged(material)
                || Tag.REDSTONE_ORES.isTagged(material)
                || material == Material.NETHER_QUARTZ_ORE
                || material == Material.ANCIENT_DEBRIS;
    }
}
