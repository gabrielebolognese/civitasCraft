package dev.civitas.core.moderation;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.dao.ReportDao;
import dev.civitas.storage.row.ReportRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 15.3's moderation queue.
 *
 * <p>Two things get the most attention. First, that a handled report is <b>kept</b>: a
 * moderation decision that erased its own evidence would be the single action in this plugin
 * nobody could review afterwards, which is the opposite of what SPEC 1.5 builds every other
 * record for. Second, that two moderators cannot both close the same report — without that
 * they each believe they dealt with it, which is how one gets actioned twice.
 */
class ReportServiceTest {

    @TempDir
    Path directory;

    private static final long NOW = System.currentTimeMillis();

    private CityTestSupport support;
    private ReportService reports;
    private UUID reporter;
    private UUID target;
    private UUID moderator;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        reports = new ReportService(support.daos, support.configs);
        reporter = support.givenEligiblePlayer("Cicero");
        target = support.givenEligiblePlayer("Catilina");
        moderator = support.givenEligiblePlayer("Cato");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private ReportRow file(String reason) {
        Result<ReportRow> filed = await(reports.file(reporter, target, reason, NOW));
        assertTrue(filed.isSuccess(), reasonOf(filed));
        return filed.orElseThrow();
    }

    // ==================================================================================
    // Filing
    // ==================================================================================

    @Nested
    @DisplayName("filing")
    class Filing {

        @Test
        @DisplayName("a report reaches the queue")
        void reachesTheQueue() {
            file("griefing my farm");

            assertEquals(1, await(reports.queue()).size());
        }

        @Test
        @DisplayName("you cannot report yourself")
        void notYourself() {
            assertEquals("SELF_REPORT",
                    reasonOf(await(reports.file(reporter, reporter, "test", NOW))));
        }

        @Test
        @DisplayName("a report needs a reason")
        void needsAReason() {
            assertEquals("NO_REASON",
                    reasonOf(await(reports.file(reporter, target, "   ", NOW))));
        }

        @Test
        @DisplayName("one player cannot bury the queue")
        void rateLimited() {
            // SPEC 15.3 specifies no limit, and a player-facing command that writes to a queue
            // needs one: without it a single player makes the feature useless for everybody.
            // Recorded in OPEN_QUESTIONS.
            for (int i = 0; i < reports.reportsPerWindow(); i++) {
                file("report " + i);
            }

            assertEquals("TOO_MANY",
                    reasonOf(await(reports.file(reporter, target, "one too many", NOW))));
        }

        @Test
        @DisplayName("and can report again once the window has passed")
        void limitIsAWindow() {
            for (int i = 0; i < reports.reportsPerWindow(); i++) {
                file("report " + i);
            }
            long later = NOW + TimeUnit.HOURS.toMillis(reports.cooldownHours() + 1);

            assertTrue(await(reports.file(reporter, target, "much later", later)).isSuccess());
        }

        @Test
        @DisplayName("an overlong reason is trimmed rather than refused")
        void trimsRatherThanRefuses() {
            // Somebody typing a long complaint has a complaint. Refusing it over length would
            // discard the report to protect a column.
            String essay = "x".repeat(1000);

            ReportRow filed = file(essay);

            assertTrue(filed.reason().length() < essay.length());
            assertFalse(filed.reason().isBlank());
        }
    }

    // ==================================================================================
    // Context, SPEC 15.3
    // ==================================================================================

    @Nested
    @DisplayName("context")
    class Context {

        @Test
        @DisplayName("the reported player's recent ledger is attached when it is read")
        void attachesLedger() {
            // SPEC 15.3: "automatic attachment of the reported player's last 50 ledger
            // entries… so admins have context without asking".
            await(support.economy.give(target, new BigDecimal("5000"),
                    TransactionType.ADMIN_GIVE, null, null));
            ReportRow filed = file("suspicious wealth");

            ReportService.Detailed detail = await(reports.detail(filed, NOW));

            assertFalse(detail.ledger().isEmpty(), "the ledger came with it");
            assertEquals(filed.id(), detail.report().id());
        }

        @Test
        @DisplayName("it is read fresh, so a report read later shows what happened since")
        void contextIsCurrent() {
            // The reason the context is not copied in at write time: a report filed on Monday
            // and read on Friday should show the week, and a copy would show Monday.
            ReportRow filed = file("suspicious wealth");
            assertTrue(await(reports.detail(filed, NOW)).ledger().isEmpty());

            await(support.economy.give(target, new BigDecimal("5000"),
                    TransactionType.ADMIN_GIVE, null, null));

            assertFalse(await(reports.detail(filed, NOW)).ledger().isEmpty());
        }

        @Test
        @DisplayName("a quiet player's report has no context and that is not an error")
        void quietPlayerIsFine() {
            ReportRow filed = file("rude in chat");

            ReportService.Detailed detail = await(reports.detail(filed, NOW));

            assertTrue(detail.ledger().isEmpty());
            assertTrue(detail.kills().isEmpty());
        }
    }

    // ==================================================================================
    // Handling
    // ==================================================================================

    @Nested
    @DisplayName("handling")
    class Handling {

        @Test
        @DisplayName("a resolved report leaves the queue but not the table")
        void keptAfterHandling() {
            // The rule that matters most here. A moderation decision that erased its own
            // evidence would be the one action in this plugin nobody could review.
            ReportRow filed = file("griefing");

            assertTrue(await(reports.handle(filed.id(), moderator, true, "warned", NOW))
                    .isSuccess());

            assertTrue(await(reports.queue()).isEmpty(), "it is out of the queue");
            assertEquals(1, await(reports.about(target)).size(), "and still on the record");
        }

        @Test
        @DisplayName("the moderator and their note are recorded")
        void recordsWhoAndWhy() {
            ReportRow filed = file("griefing");

            await(reports.handle(filed.id(), moderator, true, "warned and rolled back", NOW));

            ReportRow handled = await(reports.about(target)).get(0);
            assertEquals(moderator, handled.handledBy());
            assertEquals("warned and rolled back", handled.resolution());
            assertEquals(ReportDao.RESOLVED, handled.state());
        }

        @Test
        @DisplayName("a dismissed report is marked dismissed rather than resolved")
        void dismissIsDistinct() {
            // "We looked and there was nothing" and "we looked and acted" are different
            // answers, and a moderator reading the history needs to tell them apart.
            ReportRow filed = file("nothing much");

            await(reports.handle(filed.id(), moderator, false, "no evidence", NOW));

            assertEquals(ReportDao.DISMISSED, await(reports.about(target)).get(0).state());
        }

        @Test
        @DisplayName("two moderators cannot both close the same report")
        void handledOnce() {
            // Without this they each believe they dealt with it, which is how a report gets
            // actioned twice — two warnings for one incident.
            ReportRow filed = file("griefing");
            await(reports.handle(filed.id(), moderator, true, "warned", NOW));

            assertEquals("ALREADY_HANDLED",
                    reasonOf(await(reports.handle(filed.id(), reporter, false, "again", NOW))));
        }

        @Test
        @DisplayName("the queue is oldest first, because the oldest has waited longest")
        void oldestFirst() {
            ReportRow first = await(reports.file(reporter, target, "first", NOW))
                    .orElseThrow();
            await(reports.file(reporter, target, "second", NOW + 60_000L));

            assertEquals(first.id(), await(reports.queue()).get(0).id());
        }
    }
}
