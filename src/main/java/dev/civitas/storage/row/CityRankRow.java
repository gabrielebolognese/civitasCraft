package dev.civitas.storage.row;

/**
 * A row of {@code city_ranks}, SPEC 3.3.
 *
 * @param permissions the SPEC 5.4 permission bitmask
 * @param isDefault   whether new joiners receive this rank
 */
public record CityRankRow(
        int id,
        int cityId,
        String name,
        int weight,
        long permissions,
        boolean isDefault) {
}
