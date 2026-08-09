package dev.civitas.core.defense;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;

/**
 * Everything SPEC 28.8 requires turned off, and the one thing it requires turned on.
 *
 * <p>The vanilla Warden is the only mob in the game whose aggression the plugin does not already
 * control, and SPEC 28.8 is emphatic about why that matters: drive targeting "exclusively from the
 * plugin... or the Warden will aggro on a member walking past". A city that paid 750,000 C for a
 * landmark must not have it kill the people who live there.
 *
 * <h2>Anger is the target, and that is not a style choice</h2>
 *
 * <p>A Warden is a brain mob, not a goal mob. {@code Mob#setTarget} writes a field its brain does
 * not read, and its melee attack fires from the anger map at {@code AngerLevel.ANGRY}. So SPEC
 * 28.8's "drive targeting exclusively from the plugin" is implemented by <b>owning the anger
 * map</b>: anger is set on exactly the one candidate {@link TargetingRule} has permitted and
 * cleared from everything else, every tick.
 *
 * <h2>What SPEC 28.8 asks for and the API cannot give</h2>
 *
 * <p>Two literal instructions in SPEC 28.8 have no public implementation in paper-api 1.21.11, and
 * both are recorded rather than quietly approximated.
 *
 * <p>First, {@code Warden#clearAnger()} — the no-argument form SPEC names by hand — does not
 * exist. The interface offers only {@code clearAnger(Entity)}, and there is no way to enumerate
 * the anger map, so "clearAnger() every tick" cannot be written as SPEC writes it. What is written
 * instead reaches the same state from two directions: {@link #suppressAnger} clears the entity the
 * Warden is currently angry at whenever that is not the plugin's chosen target, and the listener
 * cancels {@code WardenAngerChangeEvent} for every increase the plugin did not initiate — which is
 * the vibration system's own entry point, so no disturbance ever enters the map to be enumerated.
 *
 * <p>Second, "cancel the sonic boom goal via the Paper Goal API so the animation never plays"
 * cannot be done. {@code VanillaGoal} carries 194 goal keys and not one of them is the Warden's;
 * a brain mob has no {@code GoalSelector} entries to remove. The <b>damage</b> is cancelled
 * unconditionally and is verified by a test, so the attack does nothing — but a player in front of
 * an angry Warden will still see the windup, which is the one line of SPEC 28.8 this milestone
 * does not deliver. It is the same shape as M17's beehive NBT: an API limitation, recorded in
 * OPEN_QUESTIONS, needing a developer decision about NMS rather than a workaround here.
 */
public final class WardenSuppression {

    private final DefenseCatalogue catalogue;

    /**
     * Wardens whose anger the plugin is in the middle of writing.
     *
     * <p>{@code setAnger} fires {@code WardenAngerChangeEvent}, the same event the listener
     * cancels to keep the vibration system out of the map. Without this flag the listener would
     * cancel the plugin's own writes, the Warden would never become angry at anything, and the
     * bug would present as "the Warden is passive" rather than as a cancelled event.
     */
    private final Set<UUID> driving = ConcurrentHashMap.newKeySet();

    public WardenSuppression(DefenseCatalogue catalogue) {
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
    }

    /** Whether this entity is a Warden the plugin owns. */
    public static Optional<Warden> asWarden(Entity entity, DefenseUnitType type) {
        boolean isWardenType = type != null && CityWarden.TYPE_KEY.equals(type.key());
        return isWardenType && entity instanceof Warden warden
                ? Optional.of(warden)
                : Optional.empty();
    }

    // ==================================================================================
    // Spawn
    // ==================================================================================

    /**
     * The flags SPEC 28.8 requires on every materialisation.
     *
     * <p>Per materialisation and not per purchase, for the reason SPEC 30.2 case 108 gives about
     * daylight burning: under SPEC 25.4 a unit is a fresh entity every time a player walks past,
     * so a flag set once at placement holds only until the first time everybody leaves.
     *
     * <p>{@link DefenseSpawner} has already applied SPEC 28.3's health, damage, speed and knockback
     * resistance from the catalogue by the time this runs; what is left is the behaviour that has
     * no attribute.
     */
    public void shape(LivingEntity living, DefenseUnitType type) {
        Optional<Warden> found = asWarden(living, type);
        if (found.isEmpty()) {
            return;
        }
        Warden warden = found.get();

        // SPEC 28.3: "Despawn disabled. Must persist." DefenseSpawner sets both already; repeated
        // here because a Warden that despawned would take a 750,000 C row with no entity with it.
        warden.setRemoveWhenFarAway(false);
        warden.setPersistent(true);

        // SPEC 28.3: vibration targeting disabled. A freshly spawned Warden has heard nothing
        // yet, so this is belt and braces -- what actually keeps the map empty is the listener
        // cancelling every anger change the plugin did not initiate.
        clearAllAnger(warden);
    }

    // ==================================================================================
    // The tick, SPEC 28.8
    // ==================================================================================

    /**
     * Keeps the anger map to exactly what the plugin put there.
     *
     * <p>Run every tick of the defense sweep. The common case is a PASSIVE Warden with no chosen
     * target, where this clears whatever the vibration system managed to write before the listener
     * saw it and costs one accessor call.
     *
     * <p>Cleared over the players in range rather than through {@code Warden#getEntityAngryAt},
     * which returns only the single highest entry: clearing one per tick would let a crowd of
     * bystanders each hold anger for as many ticks as there are of them, and the case SPEC 28.8
     * names -- a member walking past -- is precisely a crowd near a city's own City Hall.
     *
     * @param chosen the one player {@link TargetingRule} has permitted, or null in every state
     *               but ALERTED and HOSTILE
     * @param nearby everyone close enough to have provoked it
     * @return whether anything was cleared, which is what the test asserts
     */
    public boolean suppressAnger(Warden warden, Player chosen,
                                 Collection<? extends Player> nearby) {
        boolean cleared = false;
        for (Player player : nearby) {
            boolean isChosen = chosen != null
                    && player.getUniqueId().equals(chosen.getUniqueId());
            if (!isChosen && warden.getAnger(player) > 0) {
                // SPEC 28.8: "or the Warden will aggro on a member walking past". This is that
                // line, and it is the one that has to hold every tick rather than once.
                clear(warden, player);
                cleared = true;
            }
        }
        if (chosen != null) {
            drive(warden, chosen);
        }
        return cleared;
    }

    /**
     * Makes the Warden attack the one candidate the rule allowed.
     *
     * <p>SPEC 28.4 fixes the damage at 10 and spends a table explaining why: two hits on an
     * unarmoured trespasser, twenty on a netherite raider, "and that asymmetry is the entire
     * design". None of it lands unless the Warden actually swings, and a brain mob swings from its
     * anger map — so this is what makes SPEC 28.4 true rather than decorative.
     */
    public void drive(Warden warden, Player target) {
        int level = catalogue.wardenAngerOnTarget();
        if (warden.getAnger(target) >= level) {
            return;
        }
        UUID id = warden.getUniqueId();
        driving.add(id);
        try {
            warden.setAnger(target, level);
        } finally {
            driving.remove(id);
        }
    }

    /** Whether the plugin is the one writing this Warden's anger right now. */
    public boolean isDriving(UUID warden) {
        return warden != null && driving.contains(warden);
    }

    /**
     * Forgets the one entity the API will name, which is as far as it can be asked.
     *
     * <p>{@code Warden#getEntityAngryAt} returns only the highest entry and there is no way to
     * enumerate the map, which is the whole of why SPEC 28.8's {@code clearAnger()} cannot be
     * written as SPEC writes it. Used at spawn, where a fresh entity has heard nothing anyway;
     * the tick clears over the players in range instead, and the listener stops anything entering.
     */
    public void clearAllAnger(Warden warden) {
        LivingEntity angryAt = warden.getEntityAngryAt();
        if (angryAt != null) {
            clear(warden, angryAt);
        }
    }

    /**
     * Forgets one entity, through {@code setAnger(at, 0)} rather than {@code clearAnger(at)}.
     *
     * <p>The two are the same operation. The choice is deliberate for the reason
     * {@code UnitMaterializer.useSpawn} exists: MockBukkit implements {@code setAnger} and does
     * not implement {@code clearAnger}, and an unimplemented Bukkit method is recorded by JUnit as
     * a <em>skip</em> rather than a failure — so SPEC 31's "disabled and verified" would have been
     * a green suite in which the single most important line of SPEC 28.8 never ran.
     *
     * <p>Inside the driving flag because a decrease fires {@code WardenAngerChangeEvent} too, and
     * the listener must be able to tell the plugin's own writes from the vibration system's.
     */
    private void clear(Warden warden, Entity at) {
        UUID id = warden.getUniqueId();
        driving.add(id);
        try {
            warden.setAnger(at, 0);
        } finally {
            driving.remove(id);
        }
    }
}
