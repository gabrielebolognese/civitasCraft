package dev.civitas.storage.row;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of {@code city_members}, SPEC 3.9.
 *
 * @param contributedTotal lifetime treasury deposits, the Contribution leaderboard metric
 */
public record CityMemberRow(
        UUID uuid,
        int cityId,
        int rankId,
        long joinedAt,
        BigDecimal contributedTotal) {
}
