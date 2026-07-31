package dev.civitas.core.city;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.storage.row.CityInviteRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Joining, leaving, kicking, transferring and disbanding, SPEC 5.2, 5.3 and 17.1. */
class CityMembershipTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private UUID mayor;
    private City city;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    // ==================================================================================
    // Invites, SPEC 5.2
    // ==================================================================================

    @Nested
    @DisplayName("Invites")
    class Invites {

        @Test
        @DisplayName("an invited player joins and lands on the default rank")
        void inviteAndAccept() {
            UUID guest = support.givenEligiblePlayer("Titus");

            assertTrue(await(support.cities.invite(mayor, city, guest)).isSuccess());
            assertTrue(await(support.cities.acceptInvite(guest, city)).isSuccess());

            assertTrue(city.isMember(guest));
            assertEquals("Recruit", city.rankOf(guest).orElseThrow().name());
            assertEquals(city, support.registry.cityOf(guest).orElseThrow());
            assertEquals(city.id(), support.playerRow(guest).cityId());
        }

        @Test
        @DisplayName("joining without an invite is refused")
        void acceptWithoutInvite() {
            UUID guest = support.givenEligiblePlayer("Titus");

            assertEquals("NO_INVITE", reasonOf(await(support.cities.acceptInvite(guest, city))));
            assertFalse(city.isMember(guest));
        }

        @Test
        @DisplayName("an expired invite cannot be accepted")
        void expiredInvite() {
            UUID guest = support.givenEligiblePlayer("Titus");
            await(support.daos.cityInvites().upsert(
                    new CityInviteRow(city.id(), guest, mayor, System.currentTimeMillis() - 1_000L)));

            assertEquals("NO_INVITE", reasonOf(await(support.cities.acceptInvite(guest, city))));
        }

        @Test
        @DisplayName("denying an invite removes it")
        void denyInvite() {
            UUID guest = support.givenEligiblePlayer("Titus");
            await(support.cities.invite(mayor, city, guest));

            assertTrue(await(support.cities.denyInvite(guest, city)).isSuccess());
            assertEquals("NO_INVITE", reasonOf(await(support.cities.acceptInvite(guest, city))));
        }

        @Test
        @DisplayName("a member without INVITE cannot invite")
        void inviteNeedsPermission() {
            UUID member = support.givenMember(city, "Titus");
            UUID guest = support.givenEligiblePlayer("Marcus");

            assertEquals("NO_CITY_PERMISSION",
                    reasonOf(await(support.cities.invite(member, city, guest))));
        }

        @Test
        @DisplayName("a player already in a city cannot join a second one")
        void cannotJoinTwoCities() {
            UUID other = support.givenEligiblePlayer("Remus");
            City ostia = support.givenCity(other, "Ostia", 50, 50);

            await(support.cities.invite(mayor, city, other));
            assertEquals("ALREADY_IN_CITY", reasonOf(await(support.cities.acceptInvite(other, city))));
            assertTrue(ostia.isMember(other));
        }
    }

    // ==================================================================================
    // Open join, SPEC 5.2
    // ==================================================================================

    @Nested
    @DisplayName("Open join")
    class OpenJoin {

        @Test
        @DisplayName("an invite-only city refuses a walk-in")
        void closedByDefault() {
            UUID guest = support.givenEligiblePlayer("Titus");

            assertFalse(city.isOpenJoin());
            assertEquals("NOT_OPEN", reasonOf(await(support.cities.joinOpen(guest, city))));
        }

        @Test
        @DisplayName("an open city accepts a walk-in")
        void openAcceptsWalkIns() {
            assertTrue(await(support.cities.setOpenJoin(mayor, city, true)).isSuccess());
            UUID guest = support.givenEligiblePlayer("Titus");

            assertTrue(await(support.cities.joinOpen(guest, city)).isSuccess());
            assertTrue(city.isMember(guest));
        }

        @Test
        @DisplayName("SPEC 5.2: leaving locks a walk-in out of a different city for 24 hours")
        void switchCooldownBlocksWalkIns() {
            UUID wanderer = support.givenMember(city, "Titus");
            assertTrue(await(support.cities.leave(wanderer, city)).isSuccess());

            UUID otherMayor = support.givenEligiblePlayer("Remus");
            City ostia = support.givenCity(otherMayor, "Ostia", 50, 50);
            assertTrue(await(support.cities.setOpenJoin(otherMayor, ostia, true)).isSuccess());

            Result<City> result = await(support.cities.joinOpen(wanderer, ostia));

            assertEquals("SWITCH_COOLDOWN", reasonOf(result));
            assertEquals("24", ((Result.Failure<City>) result).placeholders().get("hours"));
        }

        @Test
        @DisplayName("an invite bypasses the cooldown, because the city asked for them back")
        void inviteBypassesCooldown() {
            UUID wanderer = support.givenMember(city, "Titus");
            assertTrue(await(support.cities.leave(wanderer, city)).isSuccess());

            assertTrue(await(support.cities.invite(mayor, city, wanderer)).isSuccess());
            assertTrue(await(support.cities.acceptInvite(wanderer, city)).isSuccess());
            assertTrue(city.isMember(wanderer));
        }
    }

    // ==================================================================================
    // Leaving and kicking, SPEC 5.3
    // ==================================================================================

    @Nested
    @DisplayName("Leaving and kicking")
    class LeavingAndKicking {

        @Test
        @DisplayName("a member leaves and is forgotten by the registry")
        void leave() {
            UUID member = support.givenMember(city, "Titus");

            assertTrue(await(support.cities.leave(member, city)).isSuccess());

            assertFalse(city.isMember(member));
            assertTrue(support.registry.cityOf(member).isEmpty());
            assertEquals(null, support.playerRow(member).cityId());
            assertTrue(support.playerRow(member).lastCityLeave() > 0,
                    "leaving must stamp the cooldown");
        }

        @Test
        @DisplayName("SPEC 17.1 case 4: the mayor cannot leave their own city")
        void case4MayorCannotLeave() {
            assertEquals("MAYOR_CANNOT_LEAVE", reasonOf(await(support.cities.leave(mayor, city))));
            assertTrue(city.isMember(mayor));
        }

        @Test
        @DisplayName("kicking requires KICK and outranking the target")
        void kickRules() {
            UUID member = support.givenMember(city, "Titus");
            UUID other = support.givenMember(city, "Marcus");

            assertEquals("NO_CITY_PERMISSION",
                    reasonOf(await(support.cities.kick(member, city, other))));

            assertTrue(await(support.cities.kick(mayor, city, member)).isSuccess());
            assertFalse(city.isMember(member));
        }

        @Test
        @DisplayName("nobody can kick themselves or the mayor")
        void kickSelfOrMayor() {
            assertEquals("CANNOT_KICK_SELF", reasonOf(await(support.cities.kick(mayor, city, mayor))));

            UUID member = support.givenMember(city, "Titus");
            CityRank coMayorRank = city.rankByName("Co-Mayor").orElseThrow();
            assertTrue(await(support.ranks.assign(mayor, city, member, coMayorRank)).isSuccess());

            assertEquals("CANNOT_KICK_MAYOR",
                    reasonOf(await(support.cities.kick(member, city, mayor))));
        }

        @Test
        @DisplayName("a member of equal rank cannot be kicked")
        void cannotKickPeers() {
            UUID first = support.givenMember(city, "Titus");
            UUID second = support.givenMember(city, "Marcus");

            CityRank coMayorRank = city.rankByName("Co-Mayor").orElseThrow();
            await(support.ranks.assign(mayor, city, first, coMayorRank));
            await(support.ranks.assign(mayor, city, second, coMayorRank));

            assertEquals("OUTRANKED", reasonOf(await(support.cities.kick(first, city, second))));
        }
    }

    // ==================================================================================
    // Transfer, SPEC 5.3 and 17.1 case 9
    // ==================================================================================

    @Nested
    @DisplayName("Mayorship transfer")
    class Transfer {

        @Test
        @DisplayName("an offer that is accepted moves mayorship and demotes the old mayor")
        void transferSucceeds() {
            UUID heir = support.givenMember(city, "Numa");

            assertTrue(support.cities.offerTransfer(mayor, city, heir, true).isSuccess());
            assertTrue(await(support.cities.acceptTransfer(heir)).isSuccess());

            assertTrue(city.isMayor(heir));
            assertFalse(city.isMayor(mayor));
            assertEquals("Mayor", city.rankOf(heir).orElseThrow().name());
            assertEquals("Co-Mayor", city.rankOf(mayor).orElseThrow().name(),
                    "the outgoing mayor keeps a seat rather than being cast out");
        }

        @Test
        @DisplayName("SPEC 17.1 case 9: an offline player cannot be handed mayorship")
        void case9OfflineTarget() {
            UUID heir = support.givenMember(city, "Numa");

            assertEquals("TARGET_OFFLINE",
                    reasonOf(support.cities.offerTransfer(mayor, city, heir, false)));
            assertTrue(city.isMayor(mayor));
        }

        @Test
        @DisplayName("only the mayor may offer, and never to themselves or a non-member")
        void offerRules() {
            UUID member = support.givenMember(city, "Numa");
            UUID stranger = support.givenEligiblePlayer("Outsider");

            assertEquals("NOT_MAYOR",
                    reasonOf(support.cities.offerTransfer(member, city, mayor, true)));
            assertEquals("CANNOT_TRANSFER_SELF",
                    reasonOf(support.cities.offerTransfer(mayor, city, mayor, true)));
            assertEquals("NOT_A_MEMBER",
                    reasonOf(support.cities.offerTransfer(mayor, city, stranger, true)));
        }

        @Test
        @DisplayName("accepting with no offer outstanding is refused")
        void acceptWithoutOffer() {
            UUID member = support.givenMember(city, "Numa");

            assertEquals("NO_TRANSFER_OFFER", reasonOf(await(support.cities.acceptTransfer(member))));
        }

        @Test
        @DisplayName("a cancelled offer cannot be accepted afterwards")
        void cancelledOffer() {
            UUID heir = support.givenMember(city, "Numa");
            support.cities.offerTransfer(mayor, city, heir, true);
            support.cities.cancelTransfer(heir);

            assertEquals("NO_TRANSFER_OFFER", reasonOf(await(support.cities.acceptTransfer(heir))));
        }

        @Test
        @DisplayName("the new mayor is persisted, not only cached")
        void transferIsPersisted() {
            UUID heir = support.givenMember(city, "Numa");
            support.cities.offerTransfer(mayor, city, heir, true);
            await(support.cities.acceptTransfer(heir));

            support.registry.clear();
            await(support.registry.loadAll());

            assertTrue(support.registry.cityByName("Roma").orElseThrow().isMayor(heir));
        }
    }

    // ==================================================================================
    // Disband, SPEC 5.3 and 17.1 case 10
    // ==================================================================================

    @Nested
    @DisplayName("Disband")
    class Disband {

        @Test
        @DisplayName("the city is soft-deleted, its land freed and its members released")
        void disbandClearsEverything() {
            UUID member = support.givenMember(city, "Titus");

            assertTrue(await(support.cities.disband(mayor, city)).isSuccess());

            assertTrue(support.registry.cityByName("Roma").isEmpty());
            assertTrue(support.registry.cityOf(member).isEmpty());
            assertEquals(0L, await(support.daos.claims().count()));
            assertEquals(0L, await(support.daos.cityMembers().count()));
            assertEquals(null, support.playerRow(member).cityId());

            // Soft, not hard: SPEC 5.3 gives admins a restore window.
            assertEquals(1L, await(support.daos.cities().count()));
            assertEquals(1, await(support.daos.cities().findDeletedSince(0L)).size());
        }

        @Test
        @DisplayName("SPEC 17.1 case 10: the treasury is split evenly among the members")
        void case10TreasuryIsSplit() {
            UUID member = support.givenMember(city, "Titus");
            await(support.daos.cities().updateTreasury(city.id(), new BigDecimal("1000.00")));
            city.setTreasury(new BigDecimal("1000.00"));

            BigDecimal mayorBefore = support.playerRow(mayor).balance();
            BigDecimal memberBefore = support.playerRow(member).balance();

            assertTrue(await(support.cities.disband(mayor, city)).isSuccess());

            assertEquals(0, mayorBefore.add(new BigDecimal("500.00"))
                    .compareTo(support.playerRow(mayor).balance()));
            assertEquals(0, memberBefore.add(new BigDecimal("500.00"))
                    .compareTo(support.playerRow(member).balance()));
        }

        @Test
        @DisplayName("a member without DISBAND cannot disband the city")
        void disbandNeedsPermission() {
            UUID member = support.givenMember(city, "Titus");

            assertEquals("NO_CITY_PERMISSION",
                    reasonOf(await(support.cities.disband(member, city))));
            assertTrue(support.registry.cityByName("Roma").isPresent());
        }

        @Test
        @DisplayName("the name is freed for reuse once the city is gone")
        void nameIsFreed() {
            assertTrue(await(support.cities.disband(mayor, city)).isSuccess());

            assertFalse(support.registry.isNameTaken("Roma"));
        }
    }

    // ==================================================================================
    // Settings
    // ==================================================================================

    @Test
    @DisplayName("renaming charges the treasury and reindexes the city")
    void rename() {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal("20000.00")));
        city.setTreasury(new BigDecimal("20000.00"));

        assertTrue(await(support.cities.rename(mayor, city, "Roma_Nova")).isSuccess());

        assertEquals("Roma_Nova", city.name());
        assertTrue(support.registry.cityByName("Roma_Nova").isPresent());
        assertFalse(support.registry.cityByName("Roma").isPresent());
        assertEquals(0, new BigDecimal("5000.00").compareTo(city.treasury()));
    }

    @Test
    @DisplayName("renaming is refused when the treasury cannot cover the fee")
    void renameNeedsFunds() {
        Result<City> result = await(support.cities.rename(mayor, city, "Roma_Nova"));

        assertEquals("TREASURY_SHORT", reasonOf(result));
        assertEquals("Roma", city.name());
    }

    @Test
    @DisplayName("a frozen city refuses every mutation")
    void frozenCityIsReadOnly() {
        city.setFrozen(true);
        UUID guest = support.givenEligiblePlayer("Titus");

        assertEquals("CITY_FROZEN", reasonOf(await(support.cities.invite(mayor, city, guest))));
        assertEquals("CITY_FROZEN", reasonOf(await(support.cities.disband(mayor, city))));
        assertEquals("CITY_FROZEN", reasonOf(await(support.cities.setOpenJoin(mayor, city, true))));
        assertEquals("CITY_FROZEN", reasonOf(await(support.ranks.create(mayor, city, "X", 10))));
    }
}
