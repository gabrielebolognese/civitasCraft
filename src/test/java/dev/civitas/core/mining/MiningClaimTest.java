package dev.civitas.core.mining;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.world.WorldRegistry;
import dev.civitas.storage.row.MiningClaimRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 32.6's personal mining claims.
 *
 * <p>The point of the feature is in one sentence of SPEC 32.6 — "This is the only form of land
 * ownership available to a player with no city, and it is deliberately available to them" — so
 * the first thing asserted is that a player with no city can stake one.
 */
class MiningClaimTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private MiningClaimService mines;
    private MiningClaimRegistry registry;
    private UUID miner;

    private static final String RESOURCE = "resource";
    private static final String MAIN = "world";
    private static final long NOON = 1_754_000_000_000L;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        registry = new MiningClaimRegistry(support.daos.miningClaims(),
                CityTestSupport.quietLogger());
        mines = new MiningClaimService(support.db, support.daos.miningClaims(), registry,
                support.economy, new WorldRegistry(support.configs), support.configs,
                Scheduler.direct());
        miner = support.givenPlayer("Fossor", new BigDecimal("100000.00"), 0L);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private Result<MiningClaimRow> claim(int x, int z) {
        return claim(miner, RESOURCE, x, z);
    }

    private Result<MiningClaimRow> claim(UUID who, String world, int x, int z) {
        return await(mines.claim(who, world, x, z, mines.baseLimit(), NOON));
    }

    private BigDecimal wallet(UUID player) {
        return support.playerRow(player).balance();
    }

    // ==================================================================================
    // The point of the feature
    // ==================================================================================

    @Nested
    @DisplayName("the only land a player without a city can own, SPEC 32.6")
    class Claiming {

        @Test
        @DisplayName("a player with no city can stake one")
        void noCityRequired() {
            // The sentence the whole feature exists for. A city is not a precondition anywhere
            // in this path, and asserting it stops one being added by accident later.
            assertTrue(support.registry.cityOf(miner).isEmpty(), "the fixture has no city");

            Result<MiningClaimRow> result = claim(0, 0);

            assertTrue(result.isSuccess(), reasonOf(result));
            assertEquals(miner, result.orElseThrow().uuid());
        }

        @Test
        @DisplayName("it costs SPEC 32.6's price, taken from their own balance")
        void costsFromPersonalBalance() {
            // Personal, not a treasury. That is what makes it available to somebody with no city.
            BigDecimal before = wallet(miner);
            claim(0, 0);

            assertEquals(0, before.subtract(wallet(miner)).compareTo(mines.cost()));
            assertEquals(0, mines.cost().compareTo(new BigDecimal("15000")));
        }

        @Test
        @DisplayName("a player who cannot afford it is refused and charged nothing")
        void cannotAfford() {
            UUID pauper = support.givenPlayer("Pauper", new BigDecimal("10.00"), 0L);

            Result<MiningClaimRow> result = claim(pauper, RESOURCE, 5, 5);

            assertTrue(result instanceof Result.Failure, "claimed with no money");
            assertEquals(0, wallet(pauper).compareTo(new BigDecimal("10.00")));
            assertFalse(registry.isClaimed(RESOURCE, 5, 5),
                    "the whole transaction rolled back, so no row survived either");
        }

        @Test
        @DisplayName("only in the resource worlds, SPEC 32.6")
        void resourceWorldsOnly() {
            assertEquals("WRONG_WORLD", reasonOf(claim(miner, MAIN, 0, 0)));
            assertEquals("WRONG_WORLD", reasonOf(claim(miner, "world_nether", 0, 0)));
            assertTrue(claim(miner, "resource_nether", 0, 0).isSuccess());
        }

        @Test
        @DisplayName("one chunk each, and the limit is enforced")
        void baseLimitIsOne() {
            assertEquals(1, mines.baseLimit());
            assertTrue(claim(0, 0).isSuccess());

            assertEquals("AT_LIMIT", reasonOf(claim(10, 10)));
        }

        @Test
        @DisplayName("a higher limit lets a player hold more, SPEC 32.6's permission node")
        void limitIsRaisable() {
            // "1 per player, 2 with civitas.limit.miningclaims.2." The service takes the limit
            // rather than reading the node, so the permission lives with the command.
            assertTrue(await(mines.claim(miner, RESOURCE, 0, 0, 2, NOON)).isSuccess());
            assertTrue(await(mines.claim(miner, RESOURCE, 10, 10, 2, NOON)).isSuccess());
            assertEquals(2, registry.ownedBy(miner).size());
        }

        @Test
        @DisplayName("two players cannot own one chunk")
        void oneOwnerPerChunk() {
            UUID other = support.givenPlayer("Alter", new BigDecimal("100000.00"), 0L);
            assertTrue(claim(0, 0).isSuccess());

            Result<MiningClaimRow> second = claim(other, RESOURCE, 0, 0);

            assertEquals("ALREADY_CLAIMED", reasonOf(second));
            assertEquals(0, wallet(other).compareTo(new BigDecimal("100000.00")),
                    "the loser of the race pays nothing");
        }

        @Test
        @DisplayName("every figure is configurable")
        void configurable() {
            support.configs.get(ConfigFile.WORLD).set("mining-claims.cost", 99);
            support.configs.get(ConfigFile.WORLD).set("mining-claims.base-limit", 3);

            assertEquals(0, mines.cost().compareTo(new BigDecimal("99")));
            assertEquals(3, mines.baseLimit());
        }
    }

    // ==================================================================================
    // Protection
    // ==================================================================================

    @Nested
    @DisplayName("protection and trust, SPEC 32.6")
    class Protection {

        private UUID friend;

        @BeforeEach
        void setUp() {
            friend = support.givenPlayer("Amicus", BigDecimal.ZERO, 0L);
            claim(0, 0);
        }

        @Test
        @DisplayName("the owner may build, a stranger may not")
        void ownershipDecides() {
            assertTrue(registry.mayBuild(miner, RESOURCE, 0, 0));
            assertFalse(registry.mayBuild(friend, RESOURCE, 0, 0));
        }

        @Test
        @DisplayName("an unclaimed chunk in a resource world is open to anyone")
        void unclaimedIsOpen() {
            // SPEC 32.5 says it outright: everything outside a mining claim is unprotected.
            assertTrue(registry.mayBuild(friend, RESOURCE, 50, 50));
        }

        @Test
        @DisplayName("a trusted player may build")
        void trustGrantsAccess() {
            assertTrue(await(mines.trust(miner, friend, NOON)).isSuccess());

            assertTrue(registry.mayBuild(friend, RESOURCE, 0, 0));
        }

        @Test
        @DisplayName("trust covers everything the owner holds, not one claim")
        void trustIsPerOwner() {
            // SPEC 32.6's /mine trust takes no claim argument, so it cannot be per claim.
            await(mines.claim(miner, RESOURCE, 20, 20, 2, NOON));
            await(mines.trust(miner, friend, NOON));

            assertTrue(registry.mayBuild(friend, RESOURCE, 0, 0));
            assertTrue(registry.mayBuild(friend, RESOURCE, 20, 20));
        }

        @Test
        @DisplayName("untrusting takes it away again")
        void untrust() {
            await(mines.trust(miner, friend, NOON));
            assertTrue(await(mines.untrust(miner, friend)).isSuccess());

            assertFalse(registry.mayBuild(friend, RESOURCE, 0, 0));
        }

        @Test
        @DisplayName("at most four trusted players, SPEC 32.6")
        void trustLimit() {
            for (int i = 0; i < mines.maxTrusted(); i++) {
                UUID extra = support.givenPlayer("Trusted" + i, BigDecimal.ZERO, 0L);
                assertTrue(await(mines.trust(miner, extra, NOON)).isSuccess(),
                        "grant " + (i + 1) + " of " + mines.maxTrusted() + " was refused");
            }
            assertEquals("TRUST_FULL", reasonOf(await(mines.trust(miner, friend, NOON))));
            assertEquals(4, mines.maxTrusted(), "SPEC 32.6's figure");
        }

        @Test
        @DisplayName("trusting yourself and trusting twice are both refused")
        void degenerateGrants() {
            assertEquals("SELF_TRUST", reasonOf(await(mines.trust(miner, miner, NOON))));
            await(mines.trust(miner, friend, NOON));
            assertEquals("ALREADY_TRUSTED", reasonOf(await(mines.trust(miner, friend, NOON))));
            assertEquals("NOT_TRUSTED", reasonOf(await(mines.untrust(miner,
                    UUID.randomUUID()))));
        }
    }

    // ==================================================================================
    // Releasing
    // ==================================================================================

    @Nested
    @DisplayName("releasing a claim")
    class Releasing {

        @Test
        @DisplayName("unclaiming refunds half of what was paid, to the player")
        void refundsHalf() {
            claim(0, 0);
            BigDecimal before = wallet(miner);

            Result<MiningClaimRow> result = await(mines.unclaim(miner, RESOURCE, 0, 0));

            assertTrue(result.isSuccess(), reasonOf(result));
            assertEquals(0, wallet(miner).subtract(before)
                            .compareTo(mines.cost().divide(new BigDecimal("2"),
                                    java.math.RoundingMode.DOWN)),
                    "half, matching SPEC 6.4's refund on city land");
            assertFalse(registry.isClaimed(RESOURCE, 0, 0));
        }

        @Test
        @DisplayName("the refund is of the price paid, not of the current price")
        void refundsWhatWasPaid() {
            // SPEC 21.4 F2's rule: a discount must not be launderable into a full-price refund.
            support.configs.get(ConfigFile.WORLD).set("mining-claims.cost", 1000);
            claim(0, 0);
            support.configs.get(ConfigFile.WORLD).set("mining-claims.cost", 50_000);

            BigDecimal before = wallet(miner);
            await(mines.unclaim(miner, RESOURCE, 0, 0));

            assertEquals(0, wallet(miner).subtract(before).compareTo(new BigDecimal("500")),
                    "refunded half of 1000, not half of 50000");
        }

        @Test
        @DisplayName("only the owner may unclaim")
        void ownerOnly() {
            claim(0, 0);
            UUID other = support.givenPlayer("Alter", BigDecimal.ZERO, 0L);

            assertEquals("NOT_OWNER", reasonOf(await(mines.unclaim(other, RESOURCE, 0, 0))));
            assertTrue(registry.isClaimed(RESOURCE, 0, 0));
        }

        @Test
        @DisplayName("unclaiming nothing is refused rather than silently fine")
        void nothingToUnclaim() {
            assertEquals("NOT_CLAIMED", reasonOf(await(mines.unclaim(miner, RESOURCE, 9, 9))));
        }
    }

    // ==================================================================================
    // Upkeep, SPEC 32.6
    // ==================================================================================

    @Nested
    @DisplayName("upkeep and its grace, SPEC 32.6")
    class Upkeep {

        @Test
        @DisplayName("upkeep comes out of the owner's own balance")
        void chargesTheOwner() {
            claim(0, 0);
            BigDecimal before = wallet(miner);

            await(mines.chargeUpkeep(NOON));

            assertEquals(0, before.subtract(wallet(miner)).compareTo(mines.upkeepPerDay()));
            assertEquals(0, mines.upkeepPerDay().compareTo(new BigDecimal("500")));
        }

        @Test
        @DisplayName("a player who cannot pay starts the grace clock rather than losing it")
        void startsGrace() {
            claim(0, 0);
            await(support.economy.setBalance(miner, BigDecimal.ZERO,
                    dev.civitas.core.economy.TransactionType.ADMIN_SET, "test"));

            await(mines.chargeUpkeep(NOON));

            MiningClaimRow row = registry.at(RESOURCE, 0, 0).orElseThrow();
            assertTrue(row.isDelinquent(), "the clock did not start");
            assertEquals(NOON, row.delinquentSince());
            assertTrue(registry.isClaimed(RESOURCE, 0, 0), "and the claim is still theirs");
        }

        @Test
        @DisplayName("the clock is from the FIRST missed payment, not the latest")
        void graceDoesNotRestart() {
            // Otherwise a player who cannot pay for a month never runs out of grace, because
            // every sweep would push the deadline back a day.
            claim(0, 0);
            await(support.economy.setBalance(miner, BigDecimal.ZERO,
                    dev.civitas.core.economy.TransactionType.ADMIN_SET, "test"));

            await(mines.chargeUpkeep(NOON));
            await(mines.chargeUpkeep(NOON + 86_400_000L));

            assertEquals(NOON, registry.at(RESOURCE, 0, 0).orElseThrow().delinquentSince());
        }

        @Test
        @DisplayName("paying again clears the clock")
        void payingClearsGrace() {
            claim(0, 0);
            await(support.economy.setBalance(miner, BigDecimal.ZERO,
                    dev.civitas.core.economy.TransactionType.ADMIN_SET, "test"));
            await(mines.chargeUpkeep(NOON));
            assertTrue(registry.at(RESOURCE, 0, 0).orElseThrow().isDelinquent());

            await(support.economy.setBalance(miner, new BigDecimal("10000"),
                    dev.civitas.core.economy.TransactionType.ADMIN_SET, "test"));
            await(mines.chargeUpkeep(NOON + 3_600_000L));

            assertFalse(registry.at(RESOURCE, 0, 0).orElseThrow().isDelinquent());
        }

        @Test
        @DisplayName("past the grace it is released, and no refund is paid")
        void releasedAfterGrace() {
            claim(0, 0);
            await(support.economy.setBalance(miner, BigDecimal.ZERO,
                    dev.civitas.core.economy.TransactionType.ADMIN_SET, "test"));
            await(mines.chargeUpkeep(NOON));

            BigDecimal before = wallet(miner);
            int released = await(mines.chargeUpkeep(NOON + mines.graceMillis() + 1));

            assertEquals(1, released);
            assertFalse(registry.isClaimed(RESOURCE, 0, 0));
            assertEquals(0, wallet(miner).compareTo(before),
                    "no refund: the upkeep was never paid");
        }

        @Test
        @DisplayName("the grace is SPEC 32.6's seven days, and is configurable")
        void graceIsConfigurable() {
            assertEquals(7 * 86_400_000L, mines.graceMillis());

            support.configs.get(ConfigFile.WORLD).set("mining-claims.grace-days", 2);
            assertEquals(2 * 86_400_000L, mines.graceMillis());
        }

        @Test
        @DisplayName("a released claim leaves its blocks: nothing here touches the world")
        void blocksSurvive() {
            // SPEC 32.6: "7-day grace, then released. Blocks are not removed." Asserted the only
            // way a unit test can — that the service holds no reference to a World at all, so it
            // has no way to remove anything.
            String source = readSource(
                    "src/main/java/dev/civitas/core/mining/MiningClaimService.java");

            assertFalse(source.contains("org.bukkit.World"),
                    "MiningClaimService reached for a World, which is how blocks get removed");
            assertFalse(source.contains("setType("),
                    "MiningClaimService sets block types, which SPEC 32.6 forbids on release");
        }
    }

    // ==================================================================================
    // The registry, on the hot path
    // ==================================================================================

    @Nested
    @DisplayName("the registry")
    class Registry {

        @Test
        @DisplayName("it survives a reload")
        void persisted() {
            claim(0, 0);
            await(mines.trust(miner, support.givenPlayer("Amicus", BigDecimal.ZERO, 0L), NOON));

            MiningClaimRegistry reopened = new MiningClaimRegistry(support.daos.miningClaims(),
                    CityTestSupport.quietLogger());
            await(reopened.loadAll());

            assertTrue(reopened.isClaimed(RESOURCE, 0, 0));
            assertEquals(1, reopened.ownedBy(miner).size());
            assertEquals(1, reopened.trustedBy(miner).size());
        }

        @Test
        @DisplayName("world names are matched however they are capitalised")
        void caseInsensitive() {
            claim(0, 0);

            assertTrue(registry.isClaimed("RESOURCE", 0, 0));
            assertTrue(registry.isClaimed("Resource", 0, 0));
        }

        @Test
        @DisplayName("an empty registry claims nothing and permits everything")
        void emptyRegistry() {
            assertEquals(0, registry.count());
            assertFalse(registry.isClaimed(RESOURCE, 0, 0));
            assertTrue(registry.mayBuild(UUID.randomUUID(), RESOURCE, 0, 0));
        }

        @Test
        @DisplayName("a null world is not a claim rather than an exception")
        void nullWorld() {
            assertFalse(registry.isClaimed(null, 0, 0));
        }
    }

    private static String readSource(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
