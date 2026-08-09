package dev.civitas.gui.menus;

import java.math.BigDecimal;
import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.travel.TravelKind;
import dev.civitas.core.waystation.Waystation;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.ConfirmationMenu;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * SPEC 39.12 slot 24's submenu: the city's waystations, SPEC 39.10.
 *
 * <p>Reached from the Outposts screen because the two are the same kind of decision to a
 * player — where the city reaches, and what that costs a day — but they are separate systems
 * with separate limits, and this screen says so rather than letting the shared entry point
 * imply one pool.
 */
public final class WaystationsMenu extends CityMenu {

    private static final int[] SLOTS = {11, 15};

    public WaystationsMenu(MenuManager manager, CivitasServices services, Player viewer,
                           City city, Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.waystations.title");
    }

    @Override
    protected boolean live() {
        // The found button follows the player between resource worlds.
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        var service = services.waystations();
        List<Waystation> held = service.registry().of(city().id());

        set(4, info(Material.ICE, text("gui.waystations.header"),
                text("gui.waystations.held", "count", String.valueOf(held.size())),
                text("gui.waystations.upkeep", "amount", money(service.upkeepFor(city()))),
                // Stated on the header, because a player who has spent their outposts will
                // otherwise assume these are gone too.
                text("gui.waystations.separate-pool")));

        for (int index = 0; index < held.size() && index < SLOTS.length; index++) {
            set(SLOTS[index], entry(held.get(index)));
        }

        set(31, foundButton());
    }

    private Button entry(Waystation waystation) {
        var service = services.waystations();

        return Button.of(Material.ICE, text("gui.waystations.entry",
                        "world", waystation.world()))
                .lore(text("gui.waystations.entry-chunks",
                        "chunks", String.valueOf(service.registry()
                                .chunkCount(waystation.id())),
                        "max", String.valueOf(service.costs().maxChunks())))
                .lore(text("gui.waystations.entry-upkeep",
                        "amount", money(service.upkeepFor(waystation))))
                .lore(text("gui.waystations.entry-fare",
                        "amount", money(service.costs().teleportCost())))
                .lore(text("gui.waystations.entry-hint"))
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.OUTPOST_TP),
                        manager.missingPermission(CityPermission.OUTPOST_TP.name()))
                .onClick(context -> {
                    if (context.isShiftClick()) {
                        confirmDelete(context.player(), waystation);
                        return;
                    }
                    travel(context.player(), waystation);
                })
                .build();
    }

    private void travel(Player player, Waystation waystation) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(waystation.world());
        if (world == null) {
            manager.lang().send(player, "waystation.world-missing");
            return;
        }
        player.closeInventory();
        Result<Long> started = services.teleports().begin(player, TravelKind.WAYSTATION_TP,
                new Location(world, waystation.warpX(), waystation.warpY(), waystation.warpZ(),
                        waystation.warpYaw(), waystation.warpPitch()),
                services.waystations().costs().teleportCost(),
                manager.lang().plain("waystation.destination-label"));
        if (started instanceof Result.Failure<Long> failure) {
            Replies.sendFailure(player, manager.lang(), failure);
        }
    }

    /** Founding one where the player stands, or the reason that is refused. */
    private Button foundButton() {
        Location at = viewer.getLocation();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;
        var service = services.waystations();

        Result<BigDecimal> quote = service.checkCreatable(viewer.getUniqueId(), city(),
                at.getWorld().getName(), chunkX, chunkZ);

        Button.Builder builder = Button.of(Material.EMERALD, text("gui.waystations.found"));

        if (quote instanceof Result.Failure<BigDecimal> failure) {
            // SPEC 39.12's rule for the outpost buttons, applied here for the same reason: a
            // hidden option leaves a player guessing at a rule they cannot see.
            return builder.lore(manager.lang().get(failure.messageKey(),
                    dev.civitas.lang.LangManager.placeholders(failure.placeholders()))).build();
        }

        return builder
                .lore(text("gui.waystations.found-cost", "amount", money(quote.orElseThrow())))
                .lore(text("gui.waystations.found-upkeep", "amount",
                        money(service.costs().upkeepPerDay(1,
                                service.blocksFromSpawn(at.getWorld().getName(),
                                        chunkX, chunkZ)))))
                .lore(text("gui.waystations.found-here",
                        "x", String.valueOf(chunkX * 16), "z", String.valueOf(chunkZ * 16)))
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.OUTPOST_MANAGE),
                        manager.missingPermission(CityPermission.OUTPOST_MANAGE.name()))
                .onClick(context -> service.create(context.player().getUniqueId(), city(),
                                at.getWorld().getName(), chunkX, chunkZ,
                                at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch())
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(context.player(), result, error, "gui.waystations.founded");
                            open();
                        })))
                .build();
    }

    private void confirmDelete(Player player, Waystation waystation) {
        ConfirmationMenu.builder(manager, player)
                .title(text("gui.waystations.delete-title"))
                .question(text("gui.waystations.delete-confirm",
                        "world", waystation.world()))
                .detail(text("gui.waystations.delete-refund"))
                .parent(this)
                .onConfirm(() -> services.waystations()
                        .delete(player.getUniqueId(), city(), waystation.world())
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(player, result, error, "gui.waystations.deleted");
                            open();
                        })))
                .onCancel(this::open)
                .build()
                .open();
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
