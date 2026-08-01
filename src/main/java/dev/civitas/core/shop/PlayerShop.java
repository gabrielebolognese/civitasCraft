package dev.civitas.core.shop;

import java.util.Objects;
import java.util.UUID;

import dev.civitas.storage.row.PlayerShopRow;

/**
 * One chest shop, SPEC 4.5.
 *
 * <p>Position is stored rather than held as a Bukkit {@code Block}, so a shop survives its
 * chunk unloading and the server restarting without keeping a world reference alive.
 */
public record PlayerShop(
        long id,
        UUID owner,
        String world,
        int signX, int signY, int signZ,
        int chestX, int chestY, int chestZ,
        String material,
        ShopTerms terms,
        long createdAt) {

    public PlayerShop {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(terms, "terms");
    }

    public static PlayerShop from(PlayerShopRow row) {
        return new PlayerShop(row.id(), row.ownerUuid(), row.world(),
                row.signX(), row.signY(), row.signZ(),
                row.chestX(), row.chestY(), row.chestZ(),
                row.material(),
                new ShopTerms(row.quantity(), row.buyPrice(), row.sellPrice()),
                row.createdAt());
    }

    public PlayerShopRow toRow() {
        return new PlayerShopRow(id, owner, world, signX, signY, signZ,
                chestX, chestY, chestZ, material, terms.quantity(),
                terms.customerPays(), terms.customerGets(), createdAt);
    }

    public boolean isOwner(UUID player) {
        return owner.equals(player);
    }

    /** A key for the sign position, for the in-memory index. */
    public String signKey() {
        return key(world, signX, signY, signZ);
    }

    public String chestKey() {
        return key(world, chestX, chestY, chestZ);
    }

    public static String key(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }
}
