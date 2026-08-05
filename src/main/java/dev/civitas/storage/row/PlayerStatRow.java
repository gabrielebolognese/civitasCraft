package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code player_stats}, the lifetime counters behind SPEC 13.3's Builder and Farmer
 * leaderboards.
 *
 * @param stat  the {@code PlayerStat} name; stored as text so a counter can be added without
 *              a migration, and read back through {@code PlayerStat.parse}, which discards a
 *              name it does not recognise rather than throwing
 * @param value monotonically increasing, never reset
 */
public record PlayerStatRow(
        UUID uuid,
        String stat,
        long value,
        long updatedAt) {
}
