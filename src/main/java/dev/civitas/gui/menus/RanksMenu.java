package dev.civitas.gui.menus;

import java.util.Comparator;
import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The rank list, SPEC 8.7.
 *
 * <p>One row per rank, highest weight first, each showing its weight and how many members
 * hold it. Clicking one opens the permission editor.
 */
public final class RanksMenu extends CityMenu {

    public static final String LAYOUT = "ranks.yml";

    public RanksMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                     Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.ranks.title", "city", city().name());
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();
        List<CityRank> ranks = city.ranks().stream()
                .sorted(Comparator.comparingInt(CityRank::weight).reversed())
                .toList();

        // One row each, which is what SPEC 8.7 asks for and what keeps the weights readable
        // as an ordering rather than as a list of numbers.
        for (int index = 0; index < ranks.size() && index < 4; index++) {
            set(10 + index * 9, rankButton(city, ranks.get(index)));
        }
    }

    private Button rankButton(City city, CityRank rank) {
        long holders = city.membersWithRank(rank.id());

        return Button.of(iconFor(rank), text("gui.ranks.rank", "rank", rank.name()))
                .lore(text("gui.ranks.weight", "weight", String.valueOf(rank.weight())))
                .lore(text("gui.ranks.holders", "count", String.valueOf(holders)))
                .lore(text("gui.ranks.permissions", "count",
                        String.valueOf(rank.permissions().size())))
                .lore(rank.isDefault() ? text("gui.ranks.default") : Component.empty())
                .requires(player -> {
                    City live = city();
                    return live.hasPermission(player.getUniqueId(), CityPermission.MANAGE_RANKS)
                            && live.weightOf(player.getUniqueId()) > rank.weight();
                }, text("gui.ranks.outranked"))
                .onClick(context -> new PermissionEditorMenu(manager, services, viewer, city(),
                        rank, this).open())
                .build();
    }

    /** A visual ordering, so the hierarchy reads at a glance rather than from the numbers. */
    private static Material iconFor(CityRank rank) {
        if (rank.weight() >= CityRank.MAYOR_WEIGHT) {
            return Material.GOLDEN_HELMET;
        }
        if (rank.weight() >= 60) {
            return Material.IRON_HELMET;
        }
        return rank.weight() >= 40 ? Material.CHAINMAIL_HELMET : Material.LEATHER_HELMET;
    }
}
