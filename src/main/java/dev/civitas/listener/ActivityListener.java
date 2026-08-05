package dev.civitas.listener;

import java.util.Objects;

import dev.civitas.core.income.ActivityKind;
import dev.civitas.core.income.ActivityTracker;
import dev.civitas.core.income.QuestMetric;
import dev.civitas.core.income.QuestService;
import dev.civitas.core.income.ChallengeService;
import dev.civitas.core.progression.PlayerStat;
import dev.civitas.core.progression.StatsService;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Feeds the SPEC 4.2.1 activity check and the SPEC 13.1 and 13.2 progress counters.
 *
 * <p>One listener for both, because they watch the same events and splitting them would mean
 * two handlers on every one of them. Everything here runs at {@link EventPriority#MONITOR}
 * with {@code ignoreCancelled}: an action that land protection refused did not happen, and
 * must count towards neither a stipend nor a quest.
 */
public final class ActivityListener implements Listener {

    private final ActivityTracker activity;
    private final QuestService quests;
    private final ChallengeService challenges;
    private final StatsService stats;

    public ActivityListener(ActivityTracker activity, QuestService quests,
                            ChallengeService challenges, StatsService stats) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.quests = Objects.requireNonNull(quests, "quests");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    // ==================================================================================
    // Movement
    // ==================================================================================

    /**
     * SPEC 4.2.1's movement rule, as a distance rather than an event count.
     *
     * <p>Looking around fires this event too, so the yaw-and-pitch-only case is dropped
     * first: a player spinning on the spot has not moved 32 blocks and never will.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        if (!Objects.equals(from.getWorld(), to.getWorld())) {
            return;
        }
        activity.recordMovement(event.getPlayer().getUniqueId(), from.distance(to));
    }

    // ==================================================================================
    // Blocks
    // ==================================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        activity.record(player.getUniqueId(), ActivityKind.BROKE_BLOCK);

        Block block = event.getBlock();
        report(player, QuestMetric.BREAK_BLOCKS, 1);

        if (isRipeCrop(block)) {
            report(player, QuestMetric.HARVEST_CROPS, 1);
            // SPEC 13.3's Farmer board counts the same harvest, but for a career rather than
            // for today's quest, so it goes to a counter the daily reset cannot touch.
            stats.record(player.getUniqueId(), PlayerStat.CROPS_HARVESTED, 1);
        }
        if (isOre(block.getType())) {
            report(player, QuestMetric.MINE_ORE, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        activity.record(event.getPlayer().getUniqueId(), ActivityKind.PLACED_BLOCK);
        report(event.getPlayer(), QuestMetric.PLACE_BLOCKS, 1);
        // SPEC 13.3's Builder board, which excludes war zones; StatsService owns that test.
        stats.recordPlacement(event.getPlayer().getUniqueId(), event.getBlock().getLocation());
    }

    // ==================================================================================
    // Inventories, chat and combat
    // ==================================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            activity.record(player.getUniqueId(), ActivityKind.OPENED_INVENTORY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        activity.record(player.getUniqueId(), ActivityKind.OPENED_INVENTORY);
        int made = event.getRecipe().getResult().getAmount();
        report(player, QuestMetric.CRAFT_ITEMS, Math.max(1, made));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        activity.record(event.getPlayer().getUniqueId(), ActivityKind.SPOKE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        activity.record(event.getPlayer().getUniqueId(), ActivityKind.SPOKE);
    }

    /**
     * SPEC 4.2.1 counts damaging or being damaged by an entity.
     *
     * <p>Both directions, from the one event: a player being chased by a zombie is as alive
     * as one hitting it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker) {
            activity.record(attacker.getUniqueId(), ActivityKind.FOUGHT);
        }
        if (event.getEntity() instanceof Player victim) {
            activity.record(victim.getUniqueId(), ActivityKind.FOUGHT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player) {
            report(player, QuestMetric.BREED_ANIMALS, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        activity.forget(event.getPlayer().getUniqueId());
        quests.forget(event.getPlayer().getUniqueId());
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private void report(Player player, QuestMetric metric, long amount) {
        quests.report(player.getUniqueId(), metric, amount);
        challenges.report(player.getUniqueId(), metric, amount);
    }

    /**
     * Whether this block is a crop that was ready to harvest.
     *
     * <p>Only ripe crops count. Breaking a seedling is not a harvest, and counting it would
     * make "harvest 256 wheat" clearable by planting and immediately smashing 256 seeds.
     */
    private static boolean isRipeCrop(Block block) {
        if (!Tag.CROPS.isTagged(block.getType())) {
            return false;
        }
        return !(block.getBlockData() instanceof Ageable ageable)
                || ageable.getAge() >= ageable.getMaximumAge();
    }

    /** Ore blocks, from Bukkit's own tags so a new ore type counts the day it ships. */
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
