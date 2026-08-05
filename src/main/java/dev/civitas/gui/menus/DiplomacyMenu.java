package dev.civitas.gui.menus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.diplomacy.Alliance;
import dev.civitas.core.diplomacy.AllianceState;
import dev.civitas.core.diplomacy.DiplomacyRegistry;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.ConfirmationMenu;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Alliances and truces, SPEC 8.3 slot 24.
 *
 * <p>Rows rather than a grid: alliances on the top row, proposals waiting for an answer on
 * the second, running truces on the third. Each of the three is a different decision, and
 * mixing them would make it easy to accept a proposal while meaning to break an alliance.
 */
public final class DiplomacyMenu extends CityMenu {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());

    private static final int[] ALLY_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] PENDING_SLOTS = {19, 20, 21, 22, 23, 24, 25};
    private static final int[] TRUCE_SLOTS = {28, 29, 30, 31, 32, 33, 34};

    public DiplomacyMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                         Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.diplomacy.title", "city", city().name());
    }

    @Override
    protected boolean live() {
        // Notice periods count down and truces expire while the screen is open.
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();
        long now = System.currentTimeMillis();
        DiplomacyRegistry registry = services.diplomacy().registry();

        List<Alliance> allies = registry.activeAlliancesOf(city.id());
        for (int index = 0; index < allies.size() && index < ALLY_SLOTS.length; index++) {
            set(ALLY_SLOTS[index], allyButton(allies.get(index), city));
        }
        if (allies.isEmpty()) {
            set(ALLY_SLOTS[0], info(Material.GRAY_DYE, text("gui.diplomacy.no-allies")));
        }

        List<Alliance> pending = registry.pendingFor(city.id());
        for (int index = 0; index < pending.size() && index < PENDING_SLOTS.length; index++) {
            set(PENDING_SLOTS[index], pendingButton(pending.get(index), city));
        }
        if (pending.isEmpty()) {
            set(PENDING_SLOTS[0], info(Material.GRAY_DYE, text("gui.diplomacy.no-proposals")));
        }

        List<DiplomacyRegistry.Truce> truces = registry.trucesOf(city.id(), now);
        for (int index = 0; index < truces.size() && index < TRUCE_SLOTS.length; index++) {
            set(TRUCE_SLOTS[index], truceButton(truces.get(index)));
        }
        if (truces.isEmpty()) {
            set(TRUCE_SLOTS[0], info(Material.GRAY_DYE, text("gui.diplomacy.no-truces")));
        }

        set(40, info(Material.WRITTEN_BOOK, text("gui.diplomacy.summary"),
                text("gui.diplomacy.count",
                        "count", String.valueOf(allies.size()),
                        "max", String.valueOf(services.diplomacy().maxAllies())),
                text("gui.diplomacy.invite-hint"),
                text("gui.diplomacy.truce-hint")));
    }

    // ==================================================================================
    // Buttons
    // ==================================================================================

    private Button allyButton(Alliance alliance, City city) {
        String name = nameOf(alliance.otherThan(city.id()));
        boolean breaking = alliance.state() == AllianceState.BREAKING;

        Button.Builder builder = Button.of(
                        breaking ? Material.ORANGE_BANNER : Material.WHITE_BANNER,
                        text("gui.diplomacy.ally", "city", name))
                .lore(alliance.trusted()
                        ? text("gui.diplomacy.ally.trusted")
                        : text("gui.diplomacy.ally.untrusted"));

        if (breaking) {
            builder.lore(text("gui.diplomacy.ally.breaking", "hours",
                    String.valueOf(services.diplomacy().hoursLeftOfNotice(alliance))));
        } else {
            builder.lore(text("gui.diplomacy.ally.toggle-trust"))
                    .lore(text("gui.diplomacy.ally.break-hint"));
        }

        return builder
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.MANAGE_DIPLOMACY),
                        manager.missingPermission(CityPermission.MANAGE_DIPLOMACY.name()))
                .onClick(context -> {
                    if (breaking) {
                        return;
                    }
                    if (context.isShiftClick()) {
                        confirmBreak(context.player(), alliance, name);
                        return;
                    }
                    services.diplomacy().setTrusted(context.player().getUniqueId(), city(),
                                    other(alliance, city), !alliance.trusted())
                            .whenComplete((result, error) ->
                                    services.scheduler().runOnMain(() -> {
                                        report(context.player(), result, error,
                                                alliance.trusted()
                                                        ? "gui.diplomacy.untrusted"
                                                        : "gui.diplomacy.trusted");
                                        open();
                                    }));
                })
                .build();
    }

    private Button pendingButton(Alliance alliance, City city) {
        String name = nameOf(alliance.otherThan(city.id()));

        return Button.of(Material.PAPER, text("gui.diplomacy.pending", "city", name))
                .lore(text("gui.diplomacy.pending.accept"))
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.MANAGE_DIPLOMACY),
                        manager.missingPermission(CityPermission.MANAGE_DIPLOMACY.name()))
                .onClick(context -> services.diplomacy()
                        .accept(context.player().getUniqueId(), city(), other(alliance, city))
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(context.player(), result, error, "gui.diplomacy.accepted");
                            open();
                        })))
                .build();
    }

    private Button truceButton(DiplomacyRegistry.Truce truce) {
        // No click handler: SPEC 14.3 says a truce cannot be cancelled early by either
        // party, so there is nothing here for anyone to do.
        return info(Material.LIGHT_BLUE_BANNER,
                text("gui.diplomacy.truce", "city", nameOf(truce.otherCityId())),
                text("gui.diplomacy.truce.until",
                        "when", WHEN.format(Instant.ofEpochMilli(truce.expiresAt()))),
                text("gui.diplomacy.truce.permanent"));
    }

    private void confirmBreak(Player player, Alliance alliance, String name) {
        ConfirmationMenu.builder(manager, player)
                .title(text("gui.diplomacy.break.title"))
                .question(text("gui.diplomacy.break.confirm", "city", name))
                .detail(text("gui.diplomacy.break.notice", "hours",
                        String.valueOf(services.diplomacy().noticeHours())))
                .detail(text("gui.diplomacy.break.cooldown", "days",
                        String.valueOf(services.diplomacy().reAllyCooldownDays())))
                .parent(this)
                .onConfirm(() -> services.diplomacy()
                        .breakAlliance(player.getUniqueId(), city(), other(alliance, city()))
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(player, result, error, "gui.diplomacy.breaking");
                            open();
                        })))
                .onCancel(this::open)
                .build()
                .open();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private City other(Alliance alliance, City city) {
        return services.registry().city(alliance.otherThan(city.id())).orElse(city);
    }

    private String nameOf(int cityId) {
        return services.registry().city(cityId).map(City::name).orElse("?");
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
