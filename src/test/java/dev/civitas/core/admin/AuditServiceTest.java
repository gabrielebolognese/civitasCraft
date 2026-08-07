package dev.civitas.core.admin;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.dao.AuditLogDao;
import dev.civitas.storage.row.AuditLogRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 17.6 case 80: "All admin actions write to {@code audit_log} with actor, target,
 * timestamp, and reason… The audit log is separate from the ledger and cannot be cleared
 * in-game."
 *
 * <p>The last clause is the one worth testing carefully, and it is not tested by trying to
 * clear the log and being refused. It is tested by asserting that the DAO offers no way to try:
 * a permission check can be bypassed by a bug, a method that does not exist cannot.
 */
class AuditServiceTest {

    @TempDir
    Path directory;

    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private CityTestSupport support;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        audit = new AuditService(support.daos.auditLog(), quiet());
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("audit-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private List<AuditLogRow> recorded() {
        return await(audit.recent(0L, 100));
    }

    // ==================================================================================
    // What gets written
    // ==================================================================================

    @Test
    @DisplayName("an action records who did it, to what, and why")
    void recordsTheFourThingsSpecNames() {
        await(audit.record(ADMIN, "CITY_FREEZE", "Roma", "suspected duplication"));

        List<AuditLogRow> rows = recorded();
        assertEquals(1, rows.size());
        assertEquals(ADMIN, rows.get(0).actorUuid());
        assertEquals("CITY_FREEZE", rows.get(0).action());
        assertEquals("Roma", rows.get(0).target());
        assertEquals("suspected duplication", rows.get(0).reason());
        assertTrue(rows.get(0).timestamp() > 0, "SPEC 17.6 case 80 names the timestamp too");
    }

    @Test
    @DisplayName("a console action has no actor rather than a fabricated one")
    void consoleHasNoActor() {
        // A null actor is the honest record of "the console did this". Inventing a uuid for
        // the console would make the log claim a player did something they did not.
        await(audit.record(null, "WAR_CANCEL", "3", "server maintenance"));

        assertNull(recorded().get(0).actorUuid());
    }

    @Test
    @DisplayName("an action that was refused is recorded too")
    void refusalsAreRecorded() {
        // An admin trying repeatedly to do something and being refused is exactly the pattern
        // this log exists to make visible. A log of successes only would hide it.
        await(audit.recordRefusal(ADMIN, "ECO_GIVE", "Cicero", "amount over the server cap"));

        List<AuditLogRow> rows = recorded();
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).metadata().contains("refused"), rows.get(0).metadata());
    }

    @Test
    @DisplayName("metadata is JSON an admin can read, with quotes escaped")
    void metadataIsSafeJson() {
        // A target's name is player-supplied. A quote in it must not break the column for
        // whatever an operator pipes the export into.
        await(audit.record(ADMIN, "CITY_RENAME", "Roma",
                "cleanup", Map.of("from", "a \"quoted\" name")));

        String metadata = recorded().get(0).metadata();
        assertTrue(metadata.contains("\\\""), "the quote is escaped: " + metadata);
    }

    @Test
    @DisplayName("no metadata means no metadata, not an empty object")
    void emptyMetadataIsNull() {
        await(audit.record(ADMIN, "WAR_EXTEND", "7", null));

        assertNull(recorded().get(0).metadata());
    }

    @Test
    @DisplayName("entries come back newest first, which is how an incident is read")
    void newestFirst() {
        await(audit.record(ADMIN, "FIRST", "a", null));
        await(audit.record(ADMIN, "SECOND", "b", null));

        List<AuditLogRow> rows = recorded();
        assertEquals(2, rows.size());
        assertEquals("SECOND", rows.get(0).action());
    }

    // ==================================================================================
    // What cannot be done to it
    // ==================================================================================

    @Test
    @DisplayName("the audit log offers no way to change or delete an entry")
    void cannotBeClearedInGame() {
        // SPEC 17.6 case 80. Asserted against the DAO's own surface rather than by trying and
        // being refused: the guarantee is that the code to clear it does not exist.
        //
        // deleteAll is inherited from Dao and is used by tests to reset a table; what must not
        // exist is a targeted update or delete this plugin could reach from a command.
        for (Method method : AuditLogDao.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.startsWith("update") || name.startsWith("delete")
                            || name.startsWith("clear") || name.startsWith("purge"),
                    "AuditLogDao must offer no way to rewrite history, but declares "
                            + method.getName());
        }
    }

    @Test
    @DisplayName("a write that fails does not stop the action it was recording")
    void aFailedWriteIsNotFatal() {
        // The command has already happened by the time this is called. Throwing here would
        // turn a lost log line into a failed admin action, which is the worse of the two.
        support.close();

        assertEquals(0L, await(audit.record(ADMIN, "CITY_FREEZE", "Roma", "after shutdown")));
    }
}
