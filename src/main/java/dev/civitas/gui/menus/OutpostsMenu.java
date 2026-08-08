package dev.civitas.gui.menus;

import java.math.BigDecimal;
import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.outpost.Outpost;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.ConfirmationMenu;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The outpost list, SPEC 8.3 slot 32 and SPEC 8.4 slot 24.
 *
 * <p>Each outpost is a button that teleports; the middle of the screen offers to buy another
 * one where the player is standing, with the price and the reason it would be refused both
 * shown before they commit.
 */
public final class OutpostsMenu extends CityMenu {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16};

    public OutpostsMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                        Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.outposts.title", "city", city().name());
    }

    @Override
    protected boolean live() {
        // The create button follows the player around, and its price follows the city.
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();
        List<Outpost> all = services.outposts().registry().of(city.id());

        for (int index = 0; index < all.size() && index < SLOTS.length; index++) {
            set(SLOTS[index], outpostButton(all.get(index)));
        }

        set(22, info(Material.PAPER, text("gui.outposts.summary"),
                text("gui.outposts.used", "used", String.valueOf(all.size()),
                        "max", String.valueOf(services.outposts().maxOutposts(city))),
                // SPEC 39.5 prices each outpost by its own distance, so this is a sum and not
                // a count times a rate.
                text("gui.outposts.upkeep", "amount",
                        money(services.outposts().upkeepFor(city)))));

        set(31, createButton(city));
    }

    // ==================================================================================
    // Buttons
    // ==================================================================================

    private Button outpostButton(Outpost outpost) {
        var chunk = services.outposts().claimOf(outpost);

        Button.Builder builder = Button.of(Material.FILLED_MAP,
                        text("gui.outposts.entry", "name", outpost.name()))
                .lore(text("gui.outposts.where",
                        "world", chunk.map(Claim::world).orElse("?"),
                        "x", chunk.map(claim -> String.valueOf(claim.chunkX() * 16)).orElse("?"),
                        "z", chunk.map(claim -> String.valueOf(claim.chunkZ() * 16)).orElse("?")))
                // SPEC 39.5's fare is 100 * D(d), so it is per outpost. Before the SPEC 39
                // rework this button read a flat figure from config, which meant an outpost a
                // million blocks out advertised 100 and charged 891.
                .lore(text("gui.outposts.tp-cost", "amount",
                        money(services.outpostTeleport().fareFor(city(), outpost))))
                .lore(text("gui.outposts.tp-hint"));

        return builder
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.OUTPOST_TP),
                        manager.missingPermission(CityPermission.OUTPOST_TP.name()))
                .onClick(context -> {
                    if (context.isShiftClick()) {
                        confirmDelete(context.player(), outpost);
                        return;
                    }
                    context.player().closeInventory();
                    Result<Long> started = services.outpostTeleport()
                            .request(context.player(), city(), outpost);
                    if (started instanceof Result.Failure<Long> failure) {
                        Replies.sendFailure(context.player(), manager.lang(), failure);
                    }
                })
                .build();
    }

    private Button createButton(City city) {
        var at = viewer.getLocation();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;

        Result<BigDecimal> quote = services.outposts().checkCreatable(viewer.getUniqueId(),
                city, "PlaceholderName", at.getWorld().getName(), chunkX, chunkZ);

        Button.Builder builder = Button.of(Material.GRASS_BLOCK, text("gui.outposts.create"));

        if (quote instanceof Result.Failure<BigDecimal> failure
                && !"NAME_MISSING".equals(failure.reason())
                && !"NAME_INVALID".equals(failure.reason())) {
            // Everything except the name, which the player has not typed yet.
            builder.lore(manager.lang().get(failure.messageKey(),
                    dev.civitas.lang.LangManager.placeholders(failure.placeholders())));
            return builder.build();
        }

        builder.lore(text("gui.outposts.create-cost", "amount",
                        money(services.outposts().creationCost(city, chunkX, chunkZ))))
                .lore(text("gui.outposts.create-upkeep", "amount",
                        money(services.outposts().upkeepForNewAt(city, chunkX, chunkZ))))
                .lore(text("gui.outposts.create-here",
                        "x", String.valueOf(chunkX * 16), "z", String.valueOf(chunkZ * 16)));

        return builder
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.OUTPOST_MANAGE),
                        manager.missingPermission(CityPermission.OUTPOST_MANAGE.name()))
                .onClick(context -> services.amountInput().askText(context.player(),
                        "gui.outposts.name-prompt",
                        typed -> services.outposts().create(context.player().getUniqueId(),
                                        city(), typed, at.getWorld().getName(), chunkX, chunkZ,
                                        at.getX(), at.getY(), at.getZ(), at.getYaw(),
                                        at.getPitch())
                                .whenComplete((result, error) ->
                                        services.scheduler().runOnMain(() -> {
                                            report(context.player(), result, error,
                                                    "gui.outposts.created");
                                            open();
                                        })),
                        this::open))
                .build();
    }

    private void confirmDelete(Player player, Outpost outpost) {
        ConfirmationMenu.builder(manager, player)
                .title(text("gui.outposts.delete.title"))
                .question(text("gui.outposts.delete.confirm", "name", outpost.name()))
                .detail(text("gui.outposts.delete.refund", "percent",
                        String.valueOf((long) services.outposts().refundPercent())))
                .parent(this)
                .onConfirm(() -> services.outposts()
                        .delete(player.getUniqueId(), city(), outpost)
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(player, result, error, "gui.outposts.deleted");
                            open();
                        })))
                .onCancel(this::open)
                .build()
                .open();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================


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
