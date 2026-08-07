package dev.civitas.core.war;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.inventory.ItemStack;

/**
 * Puts item frames, paintings and armor stands back, SPEC 11.8.3.
 *
 * <h2>Why these are not blocks</h2>
 * SPEC 11.8.1 lists them among the sources to log, but SPEC 3.8's table is block-shaped, so
 * M17 records each at the block it occupies with {@link WarBlockRecorder#HANGING_MARKER} in the
 * block-data column and its detail in the payload. M18's replay skipped those rows because
 * placing a block is not the same operation as spawning an entity. This class is the other
 * half: it reads the payload back and rebuilds the entity.
 *
 * <p>SPEC 11.8.3 asks for them "restored with full NBT (contents, rotation, pose)". What is
 * restored is what Bukkit exposes and M17 captured — an item frame's item, rotation, facing and
 * visibility; an armor stand's six equipment slots and its small/arms/base-plate/visible flags;
 * a painting's art and facing. There is no supported path to the rest without NMS, which SPEC
 * 2.1 forbids unless unavoidable, and the limitation is recorded in OPEN_QUESTIONS.md.
 *
 * <h2>Replacing rather than duplicating</h2>
 * A replay runs newest to oldest, so a frame broken and replaced during a war produces two rows
 * at one position. Restoring both would leave two frames in one block. Any existing hanging of
 * the same type at the position is removed first, which makes each restore idempotent and makes
 * the oldest row — the pre-war state, applied last — the one that survives.
 */
public final class HangingRestorer {

    private final Logger logger;

    public HangingRestorer(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Rebuilds one hanging entity from its payload.
     *
     * <p>On the server thread: it spawns entities.
     *
     * @param payload what {@code WarHangingListener} wrote
     * @return whether an entity was restored
     */
    public boolean restore(World world, int x, int y, int z, byte[] payload) {
        if (world == null || payload == null || payload.length == 0) {
            return false;
        }
        YamlConfiguration data = new YamlConfiguration();
        try {
            data.load(new StringReader(new String(payload, StandardCharsets.UTF_8)));
        } catch (java.io.IOException | InvalidConfigurationException e) {
            logger.log(Level.WARNING, "Unreadable hanging payload at "
                    + x + "," + y + "," + z, e);
            return false;
        }
        if (!"hanging".equals(data.getString("kind"))) {
            return false;
        }

        EntityType type;
        try {
            type = EntityType.valueOf(data.getString("type", ""));
        } catch (IllegalArgumentException e) {
            // A type this build no longer has. One decoration is lost, the city still returns.
            return false;
        }

        Location at = new Location(world, data.getDouble("x", x + 0.5),
                data.getDouble("y", y + 0.5), data.getDouble("z", z + 0.5),
                (float) data.getDouble("yaw"), (float) data.getDouble("pitch"));

        removeExisting(world, at, type);

        try {
            return switch (type) {
                case ITEM_FRAME, GLOW_ITEM_FRAME -> restoreFrame(world, at, type, data);
                case PAINTING -> restorePainting(world, at, data);
                case ARMOR_STAND -> restoreArmorStand(world, at, data);
                default -> false;
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            // A frame whose wall is not back yet, or a painting that no longer fits. Logged
            // rather than thrown: SPEC 11.8.2 step 8 wants mismatches visible, and one
            // decoration must not stop the replay.
            logger.log(Level.WARNING, "Could not restore " + type + " at "
                    + x + "," + y + "," + z + ": " + e.getMessage());
            return false;
        }
    }

    private boolean restoreFrame(World world, Location at, EntityType type,
                                 YamlConfiguration data) {
        ItemFrame frame = (ItemFrame) world.spawnEntity(at, type);
        facing(data).ifPresent(face -> {
            try {
                frame.setFacingDirection(face, true);
            } catch (IllegalArgumentException ignored) {
                // The wall it hung on is gone. It keeps whatever facing it spawned with.
            }
        });
        String rotation = data.getString("rotation");
        if (rotation != null) {
            try {
                frame.setRotation(org.bukkit.Rotation.valueOf(rotation));
            } catch (IllegalArgumentException ignored) {
                // Not a rotation this build knows.
            }
        }
        frame.setVisible(data.getBoolean("visible", true));
        frame.setFixed(data.getBoolean("fixed", false));
        ItemStack item = data.getItemStack("item");
        if (item != null) {
            frame.setItem(item, false);
        }
        return true;
    }

    private boolean restorePainting(World world, Location at, YamlConfiguration data) {
        Painting painting = (Painting) world.spawnEntity(at, EntityType.PAINTING);
        facing(data).ifPresent(face -> {
            try {
                painting.setFacingDirection(face, true);
            } catch (IllegalArgumentException ignored) {
                // No wall for it. It keeps its spawned facing.
            }
        });
        String art = data.getString("art");
        if (art != null) {
            // Through the registry: Art.valueOf and Art.getByName are both marked for removal.
            var key = org.bukkit.NamespacedKey.fromString(art);
            if (key != null) {
                var variant = io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.PAINTING_VARIANT)
                        .get(key);
                if (variant != null) {
                    painting.setArt(variant, true);
                }
            }
        }
        return true;
    }

    private boolean restoreArmorStand(World world, Location at, YamlConfiguration data) {
        ArmorStand stand = (ArmorStand) world.spawnEntity(at, EntityType.ARMOR_STAND);
        stand.setSmall(data.getBoolean("small", false));
        stand.setArms(data.getBoolean("arms", false));
        stand.setBasePlate(data.getBoolean("base-plate", true));
        stand.setVisible(data.getBoolean("visible", true));

        var equipment = stand.getEquipment();
        equipment.setHelmet(data.getItemStack("helmet"));
        equipment.setChestplate(data.getItemStack("chestplate"));
        equipment.setLeggings(data.getItemStack("leggings"));
        equipment.setBoots(data.getItemStack("boots"));
        equipment.setItemInMainHand(data.getItemStack("hand"));
        equipment.setItemInOffHand(data.getItemStack("off-hand"));
        return true;
    }

    private static java.util.Optional<BlockFace> facing(YamlConfiguration data) {
        String facing = data.getString("facing");
        if (facing == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(BlockFace.valueOf(facing));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    /** Clears whatever is already at this position, so a replay cannot stack duplicates. */
    private static void removeExisting(World world, Location at, EntityType type) {
        for (Entity entity : world.getNearbyEntities(at, 0.6, 0.6, 0.6)) {
            if (entity.getType() == type) {
                entity.remove();
            }
        }
    }
}
