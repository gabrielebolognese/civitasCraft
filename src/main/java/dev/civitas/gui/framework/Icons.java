package dev.civitas.gui.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Turning a label and some lore into an item.
 *
 * <p>One place, because two things must be true of every icon in the plugin and neither is
 * automatic: italics must be off (Minecraft italicises custom names by default, which makes
 * a carefully written menu look like a mistake), and the text must be a
 * {@link Component} the caller already resolved through the language files rather than a
 * string assembled here.
 */
public final class Icons {

    private final ConfigManager configs;

    public Icons(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /** A plain icon. */
    public ItemStack build(Material material, Component label, List<Component> lore) {
        return build(material, label, lore, false);
    }

    public ItemStack build(Material material, Component label, List<Component> lore,
                           boolean glowing) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        meta.displayName(flatten(label));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (Component line : lore) {
                lines.add(flatten(line));
            }
            meta.lore(lines);
        }
        if (glowing) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * The barrier a viewer sees in place of a button they may not use, SPEC 8.2.
     *
     * <p>The label is kept so the menu keeps its shape and the player can see what they are
     * missing; the lore says why.
     */
    public ItemStack denied(Component label, Component reason) {
        Material material = material("denied.material", Material.BARRIER);
        List<Component> lore = reason == null ? List.of() : List.of(reason);
        return build(material, label, lore);
    }

    /** The border pane: a single space for a name, no lore, SPEC 8.2. */
    public ItemStack border() {
        return build(material("border.material", Material.GRAY_STAINED_GLASS_PANE),
                Component.text(" "), List.of());
    }

    /** Reads a material from {@code gui/common.yml}, falling back rather than failing. */
    public Material material(String path, Material fallback) {
        String name = configs.get(ConfigFile.GUI).getString(path);
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material parsed = Material.matchMaterial(name);
        return parsed == null ? fallback : parsed;
    }

    /**
     * Turns off the italics Minecraft adds to every custom name.
     *
     * <p>Only where the caller has not asked for italics explicitly, so a menu that wants
     * them can still have them.
     */
    private static Component flatten(Component component) {
        return component.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET
                ? component.decoration(TextDecoration.ITALIC, false)
                : component;
    }
}
