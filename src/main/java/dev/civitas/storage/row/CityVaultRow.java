package dev.civitas.storage.row;

/**
 * One page of a city vault, added in V6 for SPEC 5.7 and 9.2.
 *
 * @param page      zero-based; page 0 is the first the Vault upgrade unlocks
 * @param contents  Bukkit's own serialisation of the page's 27 slots, or null when empty
 * @param updatedAt when it was last saved, for diagnosing a dispute
 */
public record CityVaultRow(
        int cityId,
        int page,
        byte[] contents,
        long updatedAt) {

    /** An empty page, for a vault slot that has never been written. */
    public static CityVaultRow empty(int cityId, int page, long now) {
        return new CityVaultRow(cityId, page, null, now);
    }

    public boolean isEmpty() {
        return contents == null || contents.length == 0;
    }
}
