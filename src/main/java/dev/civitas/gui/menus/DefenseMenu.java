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
                // SPEC 25.5's budget, not a unit count: the count was what "permit[ted] fifteen
                // Colossi", so showing it here would be reporting the retired rule.
                text("gui.defense.capacity",
                        "used", String.valueOf(services.defense().pointsSpent(city.id())),
                        "total", String.valueOf(services.defense().capacity(city))),
                text("gui.defense.standing", "active",
                        String.valueOf(services.defense().registry().activeCount(city.id()))),
                text("gui.defense.upkeep", "amount",
                        money(services.defense().registry().dailyUpkeep(city.id()))),
                text("gui.defense.per-chunk", "limit", String.valueOf(
                        services.defense().catalogue().maxUnitsPerChunk()))));
    }

    private Button unitButton(City city, DefenseUnitType type) {
        BigDecimal cost = services.defense().costFor(city, type);

        Button.Builder builder = Button.of(
                        dev.civitas.core.defense.DefenseService.eggMaterialFor(type),
                        text("gui.defense.unit", "name", type.displayName()))
                .lore(text(type.messageKey()));

        // Two of SPEC 27's six deal no damage at all, and SPEC 27.1 gives the Watchtower
        // Keeper's health as "n/a". A line reading "Health 40, damage 0" for a unit that cannot
        // fight reads as a broken unit rather than as a stated design.
        if (type.dealsDamage()) {
            builder.lore(text("gui.defense.stats",
                    "health", trim(type.health()),
                    "damage", trim(type.damage())));
        } else {
            builder.lore(text("gui.defense.no-damage"));
        }

        builder.lore(text("gui.defense.cost", "amount", money(cost)))
                .lore(text("gui.defense.unit-upkeep", "amount", money(type.upkeepPerDay())));

        // SPEC 25.5's price in points, which is the number a composition decision turns on. A
        // zero-point unit says so rather than reading as free: SPEC 25.5 excludes the City
        // Warden from the budget deliberately, and "0 points" invites the wrong conclusion.
        builder.lore(type.points() > 0
                ? text("gui.defense.points", "points", String.valueOf(type.points()))
                : text("gui.defense.points-free"));

        // SPEC 8.9 asks each entry to show "current count".
        builder.lore(text("gui.defense.owned", "count",
                String.valueOf(countOf(city, type))));

        if (cost.compareTo(type.cost()) > 0) {
            // SPEC 27.8: units bought during a war cost double, so defense is planned in PREP.
            builder.lore(text("gui.defense.wartime-price"));
        }
        if (!services.defense().fits(city, type)) {
            builder.lore(text("gui.defense.will-not-fit",
                    "points", String.valueOf(type.points()),
                    "free", String.valueOf(services.defense().pointsRemaining(city))));
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
                                    type.displayName()),
                            // SPEC 30.4's template. An unplaced egg costs no capacity, so this
                            // is the only warning a player gets before buying more than the
                            // budget will let them put in the ground.
                            dev.civitas.lang.LangManager.placeholder("used", String.valueOf(
                                    services.defense().pointsSpent(city().id()))),
                            dev.civitas.lang.LangManager.placeholder("total", String.valueOf(
                                    services.defense().capacity(city()))));
                    refresh();
                }));
    }

    /** How many of this unit the city already has standing, SPEC 8.9's "current count". */
    private long countOf(City city, DefenseUnitType type) {
        return services.defense().registry().activeOf(city.id()).stream()
                .filter(unit -> unit.type().equals(type.key()))
                .count();
    }

    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }
}
