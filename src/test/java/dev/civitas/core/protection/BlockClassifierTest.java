package dev.civitas.core.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Which blocks SPEC 5.5 protects.
 *
 * <p>Needs a server, because the classifier reads Bukkit's block tags, and those are backed
 * by the registry rather than by constants. MockBukkit supplies one.
 */
class BlockClassifierTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private BlockClassifier classifier;

    @BeforeAll
    static void startServer() {
        MockBukkit.mock();
    }

    @AfterAll
    static void stopServer() {
        MockBukkit.unmock();
    }

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("classifier-test");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        classifier = new BlockClassifier(configs, quiet);
    }

    private void rebuild() {
        Logger quiet = Logger.getLogger("classifier-test");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);
        classifier = new BlockClassifier(configs, quiet);
    }

    // ==================================================================================
    // The SPEC 5.5 container list
    // ==================================================================================

    @ParameterizedTest
    @ValueSource(strings = {"CHEST", "TRAPPED_CHEST", "BARREL", "FURNACE", "BLAST_FURNACE",
            "SMOKER", "HOPPER", "DISPENSER", "DROPPER", "BREWING_STAND", "BEACON"})
    @DisplayName("every container SPEC 5.5 names is protected")
    void specContainers(String name) {
        assertTrue(classifier.isContainer(Material.valueOf(name)), name + " should be a container");
    }

    @Test
    @DisplayName("shulker boxes of every colour are containers, from the tag rather than a list")
    void shulkerBoxes() {
        assertTrue(classifier.isContainer(Material.SHULKER_BOX));
        assertTrue(classifier.isContainer(Material.RED_SHULKER_BOX));
        assertTrue(classifier.isContainer(Material.LIME_SHULKER_BOX));
    }

    @Test
    @DisplayName("an ender chest is not protected, because it holds nothing of the city's")
    void enderChestIsNotProtected() {
        // Opening one shows the viewer their own inventory, so there is nothing to steal,
        // and SPEC 5.5 does not list it.
        assertFalse(classifier.isContainer(Material.ENDER_CHEST));
    }

    @Test
    @DisplayName("ordinary building blocks are not containers")
    void plainBlocksAreNotContainers() {
        assertFalse(classifier.isContainer(Material.STONE));
        assertFalse(classifier.isContainer(Material.OAK_PLANKS));
        assertFalse(classifier.isContainer(Material.DIRT));
    }

    // ==================================================================================
    // The SPEC 5.5 interactable list
    // ==================================================================================

    @ParameterizedTest
    @ValueSource(strings = {"OAK_DOOR", "IRON_DOOR", "OAK_TRAPDOOR", "STONE_BUTTON",
            "OAK_BUTTON", "LEVER", "STONE_PRESSURE_PLATE", "OAK_PRESSURE_PLATE",
            "RED_BED", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL", "ENCHANTING_TABLE"})
    @DisplayName("every interactable SPEC 5.5 names is protected")
    void specInteractables(String name) {
        assertTrue(classifier.isInteractable(Material.valueOf(name)),
                name + " should be interactable");
    }

    @Test
    @DisplayName("tags cover wood types the plugin was never told about")
    void tagsCoverNewWoodTypes() {
        // The reason for using Tag.DOORS rather than a list: these shipped after most
        // protection plugins were written, and a hand-written list would have missed them.
        assertTrue(classifier.isInteractable(Material.BAMBOO_DOOR));
        assertTrue(classifier.isInteractable(Material.CHERRY_TRAPDOOR));
        assertTrue(classifier.isInteractable(Material.MANGROVE_FENCE_GATE));
    }

    @Test
    @DisplayName("a plain block is neither, so walking through a city touches nothing")
    void plainBlocksAreNotInteractable() {
        assertFalse(classifier.isInteractable(Material.STONE));
        assertFalse(classifier.isProtected(Material.STONE));
        assertFalse(classifier.isProtected(Material.GRASS_BLOCK));
    }

    @Test
    @DisplayName("containers and interactables are distinct, so each is handled once")
    void containersAreNotInteractables() {
        // The interaction listener skips containers deliberately: the container listener
        // distinguishes looking from taking and would be bypassed otherwise.
        assertTrue(classifier.isContainer(Material.CHEST));
        assertFalse(classifier.isInteractable(Material.CHEST));

        assertTrue(classifier.isInteractable(Material.LEVER));
        assertFalse(classifier.isContainer(Material.LEVER));
    }

    // ==================================================================================
    // Config overrides
    // ==================================================================================

    @Test
    @DisplayName("an operator can protect something the specification does not list")
    void extraContainers() {
        assertFalse(classifier.isContainer(Material.ENDER_CHEST));

        configs.get(ConfigFile.CITIES)
                .set("protection.extra-containers", List.of("ENDER_CHEST"));
        rebuild();

        assertTrue(classifier.isContainer(Material.ENDER_CHEST));
    }

    @Test
    @DisplayName("an operator can leave something unprotected that would otherwise be covered")
    void unprotectedOverride() {
        assertTrue(classifier.isInteractable(Material.OAK_DOOR));

        configs.get(ConfigFile.CITIES).set("protection.unprotected", List.of("OAK_DOOR"));
        rebuild();

        assertFalse(classifier.isInteractable(Material.OAK_DOOR));
        assertTrue(classifier.isInteractable(Material.IRON_DOOR), "only the named block is freed");
    }

    @Test
    @DisplayName("a typo costs one block's protection and a warning, not the whole classifier")
    void unknownMaterialIsSkipped() {
        configs.get(ConfigFile.CITIES)
                .set("protection.extra-containers", List.of("NOT_A_REAL_BLOCK", "ENDER_CHEST"));
        rebuild();

        assertTrue(classifier.isContainer(Material.ENDER_CHEST), "the valid entry still applies");
        assertTrue(classifier.isContainer(Material.CHEST), "and the defaults are untouched");
    }
}
