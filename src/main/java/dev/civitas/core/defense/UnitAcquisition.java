package dev.civitas.core.defense;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Which of the players a unit is allowed to attack it actually goes after.
 *
 * <h2>Why this has to exist at all</h2>
 *
 * <p>SPEC 30.1's handler only ever <b>vetoes</b>. It answers "may this unit target that
 * candidate", and a veto is meaningless until something proposes a candidate — a City Guard
 * that becomes ALERTED will stand still, because a zombie's own goals never selected that
 * player and there was nothing for the rule to allow. A Warhound is worse: SPEC 27.4 makes it a
 * wolf, and the spawner tames it to its city with no owner, and a tamed ownerless wolf
 * initiates nothing whatsoever. Without an acquisition pass the entire roster is inert, and
 * every test of the state machine still passes.
 *
 * <h2>And why it is not a second targeting rule</h2>
 *
 * <p>SPEC 30.1: "no unit-specific targeting logic anywhere else." This does not decide who may
 * be attacked; it is handed the candidates {@link TargetingRule} already permitted and picks
 * one. That distinction is what makes SPEC 27.4's "prioritises the lowest-health valid target"
 * implementable at all — <em>valid</em> is the rule's word, and it is doing the work.
 */
public final class UnitAcquisition {

    private UnitAcquisition() {
    }

    /**
     * One candidate the targeting rule has already allowed.
     *
     * @param health what they are standing at, for SPEC 27.4's Warhound
     */
    public record Target(UUID uuid, double distance, double health) {

        public Target {
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    /**
     * Which permitted candidate this unit goes for.
     *
     * <p>The range filter is applied again rather than trusted to the caller, because SPEC 27.5
     * calls the Archer's 20 blocks "hard capped" and a cap that only one of two paths enforces
     * is not a cap. It is also the reason a unit's {@code range} is written onto its
     * {@code FOLLOW_RANGE} at spawn: a vanilla skeleton follows to 16, so a cap at 20 that was
     * never reached would have been decoration, and a test asserting it would have proved
     * nothing.
     *
     * @return empty when nothing is in reach, which leaves the unit's current target alone
     */
    public static Optional<Target> choose(List<Target> permitted,
                                          DefenseUnitType.TargetPriority priority,
                                          double range) {
        Objects.requireNonNull(permitted, "permitted");
        Objects.requireNonNull(priority, "priority");

        Comparator<Target> order = priority == DefenseUnitType.TargetPriority.LOWEST_HEALTH
                ? Comparator.comparingDouble(Target::health)
                        .thenComparingDouble(Target::distance)
                        .thenComparing(target -> target.uuid().toString())
                // The tie-breaks are not decoration: two candidates on identical health would
                // otherwise be chosen by whatever order the world handed back its entities in,
                // and a unit that switched target every tick would never land a hit.
                : Comparator.comparingDouble(Target::distance)
                        .thenComparing(target -> target.uuid().toString());

        return permitted.stream()
                .filter(target -> target.distance() <= range)
                .min(order);
    }
}
