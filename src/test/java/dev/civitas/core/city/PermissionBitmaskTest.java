package dev.civitas.core.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * SPEC 18.1: "Permission bitmask: grant, revoke, cannot grant what you lack, cannot edit
 * equal or higher weight."
 *
 * <p>This file covers grant and revoke and the bit layout itself. The two rank rules are in
 * {@link RankRulesTest}, where a city exists to check them against.
 */
class PermissionBitmaskTest {

    @Test
    @DisplayName("SPEC 5.4 defines exactly 22 flags")
    void flagCount() {
        assertEquals(22, CityPermission.values().length);
        assertEquals(22, PermissionSet.ALL.size());
    }

    @ParameterizedTest
    @EnumSource(CityPermission.class)
    @DisplayName("each flag occupies the bit index SPEC 5.4 assigns it")
    void bitLayoutMatchesTheSpecification(CityPermission permission) {
        assertEquals(1L << permission.bitIndex(), permission.mask());
        assertEquals(1, Long.bitCount(permission.mask()), "a flag must own exactly one bit");
    }

    @Test
    @DisplayName("the documented bit indices are the ones in use")
    void documentedIndices() {
        assertEquals(0, CityPermission.BUILD.bitIndex());
        assertEquals(1, CityPermission.CONTAINER.bitIndex());
        assertEquals(2, CityPermission.CONTAINER_READONLY.bitIndex());
        assertEquals(3, CityPermission.INTERACT.bitIndex());
        assertEquals(4, CityPermission.CLAIM.bitIndex());
        assertEquals(5, CityPermission.UNCLAIM.bitIndex());
        assertEquals(6, CityPermission.INVITE.bitIndex());
        assertEquals(7, CityPermission.KICK.bitIndex());
        assertEquals(8, CityPermission.MANAGE_RANKS.bitIndex());
        assertEquals(9, CityPermission.DEPOSIT.bitIndex());
        assertEquals(10, CityPermission.WITHDRAW.bitIndex());
        assertEquals(11, CityPermission.SET_SPAWN.bitIndex());
        assertEquals(12, CityPermission.OUTPOST_MANAGE.bitIndex());
        assertEquals(13, CityPermission.OUTPOST_TP.bitIndex());
        assertEquals(14, CityPermission.DECLARE_WAR.bitIndex());
        assertEquals(15, CityPermission.MANAGE_DIPLOMACY.bitIndex());
        assertEquals(16, CityPermission.MANAGE_DEFENSE.bitIndex());
        assertEquals(17, CityPermission.MANAGE_UPGRADES.bitIndex());
        assertEquals(18, CityPermission.EDIT_SETTINGS.bitIndex());
        assertEquals(19, CityPermission.CONTEST_SUBMIT.bitIndex());
        assertEquals(20, CityPermission.TRANSFER.bitIndex());
        assertEquals(21, CityPermission.DISBAND.bitIndex());
    }

    @Test
    @DisplayName("no two flags share a bit, which would make one silently grant the other")
    void noSharedBits() {
        long seen = 0L;
        for (CityPermission permission : CityPermission.values()) {
            assertEquals(0L, seen & permission.mask(), permission + " collides with another flag");
            seen |= permission.mask();
        }
        assertEquals(seen, PermissionSet.ALL.bits());
    }

    @Test
    @DisplayName("granting adds a flag and leaves the others alone")
    void grant() {
        PermissionSet set = PermissionSet.NONE.with(CityPermission.BUILD);
        assertTrue(set.has(CityPermission.BUILD));
        assertFalse(set.has(CityPermission.DISBAND));
        assertEquals(1, set.size());

        set = set.with(CityPermission.CONTAINER, CityPermission.INTERACT);
        assertEquals(3, set.size());
        assertTrue(set.has(CityPermission.BUILD));
    }

    @Test
    @DisplayName("granting a flag twice is the same as granting it once")
    void grantIsIdempotent() {
        PermissionSet once = PermissionSet.NONE.with(CityPermission.BUILD);
        assertEquals(once, once.with(CityPermission.BUILD));
    }

    @Test
    @DisplayName("revoking removes a flag and leaves the others alone")
    void revoke() {
        PermissionSet set = PermissionSet.of(CityPermission.BUILD, CityPermission.CONTAINER)
                .without(CityPermission.BUILD);

        assertFalse(set.has(CityPermission.BUILD));
        assertTrue(set.has(CityPermission.CONTAINER));
        assertEquals(1, set.size());
    }

    @Test
    @DisplayName("revoking a flag that was never granted changes nothing")
    void revokeIsIdempotent() {
        PermissionSet set = PermissionSet.of(CityPermission.BUILD);
        assertEquals(set, set.without(CityPermission.DISBAND));
    }

    @Test
    @DisplayName("set() grants or revokes depending on the flag it is given")
    void toggle() {
        PermissionSet granted = PermissionSet.NONE.set(CityPermission.CLAIM, true);
        assertTrue(granted.has(CityPermission.CLAIM));
        assertFalse(granted.set(CityPermission.CLAIM, false).has(CityPermission.CLAIM));
    }

    @Test
    @DisplayName("allExcept builds the SPEC 5.4 Co-Mayor rank")
    void coMayorDefaults() {
        PermissionSet coMayor = PermissionSet.allExcept(CityPermission.DISBAND,
                CityPermission.TRANSFER, CityPermission.MANAGE_RANKS);

        assertEquals(19, coMayor.size());
        assertFalse(coMayor.has(CityPermission.DISBAND));
        assertFalse(coMayor.has(CityPermission.TRANSFER));
        assertFalse(coMayor.has(CityPermission.MANAGE_RANKS));
        assertTrue(coMayor.has(CityPermission.DECLARE_WAR));
        assertTrue(coMayor.has(CityPermission.WITHDRAW));
    }

    @Test
    @DisplayName("containsAll answers whether one set covers another")
    void containsAll() {
        PermissionSet mayor = PermissionSet.ALL;
        PermissionSet citizen = PermissionSet.of(CityPermission.BUILD, CityPermission.CONTAINER);

        assertTrue(mayor.containsAll(citizen));
        assertFalse(citizen.containsAll(mayor));
        assertTrue(citizen.containsAll(citizen));
        assertTrue(citizen.containsAll(PermissionSet.NONE));
    }

    @Test
    @DisplayName("missingFrom names exactly the flags the holder lacks")
    void missingFrom() {
        PermissionSet held = PermissionSet.of(CityPermission.BUILD, CityPermission.CONTAINER);
        PermissionSet wanted = PermissionSet.of(CityPermission.BUILD, CityPermission.DISBAND,
                CityPermission.DECLARE_WAR);

        Set<CityPermission> missing = held.missingFrom(wanted).toSet();

        assertEquals(EnumSet.of(CityPermission.DISBAND, CityPermission.DECLARE_WAR), missing);
        assertTrue(held.missingFrom(held).isEmpty());
    }

    @Test
    @DisplayName("a mask from another version cannot grant a flag that does not exist here")
    void unknownBitsAreDiscarded() {
        // Bit 40 belongs to no flag. A future version might define it; this one must not
        // treat it as anything.
        PermissionSet fromFuture = new PermissionSet(PermissionSet.ALL.bits() | (1L << 40));

        assertEquals(PermissionSet.ALL, fromFuture);
        assertEquals(22, fromFuture.size());
    }

    @Test
    @DisplayName("a bitmask survives a round trip through storage unchanged")
    void roundTripsThroughALong() {
        PermissionSet original = PermissionSet.of(CityPermission.BUILD, CityPermission.CLAIM,
                CityPermission.WITHDRAW, CityPermission.DISBAND);

        assertEquals(original, new PermissionSet(original.bits()));
        assertNotEquals(original, new PermissionSet(original.bits() | CityPermission.KICK.mask()));
    }

    @Test
    @DisplayName("flag names parse case-insensitively and with either separator")
    void parsing() {
        assertEquals(CityPermission.MANAGE_RANKS, CityPermission.parse("MANAGE_RANKS").orElseThrow());
        assertEquals(CityPermission.MANAGE_RANKS, CityPermission.parse("manage_ranks").orElseThrow());
        assertEquals(CityPermission.MANAGE_RANKS, CityPermission.parse("manage-ranks").orElseThrow());
        assertEquals(CityPermission.MANAGE_RANKS, CityPermission.parse("  Manage-Ranks  ").orElseThrow());

        assertTrue(CityPermission.parse("FLY").isEmpty());
        assertTrue(CityPermission.parse(null).isEmpty());
    }

    @Test
    @DisplayName("the configured default ranks parse to the SPEC 5.4 permission sets")
    void configuredRankSyntax() {
        assertEquals(PermissionSet.ALL, CityService.parsePermissions(List.of("ALL")));

        assertEquals(PermissionSet.allExcept(CityPermission.DISBAND, CityPermission.TRANSFER,
                        CityPermission.MANAGE_RANKS),
                CityService.parsePermissions(
                        List.of("ALL_EXCEPT", "DISBAND", "TRANSFER", "MANAGE_RANKS")));

        assertEquals(PermissionSet.of(CityPermission.BUILD, CityPermission.CONTAINER),
                CityService.parsePermissions(List.of("BUILD", "CONTAINER")));

        assertEquals(PermissionSet.NONE, CityService.parsePermissions(List.of()));

        // A typo costs one permission, not every city's ability to be founded.
        assertEquals(PermissionSet.of(CityPermission.BUILD),
                CityService.parsePermissions(List.of("BUILD", "BUILDD")));
    }
}
