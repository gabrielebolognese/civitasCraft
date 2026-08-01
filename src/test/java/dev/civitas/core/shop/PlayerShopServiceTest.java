package dev.civitas.core.shop;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.util.Result;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Chest shops, SPEC 4.5: the limit, the trade, and what happens when either side cannot
 * complete it.
 *
 * <p>A real server is needed here only for inventories; the shop rules themselves are plain
 * Java over them.
 */
class PlayerShopServiceTest {

    private static final String WORLD = "world";

    @TempDir
    Path directory;

    private ServerMock server;
    private CityTestSupport support;
    private PlayerShopService shops;
    private UUID owner;
    private UUID customer;
    private Inventory chest;
    private Inventory pocket;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        support = CityTestSupport.open(directory);
        shops = support.shops;

        owner = support.givenPlayer("Merchant", new BigDecimal("10000.00"), 0L);
        customer = support.givenPlayer("Buyer", new BigDecimal("500.00"), 0L);

        chest = server.createInventory(null, 27);
        pocket = server.createInventory(null, 36);
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    // ==================================================================================
    // Fixtures
    // ==================================================================================

    private PlayerShop givenShop(String offer, int limit) {
        ShopTerms terms = new ShopSign(support.configs).parse("16", offer).orElseThrow();
        Result<PlayerShop> created = await(shops.create(owner, WORLD, 10, 64, 10, 10, 63, 10,
                Material.WHEAT.name(), terms, limit, 1_000L));
        assertTrue(created.isSuccess(), reasonOf(created));
        return created.orElseThrow();
    }

    private PlayerShop givenShop(String offer) {
        return givenShop(offer, 5);
    }

    private BigDecimal balance(UUID player) {
        return support.playerRow(player).balance();
    }

    // ==================================================================================
    // Creating
    // ==================================================================================

    @Nested
    @DisplayName("Creating")
    class Creating {

        @Test
        @DisplayName("a shop is remembered by its sign and by its chest")
        void created() {
            PlayerShop shop = givenShop("B 100 : S 60");

            assertEquals(1, shops.total());
            assertTrue(shops.atSign(WORLD, 10, 64, 10).isPresent());
            assertEquals(List.of(shop), shops.atChest(WORLD, 10, 63, 10));
            assertEquals(1, shops.countOwnedBy(owner));
        }

        @Test
        @DisplayName("two shops cannot share a sign")
        void oneShopPerSign() {
            givenShop("B 100");

            ShopTerms terms = new ShopSign(support.configs).parse("16", "B 50").orElseThrow();
            assertEquals("SIGN_TAKEN", reasonOf(await(shops.create(customer, WORLD,
                    10, 64, 10, 10, 63, 10, Material.WHEAT.name(), terms, 5, 2_000L))));
        }

        @Test
        @DisplayName("SPEC 10: an owner may not exceed their shop limit")
        void limitIsEnforced() {
            ShopTerms terms = new ShopSign(support.configs).parse("16", "B 100").orElseThrow();
            for (int i = 0; i < 2; i++) {
                assertTrue(await(shops.create(owner, WORLD, i, 64, 0, i, 63, 0,
                        Material.WHEAT.name(), terms, 2, 1_000L)).isSuccess());
            }

            Result<PlayerShop> third = await(shops.create(owner, WORLD, 9, 64, 9, 9, 63, 9,
                    Material.WHEAT.name(), terms, 2, 1_000L));

            assertEquals("SHOP_LIMIT", reasonOf(third));
            assertEquals("2", ((Result.Failure<PlayerShop>) third).placeholders().get("limit"));
        }

        @Test
        @DisplayName("a negative limit means no limit, for a permission that grants '*'")
        void unlimited() {
            ShopTerms terms = new ShopSign(support.configs).parse("16", "B 100").orElseThrow();
            for (int i = 0; i < 12; i++) {
                assertTrue(await(shops.create(owner, WORLD, i, 64, 0, i, 63, 0,
                        Material.WHEAT.name(), terms, -1, 1_000L)).isSuccess());
            }
            assertEquals(12, shops.countOwnedBy(owner));
        }

        @Test
        @DisplayName("shops survive a restart")
        void reloads() {
            givenShop("B 100 : S 60");

            assertEquals(1, await(shops.loadAll()));
            assertTrue(shops.atSign(WORLD, 10, 64, 10).isPresent());
            assertEquals(0, new BigDecimal("100").compareTo(
                    shops.atSign(WORLD, 10, 64, 10).orElseThrow().terms().customerPays()));
        }

        @Test
        @DisplayName("removing a chest removes every shop attached to it")
        void removedWithChest() {
            givenShop("B 100");

            assertEquals(1, await(shops.removeAtChest(WORLD, 10, 63, 10)));
            assertEquals(0, shops.total());
            assertTrue(shops.atSign(WORLD, 10, 64, 10).isEmpty());
        }
    }

    // ==================================================================================
    // Buying from a shop
    // ==================================================================================

    @Nested
    @DisplayName("A customer buying")
    class Buying {

        @Test
        @DisplayName("items leave the chest, money reaches the owner, and it is ledgered")
        void buy() {
            PlayerShop shop = givenShop("B 100 : S 60");
            chest.addItem(new ItemStack(Material.WHEAT, 64));

            Result<PlayerShopService.ShopReceipt> result =
                    await(shops.buy(customer, shop, chest, pocket));

            assertTrue(result.isSuccess(), reasonOf(result));
            assertEquals(48, PlayerShopService.count(chest, Material.WHEAT));
            assertEquals(16, PlayerShopService.count(pocket, Material.WHEAT));
            assertEquals(0, new BigDecimal("400.00").compareTo(balance(customer)));
            assertEquals(0, new BigDecimal("10100.00").compareTo(balance(owner)));

            List<LedgerRow> rows = await(support.daos.ledger()
                    .findByType(TransactionType.PLAYER_SHOP.name(), 0L, 10));
            assertEquals(2, rows.size(), "one row each side");
            assertTrue(rows.stream().allMatch(row -> row.metadata().contains("\"shop\"")),
                    "SPEC 4.5: shop transactions are identifiable in the ledger");
        }

        @Test
        @DisplayName("SPEC 4.5: a player shop is untaxed, unlike the server market")
        void untaxed() {
            PlayerShop shop = givenShop("B 100");
            chest.addItem(new ItemStack(Material.WHEAT, 64));

            await(shops.buy(customer, shop, chest, pocket));

            assertEquals(0, new BigDecimal("100.00").compareTo(
                    balance(owner).subtract(new BigDecimal("10000.00"))),
                    "the owner receives every coin the customer paid");
            assertTrue(await(support.daos.ledger()
                    .findByType(TransactionType.MARKET_TAX.name(), 0L, 10)).isEmpty());
        }

        @Test
        @DisplayName("an empty chest sells nothing and takes nothing")
        void outOfStock() {
            PlayerShop shop = givenShop("B 100");

            assertEquals("OUT_OF_STOCK", reasonOf(await(shops.buy(customer, shop, chest, pocket))));
            assertEquals(0, new BigDecimal("500.00").compareTo(balance(customer)));
        }

        @Test
        @DisplayName("a customer who cannot pay keeps neither the items nor the money")
        void cannotAfford() {
            PlayerShop shop = givenShop("B 100000");
            chest.addItem(new ItemStack(Material.WHEAT, 64));

            assertEquals("INSUFFICIENT_FUNDS",
                    reasonOf(await(shops.buy(customer, shop, chest, pocket))));

            assertEquals(64, PlayerShopService.count(chest, Material.WHEAT),
                    "the stock went back into the chest");
            assertEquals(0, PlayerShopService.count(pocket, Material.WHEAT));
            assertEquals(0, new BigDecimal("500.00").compareTo(balance(customer)));
        }

        @Test
        @DisplayName("a full inventory is refused before anything moves")
        void noRoom() {
            PlayerShop shop = givenShop("B 100");
            chest.addItem(new ItemStack(Material.WHEAT, 64));
            for (int slot = 0; slot < pocket.getSize(); slot++) {
                pocket.setItem(slot, new ItemStack(Material.STONE, 64));
            }

            assertEquals("INVENTORY_FULL", reasonOf(await(shops.buy(customer, shop, chest, pocket))));
            assertEquals(64, PlayerShopService.count(chest, Material.WHEAT));
        }

        @Test
        @DisplayName("a sell-only shop refuses to sell")
        void notSelling() {
            PlayerShop shop = givenShop("S 60");
            chest.addItem(new ItemStack(Material.WHEAT, 64));

            assertEquals("SHOP_DOES_NOT_SELL",
                    reasonOf(await(shops.buy(customer, shop, chest, pocket))));
        }

        @Test
        @DisplayName("the owner cannot trade with their own shop")
        void ownShop() {
            PlayerShop shop = givenShop("B 100 : S 60");
            chest.addItem(new ItemStack(Material.WHEAT, 64));

            assertEquals("OWN_SHOP", reasonOf(await(shops.buy(owner, shop, chest, pocket))));
            assertEquals("OWN_SHOP", reasonOf(await(shops.sell(owner, shop, chest, pocket))));
        }
    }

    // ==================================================================================
    // Selling to a shop
    // ==================================================================================

    @Nested
    @DisplayName("A customer selling")
    class Selling {

        @Test
        @DisplayName("items enter the chest and the owner pays")
        void sell() {
            PlayerShop shop = givenShop("B 100 : S 60");
            pocket.addItem(new ItemStack(Material.WHEAT, 32));

            assertTrue(await(shops.sell(customer, shop, chest, pocket)).isSuccess());

            assertEquals(16, PlayerShopService.count(chest, Material.WHEAT));
            assertEquals(16, PlayerShopService.count(pocket, Material.WHEAT));
            assertEquals(0, new BigDecimal("560.00").compareTo(balance(customer)));
            assertEquals(0, new BigDecimal("9940.00").compareTo(balance(owner)));
        }

        @Test
        @DisplayName("an owner who cannot pay does not get the goods")
        void ownerCannotPay() {
            PlayerShop shop = givenShop("S 999999");
            pocket.addItem(new ItemStack(Material.WHEAT, 32));

            Result<PlayerShopService.ShopReceipt> result =
                    await(shops.sell(customer, shop, chest, pocket));

            assertEquals("OWNER_CANNOT_PAY", reasonOf(result));
            assertEquals(32, PlayerShopService.count(pocket, Material.WHEAT),
                    "the customer keeps their wheat");
            assertEquals(0, PlayerShopService.count(chest, Material.WHEAT));
            assertEquals(0, new BigDecimal("10000.00").compareTo(balance(owner)));
        }

        @Test
        @DisplayName("a customer without the goods is refused")
        void nothingToSell() {
            PlayerShop shop = givenShop("S 60");
            pocket.addItem(new ItemStack(Material.WHEAT, 8));

            assertEquals("CUSTOMER_SHORT",
                    reasonOf(await(shops.sell(customer, shop, chest, pocket))));
        }

        @Test
        @DisplayName("a full chest is refused before anything moves")
        void chestFull() {
            PlayerShop shop = givenShop("S 60");
            pocket.addItem(new ItemStack(Material.WHEAT, 32));
            for (int slot = 0; slot < chest.getSize(); slot++) {
                chest.setItem(slot, new ItemStack(Material.STONE, 64));
            }

            assertEquals("CHEST_FULL", reasonOf(await(shops.sell(customer, shop, chest, pocket))));
            assertEquals(32, PlayerShopService.count(pocket, Material.WHEAT));
        }

        @Test
        @DisplayName("a buy-only shop refuses to buy")
        void notBuying() {
            PlayerShop shop = givenShop("B 100");
            pocket.addItem(new ItemStack(Material.WHEAT, 32));

            assertEquals("SHOP_DOES_NOT_BUY",
                    reasonOf(await(shops.sell(customer, shop, chest, pocket))));
        }
    }

    // ==================================================================================
    // Inventory counting
    // ==================================================================================

    @Nested
    @DisplayName("Counting")
    class Counting {

        @Test
        @DisplayName("only plain items count toward a shop")
        void enchantedDoesNotCount() {
            ItemStack named = new ItemStack(Material.WHEAT, 16);
            var meta = named.getItemMeta();
            meta.displayName(net.kyori.adventure.text.Component.text("Prize Wheat"));
            named.setItemMeta(meta);

            pocket.addItem(named);
            pocket.addItem(new ItemStack(Material.WHEAT, 5));

            assertEquals(5, PlayerShopService.count(pocket, Material.WHEAT),
                    "the named stack is not ordinary wheat and must not be sold as it");
        }

        @Test
        @DisplayName("free space counts part-filled stacks as well as empty slots")
        void freeSpace() {
            Inventory small = server.createInventory(null, 9);
            small.setItem(0, new ItemStack(Material.WHEAT, 60));

            assertEquals(8 * 64 + 4, PlayerShopService.freeSpaceFor(small, Material.WHEAT));
        }

        @Test
        @DisplayName("removing takes exactly what was asked for, across stacks")
        void removeSpansStacks() {
            pocket.addItem(new ItemStack(Material.WHEAT, 64));
            pocket.addItem(new ItemStack(Material.WHEAT, 64));

            PlayerShopService.remove(pocket, Material.WHEAT, 100);

            assertEquals(28, PlayerShopService.count(pocket, Material.WHEAT));
            assertFalse(pocket.isEmpty());
        }
    }
}
