package dev.civitas.storage.row;

/**
 * A row of {@code alliances}, SPEC 3.9.
 *
 * <p>Stored once per pair with {@code cityAId < cityBId}, so a relation cannot be recorded
 * twice in opposite orders. {@link #normalisedPair} builds that ordering.
 */
public record AllianceRow(
        int cityAId,
        int cityBId,
        String state,
        long formedAt) {

    /** @return the two ids in ascending order, the canonical form used as the primary key */
    public static int[] normalisedPair(int firstCityId, int secondCityId) {
        return firstCityId <= secondCityId
                ? new int[] {firstCityId, secondCityId}
                : new int[] {secondCityId, firstCityId};
    }
}
