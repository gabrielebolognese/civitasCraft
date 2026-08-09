package dev.civitas.gui.menus;

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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * One outpost, SPEC 39.12's detail submenu.
 *
 * <p>"Rename, set warp, expand, unclaim current chunk, delete, view defense units, and a chunk
 * layout diagram showing which of the four chunks are owned."
 *
 * <p>The diagram is the part worth having. An outpost is one to four edge-connected chunks and
 * a player standing in one of them cannot see the shape from the ground; SPEC 39.6 refuses an
 * expansion that does not border and an unclaim that would split, and both refusals are opaque
 * without a picture of what is actually owned.
 */
public final class OutpostDetailMenu extends CityMenu {

    /** A 3x3 window of chunks around the outpost, drawn on the bottom two rows. */
    private static final int[] DIAGRAM = {
        38, 39, 40,
        47, 48, 49,
    };

    private final Outpost outpost;

    public OutpostDetailMenu(MenuManager manager, CivitasServices services, Player viewer,
                             City city, Outpost outpost, Menu parent) {
        super(manager, services, viewer, city, parent);
        this.outpost = outpost;
    }

    @Override
    protected Component title() {
        return text("gui.outpost-detail.title", "name", outpost.name());
    }

    @Override
    protected boolean live() {
        // The unclaim button depends on which chunk the player is standing in.
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        var service = services.outposts();
        List<Claim> chunks = service.chunksOf(outpost);

        set(4, info(Material.FILLED_MAP, text("gui.outpost-detail.header",
                        "name", outpost.name()),
                text("gui.outpost-detail.chunks",
                        "chunks", String.valueOf(chunks.size()),
                        "max", String.valueOf(service.maxChunksPerOutpost())),
                text("gui.outpost-detail.distance", "blocks",
                        dev.civitas.msg.Formats.count(
                                Math.round(service.blocksFromCore(city(), outpost)))),
                text("gui.outpost-detail.upkeep", "amount",
                        money(service.upkeepFor(city(), outpost))),
                text("gui.outpost-detail.invested", "amount", money(invested(chunks)))));

        set(19, renameButton());
        set(21, setWarpButton());
        set(23, expandButton());
        set(25, unclaimButton());
        set(30, defenseButton());
        set(32, deleteButton());

        drawDiagram(chunks);
    }

    // ==================================================================================
    // The chunk diagram, SPEC 39.12
    // ==================================================================================

    /**
     * Six chunks around the founding one, marking which the outpost owns.
     *
     * <p>Anchored on the founding chunk rather than centred on the player, so the shape stays
     * still while they walk around it. A four-chunk outpost fits inside a 3x2 window in every
     * legal arrangement except a straight line of four, which the header's chunk count covers.
     */
    private void drawDiagram(List<Claim> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        Claim founding = chunks.get(0);
        int originX = founding.chunkX() - 1;
        int originZ = founding.chunkZ();

        for (int index = 0; index < DIAGRAM.length; index++) {
            int chunkX = originX + (index % 3);
            int chunkZ = originZ + (index / 3);
            boolean owned = chunks.stream().anyMatch(claim ->
                    claim.chunkX() == chunkX && claim.chunkZ() == chunkZ);
            boolean here = chunkX == (viewer.getLocation().getBlockX() >> 4)
                    && chunkZ == (viewer.getLocation().getBlockZ() >> 4);

            set(DIAGRAM[index], info(
                    owned ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    text(owned ? "gui.outpost-detail.tile-owned"
                                    : "gui.outpost-detail.tile-free",
                            "x", String.valueOf(chunkX * 16),
                            "z", String.valueOf(chunkZ * 16)),
                    here ? text("gui.outpost-detail.tile-here")
                            : text("gui.outpost-detail.tile-blank")));
        }
    }

    // ==================================================================================
    // Buttons
    // ==================================================================================

    private Button renameButton() {
        return gated(Material.NAME_TAG, text("gui.outpost-detail.rename"),
                CityPermission.OUTPOST_MANAGE)
                .lore(text("gui.outpost-detail.rename-hint"))
                .onClick(context -> services.amountInput().askText(context.player(),
                        "gui.outposts.name-prompt",
                        typed -> services.outposts().rename(context.player().getUniqueId(),
                                        city(), outpost, typed)
                                .whenComplete((result, error) ->
                                        services.scheduler().runOnMain(() -> {
                                            report(context.player(), result, error,
                                                    "gui.outpost-detail.renamed");
                                            back();
                                        })),
                        this::open))
                .build();
    }

    private Button setWarpButton() {
        Location at = viewer.getLocation();
        return gated(Material.COMPASS, text("gui.outpost-detail.setwarp"),
                CityPermission.OUTPOST_MANAGE)
                .lore(text("gui.outpost-detail.setwarp-hint"))
                .onClick(context -> services.outposts().setWarp(context.player().getUniqueId(),
                                city(), outpost, at.getWorld().getName(), at.getX(), at.getY(),
                                at.getZ(), at.getYaw(), at.getPitch())
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(context.player(), result, error,
                                    "gui.outpost-detail.warp-set");
                            open();
                        })))
                .build();
    }

    private Button expandButton() {
        Location at = viewer.getLocation();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;
        var service = services.outposts();

        Button.Builder builder = Button.of(Material.SCAFFOLDING,
                text("gui.outpost-detail.expand"));

        Result<Void> allowed = service.checkExpandable(viewer.getUniqueId(), city(), outpost,
                at.getWorld().getName(), chunkX, chunkZ);
        if (allowed instanceof Result.Failure<Void> failure) {
            return builder.lore(manager.lang().get(failure.messageKey(),
                    dev.civitas.lang.LangManager.placeholders(failure.placeholders()))).build();
        }

        int number = service.chunkCount(outpost) + 1;
        return builder
                .lore(text("gui.outpost-detail.expand-cost", "amount",
                        money(service.expansionCost(city(), outpost, number))))
                .lore(text("gui.outpost-detail.expand-here",
                        "x", String.valueOf(chunkX * 16), "z", String.valueOf(chunkZ * 16)))
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.OUTPOST_MANAGE),
                        manager.missingPermission(CityPermission.OUTPOST_MANAGE.name()))
                .onClick(context -> service.expand(context.player().getUniqueId(), city(),
                                outpost, at.getWorld().getName(), chunkX, chunkZ)
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(context.player(), result, error,
                                    "gui.outpost-detail.expanded");
                            open();
                        })))
                .build();
    }

    private Button unclaimButton() {
        Location at = viewer.getLocation();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;
        boolean inside = services.outposts().chunksOf(outpost).stream().anyMatch(claim ->
                claim.chunkX() == chunkX && claim.chunkZ() == chunkZ
                        && claim.world().equals(at.getWorld().getName()));

        Button.Builder builder = Button.of(Material.DIRT, text("gui.outpost-detail.unclaim"));
        if (!inside) {
            return builder.lore(text("gui.outpost-detail.unclaim-elsewhere")).build();
        }

        return builder
                .lore(text("gui.outpost-detail.unclaim-here",
                        "x", String.valueOf(chunkX * 16), "z", String.valueOf(chunkZ * 16)))
                .lore(text("gui.outpost-detail.unclaim-refund", "percent",
                        String.valueOf((long) services.outposts().refundPercent())))
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.OUTPOST_MANAGE),
                        manager.missingPermission(CityPermission.OUTPOST_MANAGE.name()))
                .onClick(context -> services.outposts()
                        .unclaimChunk(context.player().getUniqueId(), city(),
                                at.getWorld().getName(), chunkX, chunkZ)
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(context.player(), result, error,
                                    "gui.outpost-detail.unclaimed");
                            open();
                        })))
                .build();
    }

    /**
     * SPEC 39.12 asks for a defense-unit view, and SPEC 39.8 caps an outpost at four.
     *
     * <p>The roster it would list is M12a to M12f, which PLAN orders after this milestone, so
     * this renders through the framework's refusal path with the reason — the same way M8
     * handled seven screens whose systems did not exist yet. One line changes when it does.
     */
    private Button defenseButton() {
        return Button.of(Material.IRON_GOLEM_SPAWN_EGG, text("gui.outpost-detail.defense"))
                .lore(text("gui.outpost-detail.defense-unavailable"))
                .build();
    }

    private Button deleteButton() {
        return gated(Material.BARRIER, text("gui.outpost-detail.delete"),
                CityPermission.OUTPOST_MANAGE)
                .lore(text("gui.outpost-detail.delete-hint"))
                .onClick(context -> confirmDelete(context.player()))
                .build();
    }

    private void confirmDelete(Player player) {
        ConfirmationMenu.builder(manager, player)
                .title(text("gui.outposts.delete-title"))
                .question(text("gui.outposts.delete-confirm", "name", outpost.name()))
                .detail(text("gui.outposts.delete-refund", "percent",
                        String.valueOf((long) services.outposts().refundPercent())))
                .parent(this)
                .onConfirm(() -> services.outposts()
                        .delete(player.getUniqueId(), city(), outpost)
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(player, result, error, "gui.outposts.deleted");
                            back();
                        })))
                .onCancel(this::open)
                .build()
                .open();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private java.math.BigDecimal invested(List<Claim> chunks) {
        return chunks.stream()
                .map(Claim::costPaid)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    private void back() {
        parent().ifPresentOrElse(Menu::open, viewer::closeInventory);
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
