package dev.civitas.core.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.civitas.storage.row.SiegeCampRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 29.5's 200-HP camp.
 *
 * <p>{@link Destruction#exactlyOnce} is the one that matters. Destroying a camp awards the
 * defender 40 points and despawns an army; two players landing the killing blow in the same tick
 * must not do it twice, and the in-memory half of that guarantee lives here.
 */
class SiegeCampTest {

    private static SiegeCamp fresh(double health) {
        return new SiegeCamp(1, 2, 3, "world", 100, 64, 200, health, null, false);
    }

    @Nested
    @DisplayName("taking damage")
    class Damage {

        @Test
        @DisplayName("health comes off and the camp stands")
        void survivesAHit() {
            SiegeCamp camp = fresh(200);

            assertFalse(camp.damage(5, 1_000L));
            assertEquals(195, camp.health(), 0.001);
            assertTrue(camp.stands());
        }

        @Test
        @DisplayName("health floors at zero rather than going negative")
        void floors() {
            SiegeCamp camp = fresh(3);

            assertTrue(camp.damage(500, 1_000L));
            assertEquals(0, camp.health(), 0.001);
        }

        @Test
        @DisplayName("negative damage does not heal it")
        void noHealing() {
            SiegeCamp camp = fresh(100);

            camp.damage(-50, 1_000L);
            assertEquals(100, camp.health(), 0.001);
        }
    }

    @Nested
    @DisplayName("destruction")
    class Destruction {

        @Test
        @DisplayName("only one blow ever reports the kill")
        void exactlyOnce() {
            SiegeCamp camp = fresh(5);

            assertTrue(camp.damage(5, 1_000L), "the blow that took it to zero");
            assertFalse(camp.damage(5, 1_001L), "and every blow after it");
            assertFalse(camp.damage(5, 1_002L));
        }

        @Test
        @DisplayName("the time of death is the blow that landed it")
        void stampsWhen() {
            SiegeCamp camp = fresh(5);
            camp.damage(5, 4_242L);

            assertEquals(4_242L, camp.destroyedAt());
            assertFalse(camp.stands());
        }
    }

    @Nested
    @DisplayName("rebuilding, SPEC 29.5's once per war")
    class Rebuild {

        @Test
        @DisplayName("a rebuilt camp stands again at full health and has spent its allowance")
        void rebuild() {
            SiegeCamp camp = fresh(5);
            camp.damage(5, 1_000L);

            camp.markRebuilt(200);

            assertTrue(camp.stands());
            assertEquals(200, camp.health(), 0.001);
            assertTrue(camp.rebuilt(), "and cannot be rebuilt again");
        }
    }

    @Nested
    @DisplayName("round trip")
    class Row {

        @Test
        @DisplayName("a row becomes the camp it describes")
        void fromRow() {
            SiegeCamp camp = SiegeCamp.fromRow(
                    new SiegeCampRow(11, 22, 33, "world", 160, 70, -48, 120, 900L, null, true));

            assertEquals(11, camp.id());
            assertEquals(22, camp.warId());
            assertEquals(33, camp.cityId());
            assertEquals(120, camp.health(), 0.001);
            assertTrue(camp.rebuilt());
            assertEquals(10, camp.chunkX());
            assertEquals(-3, camp.chunkZ(), "negative coordinates floor rather than truncate");
        }
    }
}
