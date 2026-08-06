package dev.civitas.gui.menus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.war.War;
import dev.civitas.core.war.WarState;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.storage.row.WarKillRow;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The Wars screen, SPEC 8.8.
 *
 * <p>Three screens really, sharing a slot on the hub: what a city at peace sees, what it sees
 * while preparing, and what it sees while fighting. SPEC 8.8 lays out all three, and they have
 * almost nothing in common because a war changes what there is to decide.
 *
 * <p>Live throughout: a countdown that does not count down, or a score that does not move,
 * would be worse than no screen at all.
 */
public final class WarsMenu extends CityMenu {

    /** The kill feed as of the last refresh. Read on the server thread, never queried here. */
    private List<WarKillRow> recentKills = List.of();

    public WarsMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                    Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.wars.title", "city", city().name());
    }

    @Override
    protected boolean live() {
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        Optional<War> war = services.wars().registry().engagedWarOf(city().id());
        if (war.isEmpty()) {
            buildAtPeace();
            return;
        }
        War active = war.get();
        if (active.state() == WarState.ACTIVE) {
            buildActive(active);
        } else {
            buildPrep(active);
        }
    }

    // ==================================================================================
    // At peace, SPEC 8.8
    // ==================================================================================

    private void buildAtPeace() {
        // SPEC 8.8 puts a declare button here. It closes the window and points at the command
        // rather than opening a city picker: a declaration costs 50,000 C and commits the city
        // for nine days, and SPEC 11.3's terms are worth reading before agreeing to them.
        set(22, gated(Material.NETHERITE_SWORD, text("gui.wars.declare"),
                CityPermission.DECLARE_WAR)
                .lore(text("gui.wars.declare-lore"))
                .onClick(context -> {
                    context.player().closeInventory();
                    manager.lang().send(context.player(), "gui.wars.declare-how");
                })
                .build());

        set(31, info(Material.BOOK, text("gui.wars.history"),
                text("gui.wars.history-lore")));

        set(13, info(Material.GREEN_BANNER, text("gui.wars.at-peace"),
                text("gui.wars.at-peace-lore")));
    }

    // ==================================================================================
    // Preparing, SPEC 8.8
    // ==================================================================================

    private void buildPrep(War war) {
        long now = System.currentTimeMillis();

        set(13, info(Material.CLOCK, text("gui.wars.prep"),
                text("gui.wars.prep-remaining", "remaining",
                        describe(war.millisUntilNextPhase(now))),
                text("gui.wars.opponent", "city", opponentName(war))));

        set(20, Button.of(Material.SHIELD, text("gui.wars.defense"))
                .lore(text("gui.wars.defense-lore"))
                .onClick(context -> new DefenseMenu(manager, services, viewer, city(), this)
                        .open())
                .build());

        set(24, info(Material.ENDER_PEARL, text("gui.wars.rally"),
                text("gui.wars.rally-lore")));

        suePeaceButton(war);
    }

    // ==================================================================================
    // Fighting, SPEC 8.8
    // ==================================================================================

    private void buildActive(War war) {
        long now = System.currentTimeMillis();

        set(4, info(Material.NETHERITE_SWORD, text("gui.wars.score",
                        "attacker", cityName(war.attackerCityId()),
                        "attacker-score", String.valueOf(war.attackerScore()),
                        "defender", cityName(war.defenderCityId()),
                        "defender-score", String.valueOf(war.defenderScore())),
                text("gui.wars.ends-in", "remaining", describe(war.millisUntilNextPhase(now)))));

        set(11, info(Material.PLAYER_HEAD, text("gui.wars.enemies-online",
                        "count", String.valueOf(enemiesOnline(war))),
                text("gui.wars.enemies-online-lore")));

        set(13, killFeed());

        set(15, info(Material.BEACON, text("gui.wars.capture-points",
                        "count", String.valueOf(services.capturePoints().pointsOf(war).size())),
                text("gui.wars.capture-points-lore")));

        suePeaceButton(war);
        refreshKillFeed(war);
    }

    private Button killFeed() {
        Component[] lore = recentKills.isEmpty()
                ? new Component[] {text("gui.wars.kills-empty")}
                : recentKills.stream()
                        .limit(10)
                        .map(kill -> text("gui.wars.kills-entry",
                                "killer", nameOf(kill.killerUuid()),
                                "victim", nameOf(kill.victimUuid())))
                        .toArray(Component[]::new);
        return info(Material.IRON_SWORD, text("gui.wars.kills"), lore);
    }

    /**
     * Reloads the feed for the next redraw.
     *
     * <p>The screen is live, so a query started now shows up a tick later rather than blocking
     * the draw on storage, which SPEC 2.1 forbids on this thread anyway.
     */
    private void refreshKillFeed(War war) {
        services.wars().recentKills(war.id(), 10).thenAccept(rows ->
                services.scheduler().runOnMain(() -> this.recentKills = rows));
    }

    // ==================================================================================
    // Shared
    // ==================================================================================

    /** SPEC 8.8's Sue for Peace, on slot 40 in both the PREP and ACTIVE screens. */
    private void suePeaceButton(War war) {
        set(40, gated(Material.BARRIER, text("gui.wars.peace"), CityPermission.DECLARE_WAR)
                .lore(text("gui.wars.peace-lore", "forfeit",
                        money(services.peaceOffers().forfeitOf(war))))
                .onClick(context -> {
                    Result<Void> offered = services.peaceOffers()
                            .offer(context.player().getUniqueId(), city(), war);
                    if (offered instanceof Result.Failure<Void> failure) {
                        Replies.sendFailure(context.player(), manager.lang(), failure);
                        return;
                    }
                    manager.lang().send(context.player(), "war.peace.offered",
                            Replies.p("forfeit",
                                    money(services.peaceOffers().forfeitOf(war))));
                })
                .build());
    }

    private int enemiesOnline(War war) {
        int count = 0;
        for (Player online : viewer.getServer().getOnlinePlayers()) {
            Optional<City> theirs = services.registry().cityOf(online.getUniqueId());
            if (theirs.isPresent() && war.areEnemies(city().id(), theirs.get().id())) {
                count++;
            }
        }
        return count;
    }

    private String opponentName(War war) {
        int opponent = war.isAttackerSide(city().id())
                ? war.defenderCityId()
                : war.attackerCityId();
        return cityName(opponent);
    }

    private String cityName(int cityId) {
        return services.registry().city(cityId).map(City::name).orElse("?");
    }

    private static String describe(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        if (hours >= 24) {
            return TimeUnit.MILLISECONDS.toDays(millis) + "d";
        }
        if (hours >= 1) {
            return hours + "h";
        }
        return Math.max(1, TimeUnit.MILLISECONDS.toMinutes(millis)) + "m";
    }
}
