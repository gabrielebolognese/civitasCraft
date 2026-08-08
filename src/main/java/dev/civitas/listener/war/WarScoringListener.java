package dev.civitas.listener.war;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.war.War;
import dev.civitas.core.war.WarRegistry;
import dev.civitas.core.war.WarScoring;
import dev.civitas.core.war.WarState;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * SPEC 11.6's score table, wired to the events that produce it.
 *
 * <p>Everything here runs at {@link EventPriority#MONITOR} with {@code ignoreCancelled}: a
 * score should follow something that actually happened, and land protection, the war
 * restrictions and M17's logger have all had their say by then.
 *
 * <p>Each handler answers the same three questions before awarding anything: is there an
 * active war here, are the two parties on opposite sides of it, and is this inside the zone.
 * SPEC 17.4 case 41 is the reason the first two are not assumed — a player who has never
 * joined a city can be standing in a war zone, and they are neither a target nor a scorer.
 */
public final class WarScoringListener implements Listener {

    private final WarRegistry wars;
    private final WarScoring scoring;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final DefenseOwnership defenseUnits;

    /** Which city a defense unit belongs to, if the entity is one. Injected to avoid a cycle. */
    @FunctionalInterface
    public interface DefenseOwnership {

        Optional<Integer> cityOf(org.bukkit.entity.Entity entity);

        static DefenseOwnership none() {
            return entity -> Optional.empty();
        }
    }

    public WarScoringListener(WarRegistry wars, WarScoring scoring, CityRegistry cities,
                              ClaimRegistry claims, DefenseOwnership defenseUnits) {
        this.wars = Objects.requireNonNull(wars, "wars");
        this.scoring = Objects.requireNonNull(scoring, "scoring");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.defenseUnits = Objects.requireNonNull(defenseUnits, "defenseUnits");
    }

    // ==================================================================================
    // Kills, SPEC 11.6
    // ==================================================================================

    /**
     * A player killed by an enemy scores ten for the killer's side.
     *
     * <p>SPEC 11.6 gives the victim's side nothing, positive or negative: "no negative, deaths
     * are not punished". A losing fight should not also cost points, or the losing side's best
     * move becomes logging off.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            return;
        }

        var at = victim.getLocation();
        Optional<Integer> killerCity = cityIdOf(killer.getUniqueId());
        Optional<Integer> victimCity = cityIdOf(victim.getUniqueId());
        if (killerCity.isEmpty() || victimCity.isEmpty()) {
            return;
        }

        for (War war : wars.activeWarsCovering(at.getWorld().getName(), at.getBlockX(),
                at.getBlockZ())) {
            if (war.areEnemies(killerCity.get(), victimCity.get())) {
                scoring.awardKill(war, war.isAttackerSide(killerCity.get()));
                recordKill(war, killer.getUniqueId(), victim.getUniqueId(), at);
                payBounties(killer, victim);
                return;
            }
        }
    }

    /**
     * SPEC 4.7: a bounty is collected by "whoever kills the target <b>during an active war</b>".
     *
     * <p>Reached only from inside the loop above, which is what makes that restriction real:
     * by this line the kill is known to have happened inside a live war, between two cities on
     * opposite sides of it. A kill anywhere else never gets here and pays nothing.
     */
    private void payBounties(Player killer, Player victim) {
        if (bounties == null) {
            return;
        }
        try {
            bounties.claim(killer.getUniqueId(), victim.getUniqueId(), true,
                            System.currentTimeMillis())
                    .thenAccept(result -> {
                        // A zero payout is SPEC 21.4 F7 refunding a self-placed or IP-linked
                        // bounty to whoever staked it. "Both are silent rejections", so the
                        // killer is told nothing rather than being announced as claiming
                        // nothing.
                        if (result instanceof dev.civitas.util.Result.Success<
                                java.math.BigDecimal>(java.math.BigDecimal paid)
                                && paid.signum() > 0) {
                            bountyPaid.accept(killer.getUniqueId(), paid);
                        }
                    })
                    .exceptionally(error -> null);
        } catch (RuntimeException ignored) {
            // A closed pool throws from the call itself. The kill and its score already
            // landed; only the payout is lost, and the bounty stays open to be claimed again.
        }
    }

    private dev.civitas.core.economy.BountyService bounties;
    private java.util.function.BiConsumer<UUID, java.math.BigDecimal> bountyPaid =
            (player, amount) -> { };

    /** SPEC 4.7's payout, wired by the plugin. */
    public void useBounties(dev.civitas.core.economy.BountyService service,
                            java.util.function.BiConsumer<UUID, java.math.BigDecimal> onPaid) {
        this.bounties = service;
        this.bountyPaid = Objects.requireNonNull(onPaid, "onPaid");
    }

    /**
     * Writes the kill to {@code war_kills}, for SPEC 8.8's kill feed and the post-war report.
     *
     * <p>Fire and forget: a kill that fails to record costs a line in a feed, and blocking the
     * death event on a database write would cost every player in the fight a stutter.
     */
    private void recordKill(War war, UUID killer, UUID victim, org.bukkit.Location at) {
        if (kills == null) {
            return;
        }
        try {
            kills.insert(new dev.civitas.storage.row.WarKillRow(0, war.id(), killer, victim,
                            System.currentTimeMillis(),
                            at.getWorld().getName() + " " + at.getBlockX() + ","
                                    + at.getBlockY() + "," + at.getBlockZ()))
                    .exceptionally(error -> 0L);
        } catch (RuntimeException ignored) {
            // A closed pool throws from the call itself. The score is already awarded and
            // held in memory; only the feed line is lost.
        }
    }

    private dev.civitas.storage.dao.WarKillDao kills;

    /** SPEC 8.8's kill feed, wired by the plugin. */
    public void useKillLog(dev.civitas.storage.dao.WarKillDao dao) {
        this.kills = dao;
    }

    // ==================================================================================
    // Block breaking, SPEC 11.6
    // ==================================================================================

    /**
     * Breaking a block inside enemy claims scores a tenth of a point, up to the cap.
     *
     * <p>Inside <em>enemy claims</em>, not merely inside the zone: the zone includes a
     * one-chunk perimeter and both sides' own land, and neither digging a hole in the
     * wilderness nor demolishing your own city should score anything.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!wars.isAnyWarActive()) {
            return;
        }
        Block block = event.getBlock();
        Optional<Integer> owner = claims.ownerOf(block.getWorld().getName(),
                block.getX() >> 4, block.getZ() >> 4);
        if (owner.isEmpty()) {
            return;
        }
        Optional<Integer> breakerCity = cityIdOf(event.getPlayer().getUniqueId());
        if (breakerCity.isEmpty()) {
            return;
        }

        for (War war : wars.activeWarsCovering(block.getWorld().getName(), block.getX(),
                block.getZ())) {
            if (war.areEnemies(owner.get(), breakerCity.get())) {
                scoring.awardBlockBreak(war, war.isAttackerSide(breakerCity.get()));
                return;
            }
        }
    }

    // ==================================================================================
    // Defense units, SPEC 11.6 and SPEC 17.4 case 56
    // ==================================================================================

    /**
     * Destroying an enemy defense unit scores fifteen.
     *
     * <p>SPEC 17.4 case 56 settles the awkward case directly: "Defense unit is killed by its
     * own city's member: Allowed (removing a unit refunds nothing). No score awarded to
     * anyone." Without that check a city could farm points off its own garrison, which is both
     * free and unlosable.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Optional<Integer> unitCity = defenseUnits.cityOf(event.getEntity());
        if (unitCity.isEmpty()) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        Optional<Integer> killerCity = cityIdOf(killer.getUniqueId());
        if (killerCity.isEmpty()) {
            return;
        }

        var at = event.getEntity().getLocation();
        for (War war : wars.activeWarsCovering(at.getWorld().getName(), at.getBlockX(),
                at.getBlockZ())) {
            // areEnemies is false when the killer owns the unit, which is exactly case 56.
            if (war.areEnemies(unitCity.get(), killerCity.get())) {
                scoring.awardDefenseUnit(war, war.isAttackerSide(killerCity.get()));
                return;
            }
        }
    }

    private Optional<Integer> cityIdOf(UUID player) {
        return cities.cityOf(player).map(City::id);
    }

    /** Whether any war is worth ticking, for the objective task's first line. */
    public boolean hasActiveWars() {
        return wars.all().stream().anyMatch(war -> war.state() == WarState.ACTIVE);
    }

    /** Every active war, for the objective task. */
    public List<War> activeWars() {
        return wars.all().stream().filter(war -> war.state() == WarState.ACTIVE).toList();
    }
}
