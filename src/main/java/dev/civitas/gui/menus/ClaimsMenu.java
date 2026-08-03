package dev.civitas.gui.menus;

import java.math.BigDecimal;
import java.util.Optional;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ClaimCostEngine;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.ConfirmationMenu;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Land management, SPEC 8.4.
 *
 * <p>The cost breakdown in the Claim button's lore is the point of this screen. SPEC 6.2's
 * formula has four moving parts and a player who cannot see them experiences the price as
 * arbitrary; showing base, distance, member divisor and total turns "why did that cost
 * 12,000" into "because we are eight chunks from the core and only three of us are active".
 *
 * <p>The bottom row is the SPEC 8.4 live 3x3 minimap of the chunks around the player.
 */
public final class ClaimsMenu extends CityMenu {

    public static final String LAYOUT = "claims.yml";

    /** The nine bottom-row slots the minimap uses, SPEC 8.4. */
    private static final int MAP_ORIGIN = 45;

    private final dev.civitas.gui.framework.MenuLayout layout;

    public ClaimsMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                      Menu parent) {
        super(manager, services, viewer, city, parent);
        this.layout = services.layouts().load(LAYOUT, "gui.claims.title", 54);
    }

    @Override
    protected Component title() {
        return text(layout.titleKey());
    }

    @Override
    protected boolean live() {
        // The cost, the minimap and the contiguity warning all follow the player around.
        return true;
    }

    @Override
    protected boolean navigable() {
        // Back and Close would land on the minimap row; they are placed by hand below.
        return false;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();
        String world = viewer.getWorld().getName();
        int chunkX = viewer.getLocation().getBlockX() >> 4;
        int chunkZ = viewer.getLocation().getBlockZ() >> 4;

        set(11, claimButton(city, world, chunkX, chunkZ));
        set(13, unclaimButton(city, world, chunkX, chunkZ));
        set(15, Button.of(Material.MAP, text("gui.claims.map"))
                .lore(text("gui.claims.map.lore"))
                .onClick(context -> {
                    context.player().closeInventory();
                    var location = context.player().getLocation();
                    services.map().render(location.getWorld().getName(),
                                    location.getBlockX() >> 4, location.getBlockZ() >> 4,
                                    services.registry().cityOf(context.player().getUniqueId()))
                            .forEach(line -> context.player().sendMessage(line));
                })
                .build());

        set(20, Button.of(Material.LEAD, text("gui.claims.auto"))
                .lore(services.claims().isAutoClaiming(viewer.getUniqueId())
                        ? text("gui.claims.auto.on")
                        : text("gui.claims.auto.off"))
                .glowing(services.claims().isAutoClaiming(viewer.getUniqueId()))
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.CLAIM),
                        manager.missingPermission(CityPermission.CLAIM.name()))
                .onClick(context -> {
                    services.claims().toggleAutoClaim(context.player().getUniqueId());
                    refresh();
                })
                .build());

        set(22, Button.of(Material.GLOWSTONE, text("gui.claims.borders"))
                .lore(text("gui.claims.borders.lore"))
                .onClick(context -> {
                    context.player().closeInventory();
                    services.borders().toggle(context.player());
                })
                .build());

        set(24, unavailable(layout.entryOr("outposts", 24, Material.FILLED_MAP,
                "gui.claims.outposts")));

        set(31, statistics(city));

        drawMinimap(city, world, chunkX, chunkZ);
    }

    // ==================================================================================
    // The two actions
    // ==================================================================================

    private Button claimButton(City city, String world, int chunkX, int chunkZ) {
        Result<ClaimCostEngine.Breakdown> quote =
                services.claims().checkClaimable(viewer.getUniqueId(), city, world, chunkX, chunkZ);

        Button.Builder builder = Button.of(Material.GRASS_BLOCK, text("gui.claims.claim"));

        if (quote instanceof Result.Failure<ClaimCostEngine.Breakdown> failure) {
            // Show why rather than hiding the button: "you cannot claim here" with a reason
            // is a far better screen than a slot that is simply missing.
            builder.lore(manager.lang().get(failure.messageKey(),
                    dev.civitas.lang.LangManager.placeholders(failure.placeholders())));
            return builder.build();
        }

        ClaimCostEngine.Breakdown cost = quote.orElseThrow();
        builder.lore(text("gui.claims.claim.chunk", "index", String.valueOf(cost.chunkIndex())))
                .lore(text("gui.claims.claim.base", "amount", money(cost.base())))
                .lore(text("gui.claims.claim.distance", "multiplier",
                        two(cost.distanceMultiplier())))
                .lore(text("gui.claims.claim.members", "divisor", two(cost.memberDivisor())));
        if (cost.newcomerMultiplier() < 1.0) {
            builder.lore(text("gui.claims.claim.newcomer", "multiplier",
                    two(cost.newcomerMultiplier())));
        }
        builder.lore(text("gui.claims.claim.total", "amount", money(cost.total())));

        // SPEC 17.2 case 13: a claim that empties the treasury is allowed, and warned about.
        if (city.treasury().subtract(cost.total()).compareTo(BigDecimal.ZERO) == 0) {
            builder.lore(text("gui.claims.claim.empties-treasury"));
        }

        return builder
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.CLAIM),
                        manager.missingPermission(CityPermission.CLAIM.name()))
                .onClick(context -> services.claims()
                        .claim(context.player().getUniqueId(), city(), world, chunkX, chunkZ)
                        .whenComplete((result, error) -> onMain(() -> {
                            report(context.player(), result, error, "gui.claims.claimed");
                            refresh();
                        })))
                .build();
    }

    private Button unclaimButton(City city, String world, int chunkX, int chunkZ) {
        Optional<Claim> here = services.claimRegistry().at(world, chunkX, chunkZ)
                .filter(claim -> claim.cityId() == city.id());

        Button.Builder builder = Button.of(Material.DIRT, text("gui.claims.unclaim"));
        if (here.isEmpty()) {
            builder.lore(text("gui.claims.unclaim.not-yours"));
            return builder.build();
        }

        Result<Claim> check = services.claims()
                .checkUnclaimable(viewer.getUniqueId(), city, world, chunkX, chunkZ);
        if (check instanceof Result.Failure<Claim> failure) {
            builder.lore(manager.lang().get(failure.messageKey(),
                    dev.civitas.lang.LangManager.placeholders(failure.placeholders())));
            return builder.build();
        }

        BigDecimal refund = services.claims().costs().refundFor(here.get().costPaid());
        builder.lore(text("gui.claims.unclaim.refund", "amount", money(refund)));

        return builder
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.UNCLAIM),
                        manager.missingPermission(CityPermission.UNCLAIM.name()))
                .onClick(context -> ConfirmationMenu.builder(manager, context.player())
                        .title(text("gui.claims.unclaim.confirm-title"))
                        .question(text("gui.claims.unclaim.confirm"))
                        .detail(text("gui.claims.unclaim.refund", "amount", money(refund)))
                        .parent(this)
                        .onConfirm(() -> services.claims()
                                .unclaim(context.player().getUniqueId(), city(), world,
                                        chunkX, chunkZ)
                                .whenComplete((result, error) -> onMain(() -> {
                                    report(context.player(), result, error, "gui.claims.unclaimed");
                                    open();
                                })))
                        .onCancel(this::open)
                        .build()
                        .open())
                .build();
    }

    private Button statistics(City city) {
        var claims = services.claimRegistry().claimsOf(city.id());
        BigDecimal value = services.claims().landValueOf(city.id());
        BigDecimal average = claims.isEmpty()
                ? BigDecimal.ZERO
                : value.divide(BigDecimal.valueOf(claims.size()), 2,
                        java.math.RoundingMode.DOWN);

        return info(Material.PAPER, text("gui.claims.stats"),
                text("gui.claims.stats.total", "count", String.valueOf(claims.size())),
                text("gui.claims.stats.invested", "amount", money(value)),
                text("gui.claims.stats.average", "amount", money(average)),
                text("gui.claims.stats.upkeep", "amount",
                        money(services.upkeepTask().amountFor(city))));
    }

    // ==================================================================================
    // The SPEC 8.4 minimap
    // ==================================================================================

    /**
     * Nine slots of coloured concrete for the 3x3 chunks around the player.
     *
     * <p>The centre is the player's own head, so "where am I on this" needs no legend. The
     * colours are the SPEC 6.5 map's, which is the other place a player reads chunk ownership,
     * so the two agree.
     */
    private void drawMinimap(City city, String world, int chunkX, int chunkZ) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int slot = MAP_ORIGIN + (dz + 1) * 3 + (dx + 1);
                if (dx == 0 && dz == 0) {
                    set(slot, info(Material.PLAYER_HEAD, text("gui.claims.map.you"),
                            text("gui.claims.map.coords",
                                    "x", String.valueOf(chunkX), "z", String.valueOf(chunkZ))));
                    continue;
                }
                set(slot, tile(city, world, chunkX + dx, chunkZ + dz));
            }
        }

        // Back and Close, moved off the minimap row.
        parent().ifPresent(parent -> set(53, Button.of(Material.ARROW, manager.text("gui.back"))
                .onClick(context -> parent.open())
                .build()));
        set(44, Button.of(Material.BARRIER, manager.text("gui.close"))
                .onClick(context -> context.player().closeInventory())
                .build());
    }

    private Button tile(City city, String world, int chunkX, int chunkZ) {
        Optional<Claim> claim = services.claimRegistry().at(world, chunkX, chunkZ);
        if (claim.isEmpty()) {
            return info(Material.WHITE_CONCRETE, text("gui.claims.map.wilderness"),
                    text("gui.claims.map.coords",
                            "x", String.valueOf(chunkX), "z", String.valueOf(chunkZ)));
        }
        boolean own = claim.get().cityId() == city.id();
        String owner = services.registry().city(claim.get().cityId())
                .map(City::name)
                .orElse("?");

        return info(own ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                own ? text("gui.claims.map.yours") : text("gui.claims.map.other",
                        "city", owner),
                text("gui.claims.map.coords",
                        "x", String.valueOf(chunkX), "z", String.valueOf(chunkZ)));
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

    private void onMain(Runnable action) {
        services.scheduler().runOnMain(action);
    }

    private static String two(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
