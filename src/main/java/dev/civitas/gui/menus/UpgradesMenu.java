package dev.civitas.gui.menus;

import java.math.BigDecimal;
import java.util.Optional;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.upgrade.UpgradeType;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.ConfirmationMenu;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The six upgrade tracks, SPEC 5.7 and SPEC 8.3 slot 28.
 *
 * <p>Each track shows what it does, what level the city holds, and what the next one costs.
 * A maxed track glows; one the city cannot afford still shows its price, because "you need
 * 320,000 C" is a goal and a missing button is a mystery.
 */
public final class UpgradesMenu extends CityMenu {

    /** One track per slot, in the SPEC 5.7 table's order. */
    private static final int[] SLOTS = {10, 12, 14, 28, 30, 32};

    public UpgradesMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                        Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.upgrades.title", "city", city().name());
    }

    @Override
    protected boolean live() {
        // The treasury moves, and with it what the city can afford.
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();
        UpgradeType[] types = UpgradeType.values();

        for (int index = 0; index < types.length && index < SLOTS.length; index++) {
            set(SLOTS[index], trackButton(city, types[index]));
        }

        set(22, info(Material.PAPER, text("gui.upgrades.summary"),
                text("gui.upgrades.bought", "bought",
                        String.valueOf(services.upgrades().totalLevels(city.id())),
                        "total", String.valueOf(types.length * UpgradeType.MAX_LEVEL)),
                text("gui.upgrades.treasury", "amount", money(city.treasury()))));
    }

    private Button trackButton(City city, UpgradeType type) {
        UpgradeService upgrades = services.upgrades();
        int level = upgrades.levelOf(city, type);
        boolean maxed = level >= UpgradeType.MAX_LEVEL;

        Button.Builder builder = Button.of(iconFor(type),
                        text("gui.upgrades.track", "name", upgrades.displayName(type)))
                .lore(text(type.messageKey()))
                .lore(text("gui.upgrades.level", "level", String.valueOf(level),
                        "max", String.valueOf(UpgradeType.MAX_LEVEL)))
                .glowing(maxed);

        if (maxed) {
            builder.lore(text("gui.upgrades.maxed"));
            return builder.build();
        }

        Optional<BigDecimal> cost = upgrades.nextCost(city, type);
        if (cost.isEmpty()) {
            builder.lore(text("gui.upgrades.no-cost"));
            return builder.build();
        }

        builder.lore(text("gui.upgrades.next-cost", "amount", money(cost.get())));
        if (city.treasury().compareTo(cost.get()) < 0) {
            builder.lore(text("gui.upgrades.cannot-afford"));
        }

        return builder
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.MANAGE_UPGRADES),
                        manager.missingPermission(CityPermission.MANAGE_UPGRADES.name()))
                .onClick(context -> confirm(context.player(), type, level + 1, cost.get()))
                .build();
    }

    /**
     * Upgrades are permanent and expensive, so they are confirmed.
     *
     * <p>SPEC 8.2 asks for a confirmation on "all destructive actions"; spending 600,000 C of
     * a city's money by misclicking is not destructive in the sense SPEC means, but it is
     * irreversible, which is the property that matters.
     */
    private void confirm(Player player, UpgradeType type, int level, BigDecimal cost) {
        ConfirmationMenu.builder(manager, player)
                .title(text("gui.upgrades.confirm-title"))
                .question(text("gui.upgrades.confirm",
                        "name", services.upgrades().displayName(type),
                        "level", String.valueOf(level)))
                .detail(text("gui.upgrades.next-cost", "amount", money(cost)))
                .detail(text("gui.upgrades.permanent"))
                .parent(this)
                .onConfirm(() -> services.upgrades().purchase(player.getUniqueId(), city(), type)
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(player, result, error);
                            open();
                        })))
                .onCancel(this::open)
                .build()
                .open();
    }

    private void report(Player player, Result<UpgradeService.Purchase> result, Throwable error) {
        if (error != null) {
            manager.lang().send(player, "command.error");
            return;
        }
        if (result instanceof Result.Failure<UpgradeService.Purchase> failure) {
            Replies.sendFailure(player, manager.lang(), failure);
            return;
        }
        UpgradeService.Purchase purchase = result.orElseThrow();
        manager.lang().send(player, "upgrade.purchased",
                dev.civitas.lang.LangManager.placeholder("name",
                        services.upgrades().displayName(purchase.type())),
                dev.civitas.lang.LangManager.placeholder("level",
                        String.valueOf(purchase.level())));
    }

    private static Material iconFor(UpgradeType type) {
        return switch (type) {
            case POPULATION -> Material.PLAYER_HEAD;
            case VAULT -> Material.ENDER_CHEST;
            case TREASURY_INTEREST -> Material.GOLD_INGOT;
            case OUTPOST_RANGE -> Material.FILLED_MAP;
            case FORTIFICATION -> Material.IRON_GOLEM_SPAWN_EGG;
            case MARKET_ACCESS -> Material.EMERALD;
        };
    }
}
