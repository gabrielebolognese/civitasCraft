package dev.civitas.storage.row;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of {@code player_shops}, SPEC 4.5.
 *
 * @param buyPrice  what a customer pays for one {@code quantity}, or null if the shop does
 *                  not sell
 * @param sellPrice what a customer receives for one {@code quantity}, or null if the shop
 *                  does not buy
 */
public record PlayerShopRow(
        long id,
        UUID ownerUuid,
        String world,
        int signX,
        int signY,
        int signZ,
        int chestX,
        int chestY,
        int chestZ,
        String material,
        int quantity,
        BigDecimal buyPrice,
        BigDecimal sellPrice,
        long createdAt) {
}
