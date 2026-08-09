package dev.civitas.core.defense;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.core.city.CityColour;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * SPEC 26.2 step 1's glow: "units glow in the city colour".
 *
 * <h2>Why this is not done by alerting the units early</h2>
 *
 * <p>The obvious way to make a unit glow during the warning is to put it into ALERTED and let
 * something downstream notice. That would break the one guarantee the warning phase exists to
 * give. {@link TargetingRule} permits a unit to attack a player only in ALERTED or HOSTILE, so
 * the sole reason nothing attacks during the five seconds SPEC 26.2 grants a trespasser is that
 * {@link UnitStates} is untouched until the warning ends. Reaching for an alert to get a visual
 * effect would let guards kill somebody during the window that exists so they do not.
 *
 * <p>So the glow is set on the entity directly and never touches unit state.
 *
 * <h2>The glow ends with the warning, which is deliberate</h2>
 *
 * <p>SPEC 26.2 lists the glow in step 1 and says nothing about it in step 2. Read literally, it
 * is a warning-phase effect, and that is what ships. The practical argument for the other
 * reading — that a unit about to attack you should be visible — is real, but it costs something
 * the literal reading gets for free: the warning always ends, because ending it is a scheduled
 * task that always runs, so a glow tied to the warning always clears. A glow tied to the alert
 * would have to be cleared when the alert expires, and nothing sweeps expiries —
 * {@link UnitStates} resolves them on read — so a city's guards would glow until the next
 * restart the first time nobody asked.
 */
public final class UnitGlow {

    private final DefenseRegistry units;

    /** Which unit ids are currently lit, per city, so they can all be put out again. */
    private final Map<Integer, Set<Integer>> lit = new ConcurrentHashMap<>();

    public UnitGlow(DefenseRegistry units) {
        this.units = Objects.requireNonNull(units, "units");
    }

    /**
     * How an entity is made to glow in a colour.
     *
     * <p>A seam for the same reason {@link UnitMaterializer.Spawn} is one: the real
     * implementation registers a scoreboard team, which MockBukkit does not implement, and an
     * unimplemented call aborts a test as a <i>skip</i> rather than a failure. Without this the
     * sequencing tests would report success while never having run.
     */
    @FunctionalInterface
    public interface Paint {

        void paint(LivingEntity entity, int cityId, NamedTextColor colour, boolean on);
    }

    private Paint paint = UnitGlow::withScoreboardTeam;

    public void usePaint(Paint replacement) {
        this.paint = Objects.requireNonNull(replacement, "replacement");
    }

    /** Lights up a city's units. Anything already lit for that city is put out first. */
    public void glow(int cityId, List<DefenseUnit> which) {
        clear(cityId);
        if (which.isEmpty()) {
            return;
        }
        NamedTextColor colour = CityColour.of(cityId);
        Set<Integer> nowLit = ConcurrentHashMap.newKeySet();
        for (DefenseUnit unit : which) {
            units.entityOf(unit.id()).ifPresent(entity -> {
                paint.paint(entity, cityId, colour, true);
                nowLit.add(unit.id());
            });
        }
        if (!nowLit.isEmpty()) {
            lit.put(cityId, nowLit);
        }
    }

    /**
     * Puts a city's units out again.
     *
     * <p>A unit whose entity has gone — killed, or dematerialised — needs nothing done to it,
     * because the glow was a property of an entity that no longer exists and the one that
     * replaces it is spawned without one.
     */
    public void clear(int cityId) {
        Set<Integer> was = lit.remove(cityId);
        if (was == null) {
            return;
        }
        NamedTextColor colour = CityColour.of(cityId);
        for (int unitId : was) {
            units.entityOf(unitId)
                    .ifPresent(entity -> paint.paint(entity, cityId, colour, false));
        }
    }

    /** Whether anything of this city's is currently lit, for the tests. */
    public boolean isGlowing(int cityId) {
        return lit.containsKey(cityId);
    }

    // ==================================================================================
    // The Bukkit half
    // ==================================================================================

    /**
     * Sets the glow, and the team that decides what colour it is.
     *
     * <p><b>Known limitation.</b> A glow is rendered in the entity's scoreboard team colour, and
     * a team exists on one scoreboard. {@code WarScoreboard} hands a player their own board when
     * they run {@code /war scoreboard}, and a team created on the main board does not exist
     * there — so a player watching a war sees a white outline rather than the city's. The team
     * is created on the main board and on every board the plugin has handed out that is
     * reachable through an online player, which covers that case as long as the player is
     * online when the glow starts.
     */
    private static void withScoreboardTeam(LivingEntity entity, int cityId,
                                           NamedTextColor colour, boolean on) {
        entity.setGlowing(on);
        if (!on) {
            return;
        }
        String name = CityColour.teamName(cityId);
        for (Scoreboard board : boards()) {
            Team team = board.getTeam(name);
            if (team == null) {
                team = board.registerNewTeam(name);
                team.color(colour);
            }
            team.addEntity(entity);
        }
    }

    /** Every scoreboard a viewer might be looking at, deduplicated. */
    private static List<Scoreboard> boards() {
        var manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return List.of();
        }
        java.util.Set<Scoreboard> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        seen.add(manager.getMainScoreboard());
        for (var player : Bukkit.getOnlinePlayers()) {
            seen.add(player.getScoreboard());
        }
        return List.copyOf(seen);
    }
}
