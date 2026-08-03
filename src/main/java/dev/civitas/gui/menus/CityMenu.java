package dev.civitas.gui.menus;

import java.util.Objects;

import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuLayout;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * A screen about a particular city.
 *
 * <p>Everything in SPEC Section 8 is one of these, so the two rules they all share live here.
 * <b>The city is re-read on every draw</b>, never held from when the menu opened, and a
 * viewer who is no longer a member of it, or whose city no longer exists, has the window
 * taken away rather than left as a stale door into somebody else's city. That is SPEC 17.1
 * case 11 and SPEC 17.5 case 60, and putting it in the base class is what stops it being
 * remembered in twelve screens and forgotten in the thirteenth.
 */
public abstract class CityMenu extends Menu {

    protected final CivitasServices services;
    private final int cityId;

    protected CityMenu(MenuManager manager, CivitasServices services, Player viewer, City city) {
        super(manager, viewer);
        this.services = Objects.requireNonNull(services, "services");
        this.cityId = city.id();
    }

    protected CityMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                       Menu parent) {
        super(manager, viewer, parent);
        this.services = Objects.requireNonNull(services, "services");
        this.cityId = city.id();
    }

    /**
     * The city this screen is about, as it is right now.
     *
     * @throws IllegalStateException if it has gone; callers reach it only through
     *                               {@link #build()}, which {@link #stillValid()} guards
     */
    protected final City city() {
        return services.registry().city(cityId)
                .orElseThrow(() -> new IllegalStateException("City " + cityId + " is gone"));
    }

    /** Whether this screen still has anything to describe. */
    protected final boolean stillValid() {
        return services.registry().city(cityId)
                .filter(city -> !city.isDeleted())
                .filter(city -> city.isMember(viewer.getUniqueId()))
                .isPresent();
    }

    /**
     * Closes the window if the city or the membership behind it has gone.
     *
     * <p>Called at the top of every draw, so it catches both the refresh tick and the moment
     * a player clicks something.
     */
    protected final boolean closeIfStale() {
        if (stillValid()) {
            return false;
        }
        manager.forget(viewer);
        viewer.closeInventory();
        manager.lang().send(viewer, "gui.city-gone");
        return true;
    }

    // ==================================================================================
    // Shared button shapes
    // ==================================================================================

    /**
     * A button gated on a city permission, checked afresh at click time.
     *
     * <p>The permission is read from the live city rather than captured, so a rank change
     * takes effect on the next click rather than on the next reopen (SPEC 17.5 case 59).
     */
    protected final Button.Builder gated(Material material, Component label,
                                         CityPermission permission) {
        return Button.of(material, label)
                .requires(player -> services.registry().city(cityId)
                                .filter(city -> city.hasPermission(player.getUniqueId(), permission))
                                .isPresent(),
                        manager.missingPermission(permission.name()));
    }

    /**
     * A button for a screen whose system this server does not have yet.
     *
     * <p>SPEC 8.3's hub names thirteen screens, and seven of them belong to milestones that
     * have not been built. Keeping their slots and icons and refusing the click is honest
     * about what exists; inventing a Wars screen with no war system would not be.
     */
    protected final Button unavailable(MenuLayout.Entry entry) {
        return Button.of(entry.material(), manager.text(entry.labelKey()))
                .requires(player -> false, manager.text("gui.not-available"))
                .build();
    }

    /** A plain informational icon: something to read, nothing to click. */
    protected final Button info(Material material, Component label, Component... lore) {
        Button.Builder builder = Button.of(material, label);
        for (Component line : lore) {
            builder.lore(line);
        }
        return builder.build();
    }

    protected final Component text(String key, String... placeholderPairs) {
        if (placeholderPairs.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders come in name/value pairs");
        }
        var resolvers = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[
                placeholderPairs.length / 2];
        for (int i = 0; i < resolvers.length; i++) {
            resolvers[i] = LangManager.placeholder(placeholderPairs[i * 2],
                    placeholderPairs[i * 2 + 1]);
        }
        return manager.text(key, resolvers);
    }

    /** Money as a player should read it. */
    protected final String money(java.math.BigDecimal amount) {
        return dev.civitas.core.economy.Money.format(amount, services.economy().configs());
    }

    /** A name for a UUID, from the cache rather than a database round trip. */
    protected final String nameOf(java.util.UUID uuid) {
        org.bukkit.OfflinePlayer offline = org.bukkit.Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }
}
