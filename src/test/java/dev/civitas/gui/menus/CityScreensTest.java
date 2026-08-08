package dev.civitas.gui.menus;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
import dev.civitas.gui.framework.MenuListener;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The SPEC Section 8 screens over the real city stack.
 *
 * <p>The SPEC 18.2 requirement lives here: "GUI click validation with a revoked permission",
 * proved on the menus a player will actually use rather than on a synthetic one. So do SPEC
 * 17.1 case 11 and SPEC 17.5 cases 59, 60 and 65.
 */
class CityScreensTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private MenuTestSupport support;
    private MenuListener listener;

    private PlayerMock mayorPlayer;
    private PlayerMock memberPlayer;
    private UUID mayor;
    private UUID member;
    private City city;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin("CivitasTest");
        support = MenuTestSupport.open(directory, plugin);
        listener = new MenuListener(support.menus);

        // The mock server owns the UUIDs, so the city is founded on the accounts it handed
        // out rather than on fresh ones the screens would never see.
        mayorPlayer = server.addPlayer("Romulus");
        memberPlayer = server.addPlayer("Titus");
        mayor = mayorPlayer.getUniqueId();
        member = memberPlayer.getUniqueId();

        support.cities.givenPlayer(mayor, "Romulus", new BigDecimal("50000.00"),
                java.util.concurrent.TimeUnit.HOURS.toMillis(10));
        support.cities.givenPlayer(member, "Titus", new BigDecimal("50000.00"),
                java.util.concurrent.TimeUnit.HOURS.toMillis(10));

        city = support.cities.givenCity(mayor, "Roma", 0, 0);
        await(support.cities.cities.invite(mayor, city, member));
        await(support.cities.cities.acceptInvite(member, city));

        CityRank citizen = city.rankByName("Citizen").orElseThrow();
        await(support.cities.ranks.assign(mayor, city, member, citizen));
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private MainMenu openMain(PlayerMock player) {
        MainMenu menu = new MainMenu(support.menus, support.services, player, city);
        menu.open();
        return menu;
    }

    private InventoryClickEvent click(PlayerMock player, int slot) {
        return new InventoryClickEvent(player.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    /**
     * Waits for something a click set in motion.
     *
     * <p>A menu click dispatches a service call and returns; the write lands on the database
     * thread a moment later. Asserting straight after the click would be testing the race
     * rather than the behaviour.
     */
    private static void eventually(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.fail("timed out waiting for: " + what);
    }

    private static String plain(ItemStack stack) {
        return stack == null || stack.getItemMeta() == null
                ? ""
                : PlainTextComponentSerializer.plainText()
                        .serialize(stack.getItemMeta().displayName());
    }

    private static String lore(ItemStack stack) {
        if (stack == null || stack.getItemMeta() == null || stack.getItemMeta().lore() == null) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        stack.getItemMeta().lore().forEach(line ->
                joined.append(PlainTextComponentSerializer.plainText().serialize(line))
                        .append('\n'));
        return joined.toString();
    }

    // ==================================================================================
    // SPEC 8.3
    // ==================================================================================

    @Nested
    @DisplayName("The main menu")
    class Main {

        @Test
        @DisplayName("SPEC 8.3: every button sits on the slot the specification gives it")
        void slots() {
            MainMenu menu = openMain(mayorPlayer);

            assertEquals(Material.BEACON, menu.inventory().getItem(10).getType());
            assertEquals(Material.GRASS_BLOCK, menu.inventory().getItem(12).getType());
            assertEquals(Material.GOLD_INGOT, menu.inventory().getItem(14).getType());
            assertEquals(Material.PLAYER_HEAD, menu.inventory().getItem(16).getType());
            assertEquals(Material.ENDER_PEARL, menu.inventory().getItem(40).getType());
            assertEquals(Material.COMPARATOR, menu.inventory().getItem(42).getType());
        }

        @Test
        @DisplayName("every button SPEC 8.3 names now leads somewhere")
        void hubIsComplete() {
            // Seven of these opened nothing when M8 built the hub. Wars, on slot 22, was the
            // last to be filled in. A barrier here now means a screen was lost, not deferred.
            MainMenu menu = openMain(mayorPlayer);

            for (int slot : new int[] {10, 12, 14, 16, 20, 22, 24, 28, 30, 32, 34, 40, 42}) {
                ItemStack button = menu.inventory().getItem(slot);
                assertNotNull(button, "SPEC 8.3 slot " + slot + " is empty");
                assertNotEquals(Material.BARRIER, button.getType(),
                        "SPEC 8.3 slot " + slot + " still refuses: " + plain(button));
            }
        }

        @Test
        @DisplayName("the treasury button carries the live balance")
        void liveBalance() {
            await(support.cities.daos.cities().updateTreasury(city.id(), new BigDecimal("5000")));
            city.setTreasury(new BigDecimal("5000"));

            MainMenu menu = openMain(mayorPlayer);

            // Grouped since M7a, per SPEC 23.7's "two decimals with thousands separators".
            assertTrue(lore(menu.inventory().getItem(14)).contains("5,000.00"),
                    "got: " + lore(menu.inventory().getItem(14)));
        }

        @Test
        @DisplayName("clicking Members opens the member list")
        void opensMembers() {
            openMain(mayorPlayer);
            listener.onClick(click(mayorPlayer, 16));

            assertTrue(support.menus.openMenu(mayorPlayer).orElseThrow() instanceof MembersMenu);
        }
    }

    // ==================================================================================
    // SPEC 18.2, and SPEC 17.5 case 59
    // ==================================================================================

    @Nested
    @DisplayName("Permission validation")
    class Permissions {

        @Test
        @DisplayName("SPEC 18.2: a permission revoked while the treasury screen is open")
        void revokedWhileOpen() {
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            await(support.cities.ranks.setPermission(mayor, city, citizen,
                    CityPermission.WITHDRAW, true));

            TreasuryMenu menu = new TreasuryMenu(support.menus, support.services, memberPlayer,
                    city, null);
            menu.open();
            assertEquals(Material.REDSTONE, menu.inventory().getItem(23).getType(),
                    "drawn while they still had the permission");

            // Taken away with the screen still open.
            await(support.cities.ranks.setPermission(mayor, city, citizen,
                    CityPermission.WITHDRAW, false));

            BigDecimal before = support.cities.playerRow(member).balance();
            listener.onClick(click(memberPlayer, 23));

            assertEquals(0, before.compareTo(support.cities.playerRow(member).balance()),
                    "the stale button moved no money");
            assertEquals(Material.BARRIER, menu.inventory().getItem(23).getType(),
                    "and the click redrew it as a refusal");
        }

        @Test
        @DisplayName("a member without DEPOSIT sees a barrier, with the permission named")
        void missingPermissionIsExplained() {
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            await(support.cities.ranks.setPermission(mayor, city, citizen,
                    CityPermission.DEPOSIT, false));

            TreasuryMenu menu = new TreasuryMenu(support.menus, support.services, memberPlayer,
                    city, null);
            menu.open();

            ItemStack deposit = menu.inventory().getItem(19);
            assertEquals(Material.BARRIER, deposit.getType());
            assertTrue(lore(deposit).contains("DEPOSIT"), "got: " + lore(deposit));
        }

        @Test
        @DisplayName("a member with DEPOSIT can actually deposit from the screen")
        void depositWorks() {
            TreasuryMenu menu = new TreasuryMenu(support.menus, support.services, memberPlayer,
                    city, null);
            menu.open();

            listener.onClick(click(memberPlayer, 19));

            eventually(() -> await(support.cities.daos.cities().findById(city.id()))
                            .orElseThrow().treasury().compareTo(new BigDecimal("1000.00")) == 0,
                    "the deposit to reach the treasury");
        }
    }

    // ==================================================================================
    // SPEC 17.5 case 65 and the SPEC 5.4 rules
    // ==================================================================================

    @Nested
    @DisplayName("The permission editor")
    class Editor {

        private PermissionEditorMenu open(PlayerMock player, CityRank rank) {
            PermissionEditorMenu menu = new PermissionEditorMenu(support.menus, support.services,
                    player, city, rank, null);
            menu.open();
            return menu;
        }

        @Test
        @DisplayName("granted flags are lime, ungranted are gray")
        void colours() {
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            PermissionEditorMenu menu = open(mayorPlayer, citizen);

            int buildSlot = 10;
            assertEquals(Material.LIME_DYE, menu.inventory().getItem(buildSlot).getType(),
                    "Citizen has BUILD by default, SPEC 5.4");

            int disbandSlot = 37;
            assertEquals(Material.GRAY_DYE, menu.inventory().getItem(disbandSlot).getType());
        }

        @Test
        @DisplayName("a toggle actually changes the rank")
        void toggles() {
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            open(mayorPlayer, citizen);

            listener.onClick(click(mayorPlayer, 37));

            eventually(() -> city.rankByName("Citizen").orElseThrow().permissions()
                            .has(CityPermission.DISBAND),
                    "the mayor holds DISBAND, so may grant it");
        }

        @Test
        @DisplayName("SPEC 5.4: a member cannot grant a permission they do not hold")
        void cannotGrantWhatYouLack() {
            // Give the member rank control but nothing else, then have them try to grant
            // something they do not have.
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            await(support.cities.ranks.setPermission(mayor, city, citizen,
                    CityPermission.MANAGE_RANKS, true));

            CityRank recruit = city.rankByName("Recruit").orElseThrow();
            PermissionEditorMenu menu = open(memberPlayer, recruit);

            int disbandSlot = 37;
            assertEquals(Material.BARRIER, menu.inventory().getItem(disbandSlot).getType(),
                    "shown as refused, with the reason");

            listener.onClick(click(memberPlayer, disbandSlot));

            assertFalse(city.rankByName("Recruit").orElseThrow().permissions()
                            .has(CityPermission.DISBAND),
                    "and the click granted nothing");
        }

        @Test
        @DisplayName("SPEC 5.4: a member cannot edit a rank at or above their own weight")
        void cannotEditEqualOrHigher() {
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            await(support.cities.ranks.setPermission(mayor, city, citizen,
                    CityPermission.MANAGE_RANKS, true));

            PermissionEditorMenu menu = open(memberPlayer, citizen);

            assertEquals(Material.BARRIER, menu.inventory().getItem(10).getType(),
                    "their own rank is not theirs to edit");
        }

        @Test
        @DisplayName("case 65: a change made elsewhere shows up here rather than surprising them")
        void twoEditors() {
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            PermissionEditorMenu menu = open(mayorPlayer, citizen);
            assertEquals(Material.LIME_DYE, menu.inventory().getItem(10).getType());

            // Somebody else revokes BUILD while this screen is open.
            await(support.cities.ranks.setPermission(mayor, city, citizen,
                    CityPermission.BUILD, false));
            support.menus.refreshLive();

            assertEquals(Material.GRAY_DYE, menu.inventory().getItem(10).getType(),
                    "the live refresh caught it");
        }
    }

    // ==================================================================================
    // SPEC 17.1 case 11 and SPEC 17.5 case 60
    // ==================================================================================

    @Nested
    @DisplayName("Screens that stop applying")
    class Stale {

        @Test
        @DisplayName("case 60: a member kicked with the menu open has it taken away")
        void kickedWhileOpen() {
            MainMenu menu = new MainMenu(support.menus, support.services, memberPlayer, city);
            menu.open();
            assertEquals(1, support.menus.openCount());

            await(support.cities.cities.kick(mayor, city, member));
            support.menus.refreshLive();

            assertEquals(0, support.menus.openCount(),
                    "a stale door into a city they left is not left standing");
        }

        @Test
        @DisplayName("case 11: a city disbanding closes the menus that describe it")
        void disbandedWhileOpen() {
            MainMenu menu = new MainMenu(support.menus, support.services, mayorPlayer, city);
            menu.open();

            await(support.cities.cities.disband(mayor, city));
            support.menus.refreshLive();

            assertEquals(0, support.menus.openCount());
        }
    }

    // ==================================================================================
    // SPEC 8.4
    // ==================================================================================

    @Nested
    @DisplayName("The claims screen")
    class Claims {

        @Test
        @DisplayName("SPEC 8.4: the claim button breaks the SPEC 6.2 cost down")
        void costBreakdown() {
            mayorPlayer.setLocation(new org.bukkit.Location(server.addSimpleWorld("world"),
                    20.0, 64.0, 4.0));

            ClaimsMenu menu = new ClaimsMenu(support.menus, support.services, mayorPlayer, city,
                    null);
            menu.open();

            String text = lore(menu.inventory().getItem(11));
            assertTrue(text.contains("Base"), "got: " + text);
            assertTrue(text.contains("Distance multiplier"), "got: " + text);
            assertTrue(text.contains("Member divisor"), "got: " + text);
            assertTrue(text.contains("Total"), "got: " + text);
        }

        @Test
        @DisplayName("SPEC 8.4: the bottom row is a 3x3 map with the player at its centre")
        void minimap() {
            mayorPlayer.setLocation(new org.bukkit.Location(server.addSimpleWorld("world"),
                    8.0, 64.0, 8.0));

            ClaimsMenu menu = new ClaimsMenu(support.menus, support.services, mayorPlayer, city,
                    null);
            menu.open();

            assertEquals(Material.PLAYER_HEAD, menu.inventory().getItem(49).getType(),
                    "the centre of the 3x3 is the player, standing on their own core chunk");
            assertEquals(Material.WHITE_CONCRETE, menu.inventory().getItem(45).getType(),
                    "and the chunk to the north-west of it is unclaimed");

            for (int slot = 45; slot <= 53; slot++) {
                assertNotNull(menu.inventory().getItem(slot), "slot " + slot + " is blank");
            }
        }
    }

    // ==================================================================================
    // SPEC 8.6 and 8.7
    // ==================================================================================

    @Test
    @DisplayName("SPEC 8.6: the member list shows everyone, with their rank")
    void memberList() {
        MembersMenu menu = new MembersMenu(support.menus, support.services, mayorPlayer, city,
                null);
        menu.open();

        String names = plain(menu.inventory().getItem(10)) + plain(menu.inventory().getItem(11));
        assertTrue(names.contains("Romulus"), "got: " + names);
        assertTrue(names.contains("Titus"), "got: " + names);
    }

    @Test
    @DisplayName("SPEC 8.7: one row per rank, highest first")
    void rankList() {
        RanksMenu menu = new RanksMenu(support.menus, support.services, mayorPlayer, city, null);
        menu.open();

        assertTrue(plain(menu.inventory().getItem(10)).contains("Mayor"),
                "got: " + plain(menu.inventory().getItem(10)));
        // SPEC 5.4 forbids editing a rank at or above your own weight, and the Mayor rank is
        // the mayor's own, so even they see it as read-only. That is not an accident of the
        // screen; it is why the Mayor rank always holds every permission.
        assertEquals(Material.BARRIER, menu.inventory().getItem(10).getType());
        assertEquals(Material.IRON_HELMET, menu.inventory().getItem(19).getType(),
                "Co-Mayor, one row down, is editable and rendered by weight");
    }

    @Test
    @DisplayName("SPEC 8.10: disband is behind a confirmation, not a single click")
    void disbandIsGuarded() {
        SettingsMenu menu = new SettingsMenu(support.menus, support.services, mayorPlayer, city,
                null);
        menu.open();

        listener.onClick(click(mayorPlayer, 34));

        assertTrue(support.menus.openMenu(mayorPlayer).orElseThrow()
                        instanceof dev.civitas.gui.framework.ConfirmationMenu,
                "one click opens a dialog, it does not disband anything");
        assertTrue(support.cities.registry.city(city.id()).isPresent(),
                "and the city is still there");
    }
}
