package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code city_bans}, added by migration V2.
 *
 * <p>SPEC 5.2 makes "not on the city's ban list" a join precondition and SPEC 8.6 gives the
 * list a management screen, but SPEC 3 defines no table for it.
 *
 * @param reason optional, shown to the banned player when a join is refused
 */
public record CityBanRow(
        int cityId,
        UUID bannedUuid,
        UUID bannedBy,
        String reason,
        long bannedAt) {
}
