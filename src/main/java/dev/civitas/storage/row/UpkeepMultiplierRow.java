package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code city_upkeep_multipliers}, SPEC 9.4.2.
 *
 * <p>An absent row means the ordinary rate, which is what almost every city has.
 */
public record UpkeepMultiplierRow(
        int cityId,
        double multiplier,
        UUID setBy,
        long setAt,
        Long expiresAt,
        String reason) {

    /** Whether this override still applies. */
    public boolean isActive(long now) {
        return expiresAt == null || expiresAt > now;
    }
}
