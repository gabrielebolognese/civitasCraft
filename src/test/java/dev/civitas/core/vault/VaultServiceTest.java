package dev.civitas.core.vault;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.upgrade.UpgradeType;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * The shared city vault, SPEC 5.7 and 9.2.
 *
 * <p>A vault holds what a city cannot afford to lose (SPEC 11.7), so the tests that matter
 * are the ones about not losing it: contents survive a save and a reload, an unreadable page
 * does not take the others with it, and a page saved at one size still opens at another.
 */
class VaultServiceTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private UpgradeService upgrades;
    private VaultService vaults;
    private UUID mayor;
    private City city;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        support = CityTestSupport.open(directory);
        upgrades = new UpgradeService(support.db, support.daos.cityUpgrades(), support.treasury,
                support.configs, Scheduler.direct());
        vaults = new VaultService(support.daos.cityVault(), upgrades, support.configs);

        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal("10000000.00")));
        city.setTreasury(new BigDecimal("10000000.00"));
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    private void buyVaultLevels(int levels) {
        for (int i = 0; i < levels; i++) {
            assertTrue(await(upgrades.purchase(mayor, city, UpgradeType.VAULT)).isSuccess());
        }
    }

    // ==================================================================================
    // Pages
    // ==================================================================================

    @Nested
    @DisplayName("How much vault a city has")
    class Pages {

        @Test
        @DisplayName("SPEC 5.7: no vault at all until the upgrade is bought")
        void noneUntilBought() {
            assertEquals(0, vaults.pagesOf(city));
            assertEquals("NO_VAULT", reasonOf(vaults.checkAccess(mayor, city, 0)));
        }

        @Test
        @DisplayName("SPEC 5.7: one 27-slot page per level")
        void onePagePerLevel() {
            buyVaultLevels(3);

            assertEquals(3, vaults.pagesOf(city));
            assertEquals(27, vaults.pageSize());
        }

        @Test
        @DisplayName("a page past what the city has unlocked is refused")
        void pageOutOfRange() {
            buyVaultLevels(1);

            assertTrue(vaults.checkAccess(mayor, city, 0).isSuccess());
            Result<Integer> beyond = vaults.checkAccess(mayor, city, 1);
            assertEquals("NO_SUCH_PAGE", reasonOf(beyond));
            assertEquals("1", ((Result.Failure<Integer>) beyond).placeholders().get("pages"));
        }

        @Test
        @DisplayName("SPEC 9.2: CONTAINER is what gates the vault")
        void permission() {
            buyVaultLevels(1);
            UUID member = support.givenMember(city, "Titus");
            CityRank recruit = city.rankByName("Recruit").orElseThrow();
            await(support.ranks.assign(mayor, city, member, recruit));
            await(support.ranks.setPermission(mayor, city, recruit,
                    CityPermission.CONTAINER, false));

            assertEquals("NO_CITY_PERMISSION", reasonOf(vaults.checkAccess(member, city, 0)));

            await(support.ranks.setPermission(mayor, city, recruit,
                    CityPermission.CONTAINER, true));
            assertTrue(vaults.checkAccess(member, city, 0).isSuccess());
        }

        @Test
        @DisplayName("somebody who is not a member cannot open it at all")
        void nonMember() {
            buyVaultLevels(1);

            assertEquals("NOT_A_MEMBER",
                    reasonOf(vaults.checkAccess(UUID.randomUUID(), city, 0)));
        }
    }

    // ==================================================================================
    // Contents
    // ==================================================================================

    @Nested
    @DisplayName("Contents")
    class Contents {

        @Test
        @DisplayName("an unwritten page opens empty, at the configured size")
        void emptyPage() {
            ItemStack[] contents = await(vaults.load(city.id(), 0));

            assertEquals(27, contents.length);
            for (ItemStack stack : contents) {
                assertNull(stack);
            }
        }

        @Test
        @DisplayName("what is put in comes back out, in the same slots")
        void roundTrip() {
            ItemStack[] contents = new ItemStack[27];
            contents[0] = new ItemStack(Material.DIAMOND, 64);
            contents[13] = new ItemStack(Material.NETHERITE_INGOT, 3);
            contents[26] = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1);

            await(vaults.save(city.id(), 0, contents));
            ItemStack[] read = await(vaults.load(city.id(), 0));

            assertEquals(Material.DIAMOND, read[0].getType());
            assertEquals(64, read[0].getAmount());
            assertEquals(Material.NETHERITE_INGOT, read[13].getType());
            assertEquals(3, read[13].getAmount());
            assertEquals(Material.ENCHANTED_GOLDEN_APPLE, read[26].getType());
            assertNull(read[1], "and an empty slot stays empty rather than shifting up");
        }

        @Test
        @DisplayName("an item's name and enchantments survive")
        void itemDetailSurvives() {
            ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
            var meta = sword.getItemMeta();
            meta.displayName(Component.text("City Blade"));
            sword.setItemMeta(meta);
            sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 5);

            ItemStack[] contents = new ItemStack[27];
            contents[4] = sword;
            await(vaults.save(city.id(), 0, contents));

            ItemStack read = await(vaults.load(city.id(), 0))[4];

            assertNotNull(read);
            assertEquals(Material.DIAMOND_SWORD, read.getType());
            assertEquals(5, read.getEnchantmentLevel(
                    org.bukkit.enchantments.Enchantment.SHARPNESS));
            assertTrue(read.getItemMeta().hasDisplayName());
        }

        @Test
        @DisplayName("pages are separate stores")
        void pagesAreSeparate() {
            ItemStack[] first = new ItemStack[27];
            first[0] = new ItemStack(Material.DIAMOND, 1);
            ItemStack[] second = new ItemStack[27];
            second[0] = new ItemStack(Material.EMERALD, 1);

            await(vaults.save(city.id(), 0, first));
            await(vaults.save(city.id(), 1, second));

            assertEquals(Material.DIAMOND, await(vaults.load(city.id(), 0))[0].getType());
            assertEquals(Material.EMERALD, await(vaults.load(city.id(), 1))[0].getType());
        }

        @Test
        @DisplayName("saving twice overwrites rather than appending")
        void saveOverwrites() {
            ItemStack[] first = new ItemStack[27];
            first[0] = new ItemStack(Material.DIAMOND, 64);
            await(vaults.save(city.id(), 0, first));

            await(vaults.save(city.id(), 0, new ItemStack[27]));

            assertNull(await(vaults.load(city.id(), 0))[0], "emptying a page empties it");
            assertEquals(1, await(vaults.pagesStored(city.id())).size(),
                    "and there is still one row, not two");
        }
    }

    // ==================================================================================
    // Not losing things
    // ==================================================================================

    @Nested
    @DisplayName("Failure modes")
    class Failures {

        @Test
        @DisplayName("an unreadable page opens empty rather than throwing")
        void corruptPage() {
            // A page the server cannot parse is already lost; refusing to open the vault
            // would take the other pages with it.
            ItemStack[] read = vaults.deserialise(new byte[] {1, 2, 3, 4, 5});

            assertEquals(27, read.length);
            for (ItemStack stack : read) {
                assertNull(stack);
            }
        }

        @Test
        @DisplayName("a page written when the size was smaller still opens")
        void shorterPage() {
            byte[] blob = vaults.serialise(new ItemStack[] {
                    new ItemStack(Material.DIAMOND, 1), null, null});

            ItemStack[] read = vaults.deserialise(blob);

            assertEquals(27, read.length, "padded up to the current size");
            assertEquals(Material.DIAMOND, read[0].getType(), "and nothing was thrown away");
        }

        @Test
        @DisplayName("an empty page serialises and comes back empty")
        void emptyRoundTrip() {
            ItemStack[] read = vaults.deserialise(vaults.serialise(new ItemStack[27]));

            assertEquals(27, read.length);
            assertNull(read[0]);
        }
    }
}
