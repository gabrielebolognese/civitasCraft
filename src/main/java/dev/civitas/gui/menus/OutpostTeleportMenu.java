package dev.civitas.gui.menus;

import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.outpost.Outpost;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * SPEC 39.12 slot 30: one entry per outpost, with its fee.
 *
 * <p>A screen of its own rather than a click on the list entry, because SPEC 39.5 made the fare
 * per-outpost: an outpost a million blocks out costs 891 where a near one costs 125, and a
 * player choosing between them should see both prices side by side before committing to either.
 */
public final class OutpostTeleportMenu extends CityMenu {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16};

    public OutpostTeleportMenu(MenuManager manager, CivitasServices services, Player viewer,
                               City city, Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.outpost-travel.title");
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        List<Outpost> all = services.outposts().registry().of(city().id());

        for (int index = 0; index < all.size() && index < SLOTS.length; index++) {
            set(SLOTS[index], destination(all.get(index)));
        }

        set(22, info(Material.PAPER, text("gui.outpost-travel.hint")));
    }

    private Button destination(Outpost outpost) {
        var chunk = services.outposts().claimOf(outpost);

        return Button.of(Material.ENDER_PEARL,
                        text("gui.outpost-travel.entry", "name", outpost.name()))
                .lore(text("gui.outposts.where",
                        "world", chunk.map(Claim::world).orElse("?"),
                        "x", chunk.map(claim -> String.valueOf(claim.chunkX() * 16)).orElse("?"),
                        "z", chunk.map(claim -> String.valueOf(claim.chunkZ() * 16)).orElse("?")))
                .lore(text("gui.outposts.tp-cost", "amount",
                        money(services.outpostTeleport().fareFor(city(), outpost))))
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.OUTPOST_TP),
                        manager.missingPermission(CityPermission.OUTPOST_TP.name()))
                .onClick(context -> {
                    // Closed first: SPEC 32.7 cancels a warmup on movement, and a player
                    // staring at an inventory has no way to see that it started.
                    context.player().closeInventory();
                    Result<Long> started = services.outpostTeleport()
                            .request(context.player(), city(), outpost);
                    if (started instanceof Result.Failure<Long> failure) {
                        Replies.sendFailure(context.player(), manager.lang(), failure);
                    }
                })
                .build();
    }
}
