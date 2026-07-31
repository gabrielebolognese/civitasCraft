package dev.civitas.storage.row;

/**
 * A row of {@code city_upgrades}, SPEC 3.9.
 *
 * @param upgradeKey a key from the {@code upgrades} block of {@code cities.yml}
 * @param level      0 to 5, SPEC 5.7
 */
public record CityUpgradeRow(
        int cityId,
        String upgradeKey,
        int level) {
}
