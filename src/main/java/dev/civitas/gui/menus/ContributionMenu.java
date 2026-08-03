package dev.civitas.gui.menus;

import java.util.Comparator;
import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityMember;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.gui.framework.PaginatedMenu;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Members ranked by lifetime treasury deposits, SPEC 8.5 slot 40.
 *
 * <p>SPEC 1.3 wants contribution to be visible: a player who joined on day 90 and has paid in
 * steadily should be able to point at something. This is that something, and it is why
 * {@code city_members.contributed_total} is a running total rather than a derived figure.
 */
public final class ContributionMenu extends PaginatedMenu<CityMember> {

    private final CivitasServices services;
    private final int cityId;

    public ContributionMenu(MenuManager manager, CivitasServices services, Player viewer,
                            City city, Menu parent) {
        super(manager, viewer, parent);
        this.services = services;
        this.cityId = city.id();
    }

    @Override
    protected Component title() {
        return manager.text("gui.contributions.title");
    }

    @Override
    protected boolean live() {
        return true;
    }

    @Override
    protected List<CityMember> contents() {
        return services.registry().city(cityId)
                .map(city -> city.members().stream()
                        .sorted(Comparator.comparing(CityMember::contributedTotal).reversed())
                        .toList())
                .orElse(List.of());
    }

    @Override
    protected Button entryButton(CityMember member, int index) {
        String name = org.bukkit.Bukkit.getOfflinePlayer(member.uuid()).getName();
        return Button.of(index == 0 ? Material.GOLD_INGOT : Material.PLAYER_HEAD,
                        manager.text("gui.contributions.entry",
                                LangManager.placeholder("rank", String.valueOf(index + 1)),
                                LangManager.placeholder("player",
                                        name == null ? member.uuid().toString() : name)))
                .lore(manager.text("gui.contributions.amount",
                        LangManager.placeholder("amount",
                                dev.civitas.core.economy.Money.format(member.contributedTotal(),
                                        services.economy().configs()))))
                .build();
    }
}
