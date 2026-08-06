package dev.civitas.gui.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import dev.civitas.config.PluginResources;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Reading a YAML layout, SPEC 8.
 *
 * <p>The behaviour under test is mostly about bad files, because an operator hand-editing
 * YAML is the normal case for this loader and "the plugin refused to start" is never the
 * right answer to a misspelled material.
 */
class LayoutLoaderTest {

    @TempDir
    Path directory;

    private LayoutLoader loader;

    @BeforeEach
    void setUp() {
        // Material.matchMaterial needs a server registry.
        MockBukkit.mock();
        loader = new LayoutLoader(
                PluginResources.ofClasspath(directory.toFile(), GuiTestSupport.quiet()));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MenuLayout parse(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new AssertionError(e);
        }
        return loader.parse("test.yml", config, "gui.fallback", 54);
    }

    // ==================================================================================
    // A good file
    // ==================================================================================

    @Test
    @DisplayName("a well-formed layout parses into entries looked up by name")
    void parsesEntries() {
        MenuLayout layout = parse("""
                title: "gui.main.title"
                size: 54
                border: true
                buttons:
                  overview:
                    slot: 10
                    material: BEACON
                    label: "gui.main.overview"
                    lore:
                      - "gui.main.overview-lore"
                  claims:
                    slot: 12
                    material: GRASS_BLOCK
                    label: "gui.main.claims"
                """);

        assertEquals("gui.main.title", layout.titleKey());
        assertEquals(54, layout.size());
        assertTrue(layout.bordered());
        assertEquals(2, layout.entries().size());

        MenuLayout.Entry overview = layout.entry("overview").orElseThrow();
        assertEquals(10, overview.slot());
        assertEquals(Material.BEACON, overview.material());
        assertEquals(List.of("gui.main.overview-lore"), overview.loreKeys());

        assertEquals(12, layout.entry("claims").orElseThrow().slot());
    }

    @Test
    @DisplayName("a layout is looked up by key, so moving a button in the file moves nothing else")
    void lookupIsByKey() {
        MenuLayout layout = parse("""
                buttons:
                  overview:
                    slot: 44
                    material: BEACON
                    label: "gui.main.overview"
                """);

        assertEquals(44, layout.entry("overview").orElseThrow().slot(),
                "the code asked for 'overview' and got wherever the operator put it");
    }

    @Test
    @DisplayName("a file with no title or size falls back to what the screen asked for")
    void fallbacks() {
        MenuLayout layout = parse("buttons: {}");

        assertEquals("gui.fallback", layout.titleKey());
        assertEquals(54, layout.size());
    }

    // ==================================================================================
    // Bad files cost a button, not the screen
    // ==================================================================================

    @Test
    @DisplayName("an unknown material is skipped, and the rest of the file still loads")
    void unknownMaterial() {
        MenuLayout layout = parse("""
                buttons:
                  broken:
                    slot: 10
                    material: DIMAOND
                    label: "gui.a"
                  fine:
                    slot: 11
                    material: DIAMOND
                    label: "gui.b"
                """);

        assertTrue(layout.entry("broken").isEmpty());
        assertTrue(layout.entry("fine").isPresent(), "one typo must not cost the whole screen");
    }

    @Test
    @DisplayName("a slot outside the menu is skipped")
    void slotOutOfRange() {
        MenuLayout layout = parse("""
                size: 27
                buttons:
                  toofar:
                    slot: 40
                    material: DIAMOND
                    label: "gui.a"
                """);

        assertTrue(layout.entry("toofar").isEmpty());
    }

    @Test
    @DisplayName("two buttons on one slot: the first keeps it, the second is skipped")
    void duplicateSlot() {
        MenuLayout layout = parse("""
                buttons:
                  first:
                    slot: 22
                    material: DIAMOND
                    label: "gui.a"
                  second:
                    slot: 22
                    material: EMERALD
                    label: "gui.b"
                """);

        assertTrue(layout.entry("first").isPresent());
        assertTrue(layout.entry("second").isEmpty(),
                "silently stacking them would hide one button behind another");
    }

    @Test
    @DisplayName("an entry with no slot or no label is skipped")
    void incompleteEntries() {
        MenuLayout layout = parse("""
                buttons:
                  noslot:
                    material: DIAMOND
                    label: "gui.a"
                  nolabel:
                    slot: 10
                    material: DIAMOND
                """);

        assertTrue(layout.entry("noslot").isEmpty());
        assertTrue(layout.entry("nolabel").isEmpty());
    }

    @Test
    @DisplayName("a material that cannot be shown in an inventory is skipped")
    void nonItemMaterial() {
        MenuLayout layout = parse("""
                buttons:
                  air:
                    slot: 10
                    material: WATER
                    label: "gui.a"
                """);

        assertTrue(layout.entry("air").isEmpty());
    }

    @Test
    @DisplayName("a size that is not a whole number of rows falls back")
    void badSize() {
        assertEquals(54, parse("size: 40").size());
        assertEquals(54, parse("size: 0").size());
        assertEquals(54, parse("size: 90").size());
        assertEquals(27, parse("size: 27").size(), "and a legal one is kept");
    }

    // ==================================================================================
    // Defaults for a screen whose file was trimmed
    // ==================================================================================

    @Test
    @DisplayName("a screen can state its own fallback for a button the file does not define")
    void entryOrFallback() {
        MenuLayout layout = parse("buttons: {}");

        MenuLayout.Entry entry = layout.entryOr("treasury", 14, Material.GOLD_INGOT, "gui.t");

        assertEquals(14, entry.slot());
        assertEquals(Material.GOLD_INGOT, entry.material());
        assertTrue(entry.loreKeys().isEmpty());
    }

    @Test
    @DisplayName("an empty layout is still a usable layout")
    void emptyLayout() {
        MenuLayout layout = MenuLayout.empty("gui.x", 27);

        assertTrue(layout.isEmpty());
        assertEquals(27, layout.size());
        assertTrue(layout.bordered());
    }

    // ==================================================================================
    // On disk
    // ==================================================================================

    @Test
    @DisplayName("the shared gui/common.yml ships in the jar and copies out on first run")
    void commonFileShips() {
        // common.yml is loaded as a ConfigFile rather than a layout, but it must exist for
        // any of the SPEC 8.2 constants to resolve at all.
        assertFalse(new File(directory.toFile(), "gui/common.yml").exists());

        var configs = GuiTestSupport.configs(directory.toFile());

        assertTrue(new File(directory.toFile(), "gui/common.yml").isFile());
        assertEquals(54, configs.get(dev.civitas.config.ConfigFile.GUI).getInt("size"));
        assertEquals(49, configs.get(dev.civitas.config.ConfigFile.GUI)
                .getInt("navigation.close-slot"));
    }

    @Test
    @DisplayName("a layout whose file does not exist opens with the screen's defaults")
    void missingFile() {
        MenuLayout layout = loader.load("nosuch.yml", "gui.fallback", 54);

        assertTrue(layout.isEmpty());
        assertEquals("gui.fallback", layout.titleKey());
    }
}
