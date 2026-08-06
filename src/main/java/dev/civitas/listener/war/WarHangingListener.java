package dev.civitas.listener.war;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import dev.civitas.core.war.WarBlockRecorder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;

/**
 * Item frames, paintings and armor stands, SPEC 11.8.1's last source row.
 *
 * <p>These are entities, and SPEC 3.8's table is block-shaped, so each is recorded at the
 * block it occupies with {@link WarBlockRecorder#HANGING_MARKER} in the block-data column and
 * its detail in the payload. SPEC 11.8.3 requires them "restored with full NBT (contents,
 * rotation, pose)"; what is captured here is what Bukkit exposes, which covers contents and
 * rotation. Recorded in OPEN_QUESTIONS.md.
 */
public final class WarHangingListener implements Listener {

    private final WarBlockRecorder recorder;

    public WarHangingListener(WarBlockRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreak(HangingBreakEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        java.util.UUID actor = event instanceof HangingBreakByEntityEvent byEntity
                && byEntity.getRemover() instanceof Player player
                ? player.getUniqueId()
                : null;

        if (!recorder.recordHanging(event.getEntity().getLocation(),
                describe(event.getEntity()), actor)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlace(HangingPlaceEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        // Placing one is also a change: rollback has to know to take it away again.
        java.util.UUID actor = event.getPlayer() == null ? null : event.getPlayer().getUniqueId();
        if (!recorder.recordHanging(event.getEntity().getLocation(),
                describe(event.getEntity()), actor)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        if (!recorder.recordHanging(event.getRightClicked().getLocation(),
                describe(event.getRightClicked()), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * What an entity is, in the shape the payload column takes.
     *
     * <p>The same YAML form the tile codec uses, for the same reason: {@code ItemStack} is the
     * one thing Bukkit guarantees to serialize across versions.
     */
    private static byte[] describe(Entity entity) {
        YamlConfiguration payload = new YamlConfiguration();
        payload.set("kind", "hanging");
        payload.set("type", entity.getType().name());
        payload.set("x", entity.getLocation().getX());
        payload.set("y", entity.getLocation().getY());
        payload.set("z", entity.getLocation().getZ());
        payload.set("yaw", entity.getLocation().getYaw());
        payload.set("pitch", entity.getLocation().getPitch());

        if (entity instanceof ItemFrame frame) {
            payload.set("rotation", frame.getRotation().name());
            payload.set("facing", frame.getFacing().name());
            payload.set("visible", frame.isVisible());
            payload.set("fixed", frame.isFixed());
            if (frame.getItem().getType() != org.bukkit.Material.AIR) {
                payload.set("item", frame.getItem());
            }
        } else if (entity instanceof org.bukkit.entity.ArmorStand stand) {
            payload.set("small", stand.isSmall());
            payload.set("arms", stand.hasArms());
            payload.set("base-plate", stand.hasBasePlate());
            payload.set("visible", stand.isVisible());
            var equipment = stand.getEquipment();
            payload.set("helmet", equipment.getHelmet());
            payload.set("chestplate", equipment.getChestplate());
            payload.set("leggings", equipment.getLeggings());
            payload.set("boots", equipment.getBoots());
            payload.set("hand", equipment.getItemInMainHand());
            payload.set("off-hand", equipment.getItemInOffHand());
        } else if (entity instanceof org.bukkit.entity.Painting painting) {
            // Through the registry: Art.name() and Art.getKey() are both marked for removal.
            var art = io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.PAINTING_VARIANT)
                    .getKey(painting.getArt());
            payload.set("art", art == null ? null : art.toString());
            payload.set("facing", painting.getFacing().name());
        }

        return payload.saveToString().getBytes(StandardCharsets.UTF_8);
    }
}
