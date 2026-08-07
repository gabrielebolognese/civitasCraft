package dev.civitas.core.war;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.row.WarEntitySnapshotRow;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * SPEC 11.8.3's living entities.
 *
 * <h2>Why this is a snapshot and not a diff</h2>
 * Every other part of the rollback records the old state at the moment something changes, which
 * is what makes M17's block log work. A mob cannot do that: by the time it dies there is nothing
 * left to ask what it was, what it was called, or what it traded. So the population of the zone
 * is recorded when the fighting begins and the deaths are matched against that record.
 *
 * <p>The villager is the case that matters. A cured librarian with Mending is worth more than
 * the house around it, and losing one to a stray arrow in somebody else's war is exactly the
 * "the plugin ate my thing" outcome SPEC 1.2 exists to prevent.
 */
class WarEntitySnapshotsTest {

    @TempDir
    Path directory;

    private static final long NOW = System.currentTimeMillis();

    /** Assigned by the database: the snapshot table has a foreign key onto {@code wars}. */
    private int warId;

    private ServerMock server;
    private WorldMock world;
    private CityTestSupport support;
    private WarEntitySnapshots snapshots;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        support = CityTestSupport.open(directory);
        snapshots = new WarEntitySnapshots(support.daos.warEntitySnapshots(),
                dev.civitas.util.Scheduler.direct(), quiet());
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("civitas-entity-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    /**
     * A war whose zone is the chunk at the origin.
     *
     * <p>Written to the database first, because {@code war_entity_snapshots} has a foreign key
     * onto {@code wars} — a snapshot of a war that does not exist would be a row nothing could
     * ever restore from.
     */
    private War givenWar() {
        java.math.BigDecimal wager = new java.math.BigDecimal("50000.00");
        warId = await(support.daos.wars().insert(new dev.civitas.storage.row.WarRow(0, 1, 2,
                NOW, NOW + 1000L, NOW + 2000L, WarState.ACTIVE.key(), 0, 0, null, wager,
                null, null)));
        War created = new War(warId, 1, 2, NOW, NOW + 1000L, NOW + 2000L, WarState.ACTIVE, wager);
        created.zone(WarZone.of(List.of(new dev.civitas.core.claim.Claim(1L, 1, "world", 0, 0,
                NOW, UUID.randomUUID(), java.math.BigDecimal.ZERO,
                dev.civitas.core.claim.ClaimType.CORE, null)), 0));
        return created;
    }

    private Entity spawn(EntityType type, double x, double z) {
        return world.spawnEntity(new Location(world, x, 64, z), type);
    }

    // ==================================================================================
    // What is worth restoring
    // ==================================================================================

    @Nested
    @DisplayName("what counts")
    class WhatCounts {

        @Test
        @DisplayName("animals and villagers are restorable, hostiles and players are not")
        void theRightThings() {
            // SPEC 11.8.3's row names "villagers, animals in the war zone". A zombie respawns
            // on its own and nobody mourns it; a player is not an entity to be restored.
            assertTrue(WarEntitySnapshots.isRestorable(spawn(EntityType.COW, 1, 1)));
            assertTrue(WarEntitySnapshots.isRestorable(spawn(EntityType.VILLAGER, 2, 2)));
            assertTrue(WarEntitySnapshots.isRestorable(spawn(EntityType.WOLF, 3, 3)),
                    "somebody's tamed wolf is not replaceable by walking into a forest");

            assertFalse(WarEntitySnapshots.isRestorable(spawn(EntityType.ZOMBIE, 4, 4)));
            assertFalse(WarEntitySnapshots.isRestorable(spawn(EntityType.CREEPER, 5, 5)));
            assertFalse(WarEntitySnapshots.isRestorable(server.addPlayer("Marcus")));
        }

        @Test
        @DisplayName("a defense unit is not restored, because SPEC 12.3 makes it a real loss")
        void defenseUnitsAreConsumed() {
            // SPEC 11.8.3: "Defense units: Not restored, they are consumed resources." It is
            // what makes a war cost a defender something even though every block comes back.
            Entity guard = spawn(EntityType.ZOMBIE, 6, 6);
            guard.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey("civitas", "defense_unit"),
                    org.bukkit.persistence.PersistentDataType.INTEGER, 1);

            assertFalse(WarEntitySnapshots.isRestorable(guard));
        }
    }

    // ==================================================================================
    // Taking the snapshot
    // ==================================================================================

    @Nested
    @DisplayName("at war start")
    class AtWarStart {

        @Test
        @DisplayName("everything restorable inside the zone is recorded")
        void capturesTheZone() {
            spawn(EntityType.COW, 1, 1);
            spawn(EntityType.VILLAGER, 2, 2);
            War war = givenWar();

            assertEquals(2, await(snapshots.capture(war, NOW)).intValue());
            assertEquals(2, await(support.daos.warEntitySnapshots().findByWar(warId)).size());
        }

        @Test
        @DisplayName("an animal outside the zone is left alone")
        void ignoresOutsideTheZone() {
            spawn(EntityType.COW, 1, 1);
            spawn(EntityType.COW, 900, 900);
            War war = givenWar();

            assertEquals(1, await(snapshots.capture(war, NOW)).intValue());
        }

        @Test
        @DisplayName("hostiles are not recorded even inside the zone")
        void ignoresHostiles() {
            spawn(EntityType.ZOMBIE, 1, 1);
            War war = givenWar();

            assertEquals(0, await(snapshots.capture(war, NOW)).intValue());
        }

        @Test
        @DisplayName("capturing twice does not double what will come back")
        void captureIsIdempotent() {
            // A restart during beginActive could run this twice, and a war that respawned two
            // cows for every one killed would be minting livestock.
            spawn(EntityType.COW, 1, 1);
            War war = givenWar();

            await(snapshots.capture(war, NOW));
            await(snapshots.capture(war, NOW));

            assertEquals(1, await(support.daos.warEntitySnapshots().findByWar(warId)).size());
        }
    }

    // ==================================================================================
    // Deaths and restoration
    // ==================================================================================

    @Nested
    @DisplayName("during and after the war")
    class Deaths {

        @Test
        @DisplayName("a watched animal's death is recorded, an unwatched one's is not")
        void deathsAreMatched() {
            Entity cow = spawn(EntityType.COW, 1, 1);
            Entity stranger = spawn(EntityType.COW, 900, 900);
            War war = givenWar();
            await(snapshots.capture(war, NOW));

            assertTrue(snapshots.died(cow.getUniqueId(), NOW + 5));
            assertFalse(snapshots.died(stranger.getUniqueId(), NOW + 5),
                    "a cow a thousand blocks away is not this war's business");
        }

        @Test
        @DisplayName("only what died is brought back")
        void restoresOnlyTheDead() {
            // Restoring survivors as well would double every animal in the zone.
            Entity killed = spawn(EntityType.COW, 1, 1);
            spawn(EntityType.COW, 2, 2);
            War war = givenWar();
            await(snapshots.capture(war, NOW));
            snapshots.died(killed.getUniqueId(), NOW + 5);
            snapshots.awaitDeaths();

            List<WarEntitySnapshotRow> dead =
                    await(support.daos.warEntitySnapshots().findDead(warId));
            assertEquals(1, dead.size());
            assertEquals(killed.getUniqueId(), dead.get(0).entityUuid());
        }

        @Test
        @DisplayName("a war with no deaths restores nothing")
        void nothingDiedNothingReturns() {
            spawn(EntityType.COW, 1, 1);
            War war = givenWar();
            await(snapshots.capture(war, NOW));

            assertEquals(0, await(snapshots.restoreDead(warId)).intValue());
        }

        @Test
        @DisplayName("a dead animal is respawned where it stood")
        void respawnsAtItsPosition() {
            Entity cow = spawn(EntityType.COW, 5, 7);
            War war = givenWar();
            await(snapshots.capture(war, NOW));
            snapshots.died(cow.getUniqueId(), NOW + 5);
            cow.remove();

            // The two halves are driven separately here for the reason they are separate at
            // all: the read is storage work and the spawn is world work, and MockBukkit
            // refuses an entity added off the main thread exactly as a real server would.
            assertEquals(1, snapshots.respawnAll(await(snapshots.deadOf(warId))));

            assertTrue(world.getEntities().stream()
                            .anyMatch(entity -> entity.getType() == EntityType.COW
                                    && entity.getLocation().getBlockX() == 5
                                    && entity.getLocation().getBlockZ() == 7),
                    "a cow is back where the one that died was standing");
        }

        @Test
        @DisplayName("forgetting a war stops it watching anything")
        void forgetClearsTheWatchList() {
            spawn(EntityType.COW, 1, 1);
            War war = givenWar();
            await(snapshots.capture(war, NOW));
            assertEquals(1, snapshots.watchedCount());

            snapshots.forget(warId);

            assertEquals(0, snapshots.watchedCount());
        }
    }

    // ==================================================================================
    // What the payload carries
    // ==================================================================================

    @Test
    @DisplayName("a named animal keeps its name")
    void nameSurvives() {
        Entity cow = spawn(EntityType.COW, 1, 1);
        cow.customName(net.kyori.adventure.text.Component.text("Bessie"));

        YamlConfiguration payload = payloadOf(cow);

        assertTrue(payload.getString("name", "").contains("Bessie"), payload.saveToString());
    }

    @Test
    @DisplayName("a villager's profession, level and trades are all captured")
    void villagerDetailSurvives() {
        // The detail SPEC 11.8.3 names explicitly, and the reason this class exists at all:
        // the trades are the part that cannot be recreated by breeding another villager.
        org.bukkit.entity.Villager villager =
                (org.bukkit.entity.Villager) spawn(EntityType.VILLAGER, 1, 1);
        villager.setVillagerLevel(4);
        villager.setVillagerExperience(250);

        YamlConfiguration payload = payloadOf(villager);

        assertEquals(4, payload.getInt("level"));
        assertEquals(250, payload.getInt("experience"));
        assertTrue(payload.contains("profession"), payload.saveToString());
        assertTrue(payload.contains("recipes"), "SPEC 11.8.3 names trades directly");
    }

    @Test
    @DisplayName("the payload names what it carries, so a gap is visible rather than assumed")
    void describedFieldsAreDeclared() {
        assertTrue(WarEntitySnapshots.describedFields().contains("recipes"));
        assertTrue(WarEntitySnapshots.describedFields().contains("profession"));
    }

    private static YamlConfiguration payloadOf(Entity entity) {
        YamlConfiguration payload = new YamlConfiguration();
        try {
            payload.load(new java.io.StringReader(new String(
                    WarEntitySnapshots.describe(entity), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("payload should be readable", e);
        }
        return payload;
    }
}
