package dev.civitas.gui.menus;

import java.util.List;
import java.util.UUID;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.gui.framework.PaginatedMenu;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The city ban list, SPEC 8.6 slot 46.
 *
 * <p>M2 built the ban list and its table because joining has to check it, and deliberately
 * added no command for it: SPEC 9.2 lists none, and SPEC 8.6 puts it in a menu. This is that
 * menu, and it is the only way to work the list.
 */
public final class BanListMenu extends PaginatedMenu<UUID> {

    private final CivitasServices services;
    private final int cityId;

    public BanListMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                       Menu parent) {
        super(manager, viewer, parent);
        this.services = services;
        this.cityId = city.id();
    }

    private City city() {
        return services.registry().city(cityId).orElseThrow();
    }

    @Override
    protected Component title() {
        return manager.text("gui.bans.title");
    }

    @Override
    protected List<UUID> contents() {
        return services.registry().city(cityId)
                .map(city -> List.copyOf(city.bannedPlayers()))
                .orElse(List.of());
    }

    @Override
    protected Button entryButton(UUID banned, int index) {
        City city = city();
        return Button.of(Material.PLAYER_HEAD,
                        manager.text("gui.bans.entry",
                                LangManager.placeholder("player", nameOf(banned))))
                .lore(manager.text("gui.bans.reason", LangManager.placeholder("reason",
                        city.banReason(banned).orElse("-"))))
                .lore(manager.text("gui.bans.unban-hint"))
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.KICK),
                        manager.missingPermission(CityPermission.KICK.name()))
                .onClick(context -> services.cities()
                        .unban(context.player().getUniqueId(), city(), banned)
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(context.player(), result, error, "gui.bans.unbanned");
                            refresh();
                        })))
                .build();
    }

    @Override
    protected void decorate() {
        if (contents().isEmpty()) {
            set(22, Button.of(Material.PAPER, manager.text("gui.bans.empty")).build());
        }
    }

    private <T> void report(Player player, Result<T> result, Throwable error, String successKey) {
        if (error != null) {
            manager.lang().send(player, "command.error");
            return;
        }
        if (result instanceof Result.Failure<T> failure) {
            Replies.sendFailure(player, manager.lang(), failure);
            return;
        }
        manager.lang().send(player, successKey);
    }

    private String nameOf(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }
}
