package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.listener.WardenListener;
import io.papermc.paper.event.entity.WardenAngerChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.WardenMock;

/**
 * SPEC 31's "disabled <b>and verified</b>", for the two things SPEC 28.8 names.
 *
 * <p>This is the one part of M12f that can be asserted against a live Bukkit object, because
 * MockBukkit does implement {@code Warden} and its whole anger API. The rest of the milestone
 * asserts records for the reason {@code CityWardenTest} records; here there is a real entity, a
 * real event and a real handler, and the two suppressions SPEC calls out are fired at and checked.
 *
 * <p>What is <b>not</b> verified, and cannot be: SPEC 28.8 also asks for "the sonic boom goal" to
 * be cancelled "via the Paper Goal API so the animation never plays". {@code VanillaGoal} contains
 * 194 goal keys and none of them is the Warden's — a Warden is a brain mob with no goal selector
 * entries to remove — so the damage is gone and the windup is not. See {@link WardenSuppression}
 * and OPEN_QUESTIONS.
 */
class WardenSuppressionTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private dev.civitas.core.city.CityTestSupport support;
    private DefenseCatalogue catalogue;
    private DefenseSpawner spawner;
    private DefenseRegistry units;
    private WardenRegistry wardenRegistry;
    private WardenSuppression suppression;
    private WardenListener listener;

    /** Whether the owning city is treated as fighting an ACTIVE war. */
    private final java.util.concurrent.atomic.AtomicBoolean atWar =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin("CivitasTest");
        support = dev.civitas.core.city.CityTestSupport.open(directory);

        catalogue = new DefenseCatalogue(support.configs,
                dev.civitas.core.city.CityTestSupport.quietLogger());
        catalogue.load();
        spawner = new DefenseSpawner(plugin, catalogue, langOf(support.configs));
        units = new DefenseRegistry(support.daos.defenseUnits());
        wardenRegistry = new WardenRegistry(support.daos.cityWardens());
        suppression = new WardenSuppression(catalogue);

        // Real, not mocked. A mock would let a refactor that stopped consulting the war seam
        // pass anyway, and the war seam is the whole of SPEC 28.6.
        WardenService service = new WardenService(support.db, support.daos.cityWardens(),
                support.daos.defenseUnits(), wardenRegistry, units, catalogue, support.registry,
                support.treasury,
                new dev.civitas.core.upgrade.UpgradeService(support.db,
                        support.daos.cityUpgrades(), support.treasury, support.configs,
                        dev.civitas.util.Scheduler.direct()),
                dev.civitas.util.Scheduler.direct());
        service.useWars(cityId -> atWar.get());
        listener = new WardenListener(spawner, units, service, suppression);
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    // ==================================================================================
    // SPEC 28.8, the anger map
    // ==================================================================================

    @Test
    @DisplayName("SPEC 28.8: a Warden angry at somebody the rule did not permit is calmed")
    void clearsUnpermittedAnger() {
        Warden warden = warden();
        Player bystander = server.addPlayer("Livia");
        warden.setAnger(bystander, 90);

        // "Clear anger every tick, and drive targeting exclusively from the plugin... or the
        // Warden will aggro on a member walking past."
        assertTrue(suppression.suppressAnger(warden, null, java.util.List.of(bystander)));

        assertEquals(0, warden.getAnger(bystander));
    }

    @Test
    @DisplayName("SPEC 28.4's 10 damage lands, because the plugin drives the anger itself")
    void drivesTheChosenTarget() {
        Warden warden = warden();
        Player trespasser = server.addPlayer("Brutus");

        suppression.suppressAnger(warden, trespasser, java.util.List.of(trespasser));

        // A Warden is a brain mob: its attack fires from the anger map rather than from
        // setTarget, so without this SPEC 28.4's whole tuning table would never land a blow.
        assertEquals(catalogue.wardenAngerOnTarget(), warden.getAnger(trespasser));
    }

    @Test
    @DisplayName("the permitted target keeps its anger while everyone else loses theirs")
    void onlyOneTargetAtATime() {
        Warden warden = warden();
        Player trespasser = server.addPlayer("Brutus");
        Player bystander = server.addPlayer("Livia");
        warden.setAnger(bystander, 120);

        suppression.suppressAnger(warden, trespasser,
                java.util.List.of(trespasser, bystander));

        assertEquals(0, warden.getAnger(bystander));
        assertEquals(catalogue.wardenAngerOnTarget(), warden.getAnger(trespasser));
    }

    @Test
    @DisplayName("SPEC 28.8: vibration anger is cancelled, so a passing member is never a target")
    void vibrationAngerIsCancelled() {
        Warden warden = warden();
        Player member = server.addPlayer("Romulus");
        WardenAngerChangeEvent event =
                new WardenAngerChangeEvent(warden, member, 0, 35);

        listener().onAnger(event);

        assertTrue(event.isCancelled(),
                "the vibration system's own entry point must not reach the anger map");
    }

    @Test
    @DisplayName("but the plugin's own write is not cancelled, or the Warden never attacks")
    void theDriveIsNotCancelledByItsOwnGuard() {
        Warden warden = warden();
        Player trespasser = server.addPlayer("Brutus");
        WardenListener handler = listener();

        // setAnger fires the same event the guard above cancels. Without the re-entrancy flag
        // the Warden would be permanently calm and the bug would present as "the Warden is
        // passive" rather than as a cancelled event.
        suppression.drive(warden, trespasser);
        assertEquals(catalogue.wardenAngerOnTarget(), warden.getAnger(trespasser));

        // And the flag is not left set: the very next vibration is still refused.
        WardenAngerChangeEvent vibration =
                new WardenAngerChangeEvent(warden, server.addPlayer("Livia"), 0, 35);
        handler.onAnger(vibration);
        assertTrue(vibration.isCancelled());
    }

    @Test
    @DisplayName("a wild Warden's anger is left entirely alone")
    void wildWardensAreNotOurs() {
        Warden wild = new WardenMock(server, UUID.randomUUID());
        WardenAngerChangeEvent event =
                new WardenAngerChangeEvent(wild, server.addPlayer("Livia"), 0, 35);

        listener().onAnger(event);

        assertFalse(event.isCancelled(),
                "an ancient-city Warden has nothing to do with a city's defenses");
    }

    // ==================================================================================
    // SPEC 28.3 and 28.8, the sonic boom
    // ==================================================================================

    @Test
    @DisplayName("SPEC 28.3: the sonic boom does no damage at all")
    void sonicBoomIsCancelled() {
        Warden warden = warden();
        Player victim = server.addPlayer("Brutus");
        EntityDamageByEntityEvent event = damageBy(warden, victim,
                EntityDamageEvent.DamageCause.SONIC_BOOM, org.bukkit.damage.DamageType.SONIC_BOOM, 15.0);

        listener().onSonicBoom(event);

        // "Unblockable, uncounterable ranged damage has no place in a defense unit" -- and it is
        // the one attack against which SPEC 28.4's whole armour table would mean nothing.
        assertTrue(event.isCancelled());
    }

    @Test
    @DisplayName("a wild Warden's sonic boom still works, so the deep dark is unchanged")
    void wildSonicBoomSurvives() {
        Warden wild = new WardenMock(server, UUID.randomUUID());
        EntityDamageByEntityEvent event = damageBy(wild, server.addPlayer("Brutus"),
                EntityDamageEvent.DamageCause.SONIC_BOOM, org.bukkit.damage.DamageType.SONIC_BOOM, 15.0);

        listener().onSonicBoom(event);

        assertFalse(event.isCancelled(),
                "SPEC 28.8 says 'unconditionally', but read server-wide that would silently "
                        + "rewrite ancient-city gameplay");
    }

    @Test
    @DisplayName("its melee is untouched, which is where SPEC 28.4's 10 damage lives")
    void meleeIsNotCancelled() {
        Warden warden = warden();
        EntityDamageByEntityEvent event = damageBy(warden, server.addPlayer("Brutus"),
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, org.bukkit.damage.DamageType.MOB_ATTACK, 10.0);

        listener().onSonicBoom(event);

        assertFalse(event.isCancelled());
    }

    // ==================================================================================
    // SPEC 28.6, the peacetime defeat
    // ==================================================================================

    @Test
    @DisplayName("SPEC 28.6: a peacetime killing blow is cancelled, not survived by luck")
    void peacetimeLethalDamageIsCancelled() {
        Warden warden = warden();
        warden.setHealth(4.0);
        EntityDamageEvent event = new EntityDamageEvent(warden, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                sourceOf(org.bukkit.damage.DamageType.MOB_ATTACK, null), 40.0);

        listener().onLethalDamage(event);

        assertTrue(event.isCancelled(),
                "a 2.75 million coin asset must not be removable by one griefer in peacetime");
        assertTrue(warden.getHealth() > 0);
    }

    @Test
    @DisplayName("SPEC 28.6 and case 97: in an ACTIVE war the blow lands and it dies")
    void warLethalDamageIsNotCancelled() {
        atWar.set(true);
        Warden warden = warden();
        warden.setHealth(4.0);
        EntityDamageEvent event = new EntityDamageEvent(warden, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                sourceOf(org.bukkit.damage.DamageType.MOB_ATTACK, null), 40.0);

        listener().onLethalDamage(event);

        assertFalse(event.isCancelled(),
                "the ordinary death path must take it, or a war could never destroy one");
    }

    @Test
    @DisplayName("a survivable hit in peacetime is left alone")
    void nonLethalDamageIsLeftAlone() {
        Warden warden = warden();
        warden.setHealth(500.0);
        EntityDamageEvent event = new EntityDamageEvent(warden, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                sourceOf(org.bukkit.damage.DamageType.MOB_ATTACK, null), 10.0);

        listener().onLethalDamage(event);

        assertFalse(event.isCancelled());
    }

    // ==================================================================================
    // Fixture
    // ==================================================================================

    /** A Warden this plugin owns: stamped with a unit id, and in both registries. */
    private Warden warden() {
        Warden warden = new WardenMock(server, UUID.randomUUID());
        warden.getPersistentDataContainer()
                .set(spawner.key(), PersistentDataType.INTEGER, 7);
        units.put(new DefenseUnit(7, 1, CityWarden.TYPE_KEY, "world", 8, 64, 8,
                new BigDecimal("8000"), true, null, null));
        wardenRegistry.put(new CityWarden.Owned(1, 7, 0L, null));
        return warden;
    }

    /**
     * An {@code EntityDamageByEntityEvent}, built the only way paper-api still offers.
     *
     * <p>Every constructor that takes a damage amount directly is marked for removal, and the
     * survivors take a modifier map, so the deprecation is suppressed in exactly one place rather
     * than at four call sites -- and it is a test fixture, not a code path a server runs.
     */
    @SuppressWarnings("removal")
    private static EntityDamageByEntityEvent damageBy(org.bukkit.entity.Entity damager,
                                                      org.bukkit.entity.Entity victim,
                                                      EntityDamageEvent.DamageCause cause,
                                                      org.bukkit.damage.DamageType type,
                                                      double amount) {
        return new EntityDamageByEntityEvent(damager, victim, cause,
                sourceOf(type, damager), amount);
    }

    /** A damage source, because the (entity, cause, amount) constructors are marked for removal. */
    private static org.bukkit.damage.DamageSource sourceOf(org.bukkit.damage.DamageType type,
                                                           org.bukkit.entity.Entity causedBy) {
        org.bukkit.damage.DamageSource.Builder builder =
                org.bukkit.damage.DamageSource.builder(type);
        if (causedBy != null) {
            builder = builder.withCausingEntity(causedBy).withDirectEntity(causedBy);
        }
        return builder.build();
    }

    private WardenListener listener() {
        return listener;
    }

    private dev.civitas.lang.LangManager langOf(ConfigManager configs) {
        dev.civitas.lang.LangManager lang = new dev.civitas.lang.LangManager(
                PluginResources.ofClasspath(directory.resolve("lang").toFile(), quiet()), configs);
        lang.load();
        return lang;
    }

    private static java.util.logging.Logger quiet() {
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(WardenSuppressionTest.class.getName());
        logger.setUseParentHandlers(false);
        return logger;
    }
}
