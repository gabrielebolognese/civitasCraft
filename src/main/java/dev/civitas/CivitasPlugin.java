package dev.civitas;

import java.io.File;
import java.util.logging.Level;

import dev.civitas.command.CommandRegistry;
import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.BackupService;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.DatabaseSettings;
import dev.civitas.storage.dao.DaoRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point and lifecycle owner.
 *
 * <p>Construction order is dependency order: configuration, language, then storage. Services
 * and listeners are added by later milestones and slot in after storage.
 *
 * <p>The database is opened on an async task, because migrations can take seconds on a large
 * schema and SPEC 2.1 forbids blocking the server thread on storage. Until it finishes,
 * {@link #daos()} returns {@code null}; every consumer added from M2 onward is constructed
 * inside that callback, so nothing can observe the half-open state.
 */
public final class CivitasPlugin extends JavaPlugin {

    private static final long TICKS_PER_HOUR = 20L * 60L * 60L;

    private ConfigManager configs;
    private LangManager lang;
    private DatabaseManager database;
    private DaoRegistry daos;
    private BackupService backups;

    @Override
    public void onEnable() {
        configs = new ConfigManager(this);
        configs.loadAll();

        lang = new LangManager(this, configs);
        lang.load();

        new CommandRegistry(this, lang).registerAll();

        warnIfRollbackDisabled();
        openDatabaseAsync();

        getLogger().info(() -> "Enabled version " + getPluginMeta().getVersion()
                + ", language " + lang.activeLanguage() + ".");
    }

    @Override
    public void onDisable() {
        // Blocking here is correct: SPEC 17.7 case 84 requires buffered writes to reach
        // disk before the server exits, and there is no later opportunity.
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

    private void openDatabaseAsync() {
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

            Bukkit.getScheduler().runTask(this, () -> onDatabaseReady(manager, settings));
        });
    }

    /** Runs on the main thread once the schema is current. Later milestones wire in here. */
    private void onDatabaseReady(DatabaseManager manager, DatabaseSettings settings) {
        if (!isEnabled()) {
            // Disabled while the open was in flight; do not leak the pool.
            manager.close();
            return;
        }

        this.database = manager;
        this.daos = new DaoRegistry(manager);
        this.backups = new BackupService(getLogger(), manager, new File(getDataFolder(), "backups"));

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
