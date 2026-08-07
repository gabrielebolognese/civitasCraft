package dev.civitas.listener.war;

import java.util.Objects;
import java.util.Optional;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.war.Evacuation;
import dev.civitas.core.war.War;
import dev.civitas.core.war.WarRegistry;
import dev.civitas.lang.LangManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * The two SPEC 17.4 cases about arriving inside a war zone.
 *
 * <p><b>Case 41.</b> "A player joins the server for the first time during a war and is standing
 * in the war zone… They are teleported out of the zone on join with an explanatory message."
 * They belong to neither city, so they are not a valid target and cannot grief: they are a
 * bystander in somebody else's fight, and leaving them there means being killed by a war they
 * have no part in.
 *
 * <p><b>Case 48.</b> "A player logs off inside the war zone and logs back in after rollback…
 * they are moved to the nearest safe location. Prevents suffocation in a restored block." The
 * rollback puts back whatever was there before the war, which may well be the inside of a wall.
 *
 * <p>The two share one handler because they are the same question asked at the same moment —
 * is this player somewhere a war has made unsafe, and where should they be instead — and the
 * answer differs only in which message they are told.
 */
public final class WarJoinListener implements Listener {

    private final WarRegistry wars;
    private final CityRegistry cities;
    private final Evacuation evacuation;
    private final LangManager lang;

    public WarJoinListener(WarRegistry wars, CityRegistry cities, Evacuation evacuation,
                           LangManager lang) {
        this.wars = Objects.requireNonNull(wars, "wars");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.evacuation = Objects.requireNonNull(evacuation, "evacuation");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location at = player.getLocation();
        if (at.getWorld() == null) {
            return;
        }

        Optional<War> closed = warWithClosedZoneAt(at);
        if (closed.isPresent()) {
            // Case 48: the land under them has just been restored, or is being restored now.
            evacuation.moveOut(player, closed.get());
            lang.send(player, "war.evacuated-rollback");
            return;
        }

        Optional<War> live = liveWarAt(at);
        if (live.isEmpty()) {
            return;
        }
        if (isParticipant(player, live.get())) {
            // A combatant logging back into their own war is exactly where they should be.
            return;
        }

        // Case 41.
        evacuation.moveOut(player, live.get());
        lang.send(player, "war.evacuated-bystander");
    }

    /**
     * The same rule for anyone teleporting in.
     *
     * <p>SPEC 11.8.2 step 1 closes the zone during a restore: "entry is blocked with a
     * message". A join is not the only way in.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to.getWorld() == null) {
            return;
        }
        if (warWithClosedZoneAt(to).isPresent()) {
            event.setCancelled(true);
            lang.send(event.getPlayer(), "war.zone-closed");
        }
    }

    private Optional<War> warWithClosedZoneAt(Location at) {
        for (War war : wars.all()) {
            if (war.state().isZoneClosed()
                    && war.zone().containsBlock(at.getWorld().getName(),
                            at.getBlockX(), at.getBlockZ())) {
                return Optional.of(war);
            }
        }
        return Optional.empty();
    }

    private Optional<War> liveWarAt(Location at) {
        var covering = wars.activeWarsCovering(at.getWorld().getName(),
                at.getBlockX(), at.getBlockZ());
        return covering.isEmpty() ? Optional.empty() : Optional.of(covering.get(0));
    }

    private boolean isParticipant(Player player, War war) {
        return cities.cityOf(player.getUniqueId()).map(City::id)
                .filter(war::involves)
                .isPresent();
    }
}
