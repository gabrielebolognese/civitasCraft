package dev.civitas.gui.menus;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.CivitasServices;
import dev.civitas.config.PluginResources;
import dev.civitas.core.city.CityHall;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.city.SpawnService;
import dev.civitas.core.economy.UpkeepTask;
import dev.civitas.core.income.ChallengeService;
import dev.civitas.core.income.IncomeMultipliers;
import dev.civitas.core.income.QuestPool;
import dev.civitas.core.income.QuestService;
import dev.civitas.core.outpost.OutpostRegistry;
import dev.civitas.core.outpost.OutpostService;
import dev.civitas.core.outpost.OutpostTravel;
import dev.civitas.core.defense.DefenseBehaviour;
import dev.civitas.core.defense.DefenseCatalogue;
import dev.civitas.core.defense.DefenseRegistry;
import dev.civitas.core.defense.DefenseService;
import dev.civitas.core.defense.DefenseSpawner;
import dev.civitas.core.diplomacy.DiplomacyRegistry;
import dev.civitas.core.diplomacy.DiplomacyService;
import dev.civitas.core.progression.LeaderboardService;
import dev.civitas.core.progression.StatsService;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.vault.VaultService;
import dev.civitas.core.vault.VaultView;
import dev.civitas.gui.framework.AmountInput;
import dev.civitas.gui.framework.LayoutLoader;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.lang.LangManager;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Scheduler;
import org.bukkit.plugin.Plugin;

/**
 * A whole services record over the real city stack, so the SPEC Section 8 screens can be
 * opened and clicked in tests.
 *
 * <p>Built from {@link CityTestSupport} rather than duplicating it, so the screens are driven
 * by the same services the server runs, over the same SQLite database and the same shipped
 * configuration.
 */
final class MenuTestSupport implements AutoCloseable {

    final CityTestSupport cities;
    final MenuManager menus;
    final AmountInput input;
    final CivitasServices services;
    final LangManager lang;
    final dev.civitas.core.war.WarRegistry wars;
    final dev.civitas.core.war.PeaceOffer peace;
    final dev.civitas.core.war.CapturePoints capturePoints;
    final dev.civitas.core.war.WarScoreboard scoreboard;
    final dev.civitas.core.economy.BountyService bounties;

    private MenuTestSupport(Path directory, Plugin plugin) {
        this.cities = CityTestSupport.open(directory);

        File dataFolder = directory.resolve("plugin").toFile();
        PluginResources resources = PluginResources.ofClasspath(dataFolder, quiet());
        this.lang = new LangManager(resources, cities.configs);
        lang.load();

        this.menus = new MenuManager(cities.configs, lang);
        this.input = new AmountInput(menus, lang, Scheduler.direct());

        LayoutLoader layouts = new LayoutLoader(resources);
        UpkeepTask upkeep = new UpkeepTask(cities.db, cities.daos, cities.registry, cities.claims,
                cities.treasury, cities.upkeep, (member, key, extra) -> { }, Scheduler.direct(),
                quiet(), java.time.ZoneId.of("UTC"));
        IncomeMultipliers multipliers = new IncomeMultipliers(cities.configs);
        QuestPool questPool = new QuestPool(cities.configs, quiet());
        questPool.load("income.quests.pool");
        QuestPool challengePool = new QuestPool(cities.configs, quiet());
        challengePool.load("income.challenges.pool");
        QuestService quests = new QuestService(cities.db, cities.daos.playerQuests(),
                cities.daos.players(), cities.economy, questPool, multipliers, cities.configs,
                (player, key, extra) -> { }, java.time.ZoneId.of("UTC"));
        ChallengeService challenges = new ChallengeService(cities.db,
                cities.daos.cityChallenges(), cities.registry, cities.treasury, challengePool,
                cities.configs, (player, key, extra) -> { }, java.time.ZoneId.of("UTC"));

        UpgradeService upgradeService = new UpgradeService(cities.db,
                cities.daos.cityUpgrades(), cities.treasury, cities.configs, Scheduler.direct());
        VaultService vaultService = new VaultService(cities.daos.cityVault(), upgradeService,
                cities.configs);
        VaultView vaultView = new VaultView(plugin, vaultService, lang, quiet());
        cities.cities.useUpgrades(upgradeService);
        cities.market.useUpgrades(cities.registry, upgradeService);

        OutpostRegistry outpostRegistry = new OutpostRegistry(cities.daos.outposts());
        OutpostService outposts = new OutpostService(cities.db, cities.daos, cities.registry,
                cities.claimRegistry, cities.claims, outpostRegistry, cities.treasury,
                cities.configs, Scheduler.direct());
        outposts.useUpgrades(upgradeService);
        dev.civitas.core.travel.TeleportService teleportService =
                new dev.civitas.core.travel.TeleportService(null, cities.configs,
                        cities.economy, lang, CityTestSupport.quietLogger());
        OutpostTravel outpostTeleport = new OutpostTravel(outposts, teleportService);

        DefenseCatalogue defenseCatalogue = new DefenseCatalogue(cities.configs, quiet());
        defenseCatalogue.load();
        DefenseRegistry defenseRegistry = new DefenseRegistry(cities.daos.defenseUnits());
        DefenseService defenseService = new DefenseService(plugin, cities.db,
                cities.daos.defenseUnits(), defenseRegistry, defenseCatalogue,
                new DefenseSpawner(plugin, defenseCatalogue, lang), cities.registry,
                cities.claimRegistry, cities.treasury, upgradeService, lang, Scheduler.direct());
        dev.civitas.core.defense.WardenRegistry wardenRegistry =
                new dev.civitas.core.defense.WardenRegistry(cities.daos.cityWardens());
        dev.civitas.core.defense.WardenService wardenService =
                new dev.civitas.core.defense.WardenService(cities.db, cities.daos.cityWardens(),
                        cities.daos.defenseUnits(), wardenRegistry, defenseRegistry,
                        defenseCatalogue, cities.registry, cities.treasury, upgradeService,
                        Scheduler.direct());

        DiplomacyRegistry diplomacyRegistry = new DiplomacyRegistry(cities.daos.alliances(),
                cities.daos.truces());
        DiplomacyService diplomacyService = new DiplomacyService(cities.db, cities.daos,
                cities.registry, diplomacyRegistry, cities.configs, Scheduler.direct());
        cities.protection.useDiplomacy(diplomacyRegistry);

        SpawnService spawns = new SpawnService(plugin, cities.registry, cities.configs, lang);
        CityHall halls = new CityHall(plugin, cities.configs, lang);

        StatsService stats = new StatsService(cities.daos.playerStats(), quiet());
        LeaderboardService leaderboards = new LeaderboardService(cities.daos.players(),
                cities.daos.ledger(), cities.daos.playerStats(), cities.daos.contestEntries(),
                cities.registry, cities.claimRegistry, cities.claims, cities.configs, quiet());

        // SPEC 8.8's screen reads the war system, so the menu tests need a real one rather
        // than the nulls that stood in while M19 did not exist.
        this.wars = new dev.civitas.core.war.WarRegistry(cities.daos.wars());
        dev.civitas.core.war.WarService warService = new dev.civitas.core.war.WarService(
                cities.db, cities.daos, cities.registry, cities.claimRegistry, diplomacyRegistry,
                wars, cities.treasury, cities.configs, Scheduler.direct());
        dev.civitas.core.war.WarAllies warAllies = new dev.civitas.core.war.WarAllies(cities.db,
                cities.daos, cities.registry, diplomacyRegistry, wars, cities.treasury,
                cities.configs, Scheduler.direct());
        this.peace = new dev.civitas.core.war.PeaceOffer(cities.db, cities.daos, cities.registry,
                cities.treasury, cities.configs, Scheduler.direct());
        this.bounties = new dev.civitas.core.economy.BountyService(cities.db,
                cities.daos.bounties(), cities.economy, cities.configs,
                Scheduler.direct(), quiet());
        this.capturePoints = new dev.civitas.core.war.CapturePoints(
                new dev.civitas.core.war.WarScoring(cities.configs));
        this.scoreboard = new dev.civitas.core.war.WarScoreboard(wars, cities.registry, lang);

        this.services = new CivitasServices(cities.registry, cities.cities, cities.ranks,
                cities.claimRegistry, cities.claims, null, null, cities.protection, null, null,
                cities.economy, cities.treasury, bounties,
                new dev.civitas.core.economy.MoneySupplyService(cities.daos, cities.economy,
                        cities.treasury, cities.configs, CityTestSupport.quietLogger()),
                new dev.civitas.core.economy.LedgerRollback(cities.db, cities.daos,
                        cities.economy, cities.registry),
                cities.upkeep, upkeep, cities.market,
                cities.marketFilter,
                new dev.civitas.msg.TogglePreferences(cities.daos.playerToggles(),
                        CityTestSupport.quietLogger()),
                new dev.civitas.msg.Messenger(lang,
                        cities.configs,
                        new dev.civitas.msg.TogglePreferences(cities.daos.playerToggles(),
                                CityTestSupport.quietLogger())),
                teleportService,
                new dev.civitas.core.travel.RandomTeleport(null, cities.configs,
                        cities.claimRegistry),
                new dev.civitas.core.travel.WarpService(cities.daos.warps(),
                        CityTestSupport.quietLogger()),
                new dev.civitas.core.mining.MiningClaimService(cities.db,
                        cities.daos.miningClaims(),
                        new dev.civitas.core.mining.MiningClaimRegistry(
                                cities.daos.miningClaims(), CityTestSupport.quietLogger()),
                        cities.economy,
                        new dev.civitas.core.world.WorldRegistry(cities.configs),
                        cities.configs, dev.civitas.util.Scheduler.direct()),
                new dev.civitas.core.waystation.WaystationService(cities.db, cities.daos,
                        new dev.civitas.core.waystation.WaystationRegistry(
                                cities.daos.waystations()),
                        cities.treasury,
                        new dev.civitas.core.mining.MiningClaimRegistry(
                                cities.daos.miningClaims(), CityTestSupport.quietLogger()),
                        cities.configs, dev.civitas.util.Scheduler.direct()),
                cities.shops, quests, challenges, leaderboards, stats,
                cities.contests, cities.serverEvents, warService, warAllies, peace,
                capturePoints,
                new dev.civitas.core.war.RollbackEngine(cities.daos, cities.configs,
                        new dev.civitas.core.war.BukkitTilePayloadCodec(quiet()),
                        new dev.civitas.core.war.ChunkHasher(cities.configs), quiet()),
                war -> { },
                new dev.civitas.core.admin.AuditService(cities.daos.auditLog(), quiet()),
                new dev.civitas.core.admin.AdminProtection(cities.daos.protectedChunks(),
                        quiet()),
                new dev.civitas.core.admin.FraudHeuristics(cities.configs),
                new dev.civitas.core.admin.InspectMode(cities.claimRegistry, cities.registry),
                new dev.civitas.core.admin.LedgerExport(dataFolder),
                new dev.civitas.core.admin.UpkeepOverrides(
                        cities.daos.upkeepMultipliers(), quiet()),
                new dev.civitas.core.moderation.ReportService(cities.daos, cities.configs),
                new dev.civitas.storage.BackupService(quiet(), cities.db,
                        directory.resolve("backups").toFile()),
                // SPEC 32.8's world backups. Real here rather than null, because it is cheap
                // and because a fixture that hands out null teaches nothing when a screen
                // eventually reads one.
                new dev.civitas.core.world.WorldBackupService(quiet(),
                        directory.resolve("world-backups"),
                        name -> java.util.Optional.empty(),
                        new dev.civitas.core.world.WorldRegistry(cities.configs)
                                .backupSettings()),
                new dev.civitas.core.world.WorldRegistry(cities.configs),
                28,
                java.util.List::of,
                () -> 0,
                // /ca perf's reading. Zeroes with timings off, which is what a test fixture
                // that never wired a profiler should report.
                () -> new dev.civitas.core.admin.PerfReport(0, 0,
                        dev.civitas.util.Timings.disabled()
                                .snapshot(dev.civitas.util.Timings.Metric.CLAIM_LOOKUP),
                        dev.civitas.util.Timings.disabled()
                                .snapshot(dev.civitas.util.Timings.Metric.GUI_OPEN),
                        0, 0.0, 0, dev.civitas.storage.DatabaseManager.PoolStatus.closed(),
                        0, false),
                cities.daos,
                scoreboard, outposts,
                outpostTeleport, upgradeService, defenseService, wardenService,
                // No menu reads siege: SPEC 29 defines none, so the GUI fixture does not
                // build the service. A screen that needed it would fail loudly here.
                null, null,
                diplomacyService, vaultService,
                vaultView, menus, layouts,
                input, spawns, halls,
                cities.accounts, new PlayerLookup(cities.daos.players()), Scheduler.direct());
    }

    static MenuTestSupport open(Path directory, Plugin plugin) {
        return new MenuTestSupport(directory, plugin);
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("civitas-menu-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    @Override
    public void close() {
        cities.close();
    }
}
