package dev.civitas.gui.menus;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Picking a rank for a member, SPEC 8.6.
 *
 * <p>Ranks the clicker may not assign are still shown, as barriers with the reason: SPEC 5.4
 * forbids editing a rank at or above your own weight, and a list that silently omits those
 * would leave a Co-Mayor wondering where the Mayor rank went.
 */
public final class RankPickerMenu extends CityMenu {

    private final UUID target;

    public RankPickerMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                          UUID target, Menu parent) {
        super(manager, services, viewer, city, parent);
        this.target = target;
    }

    @Override
    protected Component title() {
        return text("gui.rank-picker.title", "player", nameOf(target));
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

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int index = 0; index < ranks.size() && index < slots.length; index++) {
            set(slots[index], rankButton(city, ranks.get(index)));
        }
    }

    private Button rankButton(City city, CityRank rank) {
        boolean current = city.rankOf(target).filter(held -> held.id() == rank.id()).isPresent();

        return Button.of(current ? Material.LIME_DYE : Material.NAME_TAG,
                        text("gui.rank-picker.rank", "rank", rank.name()))
                .lore(text("gui.rank-picker.weight", "weight", String.valueOf(rank.weight())))
                .lore(current ? text("gui.rank-picker.current") : text("gui.rank-picker.assign"))
                .glowing(current)
                .requires(player -> {
                    City live = city();
                    return live.hasPermission(player.getUniqueId(), CityPermission.MANAGE_RANKS)
                            && live.weightOf(player.getUniqueId()) > rank.weight();
                }, text("gui.rank-picker.outranked"))
                .onClick(context -> services.ranks()
                        .assign(context.player().getUniqueId(), city(), target, rank)
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(context.player(), result, error, "gui.rank-picker.assigned");
                            refresh();
                        })))
                .build();
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
}
