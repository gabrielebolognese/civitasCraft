package dev.civitas.core.market;

import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * What the market refuses to buy: SPEC 17.3 cases 29 and 30.
 *
 * <p>The market prices a material, not an item. Everything here is an item whose value is
 * not its material's value, which is why each one has to be turned away rather than
 * under-priced.
 */
class MarketItemFilterTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private MarketItemFilter filter;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();

        Logger quiet = Logger.getLogger("market-filter-test");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        filter = new MarketItemFilter(configs);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ==================================================================================
    // What is accepted
    // ==================================================================================

    @Test
    @DisplayName("a plain stack is accepted")
    void plainIsAccepted() {
        assertTrue(filter.accept(new ItemStack(Material.WHEAT, 64)).isSuccess());
        assertTrue(filter.accept(new ItemStack(Material.DIAMOND, 1)).isSuccess());
    }

    @Test
    @DisplayName("an empty hand is named as such, rather than reported as an odd item")
    void emptyHand() {
        assertEquals("EMPTY_HAND", reasonOf(filter.accept(null)));
        assertEquals("EMPTY_HAND", reasonOf(filter.accept(new ItemStack(Material.AIR))));
    }

    // ==================================================================================
    // SPEC 17.3 case 29
    // ==================================================================================

    @Test
    @DisplayName("SPEC 17.3 case 29: a damaged tool is refused")
    void damagedIsRefused() {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = pickaxe.getItemMeta();
        ((Damageable) meta).setDamage(100);
        pickaxe.setItemMeta(meta);

        assertEquals("DAMAGED", reasonOf(filter.accept(pickaxe)));
    }

    @Test
    @DisplayName("SPEC 17.3 case 29: an enchanted item is refused")
    void enchantedIsRefused() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);

        assertEquals("ENCHANTED", reasonOf(filter.accept(sword)));
    }

    @Test
    @DisplayName("SPEC 17.3 case 29: a renamed item is refused")
    void namedIsRefused() {
        ItemStack wheat = new ItemStack(Material.WHEAT, 64);
        ItemMeta meta = wheat.getItemMeta();
        meta.displayName(Component.text("Grandma's Wheat"));
        wheat.setItemMeta(meta);

        assertEquals("NAMED", reasonOf(filter.accept(wheat)));
    }

    @Test
    @DisplayName("an item carrying lore is refused too, for the same reason as a name")
    void loreIsRefused() {
        ItemStack wheat = new ItemStack(Material.WHEAT, 64);
        ItemMeta meta = wheat.getItemMeta();
        meta.lore(java.util.List.of(Component.text("From the first harvest")));
        wheat.setItemMeta(meta);

        assertEquals("NAMED", reasonOf(filter.accept(wheat)));
    }

    // ==================================================================================
    // SPEC 17.3 case 30
    // ==================================================================================

    @Test
    @DisplayName("SPEC 17.3 case 30: a shulker box with contents is refused")
    void fullShulkerIsRefused() {
        ItemStack box = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
        Container container = (Container) meta.getBlockState();
        container.getInventory().addItem(new ItemStack(Material.DIAMOND, 64));
        meta.setBlockState(container);
        box.setItemMeta(meta);

        assertEquals("HAS_CONTENTS", reasonOf(filter.accept(box)),
                "otherwise 64 diamonds sell for the price of one shulker box");
    }

    @Test
    @DisplayName("an empty shulker box is an ordinary item and may be sold")
    void emptyShulkerIsFine() {
        assertTrue(filter.accept(new ItemStack(Material.SHULKER_BOX)).isSuccess());
    }

    // ==================================================================================
    // Configurability
    // ==================================================================================

    @Test
    @DisplayName("each rule is a config toggle, and all of them default to on")
    void rulesAreConfigurable() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        assertEquals("ENCHANTED", reasonOf(filter.accept(sword)));

        configs.get(ConfigFile.ECONOMY).set("market.reject-enchanted", false);

        assertTrue(filter.accept(sword).isSuccess(),
                "an operator who wants an enchant market may have one");
    }
}
