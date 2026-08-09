package dev.civitas;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import dev.civitas.command.CommandRegistry;
import dev.civitas.command.city.CityCommand;
import dev.civitas.command.player.MoneyCommand;
import dev.civitas.command.admin.AdminCommand;
import dev.civitas.command.player.BountyCommand;
import dev.civitas.command.player.ReportCommand;
import dev.civitas.command.player.PayCommand;
import dev.civitas.command.diplomacy.AllianceChatCommand;
import dev.civitas.command.diplomacy.AllyCommand;
import dev.civitas.command.player.ContestCommand;
import dev.civitas.command.player.LeaderboardCommand;
import dev.civitas.command.player.QuestsCommand;
import dev.civitas.command.player.SellCommand;
import dev.civitas.command.player.ShopCommand;
import dev.civitas.command.player.WorthCommand;
import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.CityNameValidator;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.city.CityHall;
import dev.civitas.core.city.CityService;
import dev.civitas.core.city.SpawnService;
import dev.civitas.core.city.RankService;
import dev.civitas.core.claim.BorderRenderer;
import dev.civitas.core.claim.ClaimCostEngine;
import dev.civitas.core.claim.ClaimMap;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.core.economy.PlayerAccountService;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.InflationTracker;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.core.economy.UpkeepCalculator;
import dev.civitas.core.economy.UpkeepTask;
import dev.civitas.core.income.ActivityTracker;
import dev.civitas.core.income.ChallengeService;
import dev.civitas.core.income.DailyLoginService;
import dev.civitas.core.income.IncomeMultipliers;
import dev.civitas.core.income.IncomeReporter;
import dev.civitas.core.income.QuestPool;
import dev.civitas.core.income.QuestService;
import dev.civitas.core.income.StipendTask;
import dev.civitas.core.contest.ContestCycle;
import dev.civitas.core.contest.ContestService;
import dev.civitas.core.contest.LoginFingerprint;
import dev.civitas.core.contest.VoteWeighting;
import dev.civitas.core.events.BroadcastAnnouncer;
import dev.civitas.core.events.EventBossBar;
import dev.civitas.core.events.EventScheduler;
import dev.civitas.core.events.EventService;
import dev.civitas.core.events.InvasionWaves;
import dev.civitas.core.progression.LeaderboardService;
import dev.civitas.core.progression.StatsService;
import dev.civitas.core.war.BukkitTilePayloadCodec;
import dev.civitas.core.war.WarBlockLogger;
import dev.civitas.core.war.WarBlockRecorder;
import dev.civitas.core.war.WarZones;
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
import dev.civitas.core.diplomacy.DiplomacyTask;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.vault.VaultService;
import dev.civitas.core.vault.VaultView;
import dev.civitas.core.market.MarketItemFilter;
import dev.civitas.core.market.MarketPricing;
import dev.civitas.core.market.MarketRegistry;
import dev.civitas.core.market.MarketService;
import dev.civitas.core.market.StockDecayTask;
import dev.civitas.core.protection.BlockClassifier;
import dev.civitas.core.protection.ProtectionGuard;
import dev.civitas.core.protection.ProtectionService;
import dev.civitas.core.shop.PlayerShopService;
import dev.civitas.core.shop.ShopSign;
import dev.civitas.gui.framework.AmountInput;
import dev.civitas.gui.framework.LayoutLoader;
import dev.civitas.gui.framework.MenuListener;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.lang.LangManager;
import dev.civitas.listener.BlockProtectionListener;
import dev.civitas.listener.ActivityListener;
import dev.civitas.listener.CityChatListener;
import dev.civitas.listener.IncomeJoinListener;
import dev.civitas.listener.CityHallListener;
import dev.civitas.listener.ClaimBoundaryListener;
import dev.civitas.listener.ContainerProtectionListener;
import dev.civitas.listener.EntityProtectionListener;
import dev.civitas.listener.ExplosionProtectionListener;
import dev.civitas.listener.FireAndFluidListener;
import dev.civitas.listener.InteractionProtectionListener;
import dev.civitas.listener.PistonProtectionListener;
import dev.civitas.listener.PlayerAccountListener;
import dev.civitas.listener.TeleportWarmupListener;
import dev.civitas.listener.DefenseListener;
import dev.civitas.listener.VaultListener;
import dev.civitas.listener.ShopInteractListener;
import dev.civitas.listener.ShopSignListener;
import dev.civitas.storage.BackupService;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.DatabaseSettings;
import dev.civitas.integration.PlaceholderApiHook;
import dev.civitas.integration.VaultEconomyProvider;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.util.EventBus;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Scheduler;
import dev.civitas.util.Timings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point and lifecycle owner.
 *
 * <p>Construction order is dependency order: configuration, language, commands, then
 * storage and the services that sit on it.
 *
 * <p>The database opens on an async task, because migrations can take seconds and SPEC 2.1
 * forbids blocking the server thread on storage. Commands must be registered before that
 * finishes, so they are handed {@link #services()} as a supplier and refuse politely while
 * it still returns null.
 */
public final class CivitasPlugin extends JavaPlugin {

    private static final long TICKS_PER_HOUR = 20L * 60L * 60L;

    /** The income pieces the plugin keeps a handle on, so disable can clear them. */
    private record IncomeSystems(ActivityTracker activity, QuestService quests,
                                 ChallengeService challenges, DailyLoginService dailyLogin,
                                 IncomeMultipliers multipliers) { }

    private ConfigManager configs;
    private dev.civitas.core.abuse.PlacedBlockCache placedBlocks;
    private dev.civitas.core.combat.PvpPolicy pvpPolicy;
    private LangManager lang;
    private StatsService stats;
    private EventBossBar eventBar;
    private WarBlockLogger warBlockLog;
    private dev.civitas.core.war.WarLootLog warLootLog;
    private dev.civitas.core.war.RollbackEngine rollbackEngine;
    private DatabaseManager database;
    private DaoRegistry daos;
    private BackupService backups;
    private BorderRenderer borders;
    private MenuManager menus;
    private SpawnService spawns;
    private IncomeSystems income;
    private OutpostTravel outposts;
    private dev.civitas.core.travel.TeleportService travel;
    private VaultView vaults;

    private final AtomicReference<CivitasServices> services = new AtomicReference<>();

    @Override
    public void onEnable() {
        configs = new ConfigManager(this);
        configs.loadAll();

        lang = new LangManager(this, configs);
        lang.load();

        Scheduler scheduler = Scheduler.bukkit(this);
        CityCommand cityCommand = new CityCommand(services::get, lang, scheduler, getLogger());
        MoneyCommand moneyCommand = new MoneyCommand(services::get, lang, scheduler, getLogger());
        PayCommand payCommand = new PayCommand(services::get, lang, scheduler, getLogger());
        BountyCommand bountyCommand =
                new BountyCommand(services::get, lang, scheduler, getLogger());
        AdminCommand adminCommand =
                new AdminCommand(services::get, lang, scheduler, getLogger(), this::reloadConfigs);
        ReportCommand reportCommand =
                new ReportCommand(services::get, lang, scheduler, getLogger());
        ShopCommand shopCommand = new ShopCommand(services::get, lang, scheduler, getLogger());
        SellCommand sellCommand = new SellCommand(services::get, lang, scheduler, getLogger());
        WorthCommand worthCommand = new WorthCommand(services::get, lang);
        dev.civitas.command.player.QuotaCommand quotaCommand =
                new dev.civitas.command.player.QuotaCommand(services::get, lang, scheduler);
        dev.civitas.command.player.ToggleCommand toggleCommand =
                new dev.civitas.command.player.ToggleCommand(services::get, lang, scheduler);
        dev.civitas.command.player.TravelCommands travelCommands =
                new dev.civitas.command.player.TravelCommands(services::get, lang, scheduler);
        dev.civitas.command.player.MineCommand mineCommand =
                new dev.civitas.command.player.MineCommand(services::get, lang, scheduler);
        QuestsCommand questsCommand = new QuestsCommand(services::get, lang, scheduler);
        AllyCommand allyCommand = new AllyCommand(services::get, lang, scheduler, getLogger());
        AllianceChatCommand allianceChat = new AllianceChatCommand(services::get, lang);
        LeaderboardCommand leaderboardCommand = new LeaderboardCommand(services::get, lang);
        dev.civitas.command.war.WarCommand warCommand =
                new dev.civitas.command.war.WarCommand(services::get, lang, scheduler, getLogger());
        ContestCommand contestCommand =
                new ContestCommand(services::get, lang, scheduler, getLogger());
        dev.civitas.command.city.CityChatCommand cityChat =
                new dev.civitas.command.city.CityChatCommand(services::get, lang);
        dev.civitas.command.player.CivitasCommand civitasCommand =
                new dev.civitas.command.player.CivitasCommand(this, lang);
        new CommandRegistry(this, lang).registerAll(
                List.of(cityCommand.build(), moneyCommand.build(), payCommand.build(),
                        shopCommand.build(), sellCommand.build(), worthCommand.build(),
                        quotaCommand.build(), toggleCommand.build(),
                        travelCommands.buildSpawn(), travelCommands.buildRtp(),
                        travelCommands.buildWarp(), mineCommand.build(),
                        questsCommand.buildQuests(), questsCommand.buildChallenges(),
                        allyCommand.buildAlly(), allyCommand.buildTruce(),
                        allianceChat.build(), leaderboardCommand.build(),
                        contestCommand.build(), warCommand.build(),
                        bountyCommand.build(), adminCommand.build(),
                        reportCommand.build(), cityChat.build(), civitasCommand.build()));

        warnIfRollbackDisabled();
        openDatabaseAsync(scheduler);

        getLogger().info(() -> "Enabled version " + getPluginMeta().getVersion()
                + ", language " + lang.activeLanguage() + ".");
    }

    @Override
    public void onDisable() {
        // Blocking here is correct: SPEC 17.7 case 84 requires buffered writes to reach
        // disk before the server exits, and there is no later opportunity.
        CivitasServices current = services.getAndSet(null);
        if (current != null) {
            current.accounts().clearSessions();
        }
        if (vaults != null) {
            // SPEC 17.7 case 84's reasoning: an unsaved vault page on shutdown is a city's
            // valuables gone, so this blocks.
            vaults.saveAndCloseAll();
            vaults = null;
        }
        if (travel != null) {
            travel.stopAll();
            travel = null;
        }
        outposts = null;
        if (rollbackEngine != null) {
            // SPEC 11.8.5: a checkpoint that never reached disk is work the next boot
            // repeats. Waiting costs a moment here; not waiting costs it on every restart.
            rollbackEngine.awaitAllCheckpoints();
            rollbackEngine = null;
        }
        if (warBlockLog != null) {
            // SPEC 17.7 case 84: "Flush the log buffer synchronously in onDisable(). Never
            // lose buffered entries." A dropped entry here is a block that can never be
            // restored, so this blocks and it goes before the pool closes.
            warBlockLog.flushBlocking();
            warBlockLog = null;
        }
        if (warLootLog != null) {
            // SPEC 11.7's log is evidence rather than state, so it does not block the way the
            // block log does; it is still flushed, because an unwritten theft is a dispute an
            // admin cannot settle.
            warLootLog.flushBlocking();
            warLootLog = null;
        }
        if (eventBar != null) {
            // A bar left on screen outlives the plugin that owns it and tells every player
            // something false.
            eventBar.hide();
            eventBar = null;
        }
        if (stats != null) {
            // Before the pool closes, for the reason SPEC 17.7 case 84 gives: this is the last
            // chance, and a buffer dropped here is career totals quietly going backwards.
            stats.flushBlocking(System.currentTimeMillis());
            stats = null;
        }
        if (income != null) {
            income.activity().clear();
            income = null;
        }
        if (spawns != null) {
            spawns.stopAll();
            spawns = null;
        }
        if (menus != null) {
            // Nobody should be left holding a window into a plugin that is gone.
            menus.closeAll();
            menus = null;
        }
        if (borders != null) {
            borders.stopAll();
            borders = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
        daos = null;
        backups = null;
        getLogger().info("Disabled.");
    }

    public ConfigManager configs() {
        return configs;
    }

    public LangManager lang() {
        return lang;
    }

    /** @return the database, or {@code null} until the async open has completed */
    public DatabaseManager database() {
        return database;
    }

    /** @return the DAOs, or {@code null} until the async open has completed */
    public DaoRegistry daos() {
        return daos;
    }

    /** @return the services, or {@code null} until the async open has completed */
    public CivitasServices services() {
        return services.get();
    }

    private void openDatabaseAsync(Scheduler scheduler) {
        DatabaseSettings settings =
                DatabaseSettings.from(configs.get(ConfigFile.CONFIG), getDataFolder());

        DatabaseManager manager =
                new DatabaseManager(getLogger(), settings, Bukkit::isPrimaryThread);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                manager.open();
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Could not open the database; disabling CivitasCraft.", e);
                Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                return;
            }

            DaoRegistry loadedDaos = new DaoRegistry(manager);
            CityRegistry cityRegistry = new CityRegistry(loadedDaos);
            ClaimRegistry claimRegistry = new ClaimRegistry(loadedDaos.claims());

            try {
                int cityCount = cityRegistry.loadAll().join();
                int claimCount = claimRegistry.loadAll().join();
                getLogger().info(() -> "Loaded " + cityCount + " cities and "
                        + claimCount + " claims into the cache.");

                // SPEC 17.2 case 21's "warn on startup". Runs here rather than earlier
                // because it needs the claims loaded to know which worlds hold any.
                new dev.civitas.core.world.WorldRegistry(configs, getLogger())
                        .auditClaimedWorlds(claimRegistry.allClaims().stream()
                                .map(dev.civitas.core.claim.Claim::world)
                                .collect(java.util.stream.Collectors.toCollection(
                                        java.util.TreeSet::new)));
                int configuredCacheSize = configs.get(ConfigFile.CONFIG)
                        .getInt("performance.claim-cache-size", 100000);
                if (claimRegistry.exceedsConfiguredSize(configuredCacheSize)) {
                    getLogger().warning("This server holds " + claimCount + " claims, past the "
                            + "performance.claim-cache-size of " + configuredCacheSize
                            + ". Nothing is evicted — a dropped claim would mean unprotected "
                            + "land — but the figure is worth revisiting.");
                }
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Could not load cities; disabling CivitasCraft.", e);
                manager.close();
                Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                return;
            }

            Bukkit.getScheduler().runTask(this, () ->
                    onStorageReady(manager, loadedDaos, cityRegistry, claimRegistry, settings, scheduler));
        });
    }

    /** Runs on the server thread once the schema is current and the cache is warm. */
    private void onStorageReady(DatabaseManager manager, DaoRegistry loadedDaos,
                                CityRegistry cityRegistry, ClaimRegistry claimRegistry,
                                DatabaseSettings settings, Scheduler scheduler) {
        if (!isEnabled()) {
            // Disabled while the open was in flight; do not leak the pool.
            manager.close();
            return;
        }

        this.database = manager;
        this.daos = loadedDaos;
        this.backups = new BackupService(getLogger(), manager, new File(getDataFolder(), "backups"));

        LoginFingerprint fingerprints = loadFingerprints();

        EconomyService economyService = new EconomyService(manager, loadedDaos.players(),
                loadedDaos.ledger(), configs, getLogger());
        economyService.loadAll().thenAccept(loaded ->
                getLogger().info(() -> "Loaded " + loaded + " balances into the cache."));

        // The market and the treasury report quest progress, but they are built before the
        // quest service exists. They are handed a forwarder that does nothing until it is
        // pointed at something, rather than the wiring being reordered around a dependency
        // that only runs at report time.
        java.util.concurrent.atomic.AtomicReference<IncomeReporter> reporterRef =
                new java.util.concurrent.atomic.AtomicReference<>(IncomeReporter.noop());
        IncomeReporter reporter = (player, metric, amount) ->
                reporterRef.get().report(player, metric, amount);

        TreasuryService treasuryService = new TreasuryService(manager, loadedDaos,
                economyService, configs, scheduler, reporter);
        UpkeepCalculator upkeepCalculator = new UpkeepCalculator(configs);
        PlayerAccountService accounts =
                new PlayerAccountService(manager, loadedDaos.players(), loadedDaos.ledger(), configs);
        ClaimCostEngine costEngine = new ClaimCostEngine(configs);
        ClaimService claimService = new ClaimService(manager, loadedDaos, cityRegistry,
                claimRegistry, costEngine, configs, scheduler, EventBus.bukkit());
        claimService.loadActiveMembers();

        CityService cityService = new CityService(manager, loadedDaos, cityRegistry, configs,
                new CityNameValidator(configs), economyService, claimService, accounts, scheduler,
                EventBus.bukkit());
        RankService rankService = new RankService(manager, loadedDaos, scheduler, EventBus.bukkit());
        ClaimMap claimMap = new ClaimMap(claimRegistry, cityRegistry, configs, lang);
        BorderRenderer borderRenderer =
                new BorderRenderer(this, claimRegistry, configs, getLogger());
        PlayerLookup lookup = new PlayerLookup(loadedDaos.players());

        ProtectionService protection =
                new ProtectionService(claimRegistry, cityRegistry, configs);
        ProtectionGuard protectionGuard = new ProtectionGuard(protection, lang);
        BlockClassifier blockClassifier = new BlockClassifier(configs, getLogger());

        MarketPricing pricing = new MarketPricing(configs);
        MarketRegistry marketRegistry =
                new MarketRegistry(loadedDaos.marketStock(), configs, getLogger());
        marketRegistry.loadAll().thenAccept(loaded ->
                getLogger().info(() -> "Market trades " + loaded + " items."));
        MarketService marketService = new MarketService(manager, loadedDaos.ledger(),
                marketRegistry, pricing, economyService, configs, reporter);
        MarketItemFilter marketFilter = new MarketItemFilter(configs);

        SpawnService spawnService = new SpawnService(this, cityRegistry, configs, lang);
        CityHall cityHall = new CityHall(this, configs, lang);
        this.spawns = spawnService;

        UpkeepTask upkeepTask = new UpkeepTask(manager, loadedDaos, cityRegistry, claimService,
                treasuryService, upkeepCalculator, UpkeepTask.Notifier.online(lang), scheduler,
                getLogger(), java.time.ZoneId.systemDefault());

        // SPEC 17.1 cases 1, 2 and 3. The cache is handed to ProtectionService, which has
        // carried the isDormant seam unfilled since M4.
        dev.civitas.core.city.DormancyCache dormancy = new dev.civitas.core.city.DormancyCache();
        dev.civitas.core.city.InactivityTask inactivityTask =
                new dev.civitas.core.city.InactivityTask(loadedDaos, cityRegistry, cityService,
                        dormancy, configs, getLogger());
        protection.useDormancy(dormancy);

        // SPEC 4.2, 13.1 and 13.2, the income systems.
        ActivityTracker activityTracker = new ActivityTracker(configs);
        IncomeMultipliers incomeMultipliers = new IncomeMultipliers(configs);

        // SPEC 21.10.5, the placed-block cache behind SPEC 21.4 F9 and F10.
        placedBlocks = new dev.civitas.core.abuse.PlacedBlockCache(configs);
        QuestPool questPool = new QuestPool(configs, getLogger());
        questPool.load("income.quests.pool");
        QuestPool challengePool = new QuestPool(configs, getLogger());
        challengePool.load("income.challenges.pool");

        QuestService questService = new QuestService(manager, loadedDaos.playerQuests(),
                loadedDaos.players(), economyService, questPool, incomeMultipliers, configs,
                StipendTask.Notifier.online(lang), java.time.ZoneId.systemDefault());
        ChallengeService challengeService = new ChallengeService(manager,
                loadedDaos.cityChallenges(), cityRegistry, treasuryService, challengePool,
                configs, StipendTask.Notifier.online(lang), java.time.ZoneId.systemDefault());
        DailyLoginService dailyLogin = new DailyLoginService(manager, loadedDaos.players(),
                economyService, incomeMultipliers, configs, java.time.ZoneId.systemDefault());
        // SPEC 21.4 F12: the daily reward also needs active playtime on the day itself.
        dailyLogin.useDailyActivity(loadedDaos.dailyActivity());
        this.income = new IncomeSystems(activityTracker, questService, challengeService,
                dailyLogin, incomeMultipliers);

        reporterRef.set((player, metric, amount) -> {
            questService.report(player, metric, amount);
            challengeService.report(player, metric, amount);
        });

        // SPEC 13.3, the leaderboards and the lifetime counters two of them rank. Built after
        // the income systems because the same listener feeds both, and before the menus so a
        // later milestone can put a board on a screen without reordering anything.
        StatsService statsService = new StatsService(loadedDaos.playerStats(), getLogger());
        LeaderboardService leaderboardService = new LeaderboardService(loadedDaos.players(),
                loadedDaos.ledger(), loadedDaos.playerStats(), loadedDaos.contestEntries(),
                cityRegistry, claimRegistry, claimService, configs, getLogger());
        this.stats = statsService;

        // SPEC 13.5, scheduled server events. Built before the systems it changes, because
        // each of them takes the effects object in a useEvents call below and a multiplier
        // handed over after the first sale would be a multiplier that missed one.
        EventService eventService = new EventService(loadedDaos.serverEvents(), configs);
        eventService.load(System.currentTimeMillis()).thenAccept(resumed -> resumed.ifPresent(
                event -> getLogger().info(() -> "Resumed event " + event.type().key()
                        + ", " + (event.millisRemaining(System.currentTimeMillis()) / 60_000L)
                        + " minutes left.")));

        pricing.useEvents(eventService.effects());
        costEngine.useEvents(eventService.effects());
        cityService.useEvents(eventService.effects());
        upkeepCalculator.useEvents(eventService.effects());
        questService.useEvents(eventService.effects());

        // SPEC 13.4, building contests. After the leaderboards, because Contest Champions
        // reads the entries this writes, and after the treasury, which the prizes are paid into.
        ContestService contestService = new ContestService(manager, loadedDaos, cityRegistry,
                claimRegistry, treasuryService, new VoteWeighting(configs), configs, scheduler);
        contestService.load().thenAccept(loaded -> loaded.ifPresent(contest ->
                getLogger().info(() -> "Contest \"" + contest.theme() + "\" is in "
                        + contest.state() + ".")));
        if (contestService.wantsUnavailableVerification()) {
            getLogger().warning("events.yml asks for contest entries to be verified against block "
                    + "placement logs (SPEC 13.4), but no such log exists outside a war. Entries "
                    + "are accepted unverified until the war block logger lands in M17.");
        }

        // SPEC 5.7, upgrades and the vault. Built before the outposts and the menus, because
        // four systems read a level and would otherwise have to be told about it afterwards.
        UpgradeService upgradeService = new UpgradeService(manager, loadedDaos.cityUpgrades(),
                treasuryService, configs, scheduler);
        upgradeService.loadAll().thenAccept(loaded ->
                getLogger().info(() -> "Loaded " + loaded + " city upgrades."));
        VaultService vaultService = new VaultService(loadedDaos.cityVault(), upgradeService,
                configs);
        VaultView vaultView = new VaultView(this, vaultService, lang, getLogger());
        this.vaults = vaultView;

        cityService.useUpgrades(upgradeService);
        upkeepTask.useUpgrades(upgradeService);
        // SPEC 21.10.1 and 21.10.2, M6a. Built before anything can trade: SPEC 24 makes this
        // a hard blocker on M6 because "if the market ships first 'just to test it', the test
        // server's economy will be broken within an hour".
        marketService.useSafetyCheck(checkMarketSafety(marketRegistry));
        marketService.useUpgrades(cityRegistry, upgradeService);

        // SPEC 21.5's daily sell quota. Handed to the market after the safety check so a
        // market that refused to open never charges anyone a quota it will not honour.
        dev.civitas.core.market.SellQuota sellQuota = new dev.civitas.core.market.SellQuota(
                configs, loadedDaos.sellQuota(), java.time.ZoneId.systemDefault());
        marketService.useQuota(sellQuota);

        // SPEC 7, outposts.
        OutpostRegistry outpostRegistry = new OutpostRegistry(loadedDaos.outposts());
        outpostRegistry.loadAll().thenAccept(loaded ->
                getLogger().info(() -> "Loaded " + loaded + " outposts."));
        // SPEC 32.7's travel. One TeleportService for every destination in its table: the
        // warmup-and-cooldown rule had been written three times before the SPEC 39 rework
        // folded the outpost copy into this one.
        dev.civitas.core.travel.TeleportService teleportService =
                new dev.civitas.core.travel.TeleportService(this, configs, economyService, lang,
                        getLogger());
        OutpostService outpostService = new OutpostService(manager, loadedDaos, cityRegistry,
                claimRegistry, claimService, outpostRegistry, treasuryService, configs, scheduler);
        OutpostTravel outpostTeleport = new OutpostTravel(outpostService, teleportService);
        this.outposts = outpostTeleport;
        this.travel = teleportService;
        upkeepTask.useOutposts(outpostService);
        outpostService.useUpgrades(upgradeService);

        // SPEC 7.4: a claim that reaches an outpost absorbs it.
        claimService.onClaimed((city, claim) -> outpostService.convertAdjacent(city)
                .thenAccept(converted -> converted.forEach(outpost -> scheduler.runOnMain(() ->
                        notifyMayor(city, outpost.name())))));

        // SPEC 14, diplomacy. Before protection is told about it, because the trust grant
        // is read on the block path.
        DiplomacyRegistry diplomacyRegistry =
                new DiplomacyRegistry(loadedDaos.alliances(), loadedDaos.truces());
        diplomacyRegistry.loadAll(System.currentTimeMillis()).thenAccept(loaded ->
                getLogger().info(() -> "Loaded " + loaded + " alliances."));
        DiplomacyService diplomacyService = new DiplomacyService(manager, loadedDaos,
                cityRegistry, diplomacyRegistry, configs, scheduler);
        protection.useDiplomacy(diplomacyRegistry);
        scheduleDiplomacy(diplomacyService, diplomacyRegistry, cityRegistry);
        cityService.onCityDisbanded(cityId -> diplomacyService.forgetCity(cityId));

        // SPEC 12, defense units.
        DefenseCatalogue defenseCatalogue = new DefenseCatalogue(configs, getLogger());
        getLogger().info(() -> "Defense catalogue: " + defenseCatalogue.load() + " units.");
        DefenseRegistry defenseRegistry = new DefenseRegistry(loadedDaos.defenseUnits());
        java.util.concurrent.CompletableFuture<Integer> defenseLoaded = defenseRegistry.loadAll();
        defenseLoaded.thenAccept(loaded ->
                getLogger().info(() -> "Loaded " + loaded + " defense units."));
        DefenseSpawner defenseSpawner = new DefenseSpawner(this, defenseCatalogue, lang);
        DefenseService defenseService = new DefenseService(this, manager,
                loadedDaos.defenseUnits(), defenseRegistry, defenseCatalogue, defenseSpawner,
                cityRegistry, claimRegistry, treasuryService, upgradeService, lang, scheduler);
        DefenseBehaviour defenseBehaviour = new DefenseBehaviour(defenseCatalogue, cityRegistry);

        // SPEC 30.1: one targeting handler, and no unit-specific targeting logic anywhere
        // else. DefenseBehaviour kept the two questions about sides -- including SPEC 17.4
        // case 41's, that a player with no city is a bystander -- and lost the decision.
        dev.civitas.core.defense.UnitStates unitStates =
                new dev.civitas.core.defense.UnitStates();
        dev.civitas.core.defense.UnitTargeting unitTargeting =
                new dev.civitas.core.defense.UnitTargeting(cityRegistry, defenseRegistry,
                        defenseSpawner, unitStates, defenseCatalogue, configs);
        unitTargeting.useWars((cityId, player) -> cityRegistry.city(cityId)
                .map(owner -> defenseBehaviour.isEnemyOf(owner, player))
                .orElse(false));
        unitTargeting.useDiplomacy((cityId, otherCityId) -> cityRegistry.city(cityId)
                .map(owner -> cityRegistry.city(otherCityId)
                        .map(other -> defenseBehaviour.isSameSide(owner, other.mayorUuid()))
                        .orElse(false))
                .orElse(false));

        // SPEC 25.4's materialisation. Replaces the chunk-load respawn that used to be the
        // only trigger, which had no despawn path at all.
        dev.civitas.core.defense.UnitMaterializer materializer =
                new dev.civitas.core.defense.UnitMaterializer(configs, loadedDaos.defenseUnits(),
                        defenseRegistry, defenseCatalogue, defenseSpawner, cityRegistry,
                        getLogger());
        materializer.useUpgrades(city -> upgradeService.levelOf(city,
                dev.civitas.core.upgrade.UpgradeType.FORTIFICATION));
        // SPEC 26.1's states follow materialisation, and nothing had been telling them so: every
        // unit stayed DORMANT for the life of the server, which SPEC 30.1 cancels on -- so no
        // unit could target anybody and no trespass alert could take hold. Wired here rather
        // than through the constructor because the state map is built after the materializer.
        materializer.useStates(unitStates);
        // SPEC 31 case 87: everything is dormant on startup, whatever the server was doing
        // when it died. Clearing it is what stops a week of downtime healing every unit.
        materializer.onStartup(System.currentTimeMillis());
        // SPEC 28's Warden. Built here because the spawner has to know about it before anything
        // can be materialised, and because SPEC 28.8's ten-second checkpoint shares a timer block
        // with SPEC 25.4's thirty-second one.
        dev.civitas.core.defense.WardenRegistry wardenRegistry =
                new dev.civitas.core.defense.WardenRegistry(loadedDaos.cityWardens());
        wardenRegistry.loadAll().thenAccept(loaded -> {
            if (loaded > 0) {
                getLogger().info(() -> "Loaded " + loaded + " City Warden(s).");
            }
        });
        dev.civitas.core.defense.WardenSuppression wardenSuppression =
                new dev.civitas.core.defense.WardenSuppression(defenseCatalogue);
        defenseSpawner.useWardenSuppression(wardenSuppression);
        for (String unsupported : defenseCatalogue.unsupportedWardenSettings()) {
            // SPEC 30.3 ships these as declarations rather than switches, and SPEC 28.3's own
            // comment on the first is "NEVER set true". An operator who changed one is told it
            // did nothing, rather than being left to believe it did.
            getLogger().warning(() -> unsupported + " is not a supported setting; SPEC 28 states "
                    + "the only behaviour this plugin implements. The change has no effect.");
        }
        dev.civitas.core.defense.WardenService wardenService =
                new dev.civitas.core.defense.WardenService(manager, loadedDaos.cityWardens(),
                        loadedDaos.defenseUnits(), wardenRegistry, defenseRegistry,
                        defenseCatalogue, cityRegistry, treasuryService, upgradeService, scheduler);
        wardenService.useMaterializer(materializer::dematerialize);

        if (materializer.enabled()) {
            getServer().getScheduler().runTaskTimer(this,
                    () -> materializer.sweep(System.currentTimeMillis()),
                    100L, materializer.sweepIntervalTicks());
            // Split by predicate rather than run twice: SPEC 28.8 gives the Warden ten seconds and
            // SPEC 25.4 gives everything else thirty, and a Warden written by both would cost a
            // round trip every ten seconds to discover it had not changed.
            getServer().getScheduler().runTaskTimer(this,
                    () -> materializer.checkpoint(id -> !wardenRegistry.isWarden(id)),
                    materializer.checkpointIntervalTicks(),
                    materializer.checkpointIntervalTicks());
            long wardenTicks = Math.max(1L, defenseCatalogue.wardenCheckpointSeconds() * 20L);
            getServer().getScheduler().runTaskTimer(this,
                    () -> materializer.checkpoint(wardenRegistry::isWarden),
                    wardenTicks, wardenTicks);
        }
        // SPEC 30.2 case 101. Nothing in the plugin can downgrade a Fortification level, so
        // there is no event to hook: the realistic trigger is an operator lowering
        // capacity.base and reloading, which nothing announces. It runs as a pass instead.
        dev.civitas.core.defense.CapacityReconciler capacityReconciler =
                new dev.civitas.core.defense.CapacityReconciler(defenseService, defenseRegistry,
                        defenseCatalogue, loadedDaos.defenseUnits(), materializer, scheduler);
        capacityReconciler.useNotifier((city, count) -> city.members().forEach(member -> {
            org.bukkit.entity.Player online = getServer().getPlayer(member.uuid());
            if (online != null) {
                lang.send(online, "defense.capacity-suspended",
                        dev.civitas.lang.LangManager.placeholder("count",
                                String.valueOf(count)));
            }
        }));
        defenseService.useCapacity(capacityReconciler);
        // The catalogue is parsed once at startup, so a retuned points value would not otherwise
        // reach the budget -- and an operator lowering capacity.base is the only realistic way
        // case 101 ever fires, which makes this the pass that has to see the change.
        this.capacitySweep = () -> {
            defenseCatalogue.load();
            capacityReconciler.reconcileAll(cityRegistry);
        };
        unitTargeting.useCommissioning(defenseService.commissioning());
        defenseLoaded.thenCompose(loaded -> capacityReconciler.reconcileAll(cityRegistry))
                .thenAccept(changed -> {
                    if (changed > 0) {
                        getLogger().info(() -> changed + " defense unit(s) stood down: their "
                                + "city is over its SPEC 25.5 Defense Capacity.");
                    }
                });

        // SPEC 26.2's trespass response. Built here because it needs the unit states and the
        // registry; its effects are attached further down, once the messenger and the audit
        // log it reports through exist.
        dev.civitas.core.defense.TrespassService trespassService =
                new dev.civitas.core.defense.TrespassService(configs, cityRegistry, claimRegistry,
                        unitStates, defenseRegistry);
        dev.civitas.core.defense.UnitGlow unitGlow =
                new dev.civitas.core.defense.UnitGlow(defenseRegistry);
        // SPEC 27.3's Keeper, and SPEC 30.1's blind spot. The one targeting handler only ever
        // vetoes, so until something proposes a candidate an ALERTED guard stands still --
        // DefenseTick.acquire is what makes the whole roster do anything at all.
        dev.civitas.core.defense.WatchtowerDetection watchtowers =
                new dev.civitas.core.defense.WatchtowerDetection();
        dev.civitas.core.defense.DefenseTick defenseTick =
                new dev.civitas.core.defense.DefenseTick(defenseRegistry, defenseCatalogue,
                        cityRegistry, unitStates, unitTargeting, watchtowers);
        defenseTick.useDiplomacy((cityId, otherCityId) -> cityRegistry.city(cityId)
                .map(owner -> cityRegistry.city(otherCityId)
                        .map(other -> defenseBehaviour.isSameSide(owner, other.mayorUuid()))
                        .orElse(false))
                .orElse(false));
        cityService.onCityDisbanded(watchtowers::forgetCity);
        if (defenseCatalogue.enabled()) {
            getServer().getScheduler().runTaskTimer(this,
                    () -> defenseTick.acquire(System.currentTimeMillis()), 120L, 10L);
            getServer().getScheduler().runTaskTimer(this,
                    () -> defenseTick.watchtowers(System.currentTimeMillis(),
                            (city, seen) -> announceSighting(lang, city, seen)),
                    140L, watchtowerRefreshTicks(defenseCatalogue));
        }
        protectionGuard.useViolations((cityId, player, where) ->
                trespassService.violated(cityId, player.getUniqueId(), where,
                        System.currentTimeMillis()));
        cityService.onCityDisbanded(trespassService::forgetCity);

        upkeepTask.useDefense(defenseRegistry, defenseService);
        // Registered on the same hook rather than left to the next milestone: a disbanded
        // city that keeps its units and its bought upgrade levels would hand them back to
        // whoever founds a city that happens to reuse the id.
        cityService.onCityDisbanded(upgradeService::forgetCity);
        cityService.onCityDisbanded(cityId -> cityRegistry.city(cityId)
                .ifPresent(defenseService::removeCity));
        cityService.onCityDisbanded(wardenService::removeCity);
        // SPEC 30.2 case 99. The only path in the plugin that refunds a defense unit, because an
        // operator moving a chunk is not a game outcome the city chose.
        claimService.onCoreChunkMoved(cityId -> cityRegistry.city(cityId)
                .ifPresent(wardenService::removeForAdmin));

        // SPEC 28.3, 28.7 and 28.8: the anger map, the confinement, the darkness and the one
        // title a trespasser gets. None of it applies to any other unit, which is why it is not
        // in DefenseTick.
        dev.civitas.core.defense.WardenTick wardenTick =
                new dev.civitas.core.defense.WardenTick(wardenRegistry, defenseRegistry,
                        defenseCatalogue, cityRegistry, unitStates, wardenSuppression);
        if (defenseCatalogue.wardenEnabled()) {
            getServer().getScheduler().runTaskTimer(this,
                    () -> wardenTick.tick(System.currentTimeMillis()), 130L, 10L);
            // SPEC 28.6's six hours, swept rather than scheduled: SPEC 30.2 case 98 forbids
            // recovery being accelerated, and a task cannot survive the crash it guards against.
            getServer().getScheduler().runTaskTimer(this,
                    () -> wardenService.sweepRecovered(System.currentTimeMillis()),
                    200L, 20L * 60L);
        }

        MenuManager menuManager = new MenuManager(configs, lang);
        LayoutLoader layoutLoader = new LayoutLoader(dev.civitas.config.PluginResources.of(this));
        AmountInput amountInput = new AmountInput(menuManager, lang, scheduler);
        this.menus = menuManager;

        PlayerShopService shopService =
                new PlayerShopService(loadedDaos.playerShops(), economyService);
        shopService.loadAll().thenAccept(loaded ->
                getLogger().info(() -> "Loaded " + loaded + " player shops."));

        this.borders = borderRenderer;

        // SPEC 17.6 case 80. Built early because every admin command writes to it.
        dev.civitas.core.admin.AuditService auditService =
                new dev.civitas.core.admin.AuditService(loadedDaos.auditLog(), getLogger());
        // SPEC 9.4.3's protected chunks, loaded before anything can ask about them.
        dev.civitas.core.admin.AdminProtection adminProtection =
                new dev.civitas.core.admin.AdminProtection(loadedDaos.protectedChunks(),
                        getLogger());
        adminProtection.loadAll().thenAccept(loaded -> {
            if (loaded > 0) {
                getLogger().info("Loaded " + loaded + " admin-protected chunk(s).");
            }
        });
        claimService.useAdminProtection(adminProtection);
        protection.useAdminProtection(adminProtection);

        dev.civitas.core.admin.FraudHeuristics fraudHeuristics =
                new dev.civitas.core.admin.FraudHeuristics(configs);
        dev.civitas.core.admin.InspectMode inspectMode =
                new dev.civitas.core.admin.InspectMode(claimRegistry, cityRegistry);
        dev.civitas.core.admin.LedgerExport ledgerExport =
                new dev.civitas.core.admin.LedgerExport(getDataFolder());
        dev.civitas.core.moderation.ReportService reportService =
                new dev.civitas.core.moderation.ReportService(loadedDaos, configs);

        dev.civitas.core.admin.UpkeepOverrides upkeepOverrides =
                new dev.civitas.core.admin.UpkeepOverrides(loadedDaos.upkeepMultipliers(),
                        getLogger());
        upkeepOverrides.loadAll();
        upkeepTask.useOverrides(upkeepOverrides);
        cityService.onCityDisbanded(upkeepOverrides::forgetCity);

        // SPEC 4.7's bounties. Beside the economy rather than inside the war package: the
        // money moves whether or not a war ever happens, and only the payout is war-gated.
        dev.civitas.core.economy.BountyService bountyService =
                new dev.civitas.core.economy.BountyService(manager, loadedDaos.bounties(),
                        economyService, configs, scheduler, getLogger());
        // SPEC 21.4 F7: a bounty on an account that connects from the same place as the
        // killer refunds instead of paying, so an alt cannot be a payday.
        bountyService.useLogins(loadedDaos.playerLogins());
        scheduleBountyExpiry(bountyService);

        // SPEC 9.4.4's /ca eco rollback. Beside the economy because it writes ledger rows,
        // and never deletes any: SPEC 3.6 makes that table the authority in a dispute.
        dev.civitas.core.economy.LedgerRollback ledgerRollback =
                new dev.civitas.core.economy.LedgerRollback(manager, loadedDaos, economyService,
                        cityRegistry);

        // SPEC 11, the war system. Built before the services record because the record holds
        // three of its pieces, and before the seam wiring below because those read from it.
        WarWiring warWiring = startWarSystem(manager, loadedDaos, cityRegistry, claimRegistry,
                diplomacyRegistry, treasuryService, protection, bountyService, scheduler);

        // Every seam earlier milestones left for the war system, closed in one place.
        //
        // Each of these was written as a named method answering conservatively — "no war" —
        // so that arriving here meant changing one method per subsystem rather than auditing
        // every call site. This block is the payoff, and it is deliberately a list: a seam
        // that is missing from it is a rule SPEC states and this server does not enforce.
        marketService.useWarRewards(warWiring.rewards(), warWiring.marketBonusPercent());
        diplomacyService.useWars(warWiring.registry());               // SPEC 14.1 relations
        diplomacyService.useWarRestrictions(warWiring.restrictions()); // SPEC 11.11
        leaderboardService.useWars(loadedDaos.wars());                // SPEC 13.3 War Record
        cityService.useWars(warWiring.restrictions());                // SPEC 11.5, 11.11
        claimService.useWars(warWiring.restrictions());               // SPEC 6.3 cond. 9
        claimMap.useWars(warWiring.registry());                       // SPEC 6.5 enemy tiles
        claimMap.useDiplomacy(diplomacyRegistry);                     // SPEC 6.5 ally tiles
        cityHall.useWars(warWiring.restrictions());                   // SPEC 11.6
        spawnService.useWars(warWiring.restrictions());               // SPEC 5.6 warmup
        outpostService.useWars(warWiring.restrictions());             // SPEC 11.11
        outpostTeleport.useWars(warWiring.restrictions());            // SPEC 7.4
        // SPEC 26.3 and 25.4: a war both keeps units standing with nobody watching and turns
        // them HOSTILE. This seam was written by M12a and never filled, so until now the war
        // half of both rules was dead.
        // ACTIVE only, never PREP. SPEC 26.3: "During PREP, units remain PASSIVE. Prep is a
        // building phase, not a fighting phase." isEngaged() covers both and would arm a
        // garrison two days before anyone is allowed to fight it.
        materializer.useWars((cityId, world, chunkX, chunkZ) ->
                warWiring.registry().engagedWarOf(cityId)
                        .filter(war -> war.state() == dev.civitas.core.war.WarState.ACTIVE)
                        .isPresent());
        upgradeService.useWars(warWiring.restrictions());             // SPEC 11.11
        statsService.useWars(warWiring.registry());                   // SPEC 13.3 Builder
        defenseService.useWars(warWiring.registry());                 // SPEC 12.4 price
        defenseBehaviour.useWars(warWiring.registry());               // SPEC 12.3 targeting
        // SPEC 27.3: the Watchtower Keeper is "invulnerable outside war, 40 during war".
        defenseSpawner.useWars(cityId -> warWiring.registry().engagedWarOf(cityId)
                .map(war -> war.state() == dev.civitas.core.war.WarState.ACTIVE)
                .orElse(false));
        // SPEC 26.3: trespass response is suspended while a war is being fought, because
        // everything in the zone is hostile anyway. Warning an attacker before the guards
        // engage them would be the opposite of a siege.
        trespassService.useWars(cityId -> warWiring.registry().engagedWarOf(cityId)
                .map(war -> war.state() == dev.civitas.core.war.WarState.ACTIVE)
                .orElse(false));
        // SPEC 28.6: the one thing that makes a Warden's death final. ACTIVE only, matching every
        // other war seam in this package -- SPEC 11.5 permits no grief during PREP, and a Warden
        // deletable in a phase where nothing else may be broken would be the single exception.
        wardenService.useWars(cityId -> warWiring.registry().engagedWarOf(cityId)
                .map(war -> war.state() == dev.civitas.core.war.WarState.ACTIVE)
                .orElse(false));
        warWiring.restrictions().useAdminProtection(adminProtection); // SPEC 11.6
        warWiring.restrictions().usePolicy(                           // SPEC 16.3
                new dev.civitas.core.war.RollbackPolicy(configs));

        // SPEC 9.4.6's two unmeasured timings. Sampled, so leaving it on costs less than the
        // claim lookup it measures; see Timings for why it defaults on rather than off.
        Timings timings = new Timings(configs.get(ConfigFile.CONFIG)
                .getBoolean("performance.timings-enabled", true));
        claimRegistry.useTimings(timings);                            // SPEC 17.7 case 81
        menuManager.useTimings(timings);                              // SPEC 17.7 case 86

        // SPEC 33's PvP policy. One authority: ProtectionService answered this for claims
        // and vanilla answered it everywhere else, which is two rules and a gap between them.
        pvpPolicy = new dev.civitas.core.combat.PvpPolicy(
                configs, new dev.civitas.core.world.WorldRegistry(configs, getLogger()));
        pvpPolicy.useAdminProtection(adminProtection::isProtected);
        pvpPolicy.useSpawnChunk(() -> {
            org.bukkit.World main = getServer().getWorld(
                    configs.get(ConfigFile.WORLD).getString("worlds.main", "world"));
            if (main == null) {
                return java.util.Optional.empty();
            }
            org.bukkit.Location spawn = main.getSpawnLocation();
            return java.util.Optional.of(new Object[] {
                    main.getName(), spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4});
        });

        // SPEC 32.6's mining claims. Built before travel because /mine tp goes through the
        // TeleportService below, and before the services record because two seams read it.
        dev.civitas.core.world.WorldRegistry worldRegistry =
                new dev.civitas.core.world.WorldRegistry(configs, getLogger());
        dev.civitas.core.mining.MiningClaimRegistry miningRegistry =
                new dev.civitas.core.mining.MiningClaimRegistry(loadedDaos.miningClaims(),
                        getLogger());
        dev.civitas.core.mining.MiningClaimService miningClaimService =
                new dev.civitas.core.mining.MiningClaimService(manager,
                        loadedDaos.miningClaims(), miningRegistry, economyService, worldRegistry,
                        configs, scheduler);
        miningClaimService.useNotifier((owner, key, extra) -> {
            org.bukkit.entity.Player online = Bukkit.getPlayer(owner);
            if (online != null) {
                lang.send(online, key, extra);
            }
        });
        miningRegistry.loadAll().thenAccept(count ->
                getLogger().info(() -> "Loaded " + count + " mining claims."));

        // The two seams earlier milestones left for this one, each a single call.
        protection.useMiningClaims(new dev.civitas.core.protection.ProtectionService
                .MiningAccess() {
            @Override
            public boolean claimableWorld(String world) {
                return worldRegistry.allowsMiningClaims(world);
            }

            @Override
            public dev.civitas.core.mining.MiningClaimRegistry registry() {
                return miningRegistry;
            }
        });
        // SPEC 33.1's table puts PvP off inside a mining claim, which M4a left as a seam.
        pvpPolicy.useMiningClaims(miningRegistry::isClaimed);

        // SPEC 39.10's waystations, the other thing that can own ground in a resource world.
        dev.civitas.core.waystation.WaystationRegistry waystationRegistry =
                new dev.civitas.core.waystation.WaystationRegistry(loadedDaos.waystations());
        dev.civitas.core.waystation.WaystationService waystationService =
                new dev.civitas.core.waystation.WaystationService(manager, loadedDaos,
                        waystationRegistry, treasuryService, miningRegistry, configs, scheduler);
        waystationRegistry.loadAll().thenAccept(count ->
                getLogger().info(() -> "Loaded " + count + " waystations."));
        protection.useWaystations(waystationRegistry);
        upkeepTask.useWaystations(waystationService);
        cityService.onCityDisbanded(waystationService::removeCity);

        dev.civitas.core.travel.RandomTeleport randomTeleport =
                new dev.civitas.core.travel.RandomTeleport(this, configs, claimRegistry);
        randomTeleport.useAdminProtection(adminProtection::isProtected);
        dev.civitas.core.travel.WarpService warpService =
                new dev.civitas.core.travel.WarpService(loadedDaos.warps(), getLogger());
        warpService.loadAll().thenAccept(count ->
                getLogger().info(() -> "Loaded " + count + " warps."));

        // SPEC 23.6's notification preferences and SPEC 23.4's channel router. The router
        // consults the preferences on every send, and the four SPEC 23.6 locks are enforced
        // inside the preferences so no channel can bypass them.
        dev.civitas.msg.TogglePreferences togglePreferences =
                new dev.civitas.msg.TogglePreferences(loadedDaos.playerToggles(), getLogger());
        dev.civitas.msg.Messenger messenger =
                new dev.civitas.msg.Messenger(lang, configs, togglePreferences);

        // SPEC 26.2's visible half, attached now that both the router it speaks through and
        // the audit log it records violations in exist.
        dev.civitas.listener.TrespassListener trespassListener =
                new dev.civitas.listener.TrespassListener(this, trespassService, messenger,
                        unitGlow, auditService);
        trespassService.useEffects(trespassListener::on);
        getServer().getPluginManager().registerEvents(trespassListener, this);

        // SPEC 27.2 to 27.7's abilities, and SPEC 30.2 cases 105 to 112. A separate listener
        // from DefenseListener on purpose: SPEC 30.1 requires exactly one targeting handler,
        // and the surest way to keep that visibly true is for the file holding it to contain
        // nothing else that reasons about a unit and a player.
        dev.civitas.listener.DefenseAbilityListener defenseAbilities =
                new dev.civitas.listener.DefenseAbilityListener(defenseSpawner, defenseRegistry,
                        defenseCatalogue, cityRegistry, unitStates, lang);
        // SPEC 27.6's alert network fires "regardless of trespass state", and SPEC 26.2 still
        // promises nobody is killed without being told. Both, through one sink.
        defenseAbilities.useEffects(trespassListener::on);
        getServer().getPluginManager().registerEvents(defenseAbilities, this);

        services.set(new CivitasServices(cityRegistry, cityService, rankService, claimRegistry,
                claimService, claimMap, borderRenderer, protection, protectionGuard,
                blockClassifier, economyService, treasuryService, bountyService,
                ledgerRollback,
                upkeepCalculator,
                upkeepTask, marketService, marketFilter, togglePreferences, messenger,
                teleportService, randomTeleport, warpService, miningClaimService,
                waystationService,
                shopService, questService,
                challengeService, leaderboardService, statsService, contestService,
                eventService, warWiring.service(), warWiring.allies(), warWiring.peace(),
                warWiring.capturePoints(), warWiring.rollback(), warWiring.trigger(),
                auditService, adminProtection, fraudHeuristics, inspectMode,
                ledgerExport, upkeepOverrides, reportService, backups,
                configs.get(ConfigFile.CONFIG).getInt("storage.backup.keep-count", 28),
                this::pendingMigrationNames,
                () -> warBlockLog == null ? 0 : warBlockLog.bufferedCount(),
                () -> new dev.civitas.core.admin.PerfReport(
                        claimRegistry.size(),
                        cityRegistry.cities().size(),
                        timings.snapshot(Timings.Metric.CLAIM_LOOKUP),
                        timings.snapshot(Timings.Metric.GUI_OPEN),
                        warBlockLog == null ? 0 : warBlockLog.bufferedCount(),
                        warBlockLog == null ? 0.0
                                : warBlockLog.writeRatePerSecond(System.currentTimeMillis()),
                        warWiring.registry().all().size(),
                        database.poolStatus(),
                        adminProtection.count(),
                        timings.enabled()),
                loadedDaos, warWiring.scoreboard(),
                outpostService, outpostTeleport, upgradeService,
                defenseService, wardenService, diplomacyService, vaultService, vaultView,
                menuManager, layoutLoader,
                amountInput, spawnService, cityHall, accounts, lookup, scheduler));

        PlayerAccountListener accountListener = new PlayerAccountListener(accounts,
                loadedDaos.playerLogins(), fingerprints, getLogger());
        accountListener.useMessaging(togglePreferences, messenger);
        getServer().getPluginManager().registerEvents(accountListener, this);
        getServer().getPluginManager().registerEvents(
                new CityChatListener(cityRegistry, configs, lang), this);
        getServer().getPluginManager().registerEvents(
                new ClaimBoundaryListener(cityRegistry, claimService, borderRenderer, lang), this);

        // SPEC 5.5, the land protection listeners. Registered together so it is obvious at a
        // glance which events the plugin guards.
        // SPEC 26.2 lists damaging a defense unit among the violations, and nothing else
        // catches it: most of SPEC 27's roster are hostile mob types, which entity protection
        // deliberately lets anyone hit.
        registerProtection(protectionGuard, protection, blockClassifier,
                defenseSpawner::isDefenseUnit);

        // SPEC 4.5, chest shops.
        getServer().getPluginManager().registerEvents(
                new dev.civitas.listener.InactivityJoinListener(cityRegistry, dormancy,
                        loadedDaos.playerNotices(), lang, scheduler, getLogger()), this);
        getServer().getPluginManager().registerEvents(new ShopSignListener(shopService,
                new ShopSign(configs), protectionGuard, configs, lang, scheduler, getLogger()), this);
        getServer().getPluginManager().registerEvents(
                new ShopInteractListener(shopService, configs, lang, scheduler, getLogger()), this);

        // SPEC 8.1 and 5.6.
        getServer().getPluginManager().registerEvents(
                new CityHallListener(services::get, cityHall, lang), this);
        getServer().getPluginManager().registerEvents(
                new TeleportWarmupListener(spawnService, teleportService), this);
        getServer().getPluginManager().registerEvents(new VaultListener(vaultView), this);
        getServer().getPluginManager().registerEvents(new dev.civitas.listener
                .AdminInspectListener(inspectMode, lang,
                dev.civitas.listener.AdminInspectListener.ProtectedChunkLookup.none()), this);
        getServer().getPluginManager().registerEvents(new DefenseListener(this, defenseService,
                unitTargeting, cityRegistry, lang, getLogger()), this);
        // SPEC 28.8's suppressions and SPEC 28.6's peacetime defeat, which are all event-shaped
        // and therefore all assertable -- SPEC 31 asks for the sonic boom and the vibration anger
        // to be "disabled and verified", and an event is a thing a test can fire.
        getServer().getPluginManager().registerEvents(new dev.civitas.listener.WardenListener(
                defenseSpawner, defenseRegistry, wardenService, wardenSuppression), this);
        wireWardenMessages(wardenService, wardenTick, lang, messenger);
        dev.civitas.core.defense.DefenseLeash defenseLeash =
                new dev.civitas.core.defense.DefenseLeash(defenseRegistry, defenseSpawner,
                        defenseBehaviour, defenseCatalogue);
        // SPEC 30.2 case 92's last resort: a unit whose teleport home keeps being refused is
        // taken down and stood back up at its post, which is the one route that cannot fail.
        defenseLeash.useRecovery(unit -> {
            long now = System.currentTimeMillis();
            materializer.dematerialize(unit, now);
            defenseRegistry.byId(unit.id())
                    .ifPresent(current -> materializer.materialize(current, now));
        });
        scheduleDefenseLeash(defenseLeash, defenseRegistry);
        getServer().getPluginManager().registerEvents(
                new ActivityListener(activityTracker, questService, challengeService,
                        statsService, placedBlocks), this);
        getServer().getPluginManager().registerEvents(new IncomeJoinListener(questService,
                challengeService, dailyLogin, cityRegistry, lang, scheduler, getLogger()), this);

        scheduleStipend(manager, loadedDaos, economyService, activityTracker, incomeMultipliers);

        scheduleLeaderboards(leaderboardService, statsService);

        scheduleContests(contestService, cityRegistry);

        scheduleEvents(eventService, cityRegistry, claimRegistry, treasuryService, manager);


        scheduleMarketDecay(marketRegistry, pricing, loadedDaos);

        // SPEC 8.2, the GUI framework. Registered before any screen exists so that M8 adds
        // menus rather than plumbing.
        getServer().getPluginManager().registerEvents(new MenuListener(menuManager), this);
        getServer().getPluginManager().registerEvents(amountInput, this);
        scheduleMenuRefresh(menuManager);
        warmLayouts(layoutLoader);

        // Anyone already online, on a /reload, never fired a join event for us.
        long now = System.currentTimeMillis();
        for (Player online : Bukkit.getOnlinePlayers()) {
            accounts.onJoin(online.getUniqueId(), online.getName(), now);
        }

        scheduleEconomy(loadedDaos, economyService, treasuryService, upkeepTask);
        scheduleMiningUpkeep(miningClaimService);
        scheduleInactivity(inactivityTask);
        registerIntegrations(economyService);

        getLogger().info(() -> "Storage ready on " + settings.dialect() + ".");
        scheduleBackups(settings);
    }

    /** Every SPEC 5.5 listener. */
    private void registerProtection(ProtectionGuard guard, ProtectionService protection,
                                    BlockClassifier blocks,
                                    java.util.function.Predicate<org.bukkit.entity.Entity>
                                            defenseUnits) {
        var manager = getServer().getPluginManager();
        manager.registerEvents(new BlockProtectionListener(guard), this);
        manager.registerEvents(new ContainerProtectionListener(guard, blocks), this);
        manager.registerEvents(new InteractionProtectionListener(guard, blocks), this);
        EntityProtectionListener entityProtection =
                new EntityProtectionListener(guard, pvpPolicy);
        entityProtection.useDefenseUnits(defenseUnits);
        manager.registerEvents(entityProtection, this);
        // SPEC 37's join and respawn grace, both directions.
        manager.registerEvents(
                new dev.civitas.listener.PvpListener(pvpPolicy, lang), this);
        manager.registerEvents(new ExplosionProtectionListener(protection), this);
        manager.registerEvents(new FireAndFluidListener(protection, guard), this);
        manager.registerEvents(new PistonProtectionListener(protection), this);
    }

    /**
     * The two economy timers: the SPEC 4.3 daily upkeep sweep and the SPEC 4.8 hourly
     * circulation reading.
     *
     * <p>Both run asynchronously. The upkeep sweep is checked far more often than it charges,
     * because a city becomes due at a wall-clock hour and the server may have been offline
     * when it passed; the sweep itself does nothing for a city that is not yet due.
     */
    /**
     * SPEC 17.1's inactivity sweep.
     *
     * <p>Asynchronous, like the upkeep sweep beside it, and for the same reason: it reads
     * every city member's last login, which SPEC 2.1 forbids on the server thread. The first
     * run is delayed by one full interval rather than firing at startup, so a server coming
     * back after a long outage does not delete a city in the same second the operator started
     * it and before anybody has had a chance to log in.
     */
    private void scheduleInactivity(dev.civitas.core.city.InactivityTask task) {
        if (!task.enabled()) {
            getLogger().info("The SPEC 17.1 inactivity sweep is disabled; no city will be "
                    + "marked dormant, have its mayor replaced, or be expired.");
            return;
        }
        long ticks = Math.max(1L, task.checkIntervalMinutes()) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, task, ticks, ticks);
    }

    /**
     * SPEC 32.6's daily mining-claim upkeep, from each owner's personal balance.
     *
     * <p>Its own timer rather than a line inside the city sweep, because the two charge different
     * accounts and a failure in one must not stop the other. A city that cannot pay loses chunks
     * to its treasury; a mining claim is somebody's own wallet.
     */
    private void scheduleMiningUpkeep(dev.civitas.core.mining.MiningClaimService mines) {
        if (!mines.enabled()) {
            return;
        }
        long ticks = configs.get(ConfigFile.WORLD)
                .getLong("mining-claims.check-interval-minutes", 60) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                mines.chargeUpkeep(System.currentTimeMillis())
                        .thenAccept(released -> {
                            if (released > 0) {
                                getLogger().info(() -> released
                                        + " mining claim(s) were released for unpaid upkeep.");
                            }
                        })
                        .exceptionally(error -> {
                            getLogger().log(java.util.logging.Level.WARNING,
                                    "The mining claim upkeep sweep failed.", error);
                            return null;
                        });
            } catch (RuntimeException e) {
                // A closed pool throws from the call itself rather than failing the future.
                getLogger().log(java.util.logging.Level.WARNING,
                        "The mining claim upkeep sweep failed.", e);
            }
        }, ticks, ticks);
    }

    private void scheduleEconomy(DaoRegistry loadedDaos, EconomyService economyService,
                                 TreasuryService treasuryService, UpkeepTask upkeep) {
        long checkTicks = configs.get(ConfigFile.CITIES)
                .getLong("upkeep.check-interval-minutes", 10) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, upkeep, checkTicks, checkTicks);

        if (!configs.get(ConfigFile.ECONOMY).getBoolean("inflation.enabled", true)) {
            return;
        }
        InflationTracker inflation = new InflationTracker(economyService, treasuryService,
                loadedDaos.economySnapshots(), configs, getLogger());
        long inflationTicks = configs.get(ConfigFile.ECONOMY)
                .getLong("inflation.log-interval-minutes", 60) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            long now = System.currentTimeMillis();
            inflation.record(now).thenCompose(ignored -> inflation.prune(now));
        }, inflationTicks, inflationTicks);
    }

    /**
     * SPEC 20 decision 7. Both are optional and neither is required to be installed.
     *
     * <p>The "is it installed" test lives here rather than inside each hook, and that is not
     * cosmetic. Both hook classes extend a type from the plugin they integrate with, so
     * merely calling a static method on one loads its superclass and throws
     * {@link NoClassDefFoundError} on a server without it. Short-circuiting here means the
     * class is never touched unless the plugin is really there.
     */
    private void registerIntegrations(EconomyService economyService) {
        if (hasPlugin("PlaceholderAPI") && registerQuietly("PlaceholderAPI",
                () -> PlaceholderApiHook.register(this, services::get, configs))) {
            getLogger().info("Registered PlaceholderAPI placeholders under %civitas_...%.");
        }
        if (hasPlugin("Vault") && registerQuietly("Vault",
                () -> VaultEconomyProvider.register(this, economyService, configs))) {
            getLogger().info("Registered as Vault's economy provider.");
        }
    }

    private boolean hasPlugin(String name) {
        return getServer().getPluginManager().getPlugin(name) != null;
    }

    /** An integration whose API has moved costs that integration, never the whole plugin. */
    private boolean registerQuietly(String name, BooleanSupplier registration) {
        try {
            return registration.getAsBoolean();
        } catch (LinkageError | RuntimeException e) {
            getLogger().warning("Could not hook into " + name + ": " + e);
            return false;
        }
    }

    /**
     * The SPEC 4.4 stock drift back toward target.
     *
     * <p>Hourly by default, because SPEC 4.4 states the rate per hour. Running it more often
     * with a proportionally smaller step would be smoother but would also make the rate a
     * lie, and an operator reading 2% per hour should get 2% per hour.
     */
    private void scheduleMarketDecay(MarketRegistry marketRegistry, MarketPricing pricing,
                                     DaoRegistry loadedDaos) {
        if (!configs.get(ConfigFile.ECONOMY).getBoolean("market.enabled", true)) {
            return;
        }
        long ticks = configs.get(ConfigFile.ECONOMY)
                .getLong("market.decay-interval-minutes", 60) * 60L * 20L;
        if (ticks <= 0) {
            return;
        }
        StockDecayTask decay = new StockDecayTask(marketRegistry, pricing,
                loadedDaos.marketStock(), getLogger());
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, decay, ticks, ticks);
    }

    /**
     * The SPEC 8.2 refresh tick: every open menu showing live data is redrawn on a timer.
     *
     * <p>On the server thread, because it writes to inventories, and cheap: menus that do not
     * declare themselves live are skipped, and a server with none open does nothing at all.
     */
    private void scheduleMenuRefresh(MenuManager menuManager) {
        long ticks = Math.max(1L, menuManager.refreshTicks());
        Bukkit.getScheduler().runTaskTimer(this, menuManager::refreshLive, ticks, ticks);
    }

    /**
     * Reads every layout once at startup so the files appear on disk.
     *
     * <p>{@link LayoutLoader} copies a packaged layout out the first time it is asked for,
     * which without this would be the first time a player opened that screen. An operator
     * looking for {@code gui/main.yml} to edit should find it after the first boot, not after
     * somebody has been in the menu.
     */
    private void warmLayouts(LayoutLoader layouts) {
        layouts.load(dev.civitas.gui.menus.MainMenu.LAYOUT, "gui.main.title", 54);
        layouts.load(dev.civitas.gui.menus.ClaimsMenu.LAYOUT, "gui.claims.title", 54);
        layouts.load(dev.civitas.gui.menus.TreasuryMenu.LAYOUT, "gui.treasury.title", 54);
        layouts.load("members.yml", "gui.members.title", 54);
        layouts.load(dev.civitas.gui.menus.RanksMenu.LAYOUT, "gui.ranks.title", 54);
        layouts.load(dev.civitas.gui.menus.SettingsMenu.LAYOUT, "gui.settings.title", 54);
    }

    /**
     * The salt behind SPEC 13.4's shared-connection check.
     *
     * <p>If it cannot be read or written, the plugin carries on with a salt that lasts only
     * this run. The consequence is that no stored fingerprint matches, so the rule discards
     * nothing rather than discarding the wrong votes: for an anti-abuse check, failing open is
     * the direction that cannot punish an innocent player.
     */
    private LoginFingerprint loadFingerprints() {
        try {
            return LoginFingerprint.load(getDataFolder());
        } catch (java.io.IOException e) {
            getLogger().log(Level.WARNING, "Could not read the login salt; SPEC 13.4's "
                    + "shared-connection vote check will not match anything this run.", e);
            byte[] temporary = new byte[32];
            new java.security.SecureRandom().nextBytes(temporary);
            return LoginFingerprint.withSalt(temporary);
        }
    }

    /**
     * The SPEC 11.8.1 war block logger.
     *
     * <p>Registered now although no war exists yet, which SPEC 19 asks for directly: M17 and
     * M18 build and test the rollback engine before any war gameplay, so that it is never
     * tested under time pressure with players waiting. Until M19 supplies real war zones,
     * {@link WarZones#none()} answers "no war" and every listener returns on its first line,
     * so a peacetime server pays nothing for this.
     */
    /** What the war system hands back so the systems it touches can be wired to it. */
    private record WarWiring(dev.civitas.core.war.WarRegistry registry,
                             dev.civitas.core.war.WarRewards rewards,
                             double marketBonusPercent,
                             dev.civitas.core.war.WarService service,
                             dev.civitas.core.war.WarAllies allies,
                             dev.civitas.core.war.PeaceOffer peace,
                             dev.civitas.core.war.CapturePoints capturePoints,
                             dev.civitas.core.war.WarScoreboard scoreboard,
                             dev.civitas.core.war.WarRestrictions restrictions,
                             dev.civitas.core.war.RollbackEngine rollback,
                             java.util.function.Consumer<dev.civitas.core.war.War> trigger) { }

    private WarWiring startWarSystem(DatabaseManager database, DaoRegistry loadedDaos,
                                CityRegistry cityRegistry, ClaimRegistry claimRegistry,
                                dev.civitas.core.diplomacy.DiplomacyRegistry diplomacyRegistry,
                                TreasuryService treasuryService, ProtectionService protection,
                                dev.civitas.core.economy.BountyService bounties,
                                Scheduler scheduler) {
        BukkitTilePayloadCodec codec = new BukkitTilePayloadCodec(getLogger());
        WarBlockLogger blockLog = new WarBlockLogger(loadedDaos.warBlockLog(), codec, configs,
                getLogger());
        this.warBlockLog = blockLog;

        // The operator should learn what a rollback cannot put back before a war, not after.
        for (String limitation : codec.knownLimitations()) {
            getLogger().info("War rollback limitation: " + limitation);
        }

        // SPEC 11.8.2, the rollback engine. Built here with the logger because the two share
        // the codec: what M17 wrote is exactly what M18 has to read back.
        dev.civitas.core.war.RollbackEngine rollback = new dev.civitas.core.war.RollbackEngine(
                loadedDaos, configs, codec, new dev.civitas.core.war.ChunkHasher(configs),
                getLogger());
        this.rollbackEngine = rollback;
        resumeInterruptedRollbacks(rollback, blockLog);

        // SPEC 11, the war system. This is the line that makes M17 and M18 live: with real
        // zones behind it the logger records damage and the engine puts it back, where
        // WarZones.none() meant both were tested machinery attached to nothing.
        dev.civitas.core.war.WarRegistry warRegistry =
                new dev.civitas.core.war.WarRegistry(loadedDaos.wars());
        warRegistry.loadAll().thenAccept(loaded -> {
            if (loaded > 0) {
                getLogger().info("Loaded " + loaded + " unfinished war(s).");
            }
        });

        dev.civitas.core.war.WarService warService = new dev.civitas.core.war.WarService(
                database, loadedDaos, cityRegistry, claimRegistry, diplomacyRegistry,
                warRegistry, treasuryService, configs, scheduler);
        dev.civitas.core.war.WarRestrictions warRestrictions =
                new dev.civitas.core.war.WarRestrictions(warRegistry, cityRegistry);
        protection.useWars(warRestrictions);

        // SPEC 11.10 and 8.8: allies joining, and the way out of a war already under way.
        dev.civitas.core.war.WarAllies warAllies = new dev.civitas.core.war.WarAllies(
                database, loadedDaos, cityRegistry, diplomacyRegistry, warRegistry,
                treasuryService, configs, scheduler);
        dev.civitas.core.war.PeaceOffer peaceOffers = new dev.civitas.core.war.PeaceOffer(
                database, loadedDaos, cityRegistry, treasuryService, configs, scheduler);

        dev.civitas.core.war.RegistryWarZones warZones =
                new dev.civitas.core.war.RegistryWarZones(warRegistry);
        WarBlockRecorder recorder = new WarBlockRecorder(warZones, blockLog);
        var manager = getServer().getPluginManager();

        // SPEC 11.7's container log. Evidence for the post-war report, never a restore path.
        dev.civitas.core.war.WarLootLog lootLog =
                new dev.civitas.core.war.WarLootLog(loadedDaos.warContainerLog(), getLogger());
        this.warLootLog = lootLog;
        manager.registerEvents(
                new dev.civitas.listener.war.WarContainerListener(lootLog, warZones), this);
        manager.registerEvents(new dev.civitas.listener.war.WarBlockListener(recorder), this);
        manager.registerEvents(new dev.civitas.listener.war.WarPhysicsListener(recorder), this);
        manager.registerEvents(new dev.civitas.listener.war.WarHangingListener(recorder), this);
        manager.registerEvents(new dev.civitas.listener.war.WarCombatListener(
                warRestrictions, lang), this);
        // SPEC 17.4 cases 41 and 48: arriving inside a zone, rather than being caught in one.
        manager.registerEvents(new dev.civitas.listener.war.WarJoinListener(warRegistry,
                cityRegistry, dev.civitas.core.war.Evacuation.of(cityRegistry), lang), this);

        // SPEC 11.2's clock. Checked far more often than it acts, because a phase ends at a
        // wall-clock moment and the server may have been down when it passed.
        dev.civitas.core.war.WarPhaseTask phases = new dev.civitas.core.war.WarPhaseTask(
                warService, warRegistry, loadedDaos.wars(), cityRegistry,
                dev.civitas.core.war.Evacuation.of(cityRegistry),
                war -> {
                    blockLog.freeze(war.id());
                    beginRollbackFor(rollback, blockLog, war.id());
                },
                StipendTask.Notifier.online(lang), configs, getLogger(),
                System::currentTimeMillis);
        // SPEC 11.9, the payouts and the two seven-day consequences.
        dev.civitas.core.war.WarPayouts payouts = new dev.civitas.core.war.WarPayouts(configs);
        dev.civitas.core.war.WarRewards warRewards =
                new dev.civitas.core.war.WarRewards(loadedDaos.wars());
        warRewards.load(System.currentTimeMillis(), payouts.marketBonusDays())
                .thenAccept(loaded -> {
                    if (loaded > 0) {
                        getLogger().info(loaded + " city/cities still hold a war market bonus.");
                    }
                });
        phases.useResolution(new dev.civitas.core.war.WarResolution(database, loadedDaos,
                cityRegistry, treasuryService, payouts, warRewards, getLogger()));

        // SPEC 11.8.3's living entities and SPEC 11.8.4's pre-war chunk hashes. Both are
        // snapshots taken at the moment the fighting starts, and neither can be taken later:
        // a dead cow cannot be asked what it was, and a chunk hashed mid-war measures the
        // damage rather than the state the rollback has to reach.
        dev.civitas.core.war.WarEntitySnapshots entitySnapshots =
                new dev.civitas.core.war.WarEntitySnapshots(loadedDaos.warEntitySnapshots(),
                        scheduler, getLogger());
        rollback.useEntitySnapshots(entitySnapshots);
        manager.registerEvents(
                new dev.civitas.listener.war.WarEntityListener(entitySnapshots), this);
        // SPEC 17.4 case 51. Runs at war start, before anything can be logged against this
        // war, so the seeded rows take the lowest sequences and the replay reaches them last.
        dev.civitas.core.war.OverlapSeeder overlapSeeder =
                new dev.civitas.core.war.OverlapSeeder(loadedDaos.warBlockLog(), warRegistry,
                        getLogger());
        phases.useWorldStateCapture(war -> {
            overlapSeeder.seed(war);
            captureWorldStateAt(war, entitySnapshots, rollback);
        });

        // SPEC 11.6's score table.
        dev.civitas.core.war.WarScoring warScoring =
                new dev.civitas.core.war.WarScoring(configs);
        dev.civitas.core.war.CapturePoints capturePoints =
                new dev.civitas.core.war.CapturePoints(warScoring);
        phases.useCapturePoints(capturePoints);

        dev.civitas.listener.war.WarScoringListener scoringListener =
                new dev.civitas.listener.war.WarScoringListener(warRegistry, warScoring,
                        cityRegistry, claimRegistry,
                        dev.civitas.listener.war.WarScoringListener.DefenseOwnership.none());
        scoringListener.useKillLog(loadedDaos.warKills());
        // SPEC 4.7's payout hangs off the same kill that scores, because "during an active
        // war" is exactly the condition that handler has already established by then.
        scoringListener.useBounties(bounties, (killer, paid) -> {
            org.bukkit.entity.Player online = Bukkit.getPlayer(killer);
            if (online != null) {
                lang.send(online, "bounty.claimed", dev.civitas.command.Replies.p("amount",
                        dev.civitas.core.economy.Money.format(paid, configs)));
            }
        });
        manager.registerEvents(scoringListener, this);

        // SPEC 9.3's /war scoreboard. Off for everyone until somebody asks for it, so the
        // refresh below costs one set-emptiness check a second on a server nobody has.
        dev.civitas.core.war.WarScoreboard scoreboard =
                new dev.civitas.core.war.WarScoreboard(warRegistry, cityRegistry, lang);
        manager.registerEvents(scoreboard, this);

        scheduleWarObjectives(warRegistry, capturePoints, cityRegistry, scoreboard);

        long phaseTicks = configs.get(ConfigFile.WAR)
                .getLong("phases.check-interval-minutes", 5) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, phases, phaseTicks, phaseTicks);

        long ticks = Math.max(1L, blockLog.flushIntervalSeconds()) * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, blockLog::flush, ticks, ticks);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, lootLog::flush, ticks, ticks);

        return new WarWiring(warRegistry, warRewards, payouts.marketBonusPercent(),
                warService, warAllies, peaceOffers, capturePoints, scoreboard, warRestrictions,
                rollback, war -> {
                    // SPEC 9.4.5's /ca war rollback, taking exactly the path the phase task
                    // takes when a war ends on its own: freeze the log, then replay it.
                    blockLog.freeze(war.id());
                    beginRollbackFor(rollback, blockLog, war.id());
                });
    }

    /**
     * SPEC 4.7: "Bounties expire after 30 days and refund."
     *
     * <p>Swept hourly by default rather than on a timer per bounty, because a bounty placed on
     * a server that was then down for a week still has to expire, and a per-bounty timer would
     * not survive the restart. The sweep is idempotent: the state is part of the WHERE clause,
     * so a bounty refunded once cannot be refunded twice.
     */
    private void scheduleBountyExpiry(dev.civitas.core.economy.BountyService bounties) {
        long ticks = Math.max(1L, bounties.expirySweepMinutes()) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () ->
                bounties.expireDue(System.currentTimeMillis()).thenAccept(refunded -> {
                    if (refunded > 0) {
                        getLogger().info("Refunded " + refunded + " expired bounty/bounties.");
                    }
                }), ticks, ticks);
    }

    /**
     * Records what the world looked like when a war became fightable.
     *
     * <p>Two SPEC requirements with the same deadline: SPEC 11.8.3's animal and villager
     * snapshot, and SPEC 11.8.4's per-chunk hash that the post-rollback comparison is measured
     * against. Both are read from a live world, so both run on the server thread; only the
     * writes are async.
     *
     * <p>The hash pass is the expensive one — SPEC 11.8.4 asks for a checksum over every block
     * state in every zone chunk — so it is behind {@code rollback.chunk-hash-failsafe}, which
     * SPEC 16.3 ships as true, and {@code rollback.chunk-hash-stride} for an operator who
     * would rather sample.
     */
    private void captureWorldStateAt(dev.civitas.core.war.War war,
                                     dev.civitas.core.war.WarEntitySnapshots entitySnapshots,
                                     dev.civitas.core.war.RollbackEngine rollback) {
        entitySnapshots.capture(war, System.currentTimeMillis()).thenAccept(captured -> {
            if (captured > 0) {
                getLogger().info("Snapshotted " + captured + " animal(s) and villager(s) in the "
                        + "zone of war " + war.id() + ".");
            }
        });

        // chunkList gives [worldIndex, chunkX, chunkZ]; the hasher wants [chunkX, chunkZ] and
        // one world at a time, so the list is split by world here rather than in the engine.
        java.util.Map<String, List<long[]>> byWorld = new java.util.HashMap<>();
        for (long[] chunk : war.zone().chunkList()) {
            String worldName = war.zone().worldOf(chunk[0]);
            if (worldName != null) {
                byWorld.computeIfAbsent(worldName, key -> new java.util.ArrayList<>())
                        .add(new long[] {chunk[1], chunk[2]});
            }
        }
        for (var entry : byWorld.entrySet()) {
            String worldName = entry.getKey();
            org.bukkit.World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            rollback.recordPreWarHashes(war.id(), world, entry.getValue()).thenAccept(hashed -> {
                if (hashed > 0) {
                    getLogger().info("Hashed " + hashed + " chunk(s) of war " + war.id()
                            + " in " + worldName + " for the SPEC 11.8.4 failsafe.");
                }
            });
        }
    }

    /**
     * SPEC 12.3's leash, ticked once a war can make a unit chase something.
     *
     * <p>Every two seconds rather than every tick: a unit walks about four blocks a second at
     * the fastest speed in SPEC 12.2's table, so two seconds is the coarsest interval that
     * cannot let one cross the eight-block leash unnoticed and come back. Scanning nothing
     * when no city owns a unit, which is most servers most of the time.
     */
    /**
     * How often SPEC 27.3's Keepers repaint, from the Keeper's own glow refresh.
     *
     * <p>Read from whichever unit declares it rather than from a server-wide key, because it is
     * a property of that unit and SPEC 27.3 states it there. A roster with no detection unit
     * never schedules anything worth running fast, so the fallback is deliberately slow.
     */
    private static long watchtowerRefreshTicks(
            dev.civitas.core.defense.DefenseCatalogue catalogue) {
        return catalogue.all().stream()
                .filter(type -> type.hasAbility(
                        dev.civitas.core.defense.DefenseUnitType.Ability.GLOW_REFRESH_SECONDS))
                .mapToLong(type -> Math.max(20L, (long) (type.ability(
                        dev.civitas.core.defense.DefenseUnitType.Ability.GLOW_REFRESH_SECONDS,
                        3) * 20)))
                .min()
                .orElse(60L);
    }

    /** SPEC 27.3: "Posts a message to city chat when an unknown player enters." */
    /**
     * SPEC 30.4's five {@code warden.*} messages, attached to the service that fires them.
     *
     * <p>SPEC 28.6 requires the city to be told "both when it goes down and when it returns", and
     * SPEC 23.1's first principle makes that more than politeness: a 750,000 C asset that vanished
     * for six hours with no message would read as a bug, and a city that never learned why would
     * assume it had been stolen.
     */
    private void wireWardenMessages(dev.civitas.core.defense.WardenService wardens,
                                    dev.civitas.core.defense.WardenTick tick,
                                    dev.civitas.lang.LangManager lang,
                                    dev.civitas.msg.Messenger messenger) {
        wardens.onPurchased((city, owned) -> {
            // SPEC 30.4 gives this a title as well as chat, and it is one of the few things in
            // the plugin that earns one: a server should know when a city has done this.
            org.bukkit.Bukkit.broadcast(lang.get("warden.purchased",
                    dev.civitas.lang.LangManager.placeholder("city", city.name())));
            city.members().forEach(member -> {
                org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(member.uuid());
                if (online != null) {
                    messenger.sendTitle(member.uuid(), online,
                            dev.civitas.msg.ToggleCategory.MEMBERSHIP,
                            "warden.purchased-title", null);
                }
            });
        });
        // SPEC 30.4: BOTH plus SERVER. A war that kills a Warden has destroyed the single most
        // expensive thing on the server, and SPEC 28.6 makes it permanent, so it is news.
        wardens.onDestroyedInWar(city -> org.bukkit.Bukkit.broadcast(
                lang.get("warden.destroyed-war",
                        dev.civitas.lang.LangManager.placeholder("city", city.name()))));
        wardens.onDefeated((city, hours, killer) -> toCity(lang, city,
                killer == null
                        ? "warden.defeated-peacetime-environment"
                        : "warden.defeated-peacetime",
                dev.civitas.lang.LangManager.placeholder("hours", String.valueOf(hours)),
                dev.civitas.lang.LangManager.placeholder("killer",
                        killer == null ? "" : killer)));
        wardens.onRecovered(city -> toCity(lang, city, "warden.returned"));
        wardens.onAdminRemoved((city, refund) -> toCity(lang, city, "warden.admin-refunded",
                dev.civitas.lang.LangManager.placeholder("amount",
                        dev.civitas.core.economy.Money.format(refund, configs))));
        tick.onEmerged((owned, target) -> lang.send(target, "warden.emerged-title"));
    }

    private static void toCity(dev.civitas.lang.LangManager lang,
                               dev.civitas.core.city.City city, String key,
                               net.kyori.adventure.text.minimessage.tag.resolver.TagResolver...
                                       placeholders) {
        city.members().forEach(member -> {
            org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(member.uuid());
            if (online != null) {
                lang.send(online, key, placeholders);
            }
        });
    }

    private static void announceSighting(dev.civitas.lang.LangManager lang,
                                         dev.civitas.core.city.City city,
                                         org.bukkit.entity.Player seen) {
        city.members().forEach(member -> {
            org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(member.uuid());
            if (online != null) {
                lang.send(online, "defense.watchtower-sighted",
                        dev.civitas.lang.LangManager.placeholder("player", seen.getName()),
                        dev.civitas.lang.LangManager.placeholder("x",
                                String.valueOf(seen.getLocation().getBlockX())),
                        dev.civitas.lang.LangManager.placeholder("z",
                                String.valueOf(seen.getLocation().getBlockZ())));
            }
        });
    }

    private void scheduleDefenseLeash(dev.civitas.core.defense.DefenseLeash leash,
                                      dev.civitas.core.defense.DefenseRegistry defenseRegistry) {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (defenseRegistry.linkedEntities() == 0) {
                return;
            }
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                leash.tick(new java.util.ArrayList<>(world.getLivingEntities()));
            }
        }, 40L, 40L);
    }

    /**
     * The SPEC 11.6 objectives tick: capture points and the City Hall stand.
     *
     * <p>On the server thread, because it reads where players are standing, and every second
     * rather than every tick: both objectives are measured in tens of seconds and counting
     * heads twenty times a second would be nineteen wasted counts.
     */
    private void scheduleWarObjectives(dev.civitas.core.war.WarRegistry warRegistry,
                                       dev.civitas.core.war.CapturePoints capturePoints,
                                       CityRegistry cityRegistry,
                                       dev.civitas.core.war.WarScoreboard scoreboard) {
        dev.civitas.core.war.CapturePoints.Occupancy occupancy =
                (world, chunkX, chunkZ, cities) -> {
                    int count = 0;
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        var at = player.getLocation();
                        if (at.getWorld() == null || !at.getWorld().getName().equals(world)
                                || (at.getBlockX() >> 4) != chunkX
                                || (at.getBlockZ() >> 4) != chunkZ) {
                            continue;
                        }
                        if (cityRegistry.cityOf(player.getUniqueId())
                                .filter(city -> cities.contains(city.id())).isPresent()) {
                            count++;
                        }
                    }
                    return count;
                };

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (dev.civitas.core.war.War war : warRegistry.all()) {
                if (war.state() != dev.civitas.core.war.WarState.ACTIVE) {
                    continue;
                }
                capturePoints.tick(war, occupancy, now);
                tickCityHallStands(war, capturePoints, cityRegistry, now);
            }
            // The sidebar shares this tick because it shows what the tick just computed: a
            // score updated a second after the kill that earned it reads as live.
            scoreboard.refresh(now);
        }, 20L, 20L);
    }

    /**
     * SPEC 11.6's City Hall bonus.
     *
     * <p>The City Hall sits in the city's core chunk (SPEC 5.1 step 7 places it where the
     * founder stood, which is the chunk they claimed), so that is the chunk each side has to
     * reach.
     */
    private void tickCityHallStands(dev.civitas.core.war.War war,
                                    dev.civitas.core.war.CapturePoints capturePoints,
                                    CityRegistry cityRegistry, long now) {
        for (boolean attackerSide : new boolean[] {true, false}) {
            int enemyCityId = attackerSide ? war.defenderCityId() : war.attackerCityId();
            var enemy = cityRegistry.city(enemyCityId);
            if (enemy.isEmpty()) {
                continue;
            }
            for (int cityId : war.side(attackerSide)) {
                boolean present = anyMemberIn(cityId, enemy.get().coreWorld(),
                        enemy.get().coreChunkX(), enemy.get().coreChunkZ(), cityRegistry);
                capturePoints.tickCityHallStand(war, cityId, attackerSide, present, now);
            }
        }
    }

    private boolean anyMemberIn(int cityId, String world, int chunkX, int chunkZ,
                                CityRegistry cityRegistry) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            var at = player.getLocation();
            if (at.getWorld() == null || !at.getWorld().getName().equals(world)
                    || (at.getBlockX() >> 4) != chunkX || (at.getBlockZ() >> 4) != chunkZ) {
                continue;
            }
            if (cityRegistry.cityOf(player.getUniqueId())
                    .filter(city -> city.id() == cityId).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * SPEC 11.8.5: a rollback interrupted by a crash is picked up on the next boot.
     *
     * <p>Driven from a repeating task that alternates the engine's two halves: a page is read
     * off the server thread, then at most {@code blocks-per-tick} of it is applied on it. The
     * war zone stays closed throughout, and a war whose log cannot be read is left FAILED for
     * an administrator rather than quietly reopened.
     */
    private void resumeInterruptedRollbacks(dev.civitas.core.war.RollbackEngine rollback,
                                            WarBlockLogger blockLog) {
        if (!rollback.isEnabled()) {
            getLogger().severe("war.yml has rollback.enabled: false, so an interrupted rollback "
                    + "will NOT be resumed. War damage stays permanent.");
            return;
        }

        rollback.findInterrupted().thenAccept(warIds -> {
            if (warIds.isEmpty()) {
                return;
            }
            getLogger().warning("Found " + warIds.size() + " war(s) interrupted mid-rollback. "
                    + "Resuming; their zones stay closed until it completes.");
            for (int warId : warIds) {
                blockLog.freeze(warId);
                rollback.begin(warId).thenAccept(job ->
                        Bukkit.getScheduler().runTaskTimer(this, task -> {
                            if (job.status() != dev.civitas.core.war.RollbackStatus.RUNNING) {
                                task.cancel();
                                return;
                            }
                            if (!job.hasPending()) {
                                rollback.fetchNextPage(job).thenAccept(read -> {
                                    if (read == 0 && !job.hasPending()) {
                                        Bukkit.getScheduler().runTask(this, () -> finishRollback(
                                                rollback, blockLog, job));
                                    }
                                });
                                return;
                            }
                            rollback.applySlice(job);
                        }, 1L, 1L));
            }
        });
    }

    /**
     * Starts a rollback for a war that has just ended, on the same driver a resumed one uses.
     *
     * <p>Shared so a war that ends normally and one picked up after a crash follow exactly the
     * same path: SPEC 18.3 has to sign off one behaviour, not two.
     */
    /**
     * SPEC 9.4.6's {@code /ca reload}, and the plugin's own startup path.
     *
     * <p>Configuration only. Reloading the services would mean rebuilding caches players are
     * mid-interaction with, and SPEC 9.4.6 asks for a config reload rather than a restart.
     */
    private void reloadConfigs() {
        configs.reloadAll();
        lang.load();
        capacitySweep.run();
    }

    /**
     * Re-reads the defense catalogue and brings every garrison back inside SPEC 25.5's budget.
     *
     * <p>A no-op until storage opens, like everything else built in {@code onStorageReady}.
     */
    private Runnable capacitySweep = () -> { };

    /**
     * Migrations discovered but not applied, for SPEC 9.4.6's {@code /ca migrate check}.
     *
     * <p>Read-only. On a healthy server this is always empty, because migrations run at
     * startup; a non-empty answer means the plugin jar is newer than the database it opened,
     * which is worth knowing before a maintenance window rather than during one.
     */
    private java.util.List<String> pendingMigrationNames() {
        if (database == null) {
            return java.util.List.of();
        }
        try {
            return new dev.civitas.storage.migration.MigrationRunner(getLogger(),
                    database.dialect())
                    .pending(database.dataSource()).stream()
                    .map(dev.civitas.storage.migration.Migration::resourcePath)
                    .toList();
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "Could not check for pending migrations", e);
            return java.util.List.of();
        }
    }

    private void beginRollbackFor(dev.civitas.core.war.RollbackEngine rollback,
                                  WarBlockLogger blockLog, int warId) {
        rollback.begin(warId).thenAccept(job ->
                Bukkit.getScheduler().runTaskTimer(this, task -> {
                    if (job.status() != dev.civitas.core.war.RollbackStatus.RUNNING) {
                        task.cancel();
                        return;
                    }
                    if (!job.hasPending()) {
                        rollback.fetchNextPage(job).thenAccept(read -> {
                            if (read == 0 && !job.hasPending()) {
                                Bukkit.getScheduler().runTask(this,
                                        () -> finishRollback(rollback, blockLog, job));
                            }
                        });
                        return;
                    }
                    rollback.applySlice(job);
                }, 1L, 1L));
    }

    private void finishRollback(dev.civitas.core.war.RollbackEngine rollback,
                                WarBlockLogger blockLog,
                                dev.civitas.core.war.RollbackJob job) {
        if (job.status() != dev.civitas.core.war.RollbackStatus.RUNNING) {
            return;
        }
        rollback.finish(job).thenAccept(issues -> {
            if (issues.isEmpty()) {
                getLogger().info("Rollback of war " + job.warId() + " completed cleanly, "
                        + job.applied() + " blocks restored.");
            } else {
                getLogger().warning("Rollback of war " + job.warId() + " completed with "
                        + issues.size() + " issue(s). See /ca war rollbackstatus.");
            }
            blockLog.forget(job.warId());
            rollback.forget(job.warId());
        });
    }

    /**
     * The SPEC 13.5 event system: the scheduler, the boss bar, and the invasion waves.
     *
     * <p>Three timers with different jobs and different threads. The scheduler is async and
     * only touches storage and its own state. The boss bar and the wave spawner are on the
     * server thread, because both touch the world.
     */
    private void scheduleEvents(EventService events, CityRegistry cityRegistry,
                                ClaimRegistry claimRegistry, TreasuryService treasuryService,
                                DatabaseManager manager) {
        if (!events.isEnabled()) {
            getLogger().info("Server events are disabled in events.yml.");
            return;
        }

        InvasionWaves invasion = new InvasionWaves(this, cityRegistry, claimRegistry,
                events.effects(), getLogger());
        getServer().getPluginManager().registerEvents(new dev.civitas.listener.EventListener(
                events.effects(), invasion, cityRegistry, claimRegistry, treasuryService,
                manager, getLogger()), this);

        EventScheduler scheduler = new EventScheduler(events, new BroadcastAnnouncer(lang),
                configs, getLogger(), System::currentTimeMillis, Math::random);
        scheduler.begin(System.currentTimeMillis());
        long checkTicks = configs.get(ConfigFile.EVENTS)
                .getLong("events.check-interval-minutes", 5) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, scheduler, checkTicks, checkTicks);

        this.eventBar = new EventBossBar(configs, lang);
        Bukkit.getScheduler().runTaskTimer(this,
                () -> eventBar.refresh(events.running(), System.currentTimeMillis()), 40L, 40L);

        // The wave timer runs at the shortest interval any invasion could use and does nothing
        // unless one is actually running, which keeps the schedule out of the event's config.
        long waveTicks = Math.max(1L, events.effects().invasionWaveIntervalMinutes()) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!events.effects().isInvasionActive()) {
                return;
            }
            events.running().ifPresent(event -> {
                int spawned = invasion.spawnWave(event);
                if (spawned > 0) {
                    getLogger().fine(() -> "Invasion wave: " + spawned + " mobs.");
                }
            });
        }, waveTicks, waveTicks);
    }

    /**
     * The SPEC 13.4 contest cycle.
     *
     * <p>Checked far more often than it acts, for the same reason the upkeep sweep is: a phase
     * ends at a wall-clock moment and the server may have been down when it passed. The cycle
     * itself does nothing when the recorded phase already matches the clock.
     */
    private void scheduleContests(ContestService contests, CityRegistry cityRegistry) {
        if (!contests.isEnabled()) {
            getLogger().info("Contests are disabled in events.yml.");
            return;
        }
        ContestCycle cycle = new ContestCycle(contests, cityRegistry,
                StipendTask.Notifier.online(lang), getLogger(), System::currentTimeMillis);
        long ticks = configs.get(ConfigFile.EVENTS)
                .getLong("contests.check-interval-minutes", 10) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, cycle, ticks, ticks);
    }

    /**
     * The SPEC 13.3 boards and the counters two of them rank.
     *
     * <p>Two timers with different jobs. The stats flush is frequent and small: it writes what
     * players have done since the last one, and the interval is the most work a crash can
     * cost. The board refresh is slower and heavier, and runs the aggregates. Both are async,
     * and the refresh is kicked once at startup so the first player to type
     * {@code /leaderboard} does not meet an empty board.
     */
    private void scheduleLeaderboards(LeaderboardService boards, StatsService statsService) {
        long flushTicks = configs.get(ConfigFile.EVENTS)
                .getLong("leaderboards.stats-flush-seconds", 30) * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                () -> statsService.flush(System.currentTimeMillis()), flushTicks, flushTicks);

        if (!boards.isEnabled()) {
            getLogger().info("Leaderboards are disabled in events.yml.");
            return;
        }

        long refreshTicks = boards.refreshIntervalMinutes() * 60L * 20L;
        Bukkit.getScheduler().runTaskAsynchronously(this,
                () -> boards.refresh(System.currentTimeMillis()).thenAccept(populated ->
                        getLogger().info(() -> "Leaderboards ready, " + populated
                                + " of " + dev.civitas.core.progression.LeaderboardType.all().size()
                                + " boards populated.")));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                () -> boards.refresh(System.currentTimeMillis()), refreshTicks, refreshTicks);
    }

    /**
     * The SPEC 4.2 stipend sweep.
     *
     * <p>Runs on the interval SPEC 4.2.1 defines rather than more often: the check is "did
     * they do three distinct things in this interval", and an interval that has not finished
     * cannot answer it.
     */
    private void scheduleStipend(DatabaseManager manager, DaoRegistry loadedDaos,
                                 EconomyService economyService, ActivityTracker activityTracker,
                                 IncomeMultipliers incomeMultipliers) {
        if (!configs.get(ConfigFile.ECONOMY).getBoolean("income.stipend.enabled", true)) {
            return;
        }
        StipendTask stipend = new StipendTask(manager, loadedDaos.players(), loadedDaos.ledger(),
                economyService, activityTracker, incomeMultipliers, configs,
                () -> Bukkit.getOnlinePlayers().stream()
                        .map(Player::getUniqueId)
                        .toList(),
                StipendTask.Notifier.online(lang), getLogger());

        long ticks = configs.get(ConfigFile.ECONOMY)
                .getLong("income.stipend.interval-minutes", 15) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, stipend, ticks, ticks);
    }

    /** SPEC 7.4: the mayor is told when the city has grown into one of its own outposts. */
    private void notifyMayor(dev.civitas.core.city.City city, String outpostName) {
        Player mayor = Bukkit.getPlayer(city.mayorUuid());
        if (mayor != null) {
            lang.send(mayor, "outpost.converted",
                    dev.civitas.lang.LangManager.placeholder("name", outpostName));
        }
    }

    /**
     * The SPEC 14.2 and 14.3 sweep: notice periods that have run out, truces that have ended.
     *
     * <p>Hourly, because the shortest thing it measures is a 24-hour notice and checking a
     * day-long timer every minute would be sixty times the work for the same answer.
     */
    private void scheduleDiplomacy(DiplomacyService service, DiplomacyRegistry registry,
                                   CityRegistry cityRegistry) {
        DiplomacyTask task = new DiplomacyTask(service, registry, cityRegistry,
                dev.civitas.core.income.StipendTask.Notifier.online(lang), getLogger());
        long ticks = configs.get(ConfigFile.CITIES)
                .getLong("diplomacy.sweep-interval-minutes", 60) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, task, ticks, ticks);
    }

    private void scheduleBackups(DatabaseSettings settings) {
        if (!settings.backupEnabled()) {
            return;
        }
        backups.warnIfUnsupported();
        if (!backups.isSupported()) {
            return;
        }

        long intervalTicks = settings.backupIntervalHours() * TICKS_PER_HOUR;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                () -> backups.backupNow(settings.backupKeepCount()),
                intervalTicks, intervalTicks);

        getLogger().info(() -> "Database backups every " + settings.backupIntervalHours()
                + "h, keeping " + settings.backupKeepCount() + ", in " + backups.folder() + ".");
    }

    /**
     * Rollback is the plugin's core promise (SPEC 1.2 and 11.1). Turning it off means war
     * destroys builds permanently, so an operator who does it is told loudly rather than
     * discovering it after the first war.
     */
    /**
     * SPEC 16.1 keys this build reads and deliberately does not act on.
     *
     * <p>Two of them, both about batching the ledger. SPEC 1.5 makes the ledger the authority
     * for every dispute, and a row waiting in a buffer is a row a crash loses — so it is
     * written inside the transaction that moves the money, and there is nothing to batch.
     * Stated once at startup rather than left as a setting that silently does nothing, which
     * is the failure this whole config sweep exists to remove.
     */
    /**
     * SPEC 21.10.1's startup validation, M6a.
     *
     * <p>The graph is the hardcoded tables plus whatever the server's own recipe list adds,
     * so a datapack that introduces a reversible recipe between two traded items is caught
     * as surely as a vanilla one. SPEC 21.3's flaw does not care where the recipe came from.
     */
    private dev.civitas.core.market.MarketSafetyCheck checkMarketSafety(
            dev.civitas.core.market.MarketRegistry marketRegistry) {
        dev.civitas.core.market.craft.RecipeGraph graph =
                dev.civitas.core.market.craft.CraftingEdges.baseGraph();
        int fromServer = dev.civitas.core.market.craft.BukkitRecipeSource
                .addAllTo(graph, getLogger());
        getLogger().info(() -> "Crafting equivalence graph: " + graph.size() + " materials, "
                + fromServer + " edges from the server's own recipes.");

        // SPEC 21.10.1's four assertions, over what the server BUYS. The sell catalogue is
        // not checked and does not need to be: SPEC 21.6, "every item the server sells is a
        // money sink and carries no exploit risk at all".
        java.util.List<String> buyList = marketRegistry.buyList();
        dev.civitas.core.market.MarketSafetyCheck check =
                new dev.civitas.core.market.MarketSafetyCheck();
        check.checkHardBlacklist(buyList);                       // 1, SPEC 21.8
        check.checkVillagerDisjointness(buyList);                // 2
        check.checkEquivalenceClasses(buyList, graph);           // 3, SPEC 21.3
        check.checkAutomatableDeclared(buyList, automatableComments());   // 4
        check.report(getLogger());
        return check;
    }

    /**
     * Reads the {@code # automatable: no|semi} comment beside each buy entry, SPEC 21.10.1.
     *
     * <p>A YAML comment rather than a key, which is what SPEC asks for and is the right shape:
     * nothing in the plugin uses the value, so a key would imply it changed something. What it
     * does is force an operator adding an item to answer the question SPEC 21.1 says decides
     * whether the item belongs on the list at all.
     *
     * <p>Read from the operator's own file rather than the packaged copy, so a comment they
     * deleted is a comment that is gone.
     */
    private java.util.Map<String, String> automatableComments() {
        java.util.Map<String, String> declared = new java.util.LinkedHashMap<>();
        org.bukkit.configuration.ConfigurationSection buy =
                configs.get(ConfigFile.ECONOMY).getConfigurationSection("market.buy");
        if (buy == null) {
            return declared;
        }
        for (String key : buy.getKeys(false)) {
            for (String comment : commentsOn(buy, key)) {
                String trimmed = comment == null ? "" : comment.trim();
                if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("automatable:")) {
                    declared.put(key.toUpperCase(java.util.Locale.ROOT),
                            trimmed.substring("automatable:".length()).trim()
                                    .toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return declared;
    }

    /**
     * The comments attached to one buy entry.
     *
     * <p>Above the key first, then trailing. Bukkit only reads a trailing comment when the
     * value is a scalar, and these entries are flow-style maps — so SPEC 21.11's example
     * placement is unreadable, and is also dropped when the file is saved. Both are tried so
     * an operator who writes a block-style entry with a trailing comment still works.
     */
    private static java.util.List<String> commentsOn(
            org.bukkit.configuration.ConfigurationSection section, String key) {
        java.util.List<String> comments =
                new java.util.ArrayList<>(section.getComments(key));
        comments.addAll(section.getInlineComments(key));
        return comments;
    }

    private void warnAboutUnhonouredSettings() {
        int batchSize = configs.get(ConfigFile.CONFIG).getInt("performance.ledger-batch-size", 0);
        int flushSeconds =
                configs.get(ConfigFile.CONFIG).getInt("performance.ledger-flush-seconds", 0);
        if (batchSize > 0 || flushSeconds > 0) {
            getLogger().info("performance.ledger-batch-size and ledger-flush-seconds are not "
                    + "honoured: ledger rows are written inside the transaction that moves the "
                    + "money, so an audit entry cannot be lost to a crash. See SPEC 1.5.");
        }
    }

    private void warnIfRollbackDisabled() {
        if (!configs.get(ConfigFile.WAR).getBoolean("rollback.enabled", true)) {
            getLogger().severe("war.yml has rollback.enabled: false.");
            getLogger().severe("War damage will NOT be restored. This is never correct on a live server.");
        }
        // The other four SPEC 16.3 rollback switches. Three of them now do what they say;
        // the two that describe behaviour with no alternative implementation say so here
        // rather than being silently ignored, which is what they were until M23.
        new dev.civitas.core.war.RollbackPolicy(configs).warnAboutProblems(getLogger());
        warnAboutUnhonouredSettings();
    }
}
