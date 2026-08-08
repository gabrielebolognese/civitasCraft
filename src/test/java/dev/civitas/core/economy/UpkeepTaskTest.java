package dev.civitas.core.economy;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.row.CityRow;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The daily upkeep sweep: SPEC 17.3 case 31 (cycles missed while the server was down),
 * case 32 (a treasury that cannot pay) and case 33 (two cycles firing in one tick).
 *
 * <p>Time is passed in rather than read, so a seven-day catch-up runs in a millisecond.
 */
class UpkeepTaskTest {

    private static final long DAY = 86_400_000L;
    private static final String WORLD = "world";

    @TempDir
    Path directory;

    private CityTestSupport support;
    private UpkeepTask task;
    private RecordingNotifier warnings;
    private UUID mayor;
    private City city;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        warnings = new RecordingNotifier();

        task = new UpkeepTask(support.db, support.daos, support.registry, support.claims,
                support.treasury, support.upkeep, warnings, Scheduler.direct(),
                CityTestSupport.quietLogger(), ZoneId.of("UTC"));

        mayor = support.givenPlayer("Romulus", new BigDecimal("5000000.00"), 36_000_000L);
        city = support.givenCity(mayor, "Roma", 0, 0);

        // A line of claims eastward from the core, giving the city land to be taxed on and
        // outer chunks that may legally be sold when it cannot pay. Claims are paid for out
        // of the treasury, which a new city starts empty.
        fundTreasury("500000.00");
        for (int x = 1; x <= 6; x++) {
            assertTrue(await(support.claims.claim(mayor, city, WORLD, x, 0)).isSuccess(),
                    "fixture claim " + x);
        }
        fundTreasury("100000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private void fundTreasury(String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    private CityRow row() {
        return await(support.daos.cities().findById(city.id())).orElseThrow();
    }

    /** Moves the city's next charge to a moment in the past, in both storage and cache. */
    private void dueAt(long moment) {
        CityRow current = row();
        await(support.daos.cities().update(new CityRow(current.id(), current.name(),
                current.displayName(), current.tag(), current.mayorUuid(), current.foundedAt(),
                current.treasury(), current.coreWorld(), current.coreChunkX(),
                current.coreChunkZ(), current.spawnX(), current.spawnY(), current.spawnZ(),
                current.spawnYaw(), current.spawnPitch(), current.openJoin(), current.motd(),
                moment, current.delinquentSince(), current.warProtectionUntil(),
                current.frozen(), current.deletedAt())));
        city.setUpkeepDue(moment);
    }

    private List<LedgerRow> ledger(TransactionType type) {
        return await(support.daos.ledger().findByType(type.name(), 0L, 100));
    }

    private BigDecimal owed() {
        return task.amountFor(city);
    }

    // ==================================================================================
    // A city that can pay
    // ==================================================================================

    @Nested
    @DisplayName("Charging")
    class Charging {

        @Test
        @DisplayName("a city that is not due yet is left alone")
        void notDueYet() {
            long now = 2_000_000_000_000L;
            dueAt(now + DAY);

            assertEquals(0, task.sweep(now), "nothing was due");
            assertEquals(0, new BigDecimal("100000.00").compareTo(row().treasury()));
        }

        @Test
        @DisplayName("one due cycle takes one day's upkeep and moves the due date on a day")
        void oneCycle() {
            long now = 2_000_000_000_000L;
            long due = now - 1_000L;
            dueAt(due);
            BigDecimal amount = owed();
            assertTrue(amount.signum() > 0, "the fixture city owns taxable land");

            assertEquals(1, task.sweep(now));

            assertEquals(0, new BigDecimal("100000.00").subtract(amount)
                    .compareTo(row().treasury()));
            assertEquals(due + DAY, row().upkeepDue());
            assertNull(row().delinquentSince());
            assertEquals(1, ledger(TransactionType.UPKEEP_CHARGE).size());
        }

        @Test
        @DisplayName("the charge is 0.4% of what the city actually paid for its land")
        void chargeMatchesLandValue() {
            BigDecimal landValue = dev.civitas.core.claim.ClaimCostEngine.landValue(
                    support.claimRegistry.claimsOf(city.id()));

            assertEquals(0, support.upkeep.dailyUpkeep(landValue).compareTo(owed()));
        }

        @Test
        @DisplayName("upkeep can be turned off entirely")
        void disabled() {
            support.configs.get(ConfigFile.CITIES).set("upkeep.enabled", false);
            dueAt(1_000L);

            task.run();

            assertEquals(1_000L, row().upkeepDue(), "run() did nothing at all");
        }
    }

    // ==================================================================================
    // SPEC 17.3 case 31 and 33: missed cycles and repeated sweeps
    // ==================================================================================

    @Nested
    @DisplayName("Missed and repeated cycles")
    class Timing {

        @Test
        @DisplayName("SPEC 17.3 case 31: three days offline charges exactly three days")
        void catchUp() {
            long now = 2_000_000_000_000L;
            // Due just over two days ago, so three charges have come round: that one and the
            // two dailies since.
            dueAt(now - 2 * DAY - 1_000L);
            BigDecimal amount = owed();

            task.sweep(now);

            assertEquals(3, ledger(TransactionType.UPKEEP_CHARGE).size());
            assertEquals(0, new BigDecimal("100000.00")
                    .subtract(amount.multiply(BigDecimal.valueOf(3)))
                    .compareTo(row().treasury()));
            assertTrue(row().upkeepDue() > now, "and the city is no longer due");
        }

        @Test
        @DisplayName("SPEC 17.3 case 31: a month offline charges the cap, not the month")
        void catchUpIsCapped() {
            long now = 2_000_000_000_000L;
            dueAt(now - 30 * DAY);

            task.sweep(now);

            assertEquals(support.upkeep.maxCatchupCycles(),
                    ledger(TransactionType.UPKEEP_CHARGE).size(),
                    "seven cycles, not thirty");
            assertTrue(row().upkeepDue() > now,
                    "and the timer was reset rather than left with a backlog");
        }

        @Test
        @DisplayName("the catch-up cap is a config key")
        void catchUpCapIsConfigurable() {
            support.configs.get(ConfigFile.CITIES).set("upkeep.max-catchup-cycles", 2);
            long now = 2_000_000_000_000L;
            dueAt(now - 30 * DAY);

            task.sweep(now);

            assertEquals(2, ledger(TransactionType.UPKEEP_CHARGE).size());
        }

        @Test
        @DisplayName("SPEC 17.3 case 33: sweeping twice for the same moment charges once")
        void idempotentWithinOneMoment() {
            long now = 2_000_000_000_000L;
            dueAt(now - 1_000L);

            task.sweep(now);
            int afterFirst = ledger(TransactionType.UPKEEP_CHARGE).size();
            task.sweep(now);
            task.sweep(now);

            assertEquals(1, afterFirst);
            assertEquals(1, ledger(TransactionType.UPKEEP_CHARGE).size(),
                    "the due date had already moved past now");
        }

        @Test
        @DisplayName("SPEC 17.3 case 33: a stale cache cannot charge a cycle a second time")
        void staleCacheCannotDoubleCharge() {
            long now = 2_000_000_000_000L;
            long due = now - 1_000L;
            dueAt(due);

            task.sweep(now);
            BigDecimal afterOne = row().treasury();

            // Exactly what a lag spike looks like: a second sweep still holding the old due
            // time. The charge re-reads it inside its own transaction and finds it moved.
            city.setUpkeepDue(due);
            task.sweep(now);

            assertEquals(0, afterOne.compareTo(row().treasury()), "no second charge");
            assertEquals(1, ledger(TransactionType.UPKEEP_CHARGE).size());
        }
    }

    // ==================================================================================
    // SPEC 17.3 case 32: a treasury that cannot pay
    // ==================================================================================

    @Nested
    @DisplayName("Delinquency")
    class Delinquency {

        @Test
        @DisplayName("a treasury that cannot pay becomes delinquent and is ledgered as failed")
        void becomesDelinquent() {
            long now = 2_000_000_000_000L;
            fundTreasury("1.00");
            dueAt(now - 1_000L);

            task.sweep(now);

            assertNotNull(row().delinquentSince(), "the city is now in debt");
            assertEquals(1, ledger(TransactionType.UPKEEP_FAILED).size());
            assertEquals(0, ledger(TransactionType.UPKEEP_CHARGE).size());
            assertEquals(0, new BigDecimal("1.00").compareTo(row().treasury()),
                    "and nothing was taken, rather than the treasury going negative");
            assertTrue(warnings.sawKey("economy.upkeep.failed"));
        }

        @Test
        @DisplayName("during grace the city is warned and keeps all of its land")
        void graceWarnsFirst() {
            long now = 2_000_000_000_000L;
            fundTreasury("1.00");
            dueAt(now - 1_000L);
            int before = support.claimRegistry.claimsOf(city.id()).size();

            task.sweep(now);

            assertEquals(before, support.claimRegistry.claimsOf(city.id()).size(),
                    "no land is taken inside the grace period");
            assertTrue(warnings.sawKey("economy.upkeep.grace"));
        }

        @Test
        @DisplayName("SPEC 17.3 case 32: past grace, the outermost chunks are sold, three a day")
        void pastGraceLandIsSold() {
            long now = 2_000_000_000_000L;
            fundTreasury("0.00");
            dueAt(now - 1_000L);
            task.sweep(now);

            // Four days later, still broke: the grace period has run out.
            long later = now + 4 * DAY;
            fundTreasury("0.00");
            dueAt(later - 1_000L);
            int before = support.claimRegistry.claimsOf(city.id()).size();

            task.sweep(later);

            assertEquals(before - support.upkeep.unclaimsPerDay(),
                    support.claimRegistry.claimsOf(city.id()).size(),
                    "three chunks a day, no more");
            assertTrue(warnings.sawKey("economy.upkeep.land-sold"));
        }

        @Test
        @DisplayName("SPEC 39.5: outposts go before city chunks, furthest first")
        void outpostsGoFirst() {
            // "A city should lose its frontier before it loses its home."
            dev.civitas.core.outpost.OutpostRegistry outpostRegistry =
                    new dev.civitas.core.outpost.OutpostRegistry(support.daos.outposts());
            dev.civitas.core.outpost.OutpostService outposts =
                    new dev.civitas.core.outpost.OutpostService(support.db, support.daos,
                            support.registry, support.claimRegistry, support.claims,
                            outpostRegistry, support.treasury, support.configs,
                            Scheduler.direct());
            task.useOutposts(outposts);

            fundTreasury("10000000.00");
            assertTrue(await(outposts.create(mayor, city, "Near", WORLD, 40, 0,
                    640.0, 64.0, 0.0, 0f, 0f)).isSuccess());
            assertTrue(await(outposts.create(mayor, city, "Far", WORLD, 400, 0,
                    6400.0, 64.0, 0.0, 0f, 0f)).isSuccess());

            int cityChunks = support.claimRegistry.claimsOf(city.id()).size();

            long now = 2_000_000_000_000L;
            fundTreasury("0.00");
            dueAt(now - 1_000L);
            task.sweep(now);

            long later = now + 4 * DAY;
            fundTreasury("0.00");
            dueAt(later - 1_000L);
            task.sweep(later);

            assertEquals(0, outpostRegistry.countOf(city.id()),
                    "both outposts released before any city chunk was touched");
            assertTrue(support.claimRegistry.claimsOf(city.id()).stream()
                            .noneMatch(claim -> claim.chunkX() == 400),
                    "and the furthest went first");
            assertEquals(cityChunks - 2 * 1 - 1,
                    support.claimRegistry.claimsOf(city.id()).size(),
                    "two outpost chunks and then one city chunk, inside the three-a-day budget");
        }

        @Test
        @DisplayName("the chunks sold are the furthest from the core, and never the core itself")
        void furthestFirst() {
            long now = 2_000_000_000_000L;
            fundTreasury("0.00");
            dueAt(now - 1_000L);
            task.sweep(now);

            long later = now + 4 * DAY;
            fundTreasury("0.00");
            dueAt(later - 1_000L);
            task.sweep(later);

            List<Integer> remaining = support.claimRegistry.claimsOf(city.id()).stream()
                    .map(dev.civitas.core.claim.Claim::chunkX)
                    .sorted()
                    .toList();

            assertEquals(List.of(0, 1, 2, 3), remaining,
                    "chunks 6, 5 and 4 went; the core at 0 stayed");
        }

        @Test
        @DisplayName("a refund that covers the debt clears it immediately, not tomorrow")
        void refundCanClearTheDebt() {
            long now = 2_000_000_000_000L;
            fundTreasury("0.00");
            dueAt(now - 1_000L);
            task.sweep(now);

            long later = now + 4 * DAY;
            dueAt(later - 1_000L);
            task.sweep(later);

            // Selling three chunks refunds half of what each cost, which comfortably covers
            // one day of 0.4%. The city should come out of the sweep solvent.
            assertNull(row().delinquentSince(), "the land sale settled the debt");
            assertTrue(warnings.sawKey("economy.upkeep.debt-cleared"));
        }

        @Test
        @DisplayName("a city reduced to its core stays in debt rather than losing the core")
        void coreIsNeverSold() {
            long now = 2_000_000_000_000L;
            // Give the debt away everything but the core first.
            for (int x = 6; x >= 1; x--) {
                assertTrue(await(support.claims.unclaim(mayor, city, WORLD, x, 0)).isSuccess());
            }
            fundTreasury("0.00");
            dueAt(now - 1_000L);
            task.sweep(now);

            long later = now + 4 * DAY;
            fundTreasury("0.00");
            dueAt(later - 1_000L);
            task.sweep(later);

            assertEquals(1, support.claimRegistry.claimsOf(city.id()).size(),
                    "the core survives, SPEC 17.3 case 32");
            assertTrue(support.claimRegistry.claimsOf(city.id()).iterator().next().isCore());
        }

        @Test
        @DisplayName("auto-unclaim can be turned off, leaving the city merely in debt")
        void autoUnclaimIsOptional() {
            support.configs.get(ConfigFile.CITIES).set("upkeep.delinquent-auto-unclaim", false);
            long now = 2_000_000_000_000L;
            fundTreasury("0.00");
            dueAt(now - 1_000L);
            task.sweep(now);

            long later = now + 4 * DAY;
            dueAt(later - 1_000L);
            int before = support.claimRegistry.claimsOf(city.id()).size();

            task.sweep(later);

            assertEquals(before, support.claimRegistry.claimsOf(city.id()).size());
            assertNotNull(row().delinquentSince());
        }

        @Test
        @DisplayName("paying up clears delinquency on the next cycle")
        void payingUpClearsIt() {
            long now = 2_000_000_000_000L;
            fundTreasury("1.00");
            dueAt(now - 1_000L);
            task.sweep(now);
            assertNotNull(row().delinquentSince());

            fundTreasury("100000.00");
            dueAt(now + DAY - 1_000L);
            task.sweep(now + DAY);

            assertNull(row().delinquentSince(), "solvent again");
        }
    }

    /** Collects what members would have been told, instead of needing a server to tell them. */
    private static final class RecordingNotifier implements UpkeepTask.Notifier {

        private final List<String> keys = new CopyOnWriteArrayList<>();

        @Override
        public void tell(UUID member, String messageKey,
                         net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
            keys.add(messageKey);
        }

        boolean sawKey(String key) {
            return new ArrayList<>(keys).contains(key);
        }
    }
}
