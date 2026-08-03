package dev.civitas.gui.menus;

import java.util.UUID;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.ConfirmationMenu;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * What can be done to one member, SPEC 8.6.
 *
 * <p>Promote, demote, set rank and kick, each gated on the permission SPEC 5.4 assigns it and
 * each re-checked at click time. The outranking rule from SPEC 5.4 is enforced by the rank
 * service underneath, and shown here as lore, so a player can see why a button will refuse
 * them before they press it.
 */
public final class MemberActionsMenu extends CityMenu {

    private final UUID target;

    public MemberActionsMenu(MenuManager manager, CivitasServices services, Player viewer,
                             City city, UUID target, Menu parent) {
        super(manager, services, viewer, city, parent);
        this.target = target;
    }

    @Override
    protected Component title() {
        return text("gui.member-actions.title", "player", nameOf(target));
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();

        set(13, info(Material.PLAYER_HEAD, text("gui.member-actions.who", "player",
                        nameOf(target)),
                text("gui.members.rank", "rank", city.rankOf(target)
                        .map(rank -> rank.name()).orElse("-")),
                text("gui.members.contributed", "amount", city.member(target)
                        .map(member -> money(member.contributedTotal()))
                        .orElse("-"))));

        set(20, gated(Material.EMERALD, text("gui.member-actions.promote"),
                CityPermission.MANAGE_RANKS)
                .lore(text("gui.member-actions.promote.lore"))
                .onClick(context -> run(context.player(),
                        services.ranks().promote(context.player().getUniqueId(), city(), target),
                        "gui.member-actions.promoted"))
                .build());

        set(22, gated(Material.REDSTONE, text("gui.member-actions.demote"),
                CityPermission.MANAGE_RANKS)
                .lore(text("gui.member-actions.demote.lore"))
                .onClick(context -> run(context.player(),
                        services.ranks().demote(context.player().getUniqueId(), city(), target),
                        "gui.member-actions.demoted"))
                .build());

        set(24, gated(Material.NAME_TAG, text("gui.member-actions.set-rank"),
                CityPermission.MANAGE_RANKS)
                .onClick(context -> new RankPickerMenu(manager, services, viewer, city(), target,
                        this).open())
                .build());

        set(31, gated(Material.BARRIER, text("gui.member-actions.kick"), CityPermission.KICK)
                .lore(text("gui.member-actions.kick.lore"))
                .onClick(context -> ConfirmationMenu.builder(manager, context.player())
                        .title(text("gui.member-actions.kick.confirm-title"))
                        .question(text("gui.member-actions.kick.confirm", "player",
                                nameOf(target)))
                        .parent(this)
                        .onConfirm(() -> services.cities()
                                .kick(context.player().getUniqueId(), city(), target)
                                .whenComplete((result, error) ->
                                        services.scheduler().runOnMain(() -> {
                                            report(context.player(), result, error,
                                                    "gui.member-actions.kicked");
                                            parent().ifPresent(Menu::open);
                                        })))
                        .onCancel(this::open)
                        .build()
                        .open())
                .build());
    }

    private <T> void run(Player player, java.util.concurrent.CompletableFuture<Result<T>> pending,
                         String successKey) {
        pending.whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
            report(player, result, error, successKey);
            refresh();
        }));
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
