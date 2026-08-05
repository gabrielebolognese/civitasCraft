package dev.civitas.core.diplomacy;

import java.util.Objects;

import dev.civitas.storage.row.AllianceRow;

/**
 * One pair of cities and what stands between them, SPEC 14.2.
 *
 * <p>Stored once per pair with the lower city id first, so "is A allied to B" and "is B
 * allied to A" cannot answer differently.
 */
public record Alliance(
        int cityAId,
        int cityBId,
        AllianceState state,
        long formedAt,
        long stateChangedAt,
        boolean trusted,
        int proposedBy) {

    public Alliance {
        Objects.requireNonNull(state, "state");

        // Normalised on the way in, so an alliance built as (B, A) and one loaded from the
        // database as (A, B) are the same record. Without this, otherThan() would answer
        // differently depending on which side happened to create it.
        int[] pair = AllianceRow.normalisedPair(cityAId, cityBId);
        cityAId = pair[0];
        cityBId = pair[1];
    }

    public static Alliance from(AllianceRow row) {
        return new Alliance(row.cityAId(), row.cityBId(),
                AllianceState.parse(row.state()).orElse(AllianceState.BROKEN),
                row.formedAt(), row.stateChangedAt(), row.trusted(), row.proposedBy());
    }

    public AllianceRow toRow() {
        return new AllianceRow(cityAId, cityBId, state.name(), formedAt, stateChangedAt,
                trusted, proposedBy);
    }

    public boolean involves(int cityId) {
        return cityAId == cityId || cityBId == cityId;
    }

    /** The city on the other side of this pair from the one given. */
    public int otherThan(int cityId) {
        return cityAId == cityId ? cityBId : cityAId;
    }

    /** Whether this pair counts as allied right now, notice period included. */
    public boolean isAllied() {
        return state.isAllied();
    }

    public Alliance withState(AllianceState newState, long changedAt) {
        return new Alliance(cityAId, cityBId, newState, formedAt, changedAt, trusted,
                proposedBy);
    }

    public Alliance withTrusted(boolean value) {
        return new Alliance(cityAId, cityBId, state, formedAt, stateChangedAt, value,
                proposedBy);
    }

    /** A key for the pair, in canonical order. */
    public static String key(int firstCityId, int secondCityId) {
        int[] pair = AllianceRow.normalisedPair(firstCityId, secondCityId);
        return pair[0] + ":" + pair[1];
    }

    public String key() {
        return key(cityAId, cityBId);
    }
}
