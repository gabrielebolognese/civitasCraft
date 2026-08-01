package dev.civitas.gui.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Scheduler;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Typing an amount, SPEC 8.5, and SPEC 17.5 cases 67 and 68.
 *
 * <p>Everything a player can type reaches a balance through here, so the interesting tests
 * are the ones where they type something that is not a number.
 */
class AmountInputTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private ConfigManager configs;
    private AmountInput input;
    private PlayerMock player;

    private final AtomicReference<BigDecimal> received = new AtomicReference<>();
    private final AtomicInteger cancels = new AtomicInteger();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        configs = GuiTestSupport.configs(directory.toFile());
        LangManager lang = GuiTestSupport.lang(directory.toFile(), configs);

        MenuManager menus = new MenuManager(configs, lang);
        input = new AmountInput(menus, lang, Scheduler.direct());
        player = server.addPlayer("Typist");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void ask() {
        input.ask(player, "gui.input.amount", received::set, cancels::incrementAndGet);
    }

    /** Feeds one chat line to the listener and returns the event, to check it was eaten. */
    private AsyncChatEvent say(String text) {
        AsyncChatEvent event = new AsyncChatEvent(true, player, Set.of(),
                io.papermc.paper.chat.ChatRenderer.defaultRenderer(),
                Component.text(text), Component.text(text),
                (net.kyori.adventure.chat.SignedMessage) null);
        input.onChat(event);
        return event;
    }

    // ==================================================================================
    // The happy path
    // ==================================================================================

    @Test
    @DisplayName("a plain number is accepted and handed over once")
    void acceptsANumber() {
        ask();
        assertTrue(input.isAwaiting(player));

        AsyncChatEvent event = say("1500");

        assertEquals(0, new BigDecimal("1500").compareTo(received.get()));
        assertTrue(event.isCancelled(), "the answer must not appear in public chat");
        assertFalse(input.isAwaiting(player), "and the prompt is over");
    }

    @Test
    @DisplayName("decimals are floored to the currency scale, SPEC 17.3 case 26")
    void floorsDecimals() {
        ask();
        say("1500.999");

        assertEquals(0, new BigDecimal("1500.99").compareTo(received.get()));
    }

    @Test
    @DisplayName("chat from a player who was not asked is left entirely alone")
    void otherPlayersChatNormally() {
        AsyncChatEvent event = say("hello everyone");

        assertFalse(event.isCancelled());
        assertNull(received.get());
    }

    // ==================================================================================
    // SPEC 17.5 cases 67 and 68
    // ==================================================================================

    @ParameterizedTest(name = "\"{0}\" is refused and the prompt stands")
    @ValueSource(strings = {
            "abc",           // case 67: not a number at all
            "1500 coins",
            "",
            "-100",          // case 68: negative
            "-0.01",
            "1e9",           // case 68: scientific notation
            "1E+12",
            "1.5e3",
            "+500",
            "NaN",
            "Infinity",
            "0",             // nothing to move
            "0.001"})        // floors to nothing
    @DisplayName("cases 67 and 68: bad input is refused, and the player is asked again")
    void refusesBadInput(String typed) {
        ask();

        AsyncChatEvent event = say(typed);

        assertNull(received.get(), typed + " should not have reached the callback");
        assertEquals(0, cancels.get(), "a typo is not a cancellation");
        assertTrue(event.isCancelled());
        assertTrue(input.isAwaiting(player), "still waiting, so one typo costs one retry");
    }

    @Test
    @DisplayName("a retry after a bad answer works")
    void retryWorks() {
        ask();
        say("not a number");
        say("250");

        assertEquals(0, new BigDecimal("250").compareTo(received.get()));
    }

    // ==================================================================================
    // Giving up
    // ==================================================================================

    @Test
    @DisplayName("the cancel word ends the prompt without an amount")
    void cancelWord() {
        ask();
        say("cancel");

        assertNull(received.get());
        assertEquals(1, cancels.get());
        assertFalse(input.isAwaiting(player));
    }

    @Test
    @DisplayName("the cancel word is case-insensitive and configurable")
    void cancelWordIsConfigurable() {
        configs.get(ConfigFile.GUI).set("input.cancel-word", "stop");

        ask();
        say("STOP");

        assertEquals(1, cancels.get());
    }

    @Test
    @DisplayName("a prompt that has timed out cancels rather than accepting a late answer")
    void timeout() {
        configs.get(ConfigFile.GUI).set("input.timeout-seconds", 0);

        ask();
        say("1500");

        assertNull(received.get(), "an answer arriving after the timeout is not an answer");
        assertEquals(1, cancels.get());
    }

    @Test
    @DisplayName("a player who logs out mid-prompt is no longer being asked")
    void quitClearsThePrompt() {
        ask();

        input.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player, Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertFalse(input.isAwaiting(player));
        assertEquals(0, cancels.get(), "and nothing was run on their behalf");
    }

    @Test
    @DisplayName("asking closes whatever menu they had open, so the prompt is not hidden")
    void askingClosesTheMenu() {
        MenuManager menus = new MenuManager(configs,
                GuiTestSupport.lang(directory.toFile(), configs));
        AmountInput ownInput = new AmountInput(menus, GuiTestSupport
                .lang(directory.toFile(), configs), Scheduler.direct());

        GuiTestSupport.TestMenu menu = new GuiTestSupport.TestMenu(menus, player);
        menu.open();
        assertEquals(1, menus.openCount());

        ownInput.ask(player, "gui.input.amount", received::set, null);

        assertEquals(0, menus.openCount());
    }
}
