package dev.civitas.gui.menus;

import java.math.BigDecimal;
import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.defense.DefenseUnitType;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The defense unit shop, SPEC 8.9.
 *
 * <p>A grid of what SPEC 12.2 sells, each showing cost, daily upkeep, stats and how many the
 * city already has. Clicking buys one and hands over its egg, which SPEC 12.4 then requires
 * the player to place by hand.
 */
public final class DefenseMenu extends CityMenu {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19};

    public DefenseMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                       Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.defense.title", "city", city().name());
    }

    @Override
    protected boolean live() {
        // Units die, and the treasury moves.
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();
        List<DefenseUnitType> catalogue = services.defense().catalogue().all();

        for (int index = 0; index < catalogue.size() && index < SLOTS.length; index++) {
            set(SLOTS[index], unitButton(city, catalogue.get(index)));
        }

        set(31, info(Material.PAPER, text("gui.defense.summary"),
                text("gui.defense.active", "active",
                        String.valueOf(services.defense().registry().activeCount(city.id())),
                        "max", String.valueOf(services.defense().maxUnits(city))),
                text("gui.defense.upkeep", "amount",
                        money(services.defense().registry().dailyUpkeep(city.id()))),
                text("gui.defense.per-chunk", "limit", String.valueOf(
                        services.defense().catalogue().maxUnitsPerChunk()))));
    }

    private Button unitButton(City city, DefenseUnitType type) {
        BigDecimal cost = services.defense().costFor(city, type);

        Button.Builder builder = Button.of(iconFor(type),
                        text("gui.defense.unit", "name", type.displayName()))
                .lore(text(type.messageKey()))
                .lore(text("gui.defense.stats",
                        "health", trim(type.health()),
                        "damage", trim(type.damage())))
                .lore(text("gui.defense.cost", "amount", money(cost)))
                .lore(text("gui.defense.unit-upkeep", "amount", money(type.upkeepPerDay())));

        if (cost.compareTo(type.cost()) > 0) {
            // SPEC 12.4: units bought during a war cost double, so defense is planned in PREP.
            builder.lore(text("gui.defense.wartime-price"));
        }
        if (city.treasury().compareTo(cost) < 0) {
            builder.lore(text("gui.defense.cannot-afford"));
        }

        return builder
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.MANAGE_DEFENSE),
                        manager.missingPermission(CityPermission.MANAGE_DEFENSE.name()))
                .onClick(context -> buy(context.player(), type))
                .build();
    }

    private void buy(Player player, DefenseUnitType type) {
        services.defense().purchase(player.getUniqueId(), city(), type)
                .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                    if (error != null) {
                        manager.lang().send(player, "command.error");
                        return;
                    }
                    if (result instanceof Result.Failure<ItemStack> failure) {
                        Replies.sendFailure(player, manager.lang(), failure);
                        return;
                    }
                    // The egg goes to the player, not into the world: SPEC 12.4 wants them to
                    // choose where it stands.
                    player.getInventory().addItem(result.orElseThrow()).values()
                            .forEach(left -> player.getWorld()
                                    .dropItemNaturally(player.getLocation(), left));
                    manager.lang().send(player, "defense.bought",
                            dev.civitas.lang.LangManager.placeholder("unit",
                                    type.displayName()));
                    refresh();
                }));
    }

    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    /** The unit's own spawn egg, so the grid reads like what it hands out. */
    private static Material iconFor(DefenseUnitType type) {
        Material egg = Material.matchMaterial(type.mob().name() + "_SPAWN_EGG");
        return egg == null ? Material.IRON_GOLEM_SPAWN_EGG : egg;
    }
}
