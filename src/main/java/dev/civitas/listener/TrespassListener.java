package dev.civitas.listener;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.core.admin.AuditService;
import dev.civitas.core.city.City;
import dev.civitas.core.defense.TrespassService;
import dev.civitas.core.defense.UnitGlow;
import dev.civitas.lang.LangManager;
import dev.civitas.msg.Channel;
import dev.civitas.msg.Messenger;
import dev.civitas.msg.ToggleCategory;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The visible half of SPEC 26.2's trespass response.
 *
 * <p>{@link TrespassService} decides <em>what</em> a city is doing about a player and when it
 * changes. This runs the clocks that make the phases actually pass, and turns each change into
 * the roar, the glow, the messages and the audit row SPEC 26.2 and SPEC 30.4 describe.
 *
 * <h2>Two timers, and why neither is a sweep</h2>
 *
 * <ul>
 *   <li>The <b>warning</b> ends after {@code trespass.warning-seconds}. Scheduled once when the
 *       warning starts, and it asks at the moment it runs whether the player is still on the
 *       city's land — not when it was scheduled, because that is the entire question it exists
 *       to answer.
 *   <li>The <b>de-escalation</b> takes {@code trespass.de-escalation-seconds}. SPEC 26.2 step 3
 *       says leaving "immediately begins a 10-second de-escalation", so a trespasser who steps
 *       over the border and back is not calmed. Cancelled on re-entry for exactly that reason.
 * </ul>
 *
 * <h2>What must not happen on quit</h2>
 *
 * <p>Every other listener in this package clears its per-player state on
 * {@code PlayerQuitEvent}, and copying that line here would break SPEC 30.2 case 94: "a
 * trespasser who logs out during ALERTED keeps the alert for its remaining duration, and logging
 * back in inside the claims resumes it." A logout is also not "leaving the claims", so routing
 * it into {@code leftClaims} would de-escalate and break the same case from the other side.
 */
public final class TrespassListener implements Listener {

    private final Plugin plugin;
    private final TrespassService trespass;
    private final Messenger messenger;
    private final UnitGlow glow;
    private final AuditService audit;

    /** The city each engaged player is engaged with, so a move by anyone else costs one lookup. */
    private final Map<UUID, Integer> engaged = new ConcurrentHashMap<>();

    /** A pending de-escalation, per player, so re-entry can cancel it. */
    private final Map<UUID, BukkitTask> deEscalating = new ConcurrentHashMap<>();

    public TrespassListener(Plugin plugin, TrespassService trespass, Messenger messenger,
                            UnitGlow glow, AuditService audit) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.trespass = Objects.requireNonNull(trespass, "trespass");
        this.messenger = Objects.requireNonNull(messenger, "messenger");
        this.glow = Objects.requireNonNull(glow, "glow");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    // ==================================================================================
    // What the service tells us
    // ==================================================================================

    /** Registered as {@link TrespassService#useEffects}. */
    public void on(TrespassService.Event event) {
        switch (event.kind()) {
            case VIOLATION -> record(event);
            case WARNING -> warn(event);
            case ALERTED -> alerted(event);
            case CALMED -> calmed(event);
            default -> { }
        }
    }

    /**
     * SPEC 26.2: "Violations are logged to {@code audit_log}, so an admin investigating a grief
     * report can see the pattern."
     *
     * <p>One row per counted violation, which after the service's debounce is one per deliberate
     * act rather than one per event. Every violation and not only the ones that cross the
     * threshold, because two strikes and a walk away is a pattern and never produces a warning.
     *
     * <p>Worth knowing when reading {@code /ca audit}: SPEC 3.9 calls the audit log "admin
     * actions only", and SPEC 26.2 then puts player violations in it. These rows have a player
     * as their actor and no admin anywhere.
     */
    private void record(TrespassService.Event event) {
        Location at = event.where();
        audit.record(event.player(), "TRESPASS_VIOLATION", event.city().name(), null,
                Map.of("strikes", String.valueOf(event.strikes()),
                        "world", at == null || at.getWorld() == null
                                ? "?" : at.getWorld().getName(),
                        "chunk", at == null ? "?"
                                : (at.getBlockX() >> 4) + "," + (at.getBlockZ() >> 4)));
    }

    /** SPEC 26.2 step 1: roar, glow, and tell them in plain language, twice. */
    private void warn(TrespassService.Event event) {
        City city = event.city();
        engaged.put(event.player(), city.id());
        cancelDeEscalation(event.player());

        var units = trespass.materializedUnitsNear(city, event.where());
        roar(units, event.where());
        if (trespass.glowEnabled()) {
            glow.glow(city.id(), units);
        }

        Player player = Bukkit.getPlayer(event.player());
        if (player != null) {
            // SPEC 30.4 gives this a title AND a chat line. The title is decoration over the
            // chat line, so it is dropped rather than downgraded when a player is past SPEC
            // 23.4's four-per-hour cap; the chat line always arrives.
            messenger.sendTitle(event.player(), player, ToggleCategory.WAR,
                    "trespass.warning-title", "trespass.warning-subtitle",
                    LangManager.placeholder("cityname", city.displayName()));
            messenger.send(event.player(), player, ToggleCategory.WAR, Channel.CHAT,
                    "trespass.warning",
                    LangManager.placeholder("cityname", city.displayName()),
                    LangManager.placeholder("seconds", String.valueOf(event.seconds())));
            messenger.playSound(event.player(), player, warningSound());
        }

        notifyCity(city, event);
        scheduleWarningEnd(city.id(), event.player());
    }

    /** SPEC 26.2 step 2: the units are now hostile to this one player. */
    private void alerted(TrespassService.Event event) {
        engaged.put(event.player(), event.city().id());
        // SPEC 26.2 puts the glow in step 1 and says nothing about it in step 2, so it ends
        // with the warning. See UnitGlow for why the literal reading is also the safe one.
        glow.clear(event.city().id());

        Player player = Bukkit.getPlayer(event.player());
        if (player != null) {
            messenger.send(event.player(), player, ToggleCategory.WAR, Channel.CHAT,
                    "trespass.alerted",
                    LangManager.placeholder("cityname", event.city().displayName()),
                    LangManager.placeholder("seconds", String.valueOf(event.seconds())));
        }
    }

    /** SPEC 26.2 step 3, or a warning nobody stayed for. */
    private void calmed(TrespassService.Event event) {
        engaged.remove(event.player());
        cancelDeEscalation(event.player());
        glow.clear(event.city().id());
    }

    /**
     * SPEC 30.4's {@code trespass.city_notice}, to every online member.
     *
     * <p>Once, at the warning, rather than on every violation: the warning is the moment the
     * city can still do something about it, and a line per blocked click would be the flood
     * SPEC 23.1 spends a section avoiding. SPEC 23.5's audience code CITY gives no radius, so
     * this reaches a member on the far side of a two-hundred-chunk city — reusing the units'
     * alert radius would be a second meaning for one config key.
     */
    private void notifyCity(City city, TrespassService.Event event) {
        Location at = event.where();
        String name = Optional.ofNullable(Bukkit.getOfflinePlayer(event.player()).getName())
                .orElse(event.player().toString());

        for (var each : city.members()) {
            UUID member = each.uuid();
            Player online = Bukkit.getPlayer(member);
            if (online == null) {
                continue;
            }
            messenger.send(member, online, ToggleCategory.MEMBERSHIP, Channel.CHAT,
                    "trespass.city-notice",
                    LangManager.placeholder("player", name),
                    LangManager.placeholder("x", at == null ? "?"
                            : String.valueOf(at.getBlockX() >> 4)),
                    LangManager.placeholder("z", at == null ? "?"
                            : String.valueOf(at.getBlockZ() >> 4)));
        }
    }

    // ==================================================================================
    // The clocks
    // ==================================================================================

    private void scheduleWarningEnd(int cityId, UUID player) {
        long ticks = Math.max(1L, trespass.warningSeconds() * 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(player);
            // Recomputed here rather than captured when this was scheduled, because whether
            // they are still standing there is the whole question. A player who has logged
            // out is not still inside, which is also SPEC 30.2 case 94's opening move.
            boolean inside = online != null
                    && trespass.isInsideClaims(cityId, online.getLocation());
            trespass.warningEnded(cityId, player, online == null ? null : online.getLocation(),
                    inside, System.currentTimeMillis());
        }, ticks);
    }

    /** A player crossing out of the claims their trespass is about. */
    private void handleCrossing(Player player, Location to) {
        if (to == null || engaged.isEmpty()) {
            return;
        }
        Integer cityId = engaged.get(player.getUniqueId());
        if (cityId == null) {
            return;
        }

        if (trespass.isInsideClaims(cityId, to)) {
            // Back inside within the ten seconds. SPEC 26.2's de-escalation is a window, not
            // an instant, precisely so stepping over the border and back does not reset it.
            cancelDeEscalation(player.getUniqueId());
            return;
        }
        scheduleDeEscalation(player, cityId, to);
    }

    private void scheduleDeEscalation(Player player, int cityId, Location where) {
        UUID uuid = player.getUniqueId();
        if (deEscalating.containsKey(uuid)) {
            return;
        }
        long ticks = Math.max(1L, trespass.deEscalationSeconds() * 20L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            deEscalating.remove(uuid);
            // Dropped whether or not leftClaims finds anything to calm: an alert that simply
            // ran out fires no event, so this is the only thing that clears the fast path.
            engaged.remove(uuid);
            trespass.leftClaims(cityId, uuid, where);
        }, ticks);
        deEscalating.put(uuid, task);
    }

    private void cancelDeEscalation(UUID player) {
        BukkitTask pending = deEscalating.remove(player);
        if (pending != null) {
            pending.cancel();
        }
    }

    // ==================================================================================
    // Events
    // ==================================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        handleCrossing(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        handleCrossing(event.getPlayer(), event.getTo());
    }

    /**
     * SPEC 30.2 case 94: an alert resumes when the trespasser comes back.
     *
     * <p>The response survives a logout on its own, because it is a clock. The per-unit half
     * does not: case 95 lets the units dematerialise while the trespasser is away, and every
     * one of them comes back PASSIVE. Without this the city would still consider the player
     * alerted and not one guard would move.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location at = player.getLocation();
        long now = System.currentTimeMillis();

        trespass.citiesAlerting(player.getUniqueId(), now).forEach(cityId -> {
            engaged.put(player.getUniqueId(), cityId);
            if (trespass.isInsideClaims(cityId, at)) {
                trespass.reapply(cityId, now);
            }
        });
    }

    /**
     * Only the pending timer goes.
     *
     * <p>Not {@code trespass.forget}, and not {@code leftClaims}. See the class note: SPEC 30.2
     * case 94 requires the alert itself to survive, and case 95 says the units simply
     * dematerialise on their own while it runs out.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cancelDeEscalation(event.getPlayer().getUniqueId());
        // The fast-path map goes, because it is bookkeeping about a session. The alert itself
        // does not, and {@link #onJoin} rebuilds this from the response when they come back.
        engaged.remove(event.getPlayer().getUniqueId());
    }

    // ==================================================================================
    // Effects
    // ==================================================================================

    /** SPEC 26.2 step 1: "units ... roar or play their alert sound". SPEC 30.4 names the roar. */
    private void roar(java.util.List<dev.civitas.core.defense.DefenseUnit> units, Location at) {
        if (units.isEmpty() || at == null || at.getWorld() == null) {
            return;
        }
        Sound sound = warningSound();
        for (var unit : units) {
            Optional<LivingEntity> entity = entityOf(unit);
            // Played at the unit rather than at the player, so the sound comes from the
            // direction of the thing that is about to be a problem.
            entity.ifPresent(living -> living.getWorld().playSound(sound,
                    living.getLocation().getX(), living.getLocation().getY(),
                    living.getLocation().getZ()));
        }
    }

    private Optional<LivingEntity> entityOf(dev.civitas.core.defense.DefenseUnit unit) {
        return trespass.registry().entityOf(unit.id());
    }

    private Sound warningSound() {
        return Sound.sound(Key.key(trespass.warningSound()), Sound.Source.HOSTILE,
                trespass.warningSoundVolume(), trespass.warningSoundPitch());
    }
}
