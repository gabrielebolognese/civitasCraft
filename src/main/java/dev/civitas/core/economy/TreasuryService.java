package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.income.IncomeReporter;
import dev.civitas.core.income.QuestMetric;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.CityRow;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * The shared city treasury, SPEC 8.5.
 *
 * <h2>The withdrawal cap</h2>
 * SPEC 8.5 limits a non-mayor to 25% of the treasury per 24 hours, and SPEC 17.6 case 71
 * gives the reason: "a member drains the treasury and leaves". The cap needs no counter,
 * because the ledger already records every withdrawal with its actor, its city and its
 * timestamp. Deriving it from the ledger rather than tracking it separately means the two
 * can never disagree, and an admin auditing a dispute sees exactly what the cap saw.
 */
public final class TreasuryService {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final EconomyService economy;
    private final ConfigManager configs;
    private final Scheduler scheduler;
    private final IncomeReporter reporter;

    public TreasuryService(DatabaseManager db, DaoRegistry daos, EconomyService economy,
                           ConfigManager configs, Scheduler scheduler) {
        this(db, daos, economy, configs, scheduler, IncomeReporter.noop());
    }

    public TreasuryService(DatabaseManager db, DaoRegistry daos, EconomyService economy,
                           ConfigManager configs, Scheduler scheduler, IncomeReporter reporter) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    // ==================================================================================
    // Deposit, SPEC 9.2
    // ==================================================================================

    /**
     * Moves money from a member's wallet into the treasury.
     *
     * <p>Also credits {@code city_members.contributed_total}, which is the SPEC 13.3
     * Contribution leaderboard and the SPEC 8.5 contribution list. There is no cap on
     * depositing: SPEC 1.3 wants contribution to be additive and easy.
     */
    public CompletableFuture<Result<BigDecimal>> deposit(UUID actor, City city, BigDecimal amount) {
        Result<Void> guard = require(city, actor, CityPermission.DEPOSIT);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }

        BigDecimal moved = Money.floor(amount);
        if (moved.signum() <= 0) {
            return completed(Result.failure("AMOUNT_NOT_POSITIVE", "economy.amount-not-positive"));
        }

        return db.transaction(connection -> {
            Result<BigDecimal> taken = economy.withdraw(connection, actor, moved,
                    TransactionType.TREASURY_DEPOSIT, city.id(), null);
            if (taken instanceof Result.Failure<BigDecimal> failure) {
                return Result.<BigDecimal>propagate(failure);
            }

            Optional<CityRow> current = daos.cities().findById(connection, city.id());
            if (current.isEmpty()) {
                return Result.<BigDecimal>failure("CITY_GONE", "city.unknown");
            }
            BigDecimal after = current.get().treasury().add(moved);

            daos.cities().updateTreasury(connection, city.id(), after);
            daos.cityMembers().addContribution(connection, actor, moved);
            daos.ledger().insert(connection, new LedgerRow(0, System.currentTimeMillis(),
                    TransactionType.TREASURY_DEPOSIT.name(), actor, null, city.id(),
                    moved, after, null));

            return Result.success(after);
        }).thenApply(result -> {
            if (result instanceof Result.Success<BigDecimal>) {
                // SPEC 13.1's Social quests count coins deposited.
                reporter.report(actor, QuestMetric.TREASURY_DEPOSIT, moved.longValue());
            }
            return applyTreasury(result, city);
        });
    }

    // ==================================================================================
    // Withdraw, SPEC 8.5 and 9.2
    // ==================================================================================

    /** Moves money from the treasury into a member's wallet, subject to the 24-hour cap. */
    public CompletableFuture<Result<BigDecimal>> withdraw(UUID actor, City city, BigDecimal amount) {
        Result<Void> guard = require(city, actor, CityPermission.WITHDRAW);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }

        BigDecimal moved = Money.floor(amount);
        if (moved.signum() <= 0) {
            return completed(Result.failure("AMOUNT_NOT_POSITIVE", "economy.amount-not-positive"));
        }

        long now = System.currentTimeMillis();
        boolean isMayor = city.isMayor(actor);

        return db.transaction(connection -> {
            Optional<CityRow> current = daos.cities().findById(connection, city.id());
            if (current.isEmpty()) {
                return Result.<BigDecimal>failure("CITY_GONE", "city.unknown");
            }
            BigDecimal treasury = current.get().treasury();

            if (treasury.compareTo(moved) < 0) {
                return Result.<BigDecimal>failure("TREASURY_SHORT", "city.treasury.insufficient",
                        Map.of("required", moved.toPlainString(),
                                "balance", treasury.toPlainString()));
            }

            if (!isMayor) {
                Result<BigDecimal> cap = checkCap(connection, actor, city, treasury, moved, now);
                if (cap instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<BigDecimal>propagate(failure);
                }
            }

            BigDecimal after = treasury.subtract(moved);
            daos.cities().updateTreasury(connection, city.id(), after);
            daos.ledger().insert(connection, new LedgerRow(0, now,
                    TransactionType.TREASURY_WITHDRAW.name(), actor, null, city.id(),
                    moved.negate(), after, null));

            Result<BigDecimal> given = economy.deposit(connection, actor, moved,
                    TransactionType.TREASURY_WITHDRAW, city.id(), null);
            if (given instanceof Result.Failure<BigDecimal> failure) {
                return Result.<BigDecimal>propagate(failure);
            }

            return Result.success(after);
        }).thenApply(result -> applyTreasury(result, city));
    }

    /**
     * The SPEC 8.5 cap: at most 25% of the treasury per rolling 24 hours, for anyone but the
     * mayor.
     *
     * <p>The percentage is of the treasury <em>now</em>, not of what it held when the window
     * opened. Measuring against the opening balance would let a member drain a quarter,
     * wait for a deposit, and drain a quarter of the larger figure, which is the behaviour
     * SPEC 17.6 case 71 exists to stop.
     */
    private Result<BigDecimal> checkCap(Connection connection, UUID actor, City city,
                                        BigDecimal treasury, BigDecimal requested, long now)
            throws SQLException {
        double percent = configs.get(ConfigFile.CITIES)
                .getDouble("members.withdraw-percent-per-day", 25.0);
        long window = configs.get(ConfigFile.CITIES)
                .getLong("members.withdraw-window-hours", 24) * 3_600_000L;

        BigDecimal limit = Money.percentOf(treasury, percent);

        // Withdrawals are stored negative, so the sum comes back negative.
        BigDecimal alreadyTaken = daos.ledger().sumOutflowByActor(connection, actor, city.id(),
                TransactionType.TREASURY_WITHDRAW.name(), now - window).abs();

        BigDecimal wouldTotal = alreadyTaken.add(requested);
        if (wouldTotal.compareTo(limit) > 0) {
            BigDecimal remaining = limit.subtract(alreadyTaken).max(SqlDialect.zero());
            return Result.failure("WITHDRAW_CAP", "city.treasury.cap",
                    Map.of("percent", trimPercent(percent),
                            "limit", limit.toPlainString(),
                            "taken", alreadyTaken.toPlainString(),
                            "remaining", remaining.toPlainString()));
        }
        return Result.success(requested);
    }

    /** What this member could still take right now, for the treasury screen. */
    public CompletableFuture<BigDecimal> remainingAllowance(UUID actor, City city) {
        if (city.isMayor(actor)) {
            return CompletableFuture.completedFuture(city.treasury());
        }
        double percent = configs.get(ConfigFile.CITIES)
                .getDouble("members.withdraw-percent-per-day", 25.0);
        long window = configs.get(ConfigFile.CITIES)
                .getLong("members.withdraw-window-hours", 24) * 3_600_000L;
        long since = System.currentTimeMillis() - window;

        return daos.ledger()
                .sumOutflowByActor(actor, city.id(), TransactionType.TREASURY_WITHDRAW.name(),
                        since)
                .thenApply(taken -> Money.percentOf(city.treasury(), percent)
                        .subtract(taken.abs())
                        .max(SqlDialect.zero()));
    }

    // ==================================================================================
    // Movements with no player behind them
    // ==================================================================================

    /**
     * Adds to or removes from a treasury directly, for upkeep, refunds and admin commands.
     *
     * @param delta signed; negative takes money out
     */
    public Result<BigDecimal> adjust(Connection connection, City city, BigDecimal delta,
                                     TransactionType type, UUID actor, String metadata)
            throws SQLException {
        Optional<CityRow> current = daos.cities().findById(connection, city.id());
        if (current.isEmpty()) {
            return Result.failure("CITY_GONE", "city.unknown");
        }
        BigDecimal after = current.get().treasury().add(delta);
        if (after.signum() < 0) {
            return Result.failure("TREASURY_SHORT", "city.treasury.insufficient",
                    Map.of("required", delta.abs().toPlainString(),
                            "balance", current.get().treasury().toPlainString()));
        }

        daos.cities().updateTreasury(connection, city.id(), after);
        daos.ledger().insert(connection, new LedgerRow(0, System.currentTimeMillis(),
                type.name(), actor, null, city.id(), delta, after, metadata));
        return Result.success(after);
    }

    /**
     * A city's recent ledger entries, newest first, for the SPEC 8.5 history screen.
     *
     * <p>Here rather than in the GUI because SPEC 2.3 keeps commands and menus off the DAOs,
     * and because "what counts as this city's history" is a question the treasury owns.
     */
    public CompletableFuture<java.util.List<LedgerRow>> history(int cityId, int limit) {
        return daos.ledger().findByCity(cityId, 0L, limit);
    }

    /** Total money held in treasuries, the other half of SPEC 4.8's circulation figure. */
    public CompletableFuture<BigDecimal> totalInTreasuries() {
        return daos.cities().totalTreasuries();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private Result<Void> require(City city, UUID actor, CityPermission permission) {
        if (!city.isMember(actor)) {
            return Result.failure("NOT_A_MEMBER", "city.not-a-member");
        }
        if (!city.hasPermission(actor, permission)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", permission.name()));
        }
        return Result.ok();
    }

    /** Writes the committed treasury back to the cache, on the server thread. */
    private Result<BigDecimal> applyTreasury(Result<BigDecimal> result, City city) {
        if (result instanceof Result.Success<BigDecimal>(BigDecimal after) && after != null) {
            scheduler.runOnMain(() -> city.setTreasury(after));
        }
        return result;
    }

    private static String trimPercent(double percent) {
        return percent == Math.rint(percent)
                ? String.valueOf((long) percent)
                : String.valueOf(percent);
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
