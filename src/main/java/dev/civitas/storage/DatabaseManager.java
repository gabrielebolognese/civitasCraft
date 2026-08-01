package dev.civitas.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.civitas.storage.migration.MigrationRunner;
import dev.civitas.util.Result;

/**
 * Owns the connection pool and the thread pool that all database work runs on.
 *
 * <h2>The main-thread rule</h2>
 * SPEC 2.1 and PLAN.md both state it as an absolute: zero database access on the main
 * thread, ever. That rule is enforced here rather than trusted. Every {@link #call} runs on
 * a dedicated pool, and {@link #callSync} throws if it is reached from the server thread,
 * so a misuse fails loudly during development instead of showing up as an unexplained TPS
 * drop months later.
 *
 * <p>Results are returned as {@link CompletableFuture}. Callers that need to touch the
 * Bukkit API with the result must hop back to the main thread themselves; this class
 * deliberately knows nothing about the scheduler.
 */
public final class DatabaseManager implements AutoCloseable {

    private final Logger logger;
    private final DatabaseSettings settings;
    private final BooleanSupplier onMainThread;

    private HikariDataSource dataSource;
    private ExecutorService executor;

    /**
     * @param logger       where slow queries and pool problems are reported
     * @param settings     resolved {@code storage} configuration
     * @param onMainThread tells the guard whether the calling thread is the server thread;
     *                     injected so tests need no running server
     */
    public DatabaseManager(Logger logger, DatabaseSettings settings, BooleanSupplier onMainThread) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.onMainThread = Objects.requireNonNull(onMainThread, "onMainThread");
    }

    public SqlDialect dialect() {
        return settings.dialect();
    }

    public DatabaseSettings settings() {
        return settings;
    }

    /**
     * Opens the pool and brings the schema up to date.
     *
     * <p>Blocking, and intended to be called off the main thread during enable.
     *
     * @throws StorageException if the driver is missing, the database is unreachable, or a
     *                          migration fails
     */
    public void open() {
        if (dataSource != null) {
            throw new IllegalStateException("Database is already open");
        }

        try {
            Class.forName(settings.dialect().driverClassName());
        } catch (ClassNotFoundException e) {
            throw new StorageException("JDBC driver missing for " + settings.dialect(), e);
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("civitas-" + settings.dialect());
        config.setJdbcUrl(buildJdbcUrl());
        config.setDriverClassName(settings.dialect().driverClassName());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(1);
        config.setAutoCommit(true);

        if (settings.dialect() == SqlDialect.MYSQL) {
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        }

        this.dataSource = new HikariDataSource(config);
        this.executor = Executors.newFixedThreadPool(settings.poolSize(), namedThreadFactory());

        new MigrationRunner(logger, settings.dialect()).run(dataSource);
    }

    /**
     * SQLite pragmas travel in the URL because Hikari's {@code connectionInitSql} accepts
     * only one statement. WAL lets readers proceed while the war block logger writes,
     * {@code busy_timeout} turns lock contention into a wait rather than an error, and
     * foreign keys are off by default in SQLite and must be asked for.
     *
     * <p>{@code transaction_mode=IMMEDIATE} matters more than it looks. SQLite's default
     * deferred transaction takes its write lock lazily, so two transactions that both read
     * and then write race: the second gets {@code SQLITE_BUSY_SNAPSHOT}, which
     * {@code busy_timeout} does <em>not</em> retry because waiting cannot resolve it. Two
     * players paying at the same moment is enough to hit it. Taking the write lock up front
     * turns that into an ordinary wait that the timeout does cover.
     */
    private String buildJdbcUrl() {
        if (settings.dialect() != SqlDialect.SQLITE) {
            return settings.jdbcUrl();
        }
        return settings.jdbcUrl()
                + "?journal_mode=" + settings.journalMode()
                + "&busy_timeout=" + settings.busyTimeoutMs()
                + "&transaction_mode=IMMEDIATE"
                + "&foreign_keys=on";
    }

    private ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "civitas-db-" + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    /** Runs {@code work} on the database pool. */
    public <R> CompletableFuture<R> call(SqlFunction<Connection, R> work) {
        Objects.requireNonNull(work, "work");
        requireOpen();
        return CompletableFuture.supplyAsync(() -> callSync(work), executor);
    }

    /** Runs {@code work} on the database pool, discarding its result. */
    public CompletableFuture<Void> run(SqlFunction<Connection, ?> work) {
        return call(work).thenApply(ignored -> null);
    }

    /**
     * Runs {@code work} inside a transaction on the database pool. The transaction commits
     * if {@code work} returns and rolls back if it throws.
     *
     * <p>It also rolls back when {@code work} returns a {@link Result.Failure}. SPEC 2.3
     * makes {@code Result} the way a service reports an expected failure instead of throwing,
     * so without this a service that writes half of a change and then refuses the other half
     * would commit the half it wrote: a transfer whose credit is refused would debit the
     * sender and destroy the money. Returning a failure and wanting the writes kept is not a
     * thing any caller should want, so the rule is unconditional.
     */
    public <R> CompletableFuture<R> transaction(SqlFunction<Connection, R> work) {
        Objects.requireNonNull(work, "work");
        return call(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                if (result instanceof Result.Failure<?>) {
                    connection.rollback();
                } else {
                    connection.commit();
                }
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    /**
     * Borrows a connection and runs {@code work} on the calling thread.
     *
     * <p>For use from inside the database pool, from the migration runner, and from tests.
     *
     * @throws IllegalStateException if called from the server thread
     * @throws StorageException      if the work fails
     */
    public <R> R callSync(SqlFunction<Connection, R> work) {
        requireOpen();
        requireOffMainThread();

        long startedAt = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new StorageException("Database query failed", e);
        } finally {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            if (elapsedMs >= settings.slowQueryWarnMs()) {
                logger.log(Level.WARNING, "Slow database call: {0} ms", elapsedMs);
            }
        }
    }

    /**
     * The guard behind the hard rule. A database call on the server thread stalls every
     * player on the server, so it is treated as a programming error, not a slow path.
     */
    private void requireOffMainThread() {
        if (onMainThread.getAsBoolean()) {
            throw new IllegalStateException(
                    "Database access attempted on the main thread. All storage I/O must be async "
                            + "(SPEC 2.1). Use DatabaseManager.call or a DAO method instead.");
        }
    }

    private void requireOpen() {
        if (dataSource == null) {
            throw new IllegalStateException("Database is not open");
        }
    }

    public boolean isOpen() {
        return dataSource != null && !dataSource.isClosed();
    }

    /**
     * Drains queued work, then closes the pool.
     *
     * <p>Called from {@code onDisable}, where blocking is correct: SPEC 17.7 case 84
     * requires buffered writes to reach disk before the server exits.
     */
    @Override
    public void close() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    logger.warning("Database work did not finish within 30s; forcing shutdown.");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            executor = null;
        }
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
