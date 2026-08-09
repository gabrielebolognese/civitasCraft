package dev.civitas.storage.row;

/**
 * A row of {@code city_wardens}, SPEC 28.
 *
 * <p>Not in SPEC 3, because SPEC 28 is Part III and postdates it. Added in V23 by the milestone
 * that needs it, which is the pattern M1 established for every table SPEC names outside Section 3.
 *
 * @param unitId          the {@code defense_units} row the Warden stands as
 * @param recoveringUntil SPEC 28.6's peacetime recovery deadline, or null while it is present
 */
public record CityWardenRow(int cityId, int unitId, long purchasedAt, Long recoveringUntil) {
}
