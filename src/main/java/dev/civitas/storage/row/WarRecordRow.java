package dev.civitas.storage.row;

/**
 * A city's war record, for SPEC 13.3's War Record leaderboard.
 *
 * <p>Derived rather than stored: the {@code wars} table already knows who won each resolved
 * war, so a counter would be a second copy of the same fact that could drift from it.
 *
 * @param wins   wars this city won
 * @param losses wars it lost. SPEC 13.3 ranks by wins "with losses as a tiebreaker", so fewer
 *               losses beats more at the same number of wins
 */
public record WarRecordRow(
        int cityId,
        String name,
        int wins,
        int losses) {

    /** Wars that ended without a winner. Not ranked, but shown alongside. */
    public int total() {
        return wins + losses;
    }
}
