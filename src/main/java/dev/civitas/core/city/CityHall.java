package dev.civitas.core.city;

import java.util.Objects;
import java.util.Optional;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The City Hall block, SPEC 8.1.
 *
 * <p>A Lodestone carrying {@code civitas:city_hall = <city_id>}, which is how the block knows
 * whose it is after a restart, a chunk unload, or being picked up and placed somewhere else.
 * Storing the id in the block rather than the position in the database is deliberate: a block
 * can be moved by a piston, a world edit or an admin, and a position that has drifted out of
 * sync is worse than no record at all.
 *
 * <p>SPEC 8.1's protection rules live here rather than in the listener, so "who may break
 * this" has one answer: nobody below Co-Mayor, and nobody at all during a war.
 */
public final class CityHall {

    /** The persistent-data key SPEC 8.1 names. */
    public static final String KEY = "city_hall";

    private final Plugin plugin;
    private final ConfigManager configs;
    private final LangManager lang;
    private final NamespacedKey key;

    public CityHall(Plugin plugin, ConfigManager configs, LangManager lang) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.key = new NamespacedKey(plugin, KEY);
    }

    public NamespacedKey key() {
        return key;
    }

    /** The block this is, from {@code cities.yml}. A Lodestone unless an operator says otherwise. */
    public Material material() {
        String name = configs.get(ConfigFile.CITIES).getString("city-hall.material", "LODESTONE");
        Material parsed = Material.matchMaterial(name);
        return parsed == null ? Material.LODESTONE : parsed;
    }

    // ==================================================================================
    // The item
    // ==================================================================================

    /** The placeable item a founder receives, SPEC 5.1 step 7. */
    public ItemStack item(City city) {
        ItemStack stack = new ItemStack(material());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.displayName(lang.get("city.hall.item-name",
                        LangManager.placeholder("city", city.name()))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(lang.get("city.hall.item-lore")
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, city.id());
        stack.setItemMeta(meta);
        return stack;
    }

    /** @return the city id stamped on an item, if it is a City Hall block */
    public Optional<Integer> cityIdOf(ItemStack stack) {
        if (stack == null || stack.getType() != material() || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        return read(stack.getItemMeta().getPersistentDataContainer());
    }

    // ==================================================================================
    // The placed block
    // ==================================================================================

    /** @return the city id stamped on a placed block, if it is a City Hall */
    public Optional<Integer> cityIdOf(Block block) {
        if (block == null || block.getType() != material()) {
            return Optional.empty();
        }
        if (!(block.getState() instanceof TileState state)) {
            return Optional.empty();
        }
        return read(state.getPersistentDataContainer());
    }

    /** Stamps a placed block as this city's hall. */
    public boolean mark(Block block, City city) {
        if (!(block.getState() instanceof TileState state)) {
            return false;
        }
        state.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, city.id());
        state.update();
        return true;
    }

    // ==================================================================================
    // SPEC 8.1's rules
    // ==================================================================================

    /**
     * Whether this player may break this city's hall.
     *
     * <p>SPEC 8.1: nobody below Co-Mayor, which is expressed as a rank weight rather than a
     * rank name because the ranks are editable and a city may well not have one called
     * "Co-Mayor" any more.
     */
    public boolean mayBreak(City city, java.util.UUID player) {
        if (isCityAtWar(city)) {
            // SPEC 11.6: the City Hall cannot be broken in war by anyone, including its own.
            return false;
        }
        if (city.isMayor(player)) {
            return true;
        }
        int required = configs.get(ConfigFile.CITIES).getInt("city-hall.min-break-weight", 80);
        return city.weightOf(player) >= required;
    }

    /** SPEC 11.6, once wars exist in M19. */
    private boolean isCityAtWar(City city) {
        return false;
    }

    private Optional<Integer> read(PersistentDataContainer container) {
        Integer id = container.get(key, PersistentDataType.INTEGER);
        return Optional.ofNullable(id);
    }

    /** For the {@code /city hall} replacement, SPEC 8.1. */
    public Component replacementMessage() {
        return lang.get("city.hall.given");
    }
}
