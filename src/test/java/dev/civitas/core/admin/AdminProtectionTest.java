package dev.civitas.core.admin;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.protection.ProtectionAction;
import dev.civitas.core.war.War;
import dev.civitas.core.war.WarRegistry;
import dev.civitas.core.war.WarRestrictions;
import dev.civitas.core.war.WarState;
import dev.civitas.core.war.WarZone;
import dev.civitas.storage.row.WarRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 9.4.3's admin-protected chunks, and the seam they close.
 *
 * <h2>The last seam in the plugin</h2>
 * {@code ClaimService.isAdminProtected} answered {@code false} from M3 until this milestone.
 * That was honest — the command that would set it was assigned here — but it means the three
 * rules that read it have never been exercised, and a seam that is wired but never tested is
 * indistinguishable from one that was forgotten.
 *
 * <p>SPEC 9.4.3 gives protection three separate consequences and they are genuinely different
 * rules, so each is tested on its own: unclaimable (SPEC 6.3 precondition 10), unbuildable
 * (stronger than a claim, because it applies to the owning city too), and war-immune (SPEC 11.6,
 * alongside the City Hall and defense unit spawners).
 */
class AdminProtectionTest {

    @TempDir
    Path directory;

    private static final long NOW = System.currentTimeMillis();
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");

    private CityTestSupport support;
    private AdminProtection protection;
    private City city;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        protection = new AdminProtection(support.daos.protectedChunks(), quiet());
        city = support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
        support.claims.useAdminProtection(protection);
        support.protection.useAdminProtection(protection);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("protection-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private void protect(String world, int chunkX, int chunkZ) {
        assertTrue(await(protection.protect(world, chunkX, chunkZ, ADMIN, "spawn area")));
    }

    // ==================================================================================
    // The cache itself
    // ==================================================================================

    @Nested
    @DisplayName("the protected set")
    class TheSet {

        @Test
        @DisplayName("a protected chunk is remembered and an unprotected one is not")
        void protectAndUnprotect() {
            assertFalse(protection.isProtected("world", 5, 5));

            protect("world", 5, 5);
            assertTrue(protection.isProtected("world", 5, 5));

            assertTrue(await(protection.unprotect("world", 5, 5)));
            assertFalse(protection.isProtected("world", 5, 5));
        }

        @Test
        @DisplayName("it survives a restart, because the cache is loaded from the table")
        void survivesAReload() {
            // An admin told it worked and finding it gone tomorrow has lost the build they
            // were protecting, which is why the write happens before the cache is updated.
            protect("world", 6, 6);

            AdminProtection reloaded = new AdminProtection(support.daos.protectedChunks(),
                    quiet());
            assertEquals(1, await(reloaded.loadAll()).intValue());

            assertTrue(reloaded.isProtected("world", 6, 6));
        }

        @Test
        @DisplayName("protecting twice is not an error")
        void idempotent() {
            protect("world", 7, 7);
            assertTrue(await(protection.protect("world", 7, 7, ADMIN, "again")));
            assertEquals(1, protection.count());
        }

        @Test
        @DisplayName("worlds do not collide, so the same chunk in two worlds is two chunks")
        void worldsAreDistinct() {
            protect("world", 8, 8);

            assertFalse(protection.isProtected("world_nether", 8, 8));
        }

        @Test
        @DisplayName("a block position resolves to its chunk")
        void blockToChunk() {
            protect("world", 2, 3);

            assertTrue(protection.isProtectedAtBlock("world", 2 * 16 + 8, 3 * 16 + 8));
            assertFalse(protection.isProtectedAtBlock("world", 100 * 16, 3 * 16));
        }

        @Test
        @DisplayName("an empty set answers without a lookup, which is the common case")
        void emptyIsCheap() {
            assertEquals(0, protection.count());
            assertFalse(protection.isProtected("world", 0, 0));
        }
    }

    // ==================================================================================
    // Unclaimable, SPEC 6.3 precondition 10
    // ==================================================================================

    @Test
    @DisplayName("SPEC 6.3 precondition 10: a protected chunk cannot be claimed")
    void unclaimable() {
        protect("world", 1, 0);

        Result<?> refused = await(support.claims.claim(city.mayorUuid(), city, "world", 1, 0));

        assertEquals("ADMIN_PROTECTED", reasonOf(refused));
    }

    @Test
    @DisplayName("and can be claimed again once the protection is lifted")
    void claimableAfterUnprotect() {
        // Funded, because with the protection gone the claim reaches the ordinary
        // preconditions and an empty treasury would refuse it for a different reason.
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal("100000.00")));
        city.setTreasury(new BigDecimal("100000.00"));

        protect("world", 1, 0);
        await(protection.unprotect("world", 1, 0));

        Result<?> claimed = await(support.claims.claim(city.mayorUuid(), city, "world", 1, 0));
        assertTrue(claimed.isSuccess(), reasonOf(claimed));
    }

    // ==================================================================================
    // Unbuildable
    // ==================================================================================

    @Nested
    @DisplayName("unbuildable")
    class Unbuildable {

        @Test
        @DisplayName("nobody may build there, including the city that owns the ground")
        void evenTheOwner() {
            // The rule that makes protection useful for a spawn area. A claim answers "may
            // this player build here"; this answers "may anybody", and the owning city is
            // exactly the party a claim would have said yes to.
            protect("world", 0, 0);

            assertFalse(support.protection.check(city.mayorUuid(), false, "world", 0, 0,
                    ProtectionAction.BUILD).allowed());
        }

        @Test
        @DisplayName("wilderness is protected too, which a claim could never do")
        void protectsWilderness() {
            protect("world", 500, 500);

            assertFalse(support.protection.check(city.mayorUuid(), false, "world", 500, 500,
                    ProtectionAction.BUILD).allowed());
        }

        @Test
        @DisplayName("an admin with bypass can still reach it")
        void bypassStillWorks() {
            // An admin who protected a chunk by mistake has to be able to undo the build they
            // were fixing, so bypass is checked before protection.
            protect("world", 0, 0);

            assertTrue(support.protection.check(city.mayorUuid(), true, "world", 0, 0,
                    ProtectionAction.BUILD).allowed());
        }

        @Test
        @DisplayName("an unprotected chunk of the same city is unaffected")
        void neighboursAreFine() {
            protect("world", 500, 500);

            assertTrue(support.protection.check(city.mayorUuid(), false, "world", 0, 0,
                    ProtectionAction.BUILD).allowed());
        }
    }

    // ==================================================================================
    // War-immune, SPEC 11.6
    // ==================================================================================

    @Test
    @DisplayName("SPEC 11.6: a protected chunk survives a war it sits inside")
    void warImmune() {
        // The consequence that would otherwise be invisible until somebody's protected build
        // was flattened. The rollback would put it back, but "destroyed and restored" is not
        // what SPEC 11.6 promises: it promises never touched.
        City enemy = support.givenCity(support.givenEligiblePlayer("Dido"), "Carthago", 40, 40);
        WarRegistry registry = new WarRegistry(support.daos.wars());
        WarRestrictions restrictions = new WarRestrictions(registry, support.registry);
        restrictions.useAdminProtection(protection);

        BigDecimal wager = new BigDecimal("50000.00");
        int id = await(support.daos.wars().insert(new WarRow(0, city.id(), enemy.id(),
                NOW, NOW + 1000L, NOW + 2000L, WarState.ACTIVE.key(), 0, 0, null, wager,
                null, null, 0)));
        War war = new War(id, city.id(), enemy.id(), NOW, NOW + 1000L, NOW + 2000L,
                WarState.ACTIVE, wager);
        war.zone(WarZone.of(List.of(new dev.civitas.core.claim.Claim(1L, enemy.id(), "world",
                40, 40, NOW, enemy.mayorUuid(), BigDecimal.ZERO,
                dev.civitas.core.claim.ClaimType.CORE, null)), 0));
        registry.remember(war);

        int blockX = 40 * 16 + 8;
        int blockZ = 40 * 16 + 8;
        assertTrue(restrictions.isGriefPermitted(enemy.id(), city.mayorUuid(), "world",
                        blockX, blockZ),
                "an ordinary chunk of the defender is fair game");

        protect("world", 40, 40);

        assertFalse(restrictions.isGriefPermitted(enemy.id(), city.mayorUuid(), "world",
                        blockX, blockZ),
                "SPEC 11.6: admin-protected chunks stay protected even in war");
    }
}
