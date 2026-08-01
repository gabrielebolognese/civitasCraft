package dev.civitas.core.protection;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * Decides which blocks SPEC 5.5 protects as containers and which as interactables.
 *
 * <p>Built from Bukkit's own tags wherever one exists, rather than from a list of material
 * names. {@code Tag.DOORS} already knows about every wood type including ones added after
 * this was written; a hand-written list would quietly stop protecting bamboo doors the day
 * they shipped.
 *
 * <p>Two config lists let an operator extend or override in either direction, because no
 * classification survives every modded or resource-pack setup.
 *
 * <p>Resolved once at construction into {@link EnumSet}s, so the hot path is an array index
 * rather than a tag lookup.
 */
public final class BlockClassifier {

    /**
     * The containers SPEC 5.5 names: "chest, barrel, shulker, furnace, hopper, dispenser,
     * dropper, brewing stand, beacon".
     *
     * <p>Ender chests are deliberately absent. An ender chest shows the viewer their own
     * inventory, so opening someone else's takes nothing from them, and SPEC 5.5 does not
     * list it.
     */
    private static final Set<Material> SPEC_CONTAINERS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST,
            Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.HOPPER,
            Material.DISPENSER, Material.DROPPER,
            Material.BREWING_STAND,
            Material.BEACON);

    /**
     * The interactables SPEC 5.5 names that are blocks: "doors, trapdoors, buttons, levers,
     * pressure plates, beds, anvils, enchanting tables". Item frames and armor stands are
     * entities and are handled by the entity listener.
     */
    private static final Set<Material> SPEC_INTERACTABLES = EnumSet.of(
            Material.LEVER,
            Material.ENCHANTING_TABLE,
            Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.CRAFTING_TABLE,
            Material.CAKE,
            Material.LECTERN,
            Material.NOTE_BLOCK,
            Material.JUKEBOX,
            Material.RESPAWN_ANCHOR);

    private final Set<Material> containers = EnumSet.noneOf(Material.class);
    private final Set<Material> interactables = EnumSet.noneOf(Material.class);

    public BlockClassifier(ConfigManager configs, Logger logger) {
        Objects.requireNonNull(configs, "configs");
        Objects.requireNonNull(logger, "logger");

        containers.addAll(SPEC_CONTAINERS);
        containers.addAll(Tag.SHULKER_BOXES.getValues());

        interactables.addAll(SPEC_INTERACTABLES);
        interactables.addAll(Tag.DOORS.getValues());
        interactables.addAll(Tag.TRAPDOORS.getValues());
        interactables.addAll(Tag.FENCE_GATES.getValues());
        interactables.addAll(Tag.BUTTONS.getValues());
        interactables.addAll(Tag.PRESSURE_PLATES.getValues());
        interactables.addAll(Tag.BEDS.getValues());
        interactables.addAll(Tag.CANDLES.getValues());
        interactables.addAll(Tag.FLOWER_POTS.getValues());

        var protection = configs.get(ConfigFile.CITIES);
        containers.addAll(parse(protection.getStringList("protection.extra-containers"),
                "protection.extra-containers", logger));
        interactables.addAll(parse(protection.getStringList("protection.extra-interactables"),
                "protection.extra-interactables", logger));

        Set<Material> unprotected = parse(protection.getStringList("protection.unprotected"),
                "protection.unprotected", logger);
        containers.removeAll(unprotected);
        interactables.removeAll(unprotected);
    }

    /** Whether opening this block should be gated on the container flags. */
    public boolean isContainer(Material material) {
        return containers.contains(material);
    }

    /** Whether right-clicking this block should be gated on {@code INTERACT}. */
    public boolean isInteractable(Material material) {
        return interactables.contains(material);
    }

    /** Whether this block is protected at all, as either kind. */
    public boolean isProtected(Material material) {
        return isContainer(material) || isInteractable(material);
    }

    /** Exposed for tests and for an admin dump of what the server is actually protecting. */
    public Set<Material> containers() {
        return Set.copyOf(containers);
    }

    public Set<Material> interactables() {
        return Set.copyOf(interactables);
    }

    /**
     * Reads a configured material list.
     *
     * <p>An unrecognised name is reported and skipped rather than fatal: a typo should cost
     * one block's protection and a console line, not the whole server's.
     */
    private static Set<Material> parse(List<String> names, String path, Logger logger) {
        Set<Material> parsed = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                logger.log(Level.WARNING, "cities.yml {0} lists an unknown material: {1}",
                        new Object[] {path, name});
                continue;
            }
            parsed.add(material);
        }
        return parsed;
    }
}
