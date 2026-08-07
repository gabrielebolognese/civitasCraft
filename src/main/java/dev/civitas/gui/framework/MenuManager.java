package dev.civitas.gui.framework;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Who has what open, and the tick that keeps it current.
 *
 * <p>Two jobs beyond bookkeeping. First, the SPEC 8.2 refresh: every open menu that says it
 * shows live data is redrawn on a timer, so a treasury figure or an online-member list does
 * not go stale in front of the player. Second, force-closing: SPEC 17.1 case 11 and SPEC
 * 17.5 case 60 both require that a menu about a thing which no longer concerns the viewer
 * goes away rather than sitting there as a stale door into it.
 */
public final class MenuManager {

    private final ConfigManager configs;
    private final LangManager lang;
    private final Icons icons;

    private final Map<UUID, Menu> open = new ConcurrentHashMap<>();

    public MenuManager(ConfigManager configs, LangManager lang) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.icons = new Icons(configs);
    }

    /**
     * Feeds {@code /ca perf}'s GUI open time, SPEC 9.4.6.
     *
     * <p>Set after construction for the same reason {@code ClaimRegistry}'s is: the manager
     * exists before configuration has been read, and the many tests that build one directly
     * have no interest in a profiler.
     */
    private dev.civitas.util.Timings timings = dev.civitas.util.Timings.disabled();

    /** Wires the profiler in once configuration has been read. */
    public void useTimings(dev.civitas.util.Timings timings) {
        this.timings = java.util.Objects.requireNonNull(timings, "timings");
    }

    /** The profiler menus report their open time to. Never null. */
    public dev.civitas.util.Timings timings() {
        return timings;
    }

    public ConfigManager configs() {
        return configs;
    }

    public LangManager lang() {
        return lang;
    }

    public Icons icons() {
        return icons;
    }

    /** A resolved message, for a title, a label or a piece of lore. */
    public Component text(String key, TagResolver... placeholders) {
        return lang.get(key, placeholders);
    }

    /**
     * The lore a barrier carries when a viewer lacks a city permission, SPEC 8.2.
     *
     * <p>Shared so that every screen phrases it the same way and a server translating the
     * plugin has one line to translate rather than twenty.
     */
    public Component missingPermission(String permissionName) {
        return text("gui.no-permission", LangManager.placeholder("permission", permissionName));
    }

    // ==================================================================================
    // Bookkeeping
    // ==================================================================================

    void register(Menu menu) {
        open.put(menu.viewer().getUniqueId(), menu);
    }

    /** The menu this player has open, if any. */
    public Optional<Menu> openMenu(Player player) {
        return Optional.ofNullable(open.get(player.getUniqueId()));
    }

    /** Drops a session without closing the window; the caller has already closed it. */
    public void forget(Player player) {
        open.remove(player.getUniqueId());
    }

    public int openCount() {
        return open.size();
    }

    // ==================================================================================
    // The refresh tick, SPEC 8.2
    // ==================================================================================

    /** How often {@link #refreshLive()} should be called, from {@code config.yml}. */
    public long refreshTicks() {
        return configs.get(ConfigFile.CONFIG).getLong("performance.gui-refresh-ticks", 20);
    }

    /**
     * Redraws every open menu that shows live data.
     *
     * <p>A menu whose refresh throws is closed rather than left half-drawn: a screen that
     * cannot describe the world is worse than no screen, because a player will click it.
     */
    public int refreshLive() {
        int refreshed = 0;
        for (Menu menu : List.copyOf(open.values())) {
            if (!menu.live()) {
                continue;
            }
            if (!menu.viewer().isOnline()) {
                forget(menu.viewer());
                continue;
            }
            try {
                menu.refresh();
                refreshed++;
            } catch (RuntimeException e) {
                close(menu.viewer());
                throw e;
            }
        }
        return refreshed;
    }

    // ==================================================================================
    // Closing
    // ==================================================================================

    public void close(Player player) {
        Menu menu = open.remove(player.getUniqueId());
        if (menu != null) {
            player.closeInventory();
        }
    }

    /**
     * Closes every menu matching a test, telling each viewer why.
     *
     * <p>SPEC 17.1 case 11: a city disbanding closes the menus that describe it. SPEC 17.5
     * case 60: so does being kicked from it.
     *
     * @param messageKey what to tell the viewer, or null to close silently
     * @return how many were closed
     */
    public int closeIf(Predicate<Menu> test, String messageKey) {
        int closed = 0;
        for (Menu menu : List.copyOf(open.values())) {
            if (!test.test(menu)) {
                continue;
            }
            Player viewer = menu.viewer();
            open.remove(viewer.getUniqueId());
            viewer.closeInventory();
            if (messageKey != null) {
                lang.send(viewer, messageKey);
            }
            closed++;
        }
        return closed;
    }

    /** Closes everything, on plugin disable. */
    public void closeAll() {
        for (Menu menu : List.copyOf(open.values())) {
            menu.viewer().closeInventory();
        }
        open.clear();
    }

    Collection<Menu> openMenus() {
        return List.copyOf(open.values());
    }

    // ==================================================================================
    // Feedback
    // ==================================================================================

    /** SPEC 8.2: clicking a button you may not use does nothing but make a noise. */
    public void playDenied(Player player) {
        String name = configs.get(ConfigFile.GUI)
                .getString("denied.sound", "BLOCK_NOTE_BLOCK_BASS");
        float volume = (float) configs.get(ConfigFile.GUI).getDouble("denied.volume", 1.0);
        float pitch = (float) configs.get(ConfigFile.GUI).getDouble("denied.pitch", 1.0);

        Sound sound = resolveSound(name);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    /**
     * Looks up a sound by name.
     *
     * <p>Sound moved from an enum to a registry interface in modern Paper, so this goes
     * through the registry and answers null for a name the server does not know rather than
     * throwing and taking the click with it.
     */
    private static Sound resolveSound(String name) {
        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(
                name.toLowerCase(java.util.Locale.ROOT).replace('_', '.').startsWith("minecraft:")
                        ? name.toLowerCase(java.util.Locale.ROOT)
                        : "minecraft:" + name.toLowerCase(java.util.Locale.ROOT).replace('_', '.'));
        if (key == null) {
            return null;
        }
        return org.bukkit.Registry.SOUNDS.get(key);
    }
}
