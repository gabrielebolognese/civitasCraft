package dev.civitas.core.city;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.claim.ClaimCostEngine;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.core.economy.Funds;
import dev.civitas.core.economy.PlayerAccountService;
import dev.civitas.core.economy.StorageFunds;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.DatabaseSettings;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.EventBus;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * A whole city stack over a real SQLite database, for the SPEC 18.2 integration tests.
 *
 * <p>No server is mocked. Config comes from the packaged {@code cities.yml} and
 * {@code economy.yml} through {@link PluginResources#ofClasspath}, so every default the
 * service reads is the one that ships rather than a value invented by the test, and
 * {@link Scheduler#direct()} runs cache updates inline so a test can assert on the cache the
 * moment a future completes.
 */
public final class CityTestSupport implements AutoCloseable {

    public final ConfigManager configs;
    public final DatabaseManager db;
    public final DaoRegistry daos;
    public final CityRegistry registry;
    public final CityService cities;
    public final RankService ranks;
    public final ClaimRegistry claimRegistry;
    public final ClaimCostEngine costs;
    public final ClaimService claims;
    public final PlayerAccountService accounts;
    public final Funds funds;

    private CityTestSupport(Path directory, EventBus events) {
        this.configs = new ConfigManager(
                PluginResources.ofClasspath(directory.resolve("plugin").toFile(), quietLogger()));
        configs.loadAll();

        DatabaseSettings settings = new DatabaseSettings(
                SqlDialect.SQLITE,
                "jdbc:sqlite:" + directory.resolve("city.db").toAbsolutePath(),
                "", "", 2, 5000, "WAL", Long.MAX_VALUE, false, 6, 28);

        this.db = new DatabaseManager(quietLogger(), settings, () -> false);
        db.open();

        this.daos = new DaoRegistry(db);
        this.registry = new CityRegistry(daos);
        this.accounts = new PlayerAccountService(db, daos.players(), daos.ledger(), configs);
        this.funds = new StorageFunds(daos.players(), daos.ledger(), configs);
        this.claimRegistry = new ClaimRegistry(daos.claims());
        this.costs = new ClaimCostEngine(configs);
        this.claims = new ClaimService(db, daos, registry, claimRegistry, costs, configs,
                Scheduler.direct(), events);
        this.cities = new CityService(db, daos, registry, configs,
                new CityNameValidator(configs), funds, claims, accounts, Scheduler.direct(), events);
        this.ranks = new RankService(db, daos, Scheduler.direct(), events);
    }

    public static CityTestSupport open(Path directory) {
        return new CityTestSupport(directory, EventBus.noop());
    }

    public static CityTestSupport open(Path directory, EventBus events) {
        return new CityTestSupport(directory, events);
    }

    /** A player with enough playtime and money to found a city. */
    public UUID givenEligiblePlayer(String name) {
        return givenPlayer(name, new BigDecimal("50000.00"), TimeUnit.HOURS.toMillis(10));
    }

    public UUID givenPlayer(String name, BigDecimal balance, long activePlaytimeMs) {
        UUID uuid = UUID.randomUUID();
        await(daos.players().insert(new PlayerRow(uuid, name, balance, null, null,
                1_000L, 2_000L, activePlaytimeMs, activePlaytimeMs, 0, 0L, 0L, false, 0L, 0L)));
        return uuid;
    }

    /** A core chunk and a spawn inside it. */
    public static Placement placement(int chunkX, int chunkZ) {
        return new Placement("world", chunkX, chunkZ,
                chunkX * 16 + 8.5, 64.0, chunkZ * 16 + 8.5, 0f, 0f);
    }

    public City givenCity(UUID founder, String name, int chunkX, int chunkZ) {
        Result<City> result = await(cities.create(founder, name, placement(chunkX, chunkZ)));
        if (result instanceof Result.Failure<City> failure) {
            throw new AssertionError("fixture city could not be founded: " + failure.reason()
                    + " (" + failure.messageKey() + ")");
        }
        return result.orElseThrow();
    }

    /** Refreshes the player rows the SPEC 6.2 member divisor is computed from. */
    public void refreshPricing() {
        await(claims.loadActiveMembers());
    }

    /** Puts a second player into an existing city at its default rank. */
    public UUID givenMember(City city, String name) {
        UUID uuid = givenEligiblePlayer(name);
        await(cities.invite(city.mayorUuid(), city, uuid));
        Result<City> joined = await(cities.acceptInvite(uuid, city));
        if (joined instanceof Result.Failure<City> failure) {
            throw new AssertionError("fixture member could not join: " + failure.reason());
        }
        return uuid;
    }

    public static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Reads a player row straight from storage, to assert on what was persisted. */
    public PlayerRow playerRow(UUID uuid) {
        return await(daos.players().findByUuid(uuid)).orElseThrow();
    }

    /** The reason code of a failed result, for readable assertions. */
    public static String reasonOf(Result<?> result) {
        return result instanceof Result.Failure<?> failure ? failure.reason() : "SUCCESS";
    }

    public static Logger quietLogger() {
        Logger logger = Logger.getLogger("civitas-city-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    @Override
    public void close() {
        db.close();
    }
}
