package dev.civitas.storage;

import java.io.File;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * The {@code storage} block of {@code config.yml}, resolved once at startup.
 *
 * @param dialect         which backend to open
 * @param jdbcUrl         the fully-built JDBC URL
 * @param username        MySQL username, empty for SQLite
 * @param password        MySQL password, empty for SQLite
 * @param poolSize        maximum pooled connections
 * @param busyTimeoutMs   SQLite lock wait before giving up; ignored on MySQL
 * @param journalMode     SQLite journal mode; ignored on MySQL
 * @param slowQueryWarnMs a query slower than this is logged as slow
 * @param backupEnabled   whether the scheduled backup task runs
 * @param backupIntervalHours hours between backups
 * @param backupKeepCount how many backup files to retain
 */
public record DatabaseSettings(
        SqlDialect dialect,
        String jdbcUrl,
        String username,
        String password,
        int poolSize,
        int busyTimeoutMs,
        String journalMode,
        long slowQueryWarnMs,
        boolean backupEnabled,
        int backupIntervalHours,
        int backupKeepCount) {

    /**
     * @param config     the loaded {@code config.yml}
     * @param dataFolder the plugin data folder, used to resolve the SQLite file path
     */
    public static DatabaseSettings from(FileConfiguration config, File dataFolder) {
        SqlDialect dialect = SqlDialect.parse(config.getString("storage.type", "SQLITE"));

        String url;
        String username;
        String password;
        int poolSize;

        if (dialect == SqlDialect.SQLITE) {
            File file = new File(dataFolder, config.getString("storage.sqlite.file", "civitas.db"));
            url = "jdbc:sqlite:" + file.getAbsolutePath();
            username = "";
            password = "";
            poolSize = config.getInt("storage.sqlite.pool-size", 4);
        } else {
            String properties = config.getString("storage.mysql.properties", "");
            url = "jdbc:mysql://"
                    + config.getString("storage.mysql.host", "localhost") + ":"
                    + config.getInt("storage.mysql.port", 3306) + "/"
                    + config.getString("storage.mysql.database", "civitas")
                    + (properties.isBlank() ? "" : "?" + properties);
            username = config.getString("storage.mysql.username", "");
            password = config.getString("storage.mysql.password", "");
            poolSize = config.getInt("storage.mysql.pool-size", 10);
        }

        return new DatabaseSettings(
                dialect,
                url,
                username,
                password,
                Math.max(1, poolSize),
                config.getInt("storage.sqlite.busy-timeout-ms", 5000),
                config.getString("storage.sqlite.journal-mode", "WAL"),
                config.getLong("storage.slow-query-warn-ms", 250L),
                config.getBoolean("storage.backup.enabled", true),
                Math.max(1, config.getInt("storage.backup.interval-hours", 6)),
                Math.max(1, config.getInt("storage.backup.keep-count", 28)));
    }

    /** SQLite only: the database file, resolved from {@link #jdbcUrl}. */
    public File sqliteFile() {
        if (dialect != SqlDialect.SQLITE) {
            throw new IllegalStateException("Not a SQLite configuration");
        }
        return new File(jdbcUrl.substring("jdbc:sqlite:".length()));
    }
}
