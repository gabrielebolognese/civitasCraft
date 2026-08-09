package dev.civitas.gui.menus;

import java.math.BigDecimal;
import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.outpost.Outpost;
import dev.civitas.core.outpost.OutpostCostEngine;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.ConfirmationMenu;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The outposts screen, SPEC 39.12.
 *
 * <p>SPEC 39.12 replaces Part I's list-and-a-create-button with seven slots, and the reason is
 * SPEC 39.11's: "a formula with four terms is opaque unless the game shows its work, and a
 * player about to spend two million coins deserves to see exactly why." So both buying buttons
 * carry the whole breakdown in their lore rather than a total, and a button whose placement
 * rule fails says which rule and stays visible — a hidden option leaves a player guessing at
 * a rule they cannot see.
 */
public final class OutpostsMenu extends CityMenu {

    /** SPEC 39.12: one per outpost, and six is the most a city can ever hold. */
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private static final int SLOT_HEADER = 4;
    private static final int SLOT_CLAIM = 20;
    private static final int SLOT_FOUND = 22;
    private static final int SLOT_WAYSTATIONS = 24;
    private static final int SLOT_TELEPORT = 30;
    private static final int SLOT_EXPLAINER = 32;

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
        var service = services.outposts();
        List<Outpost> all = service.registry().of(city.id());

        set(SLOT_HEADER, header(city, all));

        for (int index = 0; index < all.size() && index < SLOTS.length; index++) {
            set(SLOTS[index], outpostButton(all.get(index)));
        }

        set(SLOT_CLAIM, claimButton(city));
        set(SLOT_FOUND, createButton(city));
        set(SLOT_WAYSTATIONS, waystationsButton());
        set(SLOT_TELEPORT, teleportButton(all));
        set(SLOT_EXPLAINER, explainerButton(city));
    }

    /** SPEC 39.12 slot 4: what the city holds, in one line each. */
    private Button header(City city, List<Outpost> all) {
        var service = services.outposts();
        int chunks = all.stream().mapToInt(service::chunkCount).sum();

        return info(Material.FILLED_MAP, text("gui.outposts.summary"),
                text("gui.outposts.used", "used", String.valueOf(all.size()),
                        "max", String.valueOf(service.maxOutposts(city))),
                text("gui.outposts.total-chunks", "chunks", String.valueOf(chunks)),
                // SPEC 39.5 prices each outpost by its own distance, so this is a sum and not
                // a count times a rate.
                text("gui.outposts.upkeep", "amount", money(service.upkeepFor(city))));
    }

    /**
     * SPEC 39.12 slot 20: add the chunk under the player to an outpost they already hold.
     *
     * <p>Unlike {@code /city outpost claim}, which names its outpost, this infers it: a menu
     * button has nowhere to type a name, and the chunk a player stands beside borders at most
     * one of their outposts. Ambiguity is impossible because SPEC 39.6 keeps outposts 24 chunks
     * apart and caps them at four, so no chunk can border two.
     */
    private Button claimButton(City city) {
        var at = viewer.getLocation();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;
        var service = services.outposts();

        Outpost target = service.registry().of(city.id()).stream()
                .filter(outpost -> service.checkExpandable(viewer.getUniqueId(), city, outpost,
                        at.getWorld().getName(), chunkX, chunkZ).isSuccess())
                .findFirst()
                .orElse(null);

        Button.Builder builder = Button.of(Material.GRASS_BLOCK, text("gui.outposts.claim"));

        if (target == null) {
            // SPEC 39.12 wants the reason shown rather than the button hidden. Re-asked against
            // the nearest outpost so the lore names the rule that actually failed, instead of a
            // generic "you cannot".
            builder.lore(reasonNearestOutpostRefuses(city, chunkX, chunkZ, at.getWorld().getName()));
            return builder.build();
        }

        int number = service.chunkCount(target) + 1;
        builder.lore(text("gui.outposts.claim-into", "name", target.name(),
                        "chunks", String.valueOf(number),
                        "max", String.valueOf(service.maxChunksPerOutpost())));
        breakdown(builder, service.priceBreakdown(city, number, chunkX, chunkZ),
                service.blocksFromCore(city, chunkX, chunkZ));

        final Outpost chosen = target;
        return builder
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.OUTPOST_MANAGE),
                        manager.missingPermission(CityPermission.OUTPOST_MANAGE.name()))
                .onClick(context -> service.expand(context.player().getUniqueId(), city(),
                                chosen, at.getWorld().getName(), chunkX, chunkZ)
                        .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                            report(context.player(), result, error, "gui.outposts.claimed");
                            open();
                        })))
                .build();
    }

    /** Why the closest outpost will not take this chunk, so the lore can say which rule. */
    private Component reasonNearestOutpostRefuses(City city, int chunkX, int chunkZ,
                                                  String world) {
        var service = services.outposts();
        List<Outpost> held = service.registry().of(city.id());
        if (held.isEmpty()) {
            return text("gui.outposts.claim-none-yet");
        }
        Result<Void> quote = service.checkExpandable(viewer.getUniqueId(), city,
                held.get(0), world, chunkX, chunkZ);
        return quote instanceof Result.Failure<Void> failure
                ? manager.lang().get(failure.messageKey(),
                        dev.civitas.lang.LangManager.placeholders(failure.placeholders()))
                : text("gui.outposts.claim-none-yet");
    }

    /** SPEC 39.12 slot 24: the waystations submenu, new with SPEC 39.10. */
    private Button waystationsButton() {
        int held = services.waystations().registry().of(city().id()).size();

        return Button.of(Material.ICE, text("gui.outposts.waystations"))
                .lore(text("gui.outposts.waystations-held", "count", String.valueOf(held)))
                .lore(text("gui.outposts.waystations-hint"))
                .onClick(context -> new WaystationsMenu(manager, services, context.player(),
                        city(), this).open())
                .build();
    }

    /** SPEC 39.12 slot 30: one entry per outpost with its fee. */
    private Button teleportButton(List<Outpost> all) {
        Button.Builder builder = Button.of(Material.ENDER_PEARL, text("gui.outposts.travel"));
        if (all.isEmpty()) {
            return builder.lore(text("gui.outposts.travel-none")).build();
        }
        return builder
                .lore(text("gui.outposts.travel-hint", "count", String.valueOf(all.size())))
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.OUTPOST_TP),
                        manager.missingPermission(CityPermission.OUTPOST_TP.name()))
                .onClick(context -> new OutpostTeleportMenu(manager, services, context.player(),
                        city(), this).open())
                .build();
    }

    /** SPEC 39.12 slot 32: the formula in plain language, with this city's numbers in it. */
    private Button explainerButton(City city) {
        return Button.of(Material.BOOK, text("gui.outposts.explainer"))
                .lore(text("gui.outposts.explainer-hint"))
                .onClick(context -> new OutpostCostMenu(manager, services, context.player(),
                        city(), this).open())
                .build();
    }

    /**
     * The four terms of SPEC 39.3, as lore.
     *
     * <p>Every term, never a total on its own. A player looking at a two-million-coin button
     * should be able to see which term is responsible, and distance almost always is.
     */
    private void breakdown(Button.Builder builder, OutpostCostEngine.Breakdown costs,
                           double blocks) {
        builder.lore(text("gui.outposts.cost-base", "amount", money(costs.base())))
                .lore(text("gui.outposts.cost-distance",
                        "multiplier", twoPlaces(costs.distance()),
                        "blocks", dev.civitas.msg.Formats.count(Math.round(blocks))))
                .lore(text("gui.outposts.cost-chunk", "multiplier", twoPlaces(costs.factor())))
                .lore(text("gui.outposts.cost-members", "divisor", twoPlaces(costs.divisor())))
                .lore(text("gui.outposts.cost-total", "amount", money(costs.total())));
    }

    /** A multiplier as a player would read it: 1.25, not 1.2499999999999998. */
    static String twoPlaces(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    // ==================================================================================
    // Buttons
    // ==================================================================================

    /** SPEC 39.12 slots 10 to 16: "Click to open detail." */
    private Button outpostButton(Outpost outpost) {
        var service = services.outposts();
        var chunk = service.claimOf(outpost);

        return Button.of(Material.FILLED_MAP,
                        text("gui.outposts.entry", "name", outpost.name()))
                .lore(text("gui.outposts.where",
                        "world", chunk.map(Claim::world).orElse("?"),
                        "x", chunk.map(claim -> String.valueOf(claim.chunkX() * 16)).orElse("?"),
                        "z", chunk.map(claim -> String.valueOf(claim.chunkZ() * 16)).orElse("?")))
                .lore(text("gui.outposts.entry-chunks",
                        "chunks", String.valueOf(service.chunkCount(outpost)),
                        "max", String.valueOf(service.maxChunksPerOutpost())))
                .lore(text("gui.outposts.entry-distance", "blocks",
                        dev.civitas.msg.Formats.count(
                                Math.round(service.blocksFromCore(city(), outpost)))))
                .lore(text("gui.outposts.entry-upkeep", "amount",
                        money(service.upkeepFor(city(), outpost))))
                // SPEC 39.5's fare is 100 * D(d), so it is per outpost. Before the SPEC 39
                // rework this read a flat figure from config, which meant an outpost a million
                // blocks out advertised 100 and charged 891.
                .lore(text("gui.outposts.tp-cost", "amount",
                        money(services.outpostTeleport().fareFor(city(), outpost))))
                .lore(text("gui.outposts.entry-hint"))
                .onClick(context -> new OutpostDetailMenu(manager, services, context.player(),
                        city(), outpost, this).open())
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
