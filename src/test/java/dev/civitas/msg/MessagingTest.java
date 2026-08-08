package dev.civitas.msg;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.row.PlayerToggleRow;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 23's message framework: the palette, the preferences and the channel router.
 *
 * <p>The assertions that matter most are the four SPEC 23.6 locks. One of them, the treasury
 * withdrawal broadcast, is what SPEC 23.5.6 calls "the primary anti-fraud mechanism in the
 * plugin", and a lock with one guard is a lock one bug away from being off.
 */
class MessagingTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private TogglePreferences toggles;
    private Messenger messenger;
    private UUID player;

    private static final long NOON = 1_754_000_000_000L;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        toggles = new TogglePreferences(support.daos.playerToggles(),
                CityTestSupport.quietLogger());
        messenger = new Messenger(support.lang(), support.configs, toggles);
        player = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    // ==================================================================================
    // SPEC 23.2
    // ==================================================================================

    @Nested
    @DisplayName("the palette, SPEC 23.2")
    class PaletteTests {

        @Test
        @DisplayName("every token SPEC 23.2 names resolves")
        void allTokensResolve() {
            for (String token : Palette.STANDARD.tokenNames()) {
                Component rendered = MiniMessage.miniMessage().deserialize(
                        "<" + token + ">text</" + token + ">", Palette.STANDARD.resolver());

                assertEquals("text", PlainTextComponentSerializer.plainText().serialize(rendered),
                        "<" + token + "> rendered as literal text, so the tag is unknown");
                assertNotNull(rendered.color(), "<" + token + "> painted no colour");
            }
        }

        @Test
        @DisplayName("SPEC 23.2's table is complete, all thirteen")
        void tableIsComplete() {
            assertEquals(java.util.Set.of("pos", "neg", "money", "subject", "body", "dim",
                            "city", "land", "war", "quest", "ally", "admin", "link"),
                    Palette.STANDARD.tokenNames());
        }

        @Test
        @DisplayName("subject is bold and link is underlined, as SPEC 23.2 specifies")
        void decorationsAreNotJustColours() {
            Component subject = MiniMessage.miniMessage().deserialize(
                    "<subject>Roma</subject>", Palette.STANDARD.resolver());
            Component link = MiniMessage.miniMessage().deserialize(
                    "<link>[Accept]</link>", Palette.STANDARD.resolver());

            assertEquals(TextDecoration.State.TRUE, subject.decoration(TextDecoration.BOLD));
            assertEquals(TextDecoration.State.TRUE, link.decoration(TextDecoration.UNDERLINED));
        }

        @Test
        @DisplayName("a variant may restyle a token and may not invent one")
        void variantsMustKeepEveryToken() {
            // SPEC 36.5's colourblind palette is the reason this seam exists. A variant that
            // dropped a token would render it as literal text in every message that used it,
            // which is worse than the colour it set out to fix.
            Palette variant = Palette.STANDARD.variant("cb", java.util.Map.of(
                    "pos", net.kyori.adventure.text.format.Style.style(
                            net.kyori.adventure.text.format.TextColor.color(0x3B82F6))));

            assertEquals(Palette.STANDARD.tokenNames(), variant.tokenNames());
            assertEquals("cb", variant.id());

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> Palette.STANDARD.variant("bad", java.util.Map.of(
                            "nonexistent", net.kyori.adventure.text.format.Style.empty())));
        }
    }

    // ==================================================================================
    // SPEC 23.6
    // ==================================================================================

    @Nested
    @DisplayName("notification preferences, SPEC 23.6")
    class Preferences {

        @Test
        @DisplayName("a player nobody has heard of reads every default")
        void defaultsApply() {
            assertTrue(toggles.wants(player, ToggleCategory.QUESTS));
            assertFalse(toggles.wants(player, ToggleCategory.COMPACT),
                    "SPEC 23.6 ships compact off");
        }

        @Test
        @DisplayName("a mutable category can be turned off and stays off")
        void mutableCategories() {
            assertTrue(await(toggles.set(player, ToggleCategory.QUESTS, false)).isSuccess());

            assertFalse(toggles.wants(player, ToggleCategory.QUESTS));
            assertTrue(toggles.isOverridden(player, ToggleCategory.QUESTS));
        }

        @Test
        @DisplayName("it survives a reload, so a preference is not per-session")
        void persisted() {
            await(toggles.set(player, ToggleCategory.EVENTS, false));

            TogglePreferences reopened = new TogglePreferences(support.daos.playerToggles(),
                    CityTestSupport.quietLogger());
            await(reopened.load(player));

            assertFalse(reopened.wants(player, ToggleCategory.EVENTS));
        }

        @Test
        @DisplayName("forgetting a player drops their overrides back to the defaults")
        void forgetting() {
            await(toggles.set(player, ToggleCategory.EVENTS, false));
            toggles.forget(player);

            assertTrue(toggles.wants(player, ToggleCategory.EVENTS),
                    "an unloaded player reads defaults, which fails toward sending");
        }
    }

    @Nested
    @DisplayName("the four SPEC 23.6 locks")
    class Locks {

        @Test
        @DisplayName("exactly the four SPEC names are locked")
        void exactlyFour() {
            assertEquals(java.util.List.of(ToggleCategory.TREASURY_WITHDRAW,
                            ToggleCategory.UPKEEP_CRITICAL, ToggleCategory.WAR),
                    java.util.Arrays.stream(ToggleCategory.values())
                            .filter(ToggleCategory::locked).toList(),
                    "SPEC 23.6 locks treasury_withdraw, upkeep_critical and war. "
                            + "actionbar and sounds are presentation and stay mutable.");
        }

        @Test
        @DisplayName("setting one is refused, not silently ignored")
        void setIsRefused() {
            Result<Boolean> result = await(
                    toggles.set(player, ToggleCategory.TREASURY_WITHDRAW, false));

            assertTrue(result instanceof Result.Failure, "a lock was turned off");
            assertEquals("TOGGLE_LOCKED",
                    ((Result.Failure<Boolean>) result).reason());
            assertTrue(toggles.wants(player, ToggleCategory.TREASURY_WITHDRAW));
        }

        @Test
        @DisplayName("a row written straight into the table still cannot mute one")
        void databaseRowCannotMuteALock() {
            // The second guard, and the reason there are two. SPEC 23.5.6 calls the withdrawal
            // broadcast "the primary anti-fraud mechanism in the plugin": social transparency
            // works only while the thief knows everyone will see it.
            await(support.daos.playerToggles().upsert(new PlayerToggleRow(player,
                    ToggleCategory.WAR.key(), false)));
            await(toggles.load(player));

            assertTrue(toggles.wants(player, ToggleCategory.WAR),
                    "a hand-written row muted a locked category");
        }

        @Test
        @DisplayName("the router will not skip a locked category either")
        void routerHonoursLocks() {
            await(support.daos.playerToggles().upsert(new PlayerToggleRow(player,
                    ToggleCategory.UPKEEP_CRITICAL.key(), false)));
            await(toggles.load(player));

            java.util.List<Component> sent = new java.util.ArrayList<>();
            boolean shown = messenger.send(player, collector(sent),
                    ToggleCategory.UPKEEP_CRITICAL, "plugin.starting");

            assertTrue(shown);
            assertEquals(1, sent.size());
        }

        @Test
        @DisplayName("/toggle is never offered a locked category")
        void mutableListExcludesLocks() {
            assertTrue(ToggleCategory.mutable().stream().noneMatch(ToggleCategory::locked));
        }
    }

    // ==================================================================================
    // SPEC 23.4
    // ==================================================================================

    @Nested
    @DisplayName("the channel router, SPEC 23.4")
    class Router {

        @Test
        @DisplayName("a muted category is not sent")
        void mutedIsNotSent() {
            await(toggles.set(player, ToggleCategory.EVENTS, false));
            java.util.List<Component> sent = new java.util.ArrayList<>();

            assertFalse(messenger.send(player, collector(sent), ToggleCategory.EVENTS,
                    "plugin.starting"));
            assertTrue(sent.isEmpty());
        }

        @Test
        @DisplayName("titles are capped at four an hour, hard, per SPEC 23.4")
        void titlesAreRateLimited() {
            for (int i = 0; i < 4; i++) {
                assertTrue(messenger.allowTitle(player, NOON + i * 1000L),
                        "title " + (i + 1) + " of the permitted four was refused");
            }
            assertFalse(messenger.allowTitle(player, NOON + 5000L), "a fifth title was allowed");
        }

        @Test
        @DisplayName("the window slides, so four at 10:59 and four at 11:01 is still refused")
        void titleWindowSlides() {
            for (int i = 0; i < 4; i++) {
                messenger.allowTitle(player, NOON + i * 1000L);
            }
            // Two minutes later: a bucket that reset on the hour would allow four more.
            assertFalse(messenger.allowTitle(player, NOON + 120_000L));
            // A full window later, it is allowed again.
            assertTrue(messenger.allowTitle(player,
                    NOON + messenger.titleWindowMillis() + 1000L));
        }

        @Test
        @DisplayName("a title past the cap is downgraded to chat, not dropped")
        void titleOverflowFallsBackToChat() {
            // The message still has something to say. Dropping it would lose it entirely.
            for (int i = 0; i < 4; i++) {
                messenger.allowTitle(player, System.currentTimeMillis());
            }
            java.util.List<Component> sent = new java.util.ArrayList<>();

            assertTrue(messenger.send(player, collector(sent), ToggleCategory.WAR,
                    Channel.TITLE, "plugin.starting"));
            assertEquals(1, sent.size(), "the fifth title vanished instead of reaching chat");
        }

        @Test
        @DisplayName("action bars are throttled per player and per message")
        void actionBarThrottle() {
            // Per message, not per player: two different denials in the same second are two
            // things worth knowing.
            assertFalse(messenger.isThrottled(player, "a", NOON));
            assertTrue(messenger.isThrottled(player, "a", NOON + 500));
            assertFalse(messenger.isThrottled(player, "b", NOON + 500),
                    "a different message was suppressed by the first one's cooldown");

            assertFalse(messenger.isThrottled(player, "a",
                    NOON + messenger.actionBarCooldownMillis() + 1));
        }

        @Test
        @DisplayName("one player's throttle is not another's")
        void throttleIsPerPlayer() {
            messenger.isThrottled(player, "a", NOON);

            assertFalse(messenger.isThrottled(UUID.randomUUID(), "a", NOON));
        }

        @Test
        @DisplayName("muting the action bar mutes it whatever the category")
        void actionBarToggle() {
            await(toggles.set(player, ToggleCategory.ACTIONBAR, false));
            java.util.List<Component> sent = new java.util.ArrayList<>();

            assertFalse(messenger.send(player, collector(sent), ToggleCategory.QUESTS,
                    Channel.ACTION_BAR, "plugin.starting"));
        }

        @Test
        @DisplayName("only action bars and boss bars abbreviate, SPEC 23.7")
        void abbreviationIsPerChannel() {
            assertFalse(Channel.CHAT.abbreviatesNumbers());
            assertTrue(Channel.ACTION_BAR.abbreviatesNumbers());
            assertTrue(Channel.BOSS_BAR.abbreviatesNumbers());
            assertFalse(Channel.TITLE.abbreviatesNumbers());
        }

        @Test
        @DisplayName("the console has no preferences and always receives")
        void consoleAlwaysReceives() {
            java.util.List<Component> sent = new java.util.ArrayList<>();

            assertTrue(messenger.send(null, collector(sent), ToggleCategory.EVENTS,
                    "plugin.starting"));
        }

        @Test
        @DisplayName("rendered messages carry the palette")
        void renderAppliesPalette() {
            Component rendered = messenger.render("prefixes.economy");

            assertNotNull(rendered);
            assertFalse(PlainTextComponentSerializer.plainText().serialize(rendered).isBlank(),
                    "the prefix rendered empty, so its palette tags did not resolve");
        }
    }

    // ==================================================================================
    // SPEC 23.3
    // ==================================================================================

    @Nested
    @DisplayName("prefixes, SPEC 23.3")
    class Prefixes {

        @Test
        @DisplayName("every prefix has a full and a compact form in both languages")
        void bothFormsExist() {
            for (Prefix prefix : Prefix.values()) {
                assertFalse(support.lang().plain(prefix.messageKey()).isBlank(),
                        prefix.messageKey() + " is missing");
                assertFalse(support.lang().plain(prefix.compactMessageKey()).isBlank(),
                        prefix.compactMessageKey() + " is missing");
            }
        }

        @Test
        @DisplayName("compact really is shorter, which is the whole point")
        void compactIsShorter() {
            for (Prefix prefix : Prefix.values()) {
                String full = support.lang().plain(prefix.messageKey());
                String compact = support.lang().plain(prefix.compactMessageKey());

                assertTrue(compact.length() <= full.length(),
                        prefix + " compact form is not shorter: '" + compact + "' vs '"
                                + full + "'");
            }
        }

        @Test
        @DisplayName("no prefix key is nested under the M0 prefix string")
        void noSectionCollision() {
            // "prefix" is a string used by LangManager.send since M0. A key at prefix.economy
            // would make it a section too, and Bukkit would return a MemorySection for both.
            for (Prefix prefix : Prefix.values()) {
                assertFalse(prefix.messageKey().startsWith("prefix."),
                        "prefix keys must not nest under the existing prefix string");
                assertTrue(prefix.messageKey().startsWith("prefixes."));
            }
        }
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /** An audience that records what it was sent, so a test can assert on it. */
    private static net.kyori.adventure.audience.Audience collector(
            java.util.List<Component> into) {
        return new net.kyori.adventure.audience.Audience() {
            @Override
            public void sendMessage(Component message) {
                into.add(message);
            }

            @Override
            public void sendActionBar(Component message) {
                into.add(message);
            }

            @Override
            public void showTitle(net.kyori.adventure.title.Title title) {
                into.add(title.title());
            }
        };
    }
}
