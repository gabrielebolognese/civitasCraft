package dev.civitas.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SPEC 0 rule 3: every numeric value in SPEC.md is a config default, not a constant.
 *
 * <p>These tests read the packaged yml files and assert the documented defaults. They are
 * the guard that keeps a later milestone from quietly hardcoding a number that the
 * specification says an operator must be able to change.
 */
class ConfigDefaultsTest {

    private static final File RESOURCES = new File("src/main/resources");

    private static FileConfiguration load(ConfigFile file) {
        File onDisk = new File(RESOURCES, file.fileName());
        assertTrue(onDisk.isFile(), "Packaged config missing: " + file.fileName());
        return YamlConfiguration.loadConfiguration(onDisk);
    }

    @ParameterizedTest
    @EnumSource(ConfigFile.class)
    @DisplayName("every declared config file is packaged and parses")
    void everyConfigFileIsPackagedAndParses(ConfigFile file) {
        FileConfiguration config = load(file);

        assertNotNull(config);
        assertTrue(!config.getKeys(false).isEmpty(), file.fileName() + " parsed but is empty");
    }

    // --- SPEC 16.1, config.yml -------------------------------------------------------

    @Test
    @DisplayName("config.yml carries the SPEC 16.1 defaults")
    void configDefaults() {
        FileConfiguration config = load(ConfigFile.CONFIG);

        assertEquals("SQLITE", config.getString("storage.type"));
        assertEquals("civitas.db", config.getString("storage.sqlite.file"));
        assertEquals("WAL", config.getString("storage.sqlite.journal-mode"));
        assertEquals(4, config.getInt("storage.sqlite.pool-size"));
        assertEquals(5000, config.getInt("storage.sqlite.busy-timeout-ms"));
        assertEquals(3306, config.getInt("storage.mysql.port"));
        assertEquals(10, config.getInt("storage.mysql.pool-size"));
        assertTrue(config.getBoolean("storage.backup.enabled"));
        assertEquals(6, config.getInt("storage.backup.interval-hours"));
        assertEquals(28, config.getInt("storage.backup.keep-count"));

        assertEquals(List.of("world"), config.getStringList("worlds.city-enabled"));
        assertEquals(List.of("world_the_end", "world_nether"), config.getStringList("worlds.blacklisted"));

        assertEquals("en", config.getString("language"));

        assertEquals(100000, config.getInt("performance.claim-cache-size"));
        assertEquals(20, config.getInt("performance.gui-refresh-ticks"));
        assertEquals(200, config.getInt("performance.ledger-batch-size"));
        assertEquals(5, config.getInt("performance.ledger-flush-seconds"));
    }

    // --- SPEC 16.2, cities.yml -------------------------------------------------------

    @Test
    @DisplayName("cities.yml carries the SPEC 16.2 defaults")
    void citiesDefaults() {
        FileConfiguration config = load(ConfigFile.CITIES);

        assertEquals(10000, config.getInt("creation.cost"));
        assertEquals(2, config.getInt("creation.min-playtime-hours"));
        assertEquals(3, config.getInt("creation.name-min-length"));
        assertEquals(24, config.getInt("creation.name-max-length"));
        assertEquals("^[A-Za-z0-9_]+$", config.getString("creation.name-pattern"));
        assertEquals(5, config.getInt("creation.min-distance-chunks"));
        assertTrue(config.getStringList("creation.blocked-names").contains("admin"));

        assertEquals(8, config.getInt("claims.starter-flat-count"));
        assertEquals(500, config.getInt("claims.starter-flat-cost"));
        assertEquals(400, config.getInt("claims.formula-base"));
        assertEquals(1.25, config.getDouble("claims.formula-exponent"), 1e-9);
        assertEquals(0.05, config.getDouble("claims.distance-multiplier-per-chunk"), 1e-9);
        assertEquals(4, config.getInt("claims.distance-free-radius"));
        assertEquals(0.18, config.getDouble("claims.member-divisor-per-member"), 1e-9);
        assertEquals(14, config.getInt("claims.active-member-days"));
        assertEquals(0.75, config.getDouble("claims.new-city-discount"), 1e-9);
        assertEquals(14, config.getInt("claims.new-city-days"));
        assertEquals(5, config.getInt("claims.buffer-chunks"));
        assertEquals(50, config.getInt("claims.unclaim-refund-percent"));
        assertTrue(config.getBoolean("claims.enforce-contiguity"));
        assertEquals(5, config.getInt("claims.radius-claim-max"));

        assertTrue(config.getBoolean("upkeep.enabled"));
        assertEquals(0.4, config.getDouble("upkeep.percent-of-land-value-per-day"), 1e-9);
        assertEquals(4, config.getInt("upkeep.charge-hour"));
        assertEquals(3, config.getInt("upkeep.grace-period-days"));
        assertTrue(config.getBoolean("upkeep.delinquent-auto-unclaim"));
        assertEquals(3, config.getInt("upkeep.delinquent-unclaim-per-day"));
        assertEquals(7, config.getInt("upkeep.max-catchup-cycles"));

        assertEquals(10, config.getInt("members.base-cap"));
        assertEquals(24, config.getInt("members.switch-cooldown-hours"));
        assertEquals(5, config.getInt("members.invite-expiry-minutes"));
        assertEquals(25, config.getInt("members.withdraw-percent-per-day"));

        assertEquals(2, config.getInt("outposts.base-max"));
        assertEquals(25000, config.getInt("outposts.creation-cost-flat"));
        assertEquals(3.0, config.getDouble("outposts.creation-cost-multiplier"), 1e-9);
        assertEquals(2000, config.getInt("outposts.upkeep-per-day"));
        assertEquals(32, config.getInt("outposts.min-distance-from-own-city"));
        assertEquals(8, config.getInt("outposts.min-distance-from-other-city"));
        assertEquals(100, config.getInt("outposts.teleport-cost"));
        assertEquals(8, config.getInt("outposts.teleport-warmup-seconds"));
        assertEquals(180, config.getInt("outposts.teleport-cooldown-seconds"));
    }

    @Test
    @DisplayName("cities.yml declares the five SPEC 5.4 default ranks with their weights")
    void defaultRanks() {
        FileConfiguration config = load(ConfigFile.CITIES);

        Map<String, Integer> expected = Map.of(
                "mayor", 100,
                "co-mayor", 80,
                "architect", 60,
                "citizen", 40,
                "recruit", 20);

        assertEquals(expected.keySet(), config.getConfigurationSection("ranks").getKeys(false));
        expected.forEach((key, weight) ->
                assertEquals(weight, config.getInt("ranks." + key + ".weight"), "weight of rank " + key));

        assertTrue(config.getBoolean("ranks.recruit.default"), "Recruit is the rank new joiners receive");
    }

    @Test
    @DisplayName("cities.yml declares the six SPEC 5.7 upgrades with five levels each")
    void upgradeCostLadders() {
        FileConfiguration config = load(ConfigFile.CITIES);

        Map<String, List<Integer>> expected = Map.of(
                "population", List.of(20000, 50000, 120000, 280000, 600000),
                "vault", List.of(30000, 70000, 150000, 320000, 700000),
                "treasury-interest", List.of(40000, 90000, 200000, 420000, 900000),
                "outpost-range", List.of(60000, 140000, 300000, 650000, 1400000),
                "fortification", List.of(50000, 110000, 240000, 500000, 1100000),
                "market-access", List.of(45000, 100000, 220000, 460000, 1000000));

        assertEquals(expected.keySet(), config.getConfigurationSection("upgrades").getKeys(false));
        expected.forEach((key, costs) ->
                assertEquals(costs, config.getIntegerList("upgrades." + key + ".costs"), "costs of " + key));

        assertEquals(6, config.getInt("upgrades.outpost-range.max-total"),
                "SPEC 7.2: outposts cap at 6 with the upgrade maxed");
    }

    // --- SPEC 16.3, war.yml ----------------------------------------------------------

    @Test
    @DisplayName("war.yml carries the SPEC 16.3 defaults")
    void warDefaults() {
        FileConfiguration config = load(ConfigFile.WAR);

        assertEquals(3, config.getInt("declaration.min-members"));
        assertEquals(10, config.getInt("declaration.min-claims"));
        assertEquals(14, config.getInt("declaration.min-city-age-days"));
        assertEquals(50000, config.getInt("declaration.min-wager"));
        assertEquals(25, config.getInt("declaration.max-wager-percent-of-smaller-treasury"));
        assertEquals(21, config.getInt("declaration.same-opponent-cooldown-days"));
        assertEquals(6, config.getInt("declaration.decline-window-hours"));
        assertEquals(30, config.getInt("declaration.decline-penalty-percent"));
        assertTrue(config.getBoolean("declaration.large-vs-small-block"));
        assertEquals(20, config.getInt("declaration.large-city-member-threshold"));
        assertEquals(5, config.getInt("declaration.small-city-member-threshold"));

        assertEquals(48, config.getInt("phases.prep-hours"));
        assertEquals(7, config.getInt("phases.active-days"));
        assertEquals(1, config.getInt("zone.perimeter-chunks"));

        assertEquals(10, config.getInt("scoring.kill"));
        assertEquals(25, config.getInt("scoring.capture-point-hold"));
        assertEquals(60, config.getInt("scoring.capture-hold-seconds"));
        assertEquals(15, config.getInt("scoring.destroy-defense-unit"));
        assertEquals(0.1, config.getDouble("scoring.block-break"), 1e-9);
        assertEquals(500, config.getInt("scoring.block-break-score-cap"));
        assertEquals(100, config.getInt("scoring.city-hall-reach"));
        assertEquals(5, config.getInt("scoring.draw-threshold-percent"));

        assertEquals(80, config.getInt("rewards.winner-wager-share-percent"));
        assertEquals(20, config.getInt("rewards.loser-refund-percent"));
        assertEquals(20, config.getInt("rewards.burn-percent"));
        assertEquals(30, config.getInt("rewards.ally-payout-share-percent"));
        assertEquals(10, config.getInt("rewards.winner-market-bonus-percent"));
        assertEquals(7, config.getInt("rewards.winner-market-bonus-days"));
        assertEquals(7, config.getInt("rewards.immunity-days"));
    }

    @Test
    @DisplayName("rollback defaults to enabled and never ships otherwise")
    void rollbackIsEnabledByDefault() {
        FileConfiguration config = load(ConfigFile.WAR);

        assertTrue(config.getBoolean("rollback.enabled"),
                "PLAN.md hard rule: rollback.enabled must never default to false");
        assertEquals(400, config.getInt("rollback.blocks-per-tick"));
        assertEquals(5000, config.getInt("rollback.checkpoint-every-blocks"));
        assertEquals(2, config.getInt("rollback.verify-sample-percent"));
        assertTrue(config.getBoolean("rollback.chunk-hash-failsafe"));
        assertTrue(config.getBoolean("rollback.suppress-block-drops"));
        assertTrue(config.getBoolean("rollback.restore-entities"));
        assertTrue(config.getBoolean("rollback.restore-container-nbt"));
        assertTrue(config.getBoolean("rollback.loot-is-permanent"));
        assertTrue(config.getBoolean("rollback.vault-immune"));
    }

    // --- SPEC 4, economy.yml ---------------------------------------------------------

    @Test
    @DisplayName("economy.yml carries the SPEC 4.2 to 4.4 defaults")
    void economyDefaults() {
        FileConfiguration config = load(ConfigFile.ECONOMY);

        assertEquals(2000, config.getInt("income.starting-balance"));
        assertEquals(40, config.getInt("income.stipend.amount"));
        assertEquals(15, config.getInt("income.stipend.interval-minutes"));
        assertEquals(3, config.getInt("income.stipend.required-actions"));
        assertEquals(640, config.getInt("income.stipend.daily-cap"));
        assertEquals(32, config.getInt("income.stipend.move-distance-blocks"));

        assertEquals(250, config.getInt("income.daily-login.base"));
        assertEquals(125, config.getInt("income.daily-login.streak-bonus"));
        assertEquals(1000, config.getInt("income.daily-login.max"));
        assertEquals(48, config.getInt("income.daily-login.streak-reset-hours"));

        assertEquals(1.5, config.getDouble("income.newcomer.multiplier"), 1e-9);
        assertEquals(14, config.getInt("income.newcomer.days"));

        assertEquals(50000, config.getInt("income.contest-prizes.first"));
        assertEquals(25000, config.getInt("income.contest-prizes.second"));
        assertEquals(12000, config.getInt("income.contest-prizes.third"));

        assertEquals(5, config.getInt("sinks.market-sale-tax-percent"));
        assertEquals(0.45, config.getDouble("market.default-elasticity"), 1e-9);
        assertEquals(0.25, config.getDouble("market.clamp-min"), 1e-9);
        assertEquals(3.0, config.getDouble("market.clamp-max"), 1e-9);
        assertEquals(1.35, config.getDouble("market.buy-spread"), 1e-9);
        assertEquals(2.0, config.getDouble("market.stock-decay-percent-per-hour"), 1e-9);

        assertEquals(0, config.getInt("player-shops.tax-percent"),
                "SPEC 4.5: player shops are deliberately untaxed");
        assertEquals(1000, config.getInt("bounties.minimum"));
        assertEquals(30, config.getInt("bounties.expiry-days"));
    }

    @Test
    @DisplayName("the SPEC 4.4 market table is present with its documented prices")
    void marketItemTable() {
        FileConfiguration config = load(ConfigFile.ECONOMY);

        record Item(String material, int basePrice, int targetStock, double elasticity) { }
        List<Item> expected = List.of(
                new Item("WHEAT", 3, 20000, 0.40),
                new Item("CARROT", 3, 20000, 0.40),
                new Item("POTATO", 3, 20000, 0.40),
                new Item("PUMPKIN", 5, 12000, 0.45),
                new Item("MELON_SLICE", 2, 30000, 0.40),
                new Item("SUGAR_CANE", 4, 15000, 0.45),
                new Item("COCOA_BEANS", 6, 8000, 0.50),
                new Item("BAMBOO", 1, 40000, 0.35),
                new Item("IRON_INGOT", 45, 6000, 0.55),
                new Item("GOLD_INGOT", 70, 4000, 0.55),
                new Item("DIAMOND", 400, 1500, 0.60),
                new Item("EMERALD", 250, 2000, 0.60),
                new Item("NETHERITE_SCRAP", 3500, 200, 0.70),
                new Item("OAK_LOG", 4, 25000, 0.40),
                new Item("STONE", 1, 50000, 0.30),
                new Item("BEEF", 8, 10000, 0.45),
                new Item("LEATHER", 12, 6000, 0.50),
                new Item("HONEY_BOTTLE", 25, 3000, 0.55),
                new Item("NETHER_WART", 8, 8000, 0.50));

        for (Item item : expected) {
            String path = "market.items." + item.material();
            assertEquals(item.basePrice(), config.getInt(path + ".base-price"), path + " base price");
            assertEquals(item.targetStock(), config.getInt(path + ".target-stock"), path + " target stock");
            assertEquals(item.elasticity(), config.getDouble(path + ".elasticity"), 1e-9, path + " elasticity");
        }
    }

    // --- SPEC 12, defense.yml --------------------------------------------------------

    static Stream<org.junit.jupiter.params.provider.Arguments> defenseUnits() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("watchman", "ZOMBIE", 40, 8000, 400),
                org.junit.jupiter.params.provider.Arguments.of("city-guard", "ZOMBIE", 100, 20000, 900),
                org.junit.jupiter.params.provider.Arguments.of("elite-guard", "ZOMBIE", 160, 45000, 2000),
                org.junit.jupiter.params.provider.Arguments.of("archer", "SKELETON", 60, 15000, 700),
                org.junit.jupiter.params.provider.Arguments.of("sharpshooter", "SKELETON", 90, 32000, 1400),
                org.junit.jupiter.params.provider.Arguments.of("warhound", "WOLF", 45, 10000, 500),
                org.junit.jupiter.params.provider.Arguments.of("siege-golem", "IRON_GOLEM", 250, 60000, 3000),
                org.junit.jupiter.params.provider.Arguments.of("sentry", "SNOW_GOLEM", 30, 6000, 300));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("defenseUnits")
    @DisplayName("the SPEC 12.2 unit catalogue is present with its documented stats")
    void defenseUnitCatalogue(String key, String mob, int health, int cost, int upkeep) {
        FileConfiguration config = load(ConfigFile.DEFENSE);
        String path = "units." + key;

        assertEquals(mob, config.getString(path + ".mob"));
        assertEquals(health, config.getInt(path + ".health"));
        assertEquals(cost, config.getInt(path + ".cost"));
        assertEquals(upkeep, config.getInt(path + ".upkeep-per-day"));
    }

    @Test
    @DisplayName("defense.yml carries the SPEC 12.3 and 12.4 placement rules")
    void defensePlacementRules() {
        FileConfiguration config = load(ConfigFile.DEFENSE);

        assertEquals(5, config.getInt("placement.base-max-active-units"));
        assertEquals(2, config.getInt("placement.units-per-fortification-level"));
        assertEquals(3, config.getInt("placement.max-units-per-chunk"));
        assertEquals(2.0, config.getDouble("placement.wartime-purchase-multiplier"), 1e-9);
        assertEquals(24, config.getInt("behaviour.war-target-range"));
        assertEquals(8, config.getInt("behaviour.leash-distance-blocks"));
        assertEquals(16, config.getInt("behaviour.name-visible-range"));
    }

    // --- SPEC 13, events.yml ---------------------------------------------------------

    @Test
    @DisplayName("the SPEC 13.5 event catalogue is present with its documented durations")
    void eventCatalogue() {
        FileConfiguration config = load(ConfigFile.EVENTS);

        Map<String, Integer> durations = Map.of(
                "market-boom", 6,
                "market-crash", 6,
                "harvest-festival", 24,
                "gold-rush", 12,
                "invasion", 4,
                "founders-week", 168,
                "double-upkeep", 24,
                "tax-holiday", 24);

        assertEquals(durations.keySet(), config.getConfigurationSection("events.definitions").getKeys(false));
        durations.forEach((key, hours) -> assertEquals(hours,
                config.getInt("events.definitions." + key + ".duration-hours"), "duration of " + key));

        assertEquals(30, config.getInt("events.announce-minutes-before"));
    }

    @Test
    @DisplayName("events.yml carries the SPEC 13.4 contest cycle")
    void contestCycle() {
        FileConfiguration config = load(ConfigFile.EVENTS);

        assertEquals(14, config.getInt("contests.cycle-days"));
        assertEquals(11, config.getInt("contests.build-days"));
        assertEquals(2, config.getInt("contests.voting-days"));
        assertEquals(64, config.getInt("contests.max-region-size"));
        assertEquals(1, config.getInt("contests.vote-min"));
        assertEquals(10, config.getInt("contests.vote-max"));
        assertEquals(5, config.getInt("contests.anti-abuse.low-playtime-hours"));
        assertEquals(0.25, config.getDouble("contests.anti-abuse.low-playtime-vote-weight"), 1e-9);
        assertTrue(config.getStringList("contests.themes").contains("Medieval Market"));
    }
}
