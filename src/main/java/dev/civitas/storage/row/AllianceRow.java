package dev.civitas.storage.row;

/**
 * A row of {@code alliances}, SPEC 3.9.
 *
 * <p>Stored once per pair with {@code cityAId < cityBId}, so a relation cannot be recorded
 * twice in opposite orders. {@link #normalisedPair} builds that ordering.
 *
 * <p>{@code stateChangedAt}, {@code trusted} and {@code proposedBy} were added in V7: SPEC
 * 14.2 measures both the 24-hour break notice and the 7-day re-ally cooldown from when the
 * state last changed, which {@code formedAt} cannot answer once a row has been through
 * invite, accept and break.
 *
 * @param proposedBy which city asked, so only the other one can accept
 */
public record AllianceRow(
        int cityAId,
        int cityBId,
        String state,
        long formedAt,
        long stateChangedAt,
        boolean trusted,
        int proposedBy) {

    /** @return the two ids in ascending order, the canonical form used as the primary key */
    public static int[] normalisedPair(int firstCityId, int secondCityId) {
        return firstCityId <= secondCityId
                ? new int[] {firstCityId, secondCityId}
                : new int[] {secondCityId, firstCityId};
    }
}
