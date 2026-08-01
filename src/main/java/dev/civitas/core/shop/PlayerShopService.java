package dev.civitas.core.shop;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.dao.PlayerShopDao;
import dev.civitas.storage.row.PlayerShopRow;
import dev.civitas.util.Result;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Chest shops between players, SPEC 4.5.
 *
 * <h2>Why these are untaxed</h2>
 * SPEC 4.5 makes player shops deliberately tax-free while the server market takes 5%. That
 * gap is the whole point: it makes trading with another city strictly better than selling
 * into the void, which is what turns an economy of isolated farms into one with trade routes.
 *
 * <h2>Order of operations</h2>
 * Items move first, on the server thread, then money. If the money fails the items go back.
 * The reverse order would credit a seller for goods a second click had already taken.
 */
public final class PlayerShopService {

    private final PlayerShopDao shops;
    private final EconomyService economy;

    /** Every shop, by id. Shops are clicked constantly and must never hit the database. */
    private final Map<Long, PlayerShop> byId = new ConcurrentHashMap<>();

    /** Sign position to shop id, the lookup a click performs. */
    private final Map<String, Long> bySign = new ConcurrentHashMap<>();

    public PlayerShopService(PlayerShopDao shops, EconomyService economy) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    // ==================================================================================
    // Cache
    // ==================================================================================

    /** @return how many shops exist */
    public CompletableFuture<Integer> loadAll() {
        return shops.findAll().thenApply(rows -> {
            byId.clear();
            bySign.clear();
            for (PlayerShopRow row : rows) {
                remember(PlayerShop.from(row));
            }
            return byId.size();
        });
    }

    private void remember(PlayerShop shop) {
        byId.put(shop.id(), shop);
        bySign.put(shop.signKey(), shop.id());
    }

    private void forget(PlayerShop shop) {
        byId.remove(shop.id());
        bySign.remove(shop.signKey());
    }

    public Optional<PlayerShop> atSign(String world, int x, int y, int z) {
        Long id = bySign.get(PlayerShop.key(world, x, y, z));
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** Every shop whose chest is this block. A double chest can carry several signs. */
    public List<PlayerShop> atChest(String world, int x, int y, int z) {
        String key = PlayerShop.key(world, x, y, z);
        List<PlayerShop> found = new ArrayList<>();
        for (PlayerShop shop : byId.values()) {
            if (shop.chestKey().equals(key)) {
                found.add(shop);
            }
        }
        return found;
    }

    public List<PlayerShop> ownedBy(UUID owner) {
        List<PlayerShop> found = new ArrayList<>();
        for (PlayerShop shop : byId.values()) {
            if (shop.isOwner(owner)) {
                found.add(shop);
            }
        }
        found.sort((left, right) -> Long.compare(left.id(), right.id()));
        return found;
    }

    public int countOwnedBy(UUID owner) {
        return ownedBy(owner).size();
    }

    public int total() {
        return byId.size();
    }

    // ==================================================================================
    // Creating and removing
    // ==================================================================================

    /**
     * Registers a new shop.
     *
     * @param limit how many shops this owner may have, from {@code civitas.limit.shops.<n>}
     */
    public CompletableFuture<Result<PlayerShop>> create(UUID owner, String world,
                                                        int signX, int signY, int signZ,
                                                        int chestX, int chestY, int chestZ,
                                                        String material, ShopTerms terms,
                                                        int limit, long now) {
        if (atSign(world, signX, signY, signZ).isPresent()) {
            return CompletableFuture.completedFuture(
                    Result.failure("SIGN_TAKEN", "shop.sign-taken"));
        }
        if (limit >= 0 && countOwnedBy(owner) >= limit) {
            return CompletableFuture.completedFuture(
                    Result.failure("SHOP_LIMIT", "shop.limit-reached",
                            Map.of("limit", String.valueOf(limit))));
        }

        PlayerShopRow row = new PlayerShopRow(0, owner, world, signX, signY, signZ,
                chestX, chestY, chestZ, material, terms.quantity(),
                terms.customerPays(), terms.customerGets(), now);

        return shops.insert(row).thenApply(id -> {
            PlayerShop shop = PlayerShop.from(new PlayerShopRow(id, row.ownerUuid(), row.world(),
                    row.signX(), row.signY(), row.signZ(), row.chestX(), row.chestY(),
                    row.chestZ(), row.material(), row.quantity(), row.buyPrice(),
                    row.sellPrice(), row.createdAt()));
            remember(shop);
            return Result.success(shop);
        });
    }

    /** Removes a shop, when its sign or its chest is destroyed. */
    public CompletableFuture<Integer> remove(PlayerShop shop) {
        forget(shop);
        return shops.delete(shop.id());
    }

    /** Removes every shop attached to a chest, when the chest itself goes. */
    public CompletableFuture<Integer> removeAtChest(String world, int x, int y, int z) {
        List<PlayerShop> found = atChest(world, x, y, z);
        found.forEach(this::forget);
        return shops.deleteByChest(world, x, y, z);
    }

    // ==================================================================================
    // Trading
    // ==================================================================================

    /**
     * A customer buys from the shop: items leave the chest, money leaves the customer.
     *
     * <p>Both inventory arguments are read and written on the calling thread, which must be
     * the server thread. The money half is async, and reverts the items if it fails.
     */
    public CompletableFuture<Result<ShopReceipt>> buy(UUID customer, PlayerShop shop,
                                                      Inventory chest, Inventory customerInventory) {
        if (!shop.terms().sellsToCustomers()) {
            return failed("SHOP_DOES_NOT_SELL", "shop.not-selling");
        }
        if (shop.isOwner(customer)) {
            return failed("OWN_SHOP", "shop.own-shop");
        }

        Material material = Material.matchMaterial(shop.material());
        if (material == null) {
            return failed("UNKNOWN_MATERIAL", "shop.unknown-material");
        }
        int quantity = shop.terms().quantity();

        if (count(chest, material) < quantity) {
            return failed("OUT_OF_STOCK", "shop.out-of-stock");
        }
        if (freeSpaceFor(customerInventory, material) < quantity) {
            return failed("INVENTORY_FULL", "shop.inventory-full");
        }

        BigDecimal price = shop.terms().customerPays();
        remove(chest, material, quantity);
        customerInventory.addItem(new ItemStack(material, quantity));

        return economy.transfer(customer, shop.owner(), price, TransactionType.PLAYER_SHOP,
                        context(shop, quantity))
                .thenApply(result -> {
                    if (result instanceof Result.Failure<BigDecimal> failure) {
                        // Money did not move, so neither may the items.
                        remove(customerInventory, material, quantity);
                        chest.addItem(new ItemStack(material, quantity));
                        return Result.<ShopReceipt>propagate(failure);
                    }
                    return Result.success(new ShopReceipt(shop, material, quantity, price,
                            result.orElseThrow()));
                });
    }

    /** A customer sells to the shop: items enter the chest, money leaves the owner. */
    public CompletableFuture<Result<ShopReceipt>> sell(UUID customer, PlayerShop shop,
                                                       Inventory chest, Inventory customerInventory) {
        if (!shop.terms().buysFromCustomers()) {
            return failed("SHOP_DOES_NOT_BUY", "shop.not-buying");
        }
        if (shop.isOwner(customer)) {
            return failed("OWN_SHOP", "shop.own-shop");
        }

        Material material = Material.matchMaterial(shop.material());
        if (material == null) {
            return failed("UNKNOWN_MATERIAL", "shop.unknown-material");
        }
        int quantity = shop.terms().quantity();

        if (count(customerInventory, material) < quantity) {
            return failed("CUSTOMER_SHORT", "shop.you-have-none");
        }
        if (freeSpaceFor(chest, material) < quantity) {
            return failed("CHEST_FULL", "shop.chest-full");
        }

        BigDecimal price = shop.terms().customerGets();
        remove(customerInventory, material, quantity);
        chest.addItem(new ItemStack(material, quantity));

        return economy.transfer(shop.owner(), customer, price, TransactionType.PLAYER_SHOP,
                        context(shop, quantity))
                .thenApply(result -> {
                    if (result instanceof Result.Failure<BigDecimal> failure) {
                        remove(chest, material, quantity);
                        customerInventory.addItem(new ItemStack(material, quantity));
                        // The owner could not pay; say so plainly rather than blaming the
                        // customer's own balance.
                        return Result.<ShopReceipt>failure("OWNER_CANNOT_PAY",
                                "shop.owner-cannot-pay",
                                Map.of("reason", failure.reason()));
                    }
                    return Result.success(new ShopReceipt(shop, material, quantity, price,
                            result.orElseThrow()));
                });
    }

    // ==================================================================================
    // Inventory helpers
    // ==================================================================================

    /** How many of a material an inventory holds, counting only plain stacks. */
    public static int count(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (matches(stack, material)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** How many more of a material would fit, across empty slots and part-filled stacks. */
    public static int freeSpaceFor(Inventory inventory, Material material) {
        int space = 0;
        int max = material.getMaxStackSize();
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                space += max;
            } else if (matches(stack, material)) {
                space += Math.max(0, max - stack.getAmount());
            }
        }
        return space;
    }

    /** Takes exactly {@code amount}, assuming the caller has already counted. */
    public static void remove(Inventory inventory, Material material, int amount) {
        int left = amount;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!matches(stack, material)) {
                continue;
            }
            int taken = Math.min(left, stack.getAmount());
            left -= taken;
            if (taken >= stack.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - taken);
                inventory.setItem(slot, stack);
            }
        }
    }

    /**
     * Only plain items count toward a shop.
     *
     * <p>The same reasoning as the server market (SPEC 17.3 cases 29 and 30): a sign names a
     * material and a price, so an enchanted or renamed item of that material must not be
     * sold at the plain price by accident.
     */
    private static boolean matches(ItemStack stack, Material material) {
        return stack != null && stack.getType() == material && isPlain(stack);
    }

    /**
     * Whether a stack is the ordinary form of its material.
     *
     * <p>{@code isSimilar} against a freshly made stack rather than {@code hasItemMeta}:
     * some items carry non-null but empty meta, and the question actually being asked is
     * "would a player call this plain wheat".
     */
    public static boolean isPlain(ItemStack stack) {
        return stack != null
                && !stack.getType().isAir()
                && stack.isSimilar(new ItemStack(stack.getType()));
    }

    private static String context(PlayerShop shop, int quantity) {
        return "\"shop\":" + shop.id() + ",\"item\":\"" + shop.material()
                + "\",\"amount\":" + quantity;
    }

    private static CompletableFuture<Result<ShopReceipt>> failed(String reason, String key) {
        return CompletableFuture.completedFuture(Result.failure(reason, key));
    }

    /**
     * What a completed shop transaction moved.
     *
     * @param balance the customer's balance afterwards when buying, the payer's when selling
     */
    public record ShopReceipt(PlayerShop shop, Material material, int amount,
                              BigDecimal price, BigDecimal balance) {
    }
}
