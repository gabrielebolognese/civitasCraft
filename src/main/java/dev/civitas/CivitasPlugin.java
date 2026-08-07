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
import dev.civitas.core.outpost.OutpostTeleport;
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
    private OutpostTeleport outposts;
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
                new AdminCommand(services::get, lang, scheduler, getLogger());
        ShopCommand shopCommand = new ShopCommand(services::get, lang, scheduler, getLogger());
        SellCommand sellCommand = new SellCommand(services::get, lang, scheduler, getLogger());
        WorthCommand worthCommand = new WorthCommand(services::get, lang);
        QuestsCommand questsCommand = new QuestsCommand(services::get, lang, scheduler);
        AllyCommand allyCommand = new AllyCommand(services::get, lang, scheduler, getLogger());
        AllianceChatCommand allianceChat = new AllianceChatCommand(services::get, lang);
        LeaderboardCommand leaderboardCommand = new LeaderboardCommand(services::get, lang);
        dev.civitas.command.war.WarCommand warCommand =
                new dev.civitas.command.war.WarCommand(services::get, lang, scheduler, getLogger());
        ContestCommand contestCommand =
                new ContestCommand(services::get, lang, scheduler, getLogger());
        new CommandRegistry(this, lang).registerAll(
                List.of(cityCommand.build(), moneyCommand.build(), payCommand.build(),
                        shopCommand.build(), sellCommand.build(), worthCommand.build(),
                        questsCommand.buildQuests(), questsCommand.buildChallenges(),
                        allyCommand.buildAlly(), allyCommand.buildTruce(),
                        allianceChat.build(), leaderboardCommand.build(),
                        contestCommand.build(), warCommand.build(),
                        bountyCommand.build(), adminCommand.build()));

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
        if (outposts != null) {
            outposts.stopAll();
            outposts = null;
        }
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

        // SPEC 4.2, 13.1 and 13.2, the income systems.
        ActivityTracker activityTracker = new ActivityTracker(configs);
        IncomeMultipliers incomeMultipliers = new IncomeMultipliers(configs);
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
        marketService.useUpgrades(cityRegistry, upgradeService);

        // SPEC 7, outposts.
        OutpostRegistry outpostRegistry = new OutpostRegistry(loadedDaos.outposts());
        outpostRegistry.loadAll().thenAccept(loaded ->
                getLogger().info(() -> "Loaded " + loaded + " outposts."));
        OutpostService outpostService = new OutpostService(manager, loadedDaos, cityRegistry,
                claimRegistry, claimService, outpostRegistry, treasuryService, configs, scheduler);
        OutpostTeleport outpostTeleport = new OutpostTeleport(this, outpostService,
                economyService, configs, lang);
        this.outposts = outpostTeleport;
        upkeepTask.useOutposts(outpostRegistry);
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
        defenseRegistry.loadAll().thenAccept(loaded ->
                getLogger().info(() -> "Loaded " + loaded + " defense units."));
        DefenseSpawner defenseSpawner = new DefenseSpawner(this, defenseCatalogue, lang);
        DefenseService defenseService = new DefenseService(this, manager,
                loadedDaos.defenseUnits(), defenseRegistry, defenseCatalogue, defenseSpawner,
                cityRegistry, claimRegistry, treasuryService, upgradeService, lang, scheduler);
        DefenseBehaviour defenseBehaviour = new DefenseBehaviour(defenseCatalogue, cityRegistry);
        upkeepTask.useDefense(defenseRegistry, defenseService);
        // Registered on the same hook rather than left to the next milestone: a disbanded
        // city that keeps its units and its bought upgrade levels would hand them back to
        // whoever founds a city that happens to reuse the id.
        cityService.onCityDisbanded(upgradeService::forgetCity);
        cityService.onCityDisbanded(cityId -> cityRegistry.city(cityId)
                .ifPresent(defenseService::removeCity));

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
        scheduleBountyExpiry(bountyService);

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
        upgradeService.useWars(warWiring.restrictions());             // SPEC 11.11
        statsService.useWars(warWiring.registry());                   // SPEC 13.3 Builder
        defenseService.useWars(warWiring.registry());                 // SPEC 12.4 price
        defenseBehaviour.useWars(warWiring.registry());               // SPEC 12.3 targeting
        warWiring.restrictions().useAdminProtection(adminProtection); // SPEC 11.6

        services.set(new CivitasServices(cityRegistry, cityService, rankService, claimRegistry,
                claimService, claimMap, borderRenderer, protection, protectionGuard,
                blockClassifier, economyService, treasuryService, bountyService,
                upkeepCalculator,
                upkeepTask, marketService, marketFilter, shopService, questService,
                challengeService, leaderboardService, statsService, contestService,
                eventService, warWiring.service(), warWiring.allies(), warWiring.peace(),
                warWiring.capturePoints(), warWiring.rollback(), warWiring.trigger(),
                auditService, adminProtection, fraudHeuristics, inspectMode,
                ledgerExport, upkeepOverrides,
                loadedDaos, warWiring.scoreboard(),
                outpostService, outpostTeleport, upgradeService,
                defenseService, diplomacyService, vaultService, vaultView,
                menuManager, layoutLoader,
                amountInput, spawnService, cityHall, accounts, lookup, scheduler));

        getServer().getPluginManager().registerEvents(
                new PlayerAccountListener(accounts, loadedDaos.playerLogins(), fingerprints,
                        getLogger()), this);
        getServer().getPluginManager().registerEvents(
                new CityChatListener(cityRegistry, configs, lang), this);
        getServer().getPluginManager().registerEvents(
                new ClaimBoundaryListener(cityRegistry, claimService, borderRenderer, lang), this);

        // SPEC 5.5, the land protection listeners. Registered together so it is obvious at a
        // glance which events the plugin guards.
        registerProtection(protectionGuard, protection, blockClassifier);

        // SPEC 4.5, chest shops.
        getServer().getPluginManager().registerEvents(new ShopSignListener(shopService,
                new ShopSign(configs), protectionGuard, configs, lang, scheduler, getLogger()), this);
        getServer().getPluginManager().registerEvents(
                new ShopInteractListener(shopService, configs, lang, scheduler, getLogger()), this);

        // SPEC 8.1 and 5.6.
        getServer().getPluginManager().registerEvents(
                new CityHallListener(services::get, cityHall, lang), this);
        getServer().getPluginManager().registerEvents(
                new TeleportWarmupListener(spawnService, outpostTeleport), this);
        getServer().getPluginManager().registerEvents(new VaultListener(vaultView), this);
        getServer().getPluginManager().registerEvents(new dev.civitas.listener
                .AdminInspectListener(inspectMode, lang,
                dev.civitas.listener.AdminInspectListener.ProtectedChunkLookup.none()), this);
        getServer().getPluginManager().registerEvents(new DefenseListener(this, defenseService,
                defenseBehaviour, cityRegistry, lang, getLogger()), this);
        scheduleDefenseLeash(new dev.civitas.core.defense.DefenseLeash(defenseRegistry,
                defenseSpawner, defenseBehaviour, claimRegistry), defenseRegistry);
        getServer().getPluginManager().registerEvents(
                new ActivityListener(activityTracker, questService, challengeService,
                        statsService), this);
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
        registerIntegrations(economyService);

        getLogger().info(() -> "Storage ready on " + settings.dialect() + ".");
        scheduleBackups(settings);
    }

    /** Every SPEC 5.5 listener. */
    private void registerProtection(ProtectionGuard guard, ProtectionService protection,
                                    BlockClassifier blocks) {
        var manager = getServer().getPluginManager();
        manager.registerEvents(new BlockProtectionListener(guard), this);
        manager.registerEvents(new ContainerProtectionListener(guard, blocks), this);
        manager.registerEvents(new InteractionProtectionListener(guard, blocks), this);
        manager.registerEvents(new EntityProtectionListener(guard), this);
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
    private void warnIfRollbackDisabled() {
        if (!configs.get(ConfigFile.WAR).getBoolean("rollback.enabled", true)) {
            getLogger().severe("war.yml has rollback.enabled: false.");
            getLogger().severe("War damage will NOT be restored. This is never correct on a live server.");
        }
    }
}
