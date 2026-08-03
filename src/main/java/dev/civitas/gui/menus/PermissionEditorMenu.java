package dev.civitas.gui.menus;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The 22-flag permission grid, SPEC 8.7.
 *
 * <p>Lime Dye for granted, Gray Dye for not, one per SPEC 5.4 flag, and every click validated
 * against the two SPEC 5.4 rules: a member may never grant a permission they do not hold
 * themselves, and may never edit a rank whose weight is at or above their own.
 *
 * <p>Both rules are enforced <em>at click time</em> against the live city, not against what
 * was true when the screen was drawn. SPEC 17.5 case 65 is the reason: two members can have
 * this screen open on the same rank, and the second click must be judged against what the
 * first one did.
 */
public final class PermissionEditorMenu extends CityMenu {

    /** The 22 flags laid out across the interior, in SPEC 5.4's bit order. */
    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37};

    private final int rankId;

    public PermissionEditorMenu(MenuManager manager, CivitasServices services, Player viewer,
                                City city, CityRank rank, Menu parent) {
        super(manager, services, viewer, city, parent);
        this.rankId = rank.id();
    }

    @Override
    protected Component title() {
        return text("gui.permissions.title", "rank", rank().map(CityRank::name).orElse("?"));
    }

    @Override
    protected boolean live() {
        // SPEC 17.5 case 65: another editor's change should appear here rather than being
        // discovered when this one's click is refused.
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        CityRank rank = rank().orElse(null);
        if (rank == null) {
            // The rank was deleted while this was open. Nothing left to edit.
            manager.forget(viewer);
            viewer.closeInventory();
            manager.lang().send(viewer, "gui.permissions.rank-gone");
            return;
        }

        CityPermission[] flags = CityPermission.values();
        for (int index = 0; index < flags.length && index < SLOTS.length; index++) {
            set(SLOTS[index], toggle(rank, flags[index]));
        }

        set(4, info(Material.NAME_TAG, text("gui.permissions.rank", "rank", rank.name()),
                text("gui.ranks.weight", "weight", String.valueOf(rank.weight())),
                text("gui.permissions.legend")));
    }

    private Button toggle(CityRank rank, CityPermission permission) {
        boolean granted = rank.permissions().has(permission);

        return Button.of(granted ? Material.LIME_DYE : Material.GRAY_DYE,
                        text("gui.permissions.flag", "flag", permission.name()))
                .lore(granted ? text("gui.permissions.granted") : text("gui.permissions.denied"))
                .lore(text("gui.permissions.toggle-hint"))
                .glowing(granted)
                .requires(player -> mayEdit(player, permission),
                        text("gui.permissions.cannot-grant", "flag", permission.name()))
                .onClick(context -> services.ranks()
                        .setPermission(context.player().getUniqueId(), city(),
                                rank().orElseThrow(), permission, !granted)
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(context.player(), result, error);
                            refresh();
                        })))
                .build();
    }

    /**
     * The two SPEC 5.4 rules, asked of the live city.
     *
     * <p>The service checks these again inside the mutation, which is what actually enforces
     * them; this exists so the screen tells the truth about which buttons will work.
     */
    private boolean mayEdit(Player player, CityPermission permission) {
        City live = city();
        CityRank rank = rank().orElse(null);
        if (rank == null) {
            return false;
        }
        if (!live.hasPermission(player.getUniqueId(), CityPermission.MANAGE_RANKS)) {
            return false;
        }
        if (live.weightOf(player.getUniqueId()) <= rank.weight()) {
            return false;
        }
        // "Cannot grant what you lack", SPEC 5.4. Revoking is always allowed: taking away a
        // permission you do not hold cannot escalate anything.
        return rank.permissions().has(permission)
                || live.hasPermission(player.getUniqueId(), permission);
    }

    private java.util.Optional<CityRank> rank() {
        return services.registry().city(city().id()).flatMap(city -> city.rank(rankId));
    }

    private void report(Player player, Result<CityRank> result, Throwable error) {
        if (error != null) {
            manager.lang().send(player, "command.error");
            return;
        }
        if (result instanceof Result.Failure<CityRank> failure) {
            Replies.sendFailure(player, manager.lang(), failure);
            return;
        }
        manager.lang().send(player, "gui.permissions.changed");
    }
}
