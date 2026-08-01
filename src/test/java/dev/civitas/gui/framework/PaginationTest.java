package dev.civitas.gui.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Paging through a list, SPEC 8.2. */
class PaginationTest {

    private static final int PREVIOUS = 48;
    private static final int NEXT = 50;

    @TempDir
    Path directory;

    private ServerMock server;
    private MenuManager menus;
    private PlayerMock player;
    private GuiTestSupport.TestList list;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        ConfigManager configs = GuiTestSupport.configs(directory.toFile());
        LangManager lang = GuiTestSupport.lang(directory.toFile(), configs);

        menus = new MenuManager(configs, lang);
        player = server.addPlayer("Reader");
        list = new GuiTestSupport.TestList(menus, player);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void given(int count) {
        for (int i = 0; i < count; i++) {
            list.items.add("entry-" + i);
        }
    }

    @Test
    @DisplayName("a bordered 54-slot menu holds 28 entries a page")
    void pageSize() {
        given(28);
        list.open();

        assertEquals(1, list.pageCount());
        assertTrue(list.buttonAt(NEXT).isEmpty(), "nothing to page to");
    }

    @Test
    @DisplayName("SPEC 8.2: previous on 48 and next on 50, and only when they lead somewhere")
    void arrowSlots() {
        // Slots 48 and 50 are on the bottom border row, so an absent arrow leaves the pane
        // showing rather than a hole: the check is for the button, not for an empty slot.
        given(60);
        list.open();

        assertTrue(list.buttonAt(PREVIOUS).isEmpty(), "page one has no previous");
        assertEquals(Material.SPECTRAL_ARROW, list.inventory().getItem(NEXT).getType());

        list.turn(1);

        assertEquals(Material.SPECTRAL_ARROW, list.inventory().getItem(PREVIOUS).getType());
        assertEquals(Material.SPECTRAL_ARROW, list.inventory().getItem(NEXT).getType());

        list.turn(1);

        assertEquals(2, list.page(), "60 entries is three pages");
        assertTrue(list.buttonAt(NEXT).isEmpty(), "the last page has no next");
    }

    @Test
    @DisplayName("paging past either end does nothing")
    void clampedAtBothEnds() {
        given(10);
        list.open();

        assertFalse(list.turn(1), "one page, nowhere to go");
        assertFalse(list.turn(-1));
        assertEquals(0, list.page());
    }

    @Test
    @DisplayName("an empty list is one empty page, not zero pages")
    void emptyList() {
        list.open();

        assertEquals(1, list.pageCount());
        assertEquals(0, list.page());
        assertTrue(list.buttonAt(NEXT).isEmpty());
    }

    @Test
    @DisplayName("each page shows its own slice, in order")
    void slices() {
        given(60);
        list.open();

        assertEquals("entry-0", plainName(10));
        list.turn(1);
        assertEquals("entry-28", plainName(10));
    }

    @Test
    @DisplayName("clicking an entry passes the right one, on whichever page it is")
    void clickPassesTheEntry() {
        given(60);
        list.open();
        list.turn(1);

        list.buttonAt(10).orElseThrow();
        list.click(player, 10, org.bukkit.event.inventory.ClickType.LEFT);

        assertEquals(java.util.List.of("entry-28"), list.clicked);
    }

    @Test
    @DisplayName("a list that shrinks under an open menu lands the viewer on a real page")
    void shrinkingList() {
        // SPEC 17.5 case 64: what is drawn is a snapshot, and the data moves underneath it.
        given(90);
        list.open();
        list.turn(1);
        list.turn(1);
        assertEquals(2, list.page());

        list.items.subList(30, list.items.size()).clear();
        list.refresh();

        assertEquals(1, list.page(), "clamped down to the last page that now exists");
        assertNotNull(list.inventory().getItem(10), "and it is drawn, not blank");
    }

    @Test
    @DisplayName("a list that grows leaves the viewer where they were")
    void growingList() {
        given(30);
        list.open();
        list.turn(1);

        for (int i = 0; i < 60; i++) {
            list.items.add("more-" + i);
        }
        list.refresh();

        assertEquals(1, list.page());
        assertTrue(list.pageCount() > 2);
    }

    private String plainName(int slot) {
        var meta = list.inventory().getItem(slot).getItemMeta();
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(meta.displayName());
    }
}
