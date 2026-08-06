package dev.civitas.core.war;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import dev.civitas.storage.row.WarRow;

/**
 * One war, in memory.
 *
 * <p>Holds the state, the scores, the zone and who is on which side. The zone is attached at
 * war start and never replaced, per SPEC 11.4; everything else here changes as the war runs.
 */
public final class War {

    private final int id;
    private final int attackerCityId;
    private final int defenderCityId;
    private final long declaredAt;
    private final long prepEndsAt;
    private final long warEndsAt;
    private final BigDecimal wager;

    private volatile WarState state;
    private volatile int attackerScore;
    private volatile int defenderScore;
    private volatile Integer winnerCityId;
    private volatile WarZone zone = WarZone.empty();

    /**
     * Blocks broken per side, for the SPEC 11.6 cap.
     *
     * <p>SPEC 11.6 caps block-break points at 500 per war so "a war is decided by fighting,
     * not by whoever mines the most dirt". Counted separately from the score because the cap
     * is on the contribution, not on the total.
     */
    private volatile long attackerBlockMilli;
    private volatile long defenderBlockMilli;

    /** SPEC 11.6's City Hall reach bonus, once per war per city. */
    private final Set<Integer> cityHallReached = new LinkedHashSet<>();

    /** Allied cities that joined, by side. SPEC 11.10. */
    private final Set<Integer> attackerAllies = new LinkedHashSet<>();
    private final Set<Integer> defenderAllies = new LinkedHashSet<>();

    public War(int id, int attackerCityId, int defenderCityId, long declaredAt,
               long prepEndsAt, long warEndsAt, WarState state, BigDecimal wager) {
        this.id = id;
        this.attackerCityId = attackerCityId;
        this.defenderCityId = defenderCityId;
        this.declaredAt = declaredAt;
        this.prepEndsAt = prepEndsAt;
        this.warEndsAt = warEndsAt;
        this.state = Objects.requireNonNull(state, "state");
        this.wager = Objects.requireNonNull(wager, "wager");
    }

    public static War fromRow(WarRow row) {
        War war = new War(row.id(), row.attackerCityId(), row.defenderCityId(), row.declaredAt(),
                row.prepEndsAt(), row.warEndsAt(),
                WarState.parse(row.state()).orElse(WarState.DECLARED), row.wager());
        war.attackerScore = row.attackerScore();
        war.defenderScore = row.defenderScore();
        war.winnerCityId = row.winnerCityId();
        return war;
    }

    public WarRow toRow(Long rollbackCompletedAt, Long checkpoint) {
        return new WarRow(id, attackerCityId, defenderCityId, declaredAt, prepEndsAt, warEndsAt,
                state.key(), attackerScore, defenderScore, winnerCityId, wager,
                rollbackCompletedAt, checkpoint);
    }

    public int id() {
        return id;
    }

    public int attackerCityId() {
        return attackerCityId;
    }

    public int defenderCityId() {
        return defenderCityId;
    }

    public long declaredAt() {
        return declaredAt;
    }

    public long prepEndsAt() {
        return prepEndsAt;
    }

    public long warEndsAt() {
        return warEndsAt;
    }

    public BigDecimal wager() {
        return wager;
    }

    public WarState state() {
        return state;
    }

    public void state(WarState next) {
        this.state = Objects.requireNonNull(next, "next");
    }

    public WarZone zone() {
        return zone;
    }

    /** Attaches the zone. Called once, at war start; SPEC 11.4 makes it immutable after. */
    public void zone(WarZone computed) {
        this.zone = Objects.requireNonNull(computed, "computed");
    }

    // ==================================================================================
    // Sides
    // ==================================================================================

    /** Whether a city is party to this war at all, on either side. */
    public boolean involves(int cityId) {
        return cityId == attackerCityId || cityId == defenderCityId
                || attackerAllies.contains(cityId) || defenderAllies.contains(cityId);
    }

    /** Whether two cities are on opposite sides, which is what makes grief permitted. */
    public boolean areEnemies(int cityId, int otherCityId) {
        return involves(cityId) && involves(otherCityId)
                && isAttackerSide(cityId) != isAttackerSide(otherCityId);
    }

    public boolean isAttackerSide(int cityId) {
        return cityId == attackerCityId || attackerAllies.contains(cityId);
    }

    public boolean isDefenderSide(int cityId) {
        return cityId == defenderCityId || defenderAllies.contains(cityId);
    }

    public Set<Integer> attackerAllies() {
        return Set.copyOf(attackerAllies);
    }

    public Set<Integer> defenderAllies() {
        return Set.copyOf(defenderAllies);
    }

    /** SPEC 11.10: an ally joins during PREP only. */
    public void addAlly(int cityId, boolean attackerSide) {
        if (attackerSide) {
            attackerAllies.add(cityId);
        } else {
            defenderAllies.add(cityId);
        }
    }

    /** Every city on one side, the primary first. */
    public Set<Integer> side(boolean attackerSide) {
        Set<Integer> cities = new LinkedHashSet<>();
        cities.add(attackerSide ? attackerCityId : defenderCityId);
        cities.addAll(attackerSide ? attackerAllies : defenderAllies);
        return cities;
    }

    // ==================================================================================
    // Score, SPEC 11.6
    // ==================================================================================

    public int attackerScore() {
        return attackerScore;
    }

    public int defenderScore() {
        return defenderScore;
    }

    public void addScore(boolean attackerSide, int points) {
        if (attackerSide) {
            attackerScore += points;
        } else {
            defenderScore += points;
        }
    }

    /**
     * Adds block-break points up to the SPEC 11.6 cap.
     *
     * @return the points actually awarded, which is less than asked for once the cap is met
     */
    public int addBlockPoints(boolean attackerSide, double points, double cap) {
        // Accumulated in thousandths of a point rather than as a double. SPEC 11.6 awards 0.1
        // a block, and 0.1 added ten times in binary floating point is 0.9999999999999999, so
        // a running double would quietly award nine points for every ten a side earned. Over
        // the 5,000 blocks it takes to reach the cap the drift is not a rounding curiosity, it
        // is points somebody fought for.
        long step = Math.round(points * 1000.0);
        long capMilli = Math.round(cap * 1000.0);
        long already = attackerSide ? attackerBlockMilli : defenderBlockMilli;

        long allowed = Math.max(0L, Math.min(step, capMilli - already));
        if (allowed <= 0L) {
            return 0;
        }

        long updated = already + allowed;
        if (attackerSide) {
            attackerBlockMilli = updated;
        } else {
            defenderBlockMilli = updated;
        }

        int whole = (int) (updated / 1000L) - (int) (already / 1000L);
        if (whole > 0) {
            addScore(attackerSide, whole);
        }
        return whole;
    }

    /** Points earned from breaking blocks so far, including the fraction not yet scored. */
    public double blockPoints(boolean attackerSide) {
        return (attackerSide ? attackerBlockMilli : defenderBlockMilli) / 1000.0;
    }

    /** SPEC 11.6's City Hall bonus, awarded once per war per city. */
    public boolean claimCityHallReach(int cityId) {
        return cityHallReached.add(cityId);
    }

    public Integer winnerCityId() {
        return winnerCityId;
    }

    public void winnerCityId(Integer cityId) {
        this.winnerCityId = cityId;
    }

    // ==================================================================================
    // Phase
    // ==================================================================================

    /**
     * The phase the clock says this war should be in.
     *
     * <p>Separate from {@link #state}, which is what has been recorded, for the same reason
     * the contest cycle keeps them apart: they differ whenever a boundary passed while the
     * server was down, and reconciling them is the phase task's job.
     */
    public WarState phaseAt(long now) {
        if (state == WarState.DECLARED) {
            return now >= prepEndsAt ? WarState.ACTIVE : WarState.DECLARED;
        }
        if (state.isFinished() || state == WarState.ROLLING_BACK) {
            return state;
        }
        if (now < prepEndsAt) {
            return WarState.PREP;
        }
        if (now < warEndsAt) {
            return WarState.ACTIVE;
        }
        return WarState.ROLLING_BACK;
    }

    public long millisUntilNextPhase(long now) {
        return switch (state) {
            case DECLARED, PREP -> Math.max(0L, prepEndsAt - now);
            case ACTIVE -> Math.max(0L, warEndsAt - now);
            default -> 0L;
        };
    }
}
