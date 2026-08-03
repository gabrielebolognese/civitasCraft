package dev.civitas.gui.menus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRank;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * City Overview, SPEC 8.3 slot 10.
 *
 * <p>Everything SPEC 8.3 lists in that button's lore, at a size a player can actually read:
 * name, tag, mayor, founded date, member count, claim count and the viewer's own rank. All of
 * it comes from the caches, so opening this costs nothing.
 */
public final class OverviewMenu extends CityMenu {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy").withZone(ZoneId.systemDefault());

    public OverviewMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                        Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.overview.title", "city", city().name());
    }

    @Override
    protected boolean live() {
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();

        set(13, info(Material.BEACON, text("gui.overview.identity"),
                text("gui.overview.name", "name", city.name()),
                text("gui.overview.tag", "tag", city.tag() == null ? "-" : city.tag()),
                text("gui.overview.mayor", "mayor", nameOf(city.mayorUuid())),
                text("gui.overview.founded", "date", DATE.format(Instant.ofEpochMilli(
                        city.foundedAt())))));

        set(20, info(Material.PLAYER_HEAD, text("gui.overview.population"),
                text("gui.overview.members", "count", String.valueOf(city.memberCount())),
                text("gui.overview.your-rank", "rank", rankName(city))));

        set(22, info(Material.GRASS_BLOCK, text("gui.overview.land"),
                text("gui.overview.claims", "count",
                        String.valueOf(services.claimRegistry().claimsOf(city.id()).size())),
                text("gui.overview.land-value", "value",
                        money(services.claims().landValueOf(city.id())))));

        set(24, info(Material.GOLD_INGOT, text("gui.overview.finance"),
                text("gui.overview.treasury", "amount", money(city.treasury())),
                text("gui.overview.upkeep", "amount",
                        money(services.upkeepTask().amountFor(city))),
                city.isDelinquent()
                        ? text("gui.overview.delinquent")
                        : text("gui.overview.solvent")));

        if (city.motd() != null && !city.motd().isBlank()) {
            set(31, info(Material.PAPER, text("gui.overview.motd"),
                    text("gui.overview.motd-value", "motd", city.motd())));
        }
    }

    private String rankName(City city) {
        return city.rankOf(viewer.getUniqueId())
                .map(CityRank::name)
                .orElse("-");
    }
}
