package dev.civitas;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import dev.civitas.command.CommandRegistry;
import dev.civitas.command.city.CityCommand;
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
import dev.civitas.core.economy.Funds;
import dev.civitas.core.economy.PlayerAccountService;
import dev.civitas.core.economy.StorageFunds;
import dev.civitas.lang.LangManager;
import dev.civitas.listener.CityChatListener;
import dev.civitas.listener.ClaimBoundaryListener;
import dev.civitas.listener.PlayerAccountListener;
import dev.civitas.storage.BackupService;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.DatabaseSettings;
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
        new CommandRegistry(this, lang).registerAll(List.of(cityCommand.build()));

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

        Funds funds = new StorageFunds(loadedDaos.players(), loadedDaos.ledger(), configs);
        PlayerAccountService accounts =
                new PlayerAccountService(manager, loadedDaos.players(), loadedDaos.ledger(), configs);
        ClaimCostEngine costEngine = new ClaimCostEngine(configs);
        ClaimService claimService = new ClaimService(manager, loadedDaos, cityRegistry,
                claimRegistry, costEngine, configs, scheduler, EventBus.bukkit());
        claimService.loadActiveMembers();

        CityService cityService = new CityService(manager, loadedDaos, cityRegistry, configs,
                new CityNameValidator(configs), funds, claimService, accounts, scheduler,
                EventBus.bukkit());
        RankService rankService = new RankService(manager, loadedDaos, scheduler, EventBus.bukkit());
        ClaimMap claimMap = new ClaimMap(claimRegistry, cityRegistry, configs, lang);
        BorderRenderer borderRenderer =
                new BorderRenderer(this, claimRegistry, configs, getLogger());
        PlayerLookup lookup = new PlayerLookup(loadedDaos.players());

        this.borders = borderRenderer;

        services.set(new CivitasServices(cityRegistry, cityService, rankService, claimRegistry,
                claimService, claimMap, borderRenderer, accounts, lookup));

        getServer().getPluginManager().registerEvents(
                new PlayerAccountListener(accounts, getLogger()), this);
        getServer().getPluginManager().registerEvents(
                new CityChatListener(cityRegistry, configs, lang), this);
        getServer().getPluginManager().registerEvents(
                new ClaimBoundaryListener(cityRegistry, claimService, borderRenderer, lang), this);

        // Anyone already online, on a /reload, never fired a join event for us.
        long now = System.currentTimeMillis();
        for (Player online : Bukkit.getOnlinePlayers()) {
            accounts.onJoin(online.getUniqueId(), online.getName(), now);
        }

        getLogger().info(() -> "Storage ready on " + settings.dialect() + ".");
        scheduleBackups(settings);
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
