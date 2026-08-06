package dev.civitas.gui.menus;

import java.math.BigDecimal;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.config.ConfigFile;
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
 * City settings, SPEC 8.10.
 *
 * <p>The screen where a city can be changed and, at the bottom right, ended. SPEC 8.10 asks
 * for a double confirmation on disband and this gives it one: a confirmation dialog, and then
 * the city's own name typed in chat. Nobody disbands a city by misclicking twice.
 */
public final class SettingsMenu extends CityMenu {

    public static final String LAYOUT = "settings.yml";

    public SettingsMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                        Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.settings.title", "city", city().name());
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();

        set(12, gated(Material.PAPER, text("gui.settings.motd"), CityPermission.EDIT_SETTINGS)
                .lore(text("gui.settings.motd-current", "motd",
                        city.motd() == null || city.motd().isBlank() ? "-" : city.motd()))
                .onClick(context -> services.amountInput().askText(context.player(),
                        "gui.settings.motd-prompt",
                        typed -> services.cities()
                                .setMotd(context.player().getUniqueId(), city(), typed)
                                .whenComplete((result, error) ->
                                        services.scheduler().runOnMain(() -> {
                                            report(context.player(), result, error,
                                                    "gui.settings.motd-set");
                                            open();
                                        })),
                        this::open))
                .build());

        set(14, gated(Material.OAK_DOOR, text("gui.settings.open-join"),
                CityPermission.EDIT_SETTINGS)
                .lore(city.isOpenJoin()
                        ? text("gui.settings.open-join-on")
                        : text("gui.settings.open-join-off"))
                .glowing(city.isOpenJoin())
                .onClick(context -> services.cities()
                        .setOpenJoin(context.player().getUniqueId(), city(), !city().isOpenJoin())
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(context.player(), result, error, "gui.settings.open-join-set");
                            refresh();
                        })))
                .build());

        set(16, gated(Material.COMPASS, text("gui.settings.set-spawn"), CityPermission.SET_SPAWN)
                .lore(text("gui.settings.set-spawn-lore"))
                .onClick(context -> {
                    var location = context.player().getLocation();
                    services.cities().setSpawn(context.player().getUniqueId(), city(),
                                    location.getWorld().getName(), location.getX(),
                                    location.getY(), location.getZ(), location.getYaw(),
                                    location.getPitch())
                            .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                                report(context.player(), result, error, "gui.settings.spawn-set");
                                refresh();
                            }));
                })
                .build());

        set(22, gated(Material.NAME_TAG, text("gui.settings.ranks"), CityPermission.MANAGE_RANKS)
                .lore(text("gui.settings.ranks-lore"))
                .onClick(context -> new RanksMenu(manager, services, viewer, city(), this).open())
                .build());

        set(28, gated(Material.WRITABLE_BOOK, text("gui.settings.rename"),
                CityPermission.EDIT_SETTINGS)
                .lore(text("gui.settings.rename-cost", "amount", money(renameCost())))
                .onClick(context -> services.amountInput().askText(context.player(),
                        "gui.settings.rename-prompt",
                        typed -> services.cities()
                                .rename(context.player().getUniqueId(), city(), typed)
                                .whenComplete((result, error) ->
                                        services.scheduler().runOnMain(() -> {
                                            report(context.player(), result, error,
                                                    "gui.settings.renamed");
                                            open();
                                        })),
                        this::open))
                .build());

        set(30, gated(Material.PLAYER_HEAD, text("gui.settings.transfer"),
                CityPermission.TRANSFER)
                .lore(text("gui.settings.transfer-lore"))
                .onClick(context -> services.amountInput().askText(context.player(),
                        "gui.settings.transfer-prompt",
                        typed -> transferTo(context.player(), typed),
                        this::open))
                .build());

        set(34, gated(Material.TNT, text("gui.settings.disband"), CityPermission.DISBAND)
                .lore(text("gui.settings.disband-lore"))
                .onClick(context -> confirmDisband(context.player()))
                .build());
    }

    // ==================================================================================
    // The two dangerous ones
    // ==================================================================================

    private void transferTo(Player player, String typed) {
        services.lookup().resolve(typed).whenComplete((found, error) ->
                services.scheduler().runOnMain(() -> {
                    if (error != null || found == null || found.isEmpty()) {
                        manager.lang().send(player, "player.unknown",
                                dev.civitas.lang.LangManager.placeholder("player", typed));
                        open();
                        return;
                    }
                    Result<Void> offered = services.cities().offerTransfer(
                            player.getUniqueId(), city(), found.get().uuid(),
                            found.get().online());
                    report(player, offered, null, "gui.settings.transfer-offered");
                    open();
                }));
    }

    /**
     * SPEC 8.10: double confirmation, and the city's name typed out.
     *
     * <p>The dialog catches the misclick; the typed name catches the player who clicks
     * through dialogs without reading them, which is most of us.
     */
    private void confirmDisband(Player player) {
        ConfirmationMenu.builder(manager, player)
                .title(text("gui.settings.disband-confirm-title"))
                .question(text("gui.settings.disband-confirm", "city", city().name()))
                .detail(text("gui.settings.disband-detail-refund"))
                .detail(text("gui.settings.disband-detail-final"))
                .parent(this)
                .onConfirm(() -> services.amountInput().askText(player,
                        "gui.settings.disband-prompt",
                        typed -> {
                            if (!typed.equalsIgnoreCase(city().name())) {
                                manager.lang().send(player, "gui.settings.disband-name-mismatch");
                                open();
                                return;
                            }
                            services.cities().disband(player.getUniqueId(), city())
                                    .whenComplete((result, error) ->
                                            services.scheduler().runOnMain(() -> {
                                                report(player, result, error,
                                                        "gui.settings.disbanded");
                                                manager.close(player);
                                            }));
                        },
                        this::open))
                .onCancel(this::open)
                .build()
                .open();
    }

    private BigDecimal renameCost() {
        return new BigDecimal(manager.configs().get(ConfigFile.CITIES)
                .getString("creation.rename-cost", "15000"));
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
