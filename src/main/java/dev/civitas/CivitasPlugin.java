package dev.civitas;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import dev.civitas.command.CommandRegistry;
import dev.civitas.command.city.CityCommand;
import dev.civitas.command.player.MoneyCommand;
import dev.civitas.command.player.PayCommand;
import dev.civitas.command.player.SellCommand;
import dev.civitas.command.player.ShopCommand;
import dev.civitas.command.player.WorthCommand;
import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.CityNameValidator;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.city.CityService;
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
import dev.civitas.lang.LangManager;
import dev.civitas.listener.BlockProtectionListener;
import dev.civitas.listener.CityChatListener;
import dev.civitas.listener.ClaimBoundaryListener;
import dev.civitas.listener.ContainerProtectionListener;
import dev.civitas.listener.EntityProtectionListener;
import dev.civitas.listener.ExplosionProtectionListener;
import dev.civitas.listener.FireAndFluidListener;
import dev.civitas.listener.InteractionProtectionListener;
import dev.civitas.listener.PistonProtectionListener;
import dev.civitas.listener.PlayerAccountListener;
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

    private ConfigManager configs;
    private LangManager lang;
    private DatabaseManager database;
    private DaoRegistry daos;
    private BackupService backups;
    private BorderRenderer borders;

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
        ShopCommand shopCommand = new ShopCommand(services::get, lang, scheduler, getLogger());
        SellCommand sellCommand = new SellCommand(services::get, lang, scheduler, getLogger());
        WorthCommand worthCommand = new WorthCommand(services::get, lang);
        new CommandRegistry(this, lang).registerAll(
                List.of(cityCommand.build(), moneyCommand.build(), payCommand.build(),
                        shopCommand.build(), sellCommand.build(), worthCommand.build()));

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

        EconomyService economyService = new EconomyService(manager, loadedDaos.players(),
                loadedDaos.ledger(), configs, getLogger());
        economyService.loadAll().thenAccept(loaded ->
                getLogger().info(() -> "Loaded " + loaded + " balances into the cache."));

        TreasuryService treasuryService = new TreasuryService(manager, loadedDaos,
                economyService, configs, scheduler);
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
                marketRegistry, pricing, economyService, configs);
        MarketItemFilter marketFilter = new MarketItemFilter(configs);

        PlayerShopService shopService =
                new PlayerShopService(loadedDaos.playerShops(), economyService);
        shopService.loadAll().thenAccept(loaded ->
                getLogger().info(() -> "Loaded " + loaded + " player shops."));

        this.borders = borderRenderer;

        services.set(new CivitasServices(cityRegistry, cityService, rankService, claimRegistry,
                claimService, claimMap, borderRenderer, protection, protectionGuard,
                blockClassifier, economyService, treasuryService, upkeepCalculator,
                marketService, marketFilter, shopService, accounts, lookup));

        getServer().getPluginManager().registerEvents(
                new PlayerAccountListener(accounts, getLogger()), this);
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

        scheduleMarketDecay(marketRegistry, pricing, loadedDaos);

        // Anyone already online, on a /reload, never fired a join event for us.
        long now = System.currentTimeMillis();
        for (Player online : Bukkit.getOnlinePlayers()) {
            accounts.onJoin(online.getUniqueId(), online.getName(), now);
        }

        scheduleEconomy(manager, loadedDaos, cityRegistry, claimService, economyService,
                treasuryService, upkeepCalculator, scheduler);
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
    private void scheduleEconomy(DatabaseManager manager, DaoRegistry loadedDaos,
                                 CityRegistry cityRegistry, ClaimService claimService,
                                 EconomyService economyService, TreasuryService treasuryService,
                                 UpkeepCalculator upkeepCalculator, Scheduler scheduler) {

        UpkeepTask upkeep = new UpkeepTask(manager, loadedDaos, cityRegistry, claimService,
                treasuryService, upkeepCalculator, UpkeepTask.Notifier.online(lang), scheduler,
                getLogger(),
                java.time.ZoneId.systemDefault());

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
