package dev.civitas.listener;

import java.util.Objects;

import dev.civitas.core.protection.BlockClassifier;
import dev.civitas.core.protection.ProtectionAction;
import dev.civitas.core.protection.ProtectionGuard;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Interaction with blocks and with the entities that behave like furniture, SPEC 5.5.
 *
 * <p>SPEC lists "doors, trapdoors, buttons, levers, pressure plates, item frames, armor
 * stands, beds, anvils, enchanting tables". The first five and the last three are blocks;
 * item frames and armor stands are entities, and each needs its own event because breaking,
 * rotating and looting one are three different things in Bukkit.
 */
public final class InteractionProtectionListener implements Listener {

    private final ProtectionGuard guard;
    private final BlockClassifier blocks;

    public InteractionProtectionListener(ProtectionGuard guard, BlockClassifier blocks) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        // SPEC 5.5: "Farmland trampling by non-members". Walking on a crop field fires a
        // PHYSICAL interaction, which is also how pressure plates and tripwires trigger.
        if (event.getAction() == Action.PHYSICAL) {
            if (block.getType() == Material.FARMLAND) {
                if (!guard.allows(event.getPlayer(), block.getLocation(),
                        ProtectionAction.FARMLAND_TRAMPLE)) {
                    event.setUseInteractedBlock(Event.Result.DENY);
                }
            } else if (blocks.isInteractable(block.getType())
                    && !guard.allowsSilently(event.getPlayer(), block.getLocation(),
                            ProtectionAction.INTERACT)) {
                // Stepping on someone's pressure plate is refused silently: a player walking
                // past a door should not be nagged for something they did with their feet.
                event.setUseInteractedBlock(Event.Result.DENY);
            }
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // Containers belong to the container listener, which distinguishes looking from taking.
        if (blocks.isContainer(block.getType()) || !blocks.isInteractable(block.getType())) {
            return;
        }
        if (!guard.allows(event.getPlayer(), block.getLocation(), ProtectionAction.INTERACT)) {
            // Deny the block, not the whole event, so the item in hand still works.
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    /** Right-clicking an entity: rotating an item frame, trading with a villager. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();

        if (entity instanceof Villager || entity instanceof WanderingTrader) {
            // SPEC 5.5 lists villager trading among the protected interactions but marks it
            // "config toggle, default allowed", so out of the box a visitor may trade and
            // only a server that turns the toggle off gates it on INTERACT.
            if (guard.service().villagerTradingEnabled()) {
                return;
            }
            if (!guard.allows(event.getPlayer(), entity.getLocation(),
                    ProtectionAction.VILLAGER_TRADE)) {
                event.setCancelled(true);
            }
            return;
        }

        if (entity instanceof ItemFrame || entity instanceof ArmorStand) {
            if (!guard.allows(event.getPlayer(), entity.getLocation(),
                    ProtectionAction.INTERACT)) {
                event.setCancelled(true);
            }
        }
    }

    /** Taking a hat off an armor stand, which is neither a click nor damage. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!guard.allows(event.getPlayer(), event.getRightClicked().getLocation(),
                ProtectionAction.INTERACT)) {
            event.setCancelled(true);
        }
    }

    /** Breaking a painting or an item frame off a wall. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof org.bukkit.entity.Player player
                && !guard.allows(player, event.getEntity().getLocation(),
                        ProtectionAction.BUILD)) {
            event.setCancelled(true);
        }
    }

    /** Hanging a painting or an item frame on someone else's wall. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null
                && !guard.allows(event.getPlayer(), event.getEntity().getLocation(),
                        ProtectionAction.BUILD)) {
            event.setCancelled(true);
        }
    }
}
