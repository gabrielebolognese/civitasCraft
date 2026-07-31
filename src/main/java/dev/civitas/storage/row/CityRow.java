package dev.civitas.storage.row;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of {@code cities}, SPEC 3.2.
 *
 * @param id                 zero when inserting; the database assigns the real value
 * @param delinquentSince    null unless upkeep is unpaid
 * @param deletedAt          null unless soft-deleted, SPEC 5.3
 */
public record CityRow(
        int id,
        String name,
        String displayName,
        String tag,
        UUID mayorUuid,
        long foundedAt,
        BigDecimal treasury,
        String coreWorld,
        int coreChunkX,
        int coreChunkZ,
        double spawnX,
        double spawnY,
        double spawnZ,
        float spawnYaw,
        float spawnPitch,
        boolean openJoin,
        String motd,
        long upkeepDue,
        Long delinquentSince,
        long warProtectionUntil,
        boolean frozen,
        Long deletedAt) {
}
