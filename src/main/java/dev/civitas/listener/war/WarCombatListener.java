package dev.civitas.listener.war;

import java.util.Objects;

import dev.civitas.core.war.WarRestrictions;
import dev.civitas.lang.LangManager;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * The rules that only exist while a war is being fought, SPEC 11.8.3 and 11.8.2.
 *
 * <h2>No drops</h2>
 * SPEC 11.8.3 calls this rule critical and spells out the arithmetic: without it, attackers
 * break 50,000 blocks of the enemy city, keep every one of the materials, and the rollback
 * puts all 50,000 blocks back. That is resources created from nothing, and SPEC 17.6 case 73
 * names it the main duplication vector in the plugin. So a block broken inside a war zone
 * drops nothing, and reappears later.
 *
 * <p>SPEC 11.8.3 states the net effect plainly, and it is worth keeping in mind while reading
 * this class: "Blocks broken in war simply vanish and later reappear."
 */
public final class WarCombatListener implements Listener {

    private final WarRestrictions restrictions;
    private final LangManager lang;

    public WarCombatListener(WarRestrictions restrictions, LangManager lang) {
        this.restrictions = Objects.requireNonNull(restrictions, "restrictions");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    /**
     * SPEC 11.8.3's no-drops rule.
     *
     * <p>At MONITOR because by here the break is certain: M17's logger has already recorded
     * the block at NORMAL and land protection has already had its say. All that is left is to
     * stop the item existing.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!restrictions.isAnyWarActive()) {
            return;
        }
        Block block = event.getBlock();
        if (!restrictions.suppressesDrops(block.getWorld().getName(), block.getX(), block.getZ())) {
            return;
        }
        // The block is logged and will come back; the materials must not survive in the
        // meantime, or the war mints them.
        event.setDropItems(false);
        event.setExpToDrop(0);
    }

    /**
     * SPEC 11.8.2 step 1: "The war zone is then closed: entry is blocked with a message."
     *
     * <p>Only while a rollback is running. A player wandering in mid-restore would be standing
     * in blocks as they are rewritten around them.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null) {
            return;
        }
        // Only when the block actually changes: this fires for looking around too, and the
        // zone check is not free enough to run on every mouse movement.
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        String world = event.getTo().getWorld().getName();
        if (!restrictions.isZoneClosed(world, event.getTo().getBlockX(),
                event.getTo().getBlockZ())) {
            return;
        }
        // Already inside is not the same as walking in: somebody the evacuation missed is
        // pushed back rather than trapped by a cancelled move.
        if (restrictions.isZoneClosed(world, event.getFrom().getBlockX(),
                event.getFrom().getBlockZ())) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        lang.send(player, "war.zone-closed");
    }
}
