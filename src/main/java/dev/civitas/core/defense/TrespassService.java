package dev.civitas.core.defense;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.ClaimRegistry;
import org.bukkit.Location;

/**
 * SPEC 26.2's trespass response, joined up.
 *
 * <p>Holds the sliding window and the phases, and turns a refusal into the sequence SPEC 26.2
 * describes: three strikes, a warning, then an alert against that one player. The effects —
 * the roar, the glow, the messages, the audit row — are handed to callbacks, so this class
 * stays testable and the listener stays plumbing.
 *
 * <h2>The rule that shapes everything here</h2>
 *
 * <p>SPEC 26.2's opening: "This is what replaces 'attacks foreigners on sight.'" A city's guards
 * are not a tripwire. They ignore a visitor completely until that visitor has repeatedly done
 * something they were told they could not, and even then the first thing that happens is a
 * warning they can act on.
 */
public final class TrespassService {

    private final ConfigManager configs;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final UnitStates states;
    private final DefenseRegistry units;

    private final TrespassTracker tracker;
    private final TrespassResponse response;

    /** What the listener does about a warning, an alert and a calm. */
    private Consumer<Event> effects = event -> { };

    /** One player in one city, for the debounce map. */
    private record Offender(int cityId, UUID player) {
    }

    /** When a violation last counted, per offender. The debounce, see {@link #violated}. */
    private final java.util.Map<Offender, Long> lastCounted =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** One thing that happened, for the listener to make visible. */
    public record Event(Kind kind, City city, UUID player, Location where, long seconds,
                        int strikes) {

        public enum Kind {

            /**
             * One violation counted. Below the threshold this is all that happens.
             *
             * <p>Exists so SPEC 26.2's "violations are logged to {@code audit_log}, so an admin
             * investigating a grief report can see the pattern" has something to log. A pattern
             * is not visible from the warnings alone: two strikes and a walk away is the shape
             * of somebody testing a city's defences, and it never produces a warning.
             */
            VIOLATION,

            /** SPEC 26.2 step 1: roar, glow, chat and title. Nothing attacks yet. */
            WARNING,

            /** SPEC 26.2 step 2: units target this player only. */
            ALERTED,

            /** SPEC 26.2 step 3: they left, or it expired. */
            CALMED
        }
    }

    public TrespassService(ConfigManager configs, CityRegistry cities, ClaimRegistry claims,
                           UnitStates states, DefenseRegistry units) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.states = Objects.requireNonNull(states, "states");
        this.units = Objects.requireNonNull(units, "units");

        var defense = configs.get(ConfigFile.DEFENSE);
        this.tracker = new TrespassTracker(
                defense.getInt("trespass.violations", 3),
                defense.getLong("trespass.window-seconds", 30) * 1000L);
        this.response = new TrespassResponse(
                defense.getLong("trespass.warning-seconds", 5) * 1000L,
                defense.getLong("trespass.duration-seconds", 45) * 1000L);
    }

    public void useEffects(Consumer<Event> sink) {
        this.effects = Objects.requireNonNull(sink, "sink");
    }

    public TrespassResponse response() {
        return response;
    }

    public TrespassTracker tracker() {
        return tracker;
    }

    /** The units, so the listener can find the entity behind a unit to roar from. */
    public DefenseRegistry registry() {
        return units;
    }

    /**
     * Every city currently alerted against this player, for SPEC 30.2 case 94's rejoin.
     *
     * <p>Asked rather than remembered, because the only thing that survives a logout is the
     * response's own clock — the listener's bookkeeping does not, and neither does the state of
     * any unit.
     */
    public List<Integer> citiesAlerting(UUID player, long now) {
        List<Integer> alerting = new java.util.ArrayList<>();
        for (City city : cities.cities()) {
            if (response.alertedIn(city.id(), now).contains(player)) {
                alerting.add(city.id());
            }
        }
        return alerting;
    }

    // ==================================================================================
    // The sequence
    // ==================================================================================

    /**
     * One violation, SPEC 26.2.
     *
     * <h2>Why one player action must not be several violations</h2>
     *
     * <p>The refusals that feed this are not one per deliberate act. A held left-click on a
     * protected block fires {@code BlockBreakEvent} every tick; one bucket pour asks the guard
     * twice, for the block clicked and the block it lands on; one {@code BlockMultiPlaceEvent}
     * asks once per replaced state. Counted raw, a player who holds a mouse button for a fifth
     * of a second crosses SPEC 26.2's three-strike threshold — so the first thing a visitor
     * ever saw of a city's defences would be a false positive, and SPEC 26.2's whole design is
     * that the response is proportionate and earned.
     *
     * <p>So a violation inside {@code trespass.violation-cooldown-ms} of the last counted one,
     * for the same player in the same city, is dropped. Not in the guard, where it would be a
     * protection concern reading defense configuration; here, where the rest of SPEC 26.2's
     * rules already live and where any later violation source gets it for nothing.
     *
     * @return true when this was the strike that started a warning
     */
    public boolean violated(int cityId, UUID player, Location where, long now) {
        if (!enabled()) {
            return false;
        }
        Optional<City> city = cities.city(cityId);
        if (city.isEmpty()) {
            return false;
        }
        // SPEC 26.3: trespass response is suspended during an ACTIVE war, because everything
        // in the zone is hostile anyway. Warning an attacker before the guards engage them
        // would be the opposite of what a siege is.
        if (wars.isInActiveWar(cityId)) {
            return false;
        }
        if (!counts(cityId, player, now)) {
            return false;
        }

        boolean crossed = tracker.record(cityId, player, now);
        effects.accept(new Event(Event.Kind.VIOLATION, city.get(), player, where, 0,
                crossed ? tracker.threshold() : tracker.count(cityId, player, now)));
        if (!crossed) {
            return false;
        }
        if (!response.warn(cityId, player, now)) {
            return false;
        }
        effects.accept(new Event(Event.Kind.WARNING, city.get(), player, where,
                warningSeconds(), tracker.threshold()));
        return true;
    }

    /** The debounce: whether enough has passed since this player's last counted violation. */
    private boolean counts(int cityId, UUID player, long now) {
        long cooldown = violationCooldownMillis();
        if (cooldown <= 0) {
            return true;
        }
        Offender key = new Offender(cityId, player);
        Long last = lastCounted.get(key);
        if (last != null && now - last < cooldown) {
            return false;
        }
        lastCounted.put(key, now);
        return true;
    }

    /**
     * The warning has run out, SPEC 26.2 step 2.
     *
     * @param stillInside whether the player is still standing on this city's land
     * @return true when they are now ALERTED
     */
    public boolean warningEnded(int cityId, UUID player, Location where, boolean stillInside,
                               long now) {
        Optional<City> city = cities.city(cityId);
        if (city.isEmpty()) {
            return false;
        }
        if (!response.promote(cityId, player, stillInside, now)) {
            // Took the warning and left. Nothing happens to them, which is the outcome the
            // warning phase exists to produce.
            effects.accept(new Event(Event.Kind.CALMED, city.get(), player, where, 0, 0));
            return false;
        }

        alertNetwork(city.get(), player, where, now);
        effects.accept(new Event(Event.Kind.ALERTED, city.get(), player, where,
                alertedSeconds(), 0));
        return true;
    }

    /** SPEC 26.2 step 3: the trespasser left the city's claims. */
    public void leftClaims(int cityId, UUID player, Location where) {
        Optional<City> city = cities.city(cityId);
        if (city.isEmpty()) {
            return;
        }
        boolean wasSomething = response.phaseOf(cityId, player, System.currentTimeMillis())
                != TrespassResponse.Phase.NONE;

        response.deEscalate(cityId, player);
        tracker.clear(cityId, player);
        lastCounted.remove(new Offender(cityId, player));
        calmUnits(city.get(), player);

        if (wasSomething) {
            effects.accept(new Event(Event.Kind.CALMED, city.get(), player, where, 0, 0));
        }
    }

    /**
     * Re-applies a live alert to the units of a city, SPEC 30.2 case 94.
     *
     * <p>"A trespasser who logs out during ALERTED keeps the alert for its remaining duration,
     * and logging back in inside the claims resumes it." The response survives a logout because
     * {@link TrespassResponse} is a clock — but the per-unit half does not: case 95 lets the
     * units dematerialise while the trespasser is away, and {@link UnitStates#materialized}
     * brings every one of them back PASSIVE. Without this the response would say ALERTED, every
     * unit would say PASSIVE, and nothing would happen.
     *
     * @return how many units were put back on alert
     */
    public int reapply(int cityId, long now) {
        if (!enabled()) {
            return 0;
        }
        int alerted = 0;
        for (UUID target : response.alertedIn(cityId, now)) {
            long until = now + remainingAlertMillis(cityId, target, now);
            for (DefenseUnit unit : units.all()) {
                if (unit.cityId() == cityId && states.alert(unit.id(), target, until)) {
                    alerted++;
                }
            }
        }
        return alerted;
    }

    private long remainingAlertMillis(int cityId, UUID player, long now) {
        // The response's own clock is the authority; a re-applied alert must end when the
        // response does rather than starting a fresh 45 seconds every time a unit stands up.
        return response.endsAt(cityId, player).map(end -> Math.max(0, end - now))
                .orElse(response.alertedMillis());
    }

    /**
     * SPEC 26.2: "All materialized units in the affected chunk plus a 2-chunk radius."
     *
     * <p>A radius rather than the whole city, because a city can be hundreds of chunks across
     * and a guard on the far side has not seen anything. It is also what makes the response
     * feel local — somebody breaking into a warehouse rouses that warehouse's guards.
     */
    private void alertNetwork(City city, UUID player, Location where, long now) {
        if (where == null || where.getWorld() == null) {
            return;
        }
        int radius = alertRadiusChunks();
        int centreX = where.getBlockX() >> 4;
        int centreZ = where.getBlockZ() >> 4;
        long until = now + response.alertedMillis();

        for (DefenseUnit unit : nearbyUnits(city, where.getWorld().getName(),
                centreX, centreZ, radius)) {
            states.alert(unit.id(), player, until);
        }
    }

    private void calmUnits(City city, UUID player) {
        for (DefenseUnit unit : units.all()) {
            if (unit.cityId() == city.id()) {
                states.calm(unit.id());
            }
        }
    }

    /**
     * The units SPEC 26.2 step 1 has roar, for the listener to actually make a noise with.
     *
     * <p>Filtered on <b>materialised</b>, which SPEC 26.2 states in as many words: "All
     * materialized units in the affected chunk plus a 2-chunk radius roar or play their alert
     * sound." A dormant unit is a database row with no entity, so there is nowhere for a sound
     * to come from and nothing to glow. The alert half fails closed on its own, because
     * {@link UnitStates#alert} refuses a unit that is not standing.
     */
    public List<DefenseUnit> materializedUnitsNear(City city, Location where) {
        if (where == null || where.getWorld() == null) {
            return List.of();
        }
        return nearbyUnits(city, where.getWorld().getName(),
                where.getBlockX() >> 4, where.getBlockZ() >> 4, alertRadiusChunks())
                .stream()
                .filter(unit -> units.isMaterialized(unit.id()))
                .toList();
    }

    private List<DefenseUnit> nearbyUnits(City city, String world, int centreX, int centreZ,
                                          int radius) {
        List<DefenseUnit> near = new java.util.ArrayList<>();
        for (DefenseUnit unit : units.all()) {
            if (unit.cityId() != city.id() || !unit.world().equals(world)) {
                continue;
            }
            int dx = Math.abs(((int) Math.floor(unit.x()) >> 4) - centreX);
            int dz = Math.abs(((int) Math.floor(unit.z()) >> 4) - centreZ);
            if (Math.max(dx, dz) <= radius) {
                near.add(unit);
            }
        }
        return near;
    }

    /** Whether this chunk belongs to the city, for the "still inside" question. */
    public boolean isInsideClaims(int cityId, Location where) {
        if (where == null || where.getWorld() == null) {
            return false;
        }
        return claims.at(where.getWorld().getName(),
                        where.getBlockX() >> 4, where.getBlockZ() >> 4)
                .map(claim -> claim.cityId() == cityId)
                .orElse(false);
    }

    /**
     * Drops everything remembered about a player.
     *
     * <p><b>Not called on quit.</b> SPEC 30.2 case 94 requires an alert to survive a logout —
     * "logging back in inside the claims resumes it" — so the usual per-player cleanup that
     * every other listener does on {@code PlayerQuitEvent} would delete the one piece of state
     * this feature is asked to keep.
     */
    public void forget(UUID player) {
        tracker.forget(player);
        response.forget(player);
        lastCounted.keySet().removeIf(offender -> offender.player().equals(player));
    }

    public void forgetCity(int cityId) {
        tracker.forgetCity(cityId);
        response.forgetCity(cityId);
        lastCounted.keySet().removeIf(offender -> offender.cityId() == cityId);
    }

    // ==================================================================================
    // The seam M19's wiring fills
    // ==================================================================================

    /** Whether this city is in a war that is being fought. SPEC 26.3. */
    @FunctionalInterface
    public interface Wars {

        boolean isInActiveWar(int cityId);
    }

    private Wars wars = cityId -> false;

    public void useWars(Wars registry) {
        this.wars = Objects.requireNonNull(registry, "registry");
    }

    // ==================================================================================
    // Configuration
    // ==================================================================================

    public boolean enabled() {
        return configs.get(ConfigFile.DEFENSE).getBoolean("trespass.enabled", true);
    }

    public long warningSeconds() {
        return configs.get(ConfigFile.DEFENSE).getLong("trespass.warning-seconds", 5);
    }

    public long alertedSeconds() {
        return configs.get(ConfigFile.DEFENSE).getLong("trespass.duration-seconds", 45);
    }

    public int alertRadiusChunks() {
        return configs.get(ConfigFile.DEFENSE).getInt("trespass.alert-radius-chunks", 2);
    }

    public long deEscalationSeconds() {
        return configs.get(ConfigFile.DEFENSE).getLong("trespass.de-escalation-seconds", 10);
    }

    /** The debounce that keeps one player action from being several violations. */
    public long violationCooldownMillis() {
        return configs.get(ConfigFile.DEFENSE).getLong("trespass.violation-cooldown-ms", 1500);
    }

    /** SPEC 26.2 step 1's glow, which is purely visual and so may be switched off. */
    public boolean glowEnabled() {
        return configs.get(ConfigFile.DEFENSE).getBoolean("trespass.glow", true);
    }

    public String warningSound() {
        return configs.get(ConfigFile.DEFENSE)
                .getString("trespass.warning-sound", "entity.warden.roar");
    }

    public float warningSoundVolume() {
        return (float) configs.get(ConfigFile.DEFENSE)
                .getDouble("trespass.warning-sound-volume", 1.0);
    }

    /** SPEC 30.4 gives the trespass warning roar a pitch of 1.0. */
    public float warningSoundPitch() {
        return (float) configs.get(ConfigFile.DEFENSE)
                .getDouble("trespass.warning-sound-pitch", 1.0);
    }
}
