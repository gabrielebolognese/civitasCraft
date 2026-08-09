package dev.civitas.gui.menus;

import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.core.outpost.OutpostCostEngine;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * SPEC 39.12 slot 32: the cost formula in plain language, with this city's numbers in it.
 *
 * <p>SPEC 39.11 argues for this directly — "a formula with four terms is opaque unless the game
 * shows its work, and a player about to spend two million coins deserves to see exactly why" —
 * and a screen can do something the {@code /city outpost cost} command cannot: put each term
 * beside what it means, rather than in a list a player has to decode.
 *
 * <p>Every figure here is the city's own. A worked example with invented numbers would explain
 * the formula and answer nobody's actual question, which is "why is this one expensive".
 */
public final class OutpostCostMenu extends CityMenu {

    public OutpostCostMenu(MenuManager manager, CivitasServices services, Player viewer,
                           City city, Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.outpost-cost.title");
    }

    @Override
    protected boolean live() {
        // The distance term follows the player around, which is the point: walking further out
        // and watching the multiplier climb explains the curve better than any sentence.
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();
        var service = services.outposts();
        var at = viewer.getLocation();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;

        double blocks = service.blocksFromCore(city, chunkX, chunkZ);
        OutpostCostEngine.Breakdown costs = service.priceBreakdown(city, 1, chunkX, chunkZ);
        int chunks = service.registry().of(city.id()).stream()
                .mapToInt(service::chunkCount).sum()
                + services.claims().registry().claimsOf(city.id()).size();

        set(4, info(Material.BOOK, text("gui.outpost-cost.header"),
                text("gui.outpost-cost.intro")));

        // One slot per term, each stating what the term is, what it is worth here, and why it
        // is that. The "why" is what makes this a explainer rather than a second breakdown.
        set(19, info(Material.GRASS_BLOCK, text("gui.outpost-cost.base"),
                text("gui.outpost-cost.base-value", "amount", money(costs.base())),
                text("gui.outpost-cost.base-why", "chunks", String.valueOf(chunks))));

        set(21, info(Material.COMPASS, text("gui.outpost-cost.distance"),
                text("gui.outpost-cost.distance-value",
                        "multiplier", OutpostsMenu.twoPlaces(costs.distance()),
                        "blocks", dev.civitas.msg.Formats.count(Math.round(blocks))),
                text("gui.outpost-cost.distance-why")));

        set(23, info(Material.SCAFFOLDING, text("gui.outpost-cost.chunk"),
                text("gui.outpost-cost.chunk-value",
                        "multiplier", OutpostsMenu.twoPlaces(costs.factor())),
                text("gui.outpost-cost.chunk-why")));

        set(25, info(Material.PLAYER_HEAD, text("gui.outpost-cost.members"),
                text("gui.outpost-cost.members-value",
                        "divisor", OutpostsMenu.twoPlaces(costs.divisor())),
                text("gui.outpost-cost.members-why")));

        set(31, info(Material.GOLD_INGOT, text("gui.outpost-cost.total"),
                text("gui.outpost-cost.total-value", "amount", money(costs.total())),
                text("gui.outpost-cost.total-where",
                        "x", String.valueOf(chunkX * 16), "z", String.valueOf(chunkZ * 16))));
    }
}
