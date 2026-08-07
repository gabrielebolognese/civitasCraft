package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigManager;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.StorageException;
import dev.civitas.storage.dao.LedgerDao;
import dev.civitas.storage.dao.PlayerDao;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.Result;

/**
 * Personal balances, SPEC 4, replacing the {@code StorageFunds} placeholder M2 shipped.
 *
 * <h2>The no-negative-balance guarantee</h2>
 * SPEC 17.3 case 24 is explicit: "all mutations go through a single synchronised service
 * method with a pre-check. A negative result throws, is logged as a critical error, and rolls
 * back the transaction." That is implemented literally. Every change to a wallet holds that
 * wallet's lock across the read, the check and the write, so two threads cannot both see the
 * same balance and both spend it.
 *
 * <p>A transfer takes both locks, always in UUID order. Without that ordering two players
 * paying each other at the same instant would deadlock, which is the sort of bug that only
 * appears on a busy server months after launch.
 *
 * <h2>The cache</h2>
 * SPEC 2.3 puts balances in memory. Every wallet is loaded at startup and written through on
 * each change, so {@code /balance} and the GUI never wait on the database, and the database
 * stays the source of truth.
 */
public final class EconomyService implements Funds {

    private final DatabaseManager db;
    private final PlayerDao players;
    private final LedgerDao ledger;
    private final ConfigManager configs;
    private final Logger logger;

    /** Every known wallet. Written through on change; the database remains authoritative. */
    private final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();

    /** One lock per player, created on demand and never removed. */
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public EconomyService(DatabaseManager db, PlayerDao players, LedgerDao ledger,
                          ConfigManager configs, Logger logger) {
        this.db = Objects.requireNonNull(db, "db");
        this.players = Objects.requireNonNull(players, "players");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Loads every wallet into the cache. Runs once at startup, off the main thread. */
    public CompletableFuture<Integer> loadAll() {
        return players.findAll().thenApply(rows -> {
            balances.clear();
            for (PlayerRow row : rows) {
                balances.put(row.uuid(), row.balance());
            }
            return balances.size();
        });
    }

    /**
     * A player's balance without touching the database.
     *
     * @return the balance, or empty if the server has never seen this player
     */
    public Optional<BigDecimal> cachedBalance(UUID player) {
        return Optional.ofNullable(balances.get(player));
    }

    /** The balance, or zero for a player with no record. For display only. */
    public BigDecimal balanceOrZero(UUID player) {
        return balances.getOrDefault(player, SqlDialect.zero());
    }

    /** Called when a player's row is created or reloaded, so the cache stays complete. */
    public void remember(UUID player, BigDecimal balance) {
        balances.put(player, balance);
    }

    public ConfigManager configs() {
        return configs;
    }

    // ==================================================================================
    // Funds, the interface M2 built for this handover
    // ==================================================================================

    @Override
    public Result<BigDecimal> withdraw(Connection connection, UUID player, BigDecimal amount,
                                       TransactionType type, Integer cityId, String metadata)
            throws SQLException {
        if (amount.signum() <= 0) {
            return Result.failure("AMOUNT_NOT_POSITIVE", "economy.amount-not-positive");
        }
        BigDecimal charge = Money.floor(amount);

        synchronized (lockFor(player)) {
            Optional<PlayerRow> row = players.findByUuid(connection, player);
            if (row.isEmpty()) {
                return Result.failure("NO_PLAYER_RECORD", "economy.no-account");
            }
            if (row.get().frozen()) {
                return Result.failure("FROZEN", "economy.frozen");
            }

            BigDecimal balance = row.get().balance();
            if (balance.compareTo(charge) < 0) {
                return Result.failure("INSUFFICIENT_FUNDS", "economy.insufficient-funds",
                        Map.of("required", charge.toPlainString(),
                                "balance", balance.toPlainString(),
                                "short", charge.subtract(balance).toPlainString()));
            }

            BigDecimal after = balance.subtract(charge);
            requireNonNegative(player, after);

            players.updateBalance(connection, player, after);
            writeLedger(connection, type, player, cityId, charge.negate(), after, metadata);
            balances.put(player, after);
            return Result.success(after);
        }
    }

    @Override
    public Result<BigDecimal> deposit(Connection connection, UUID player, BigDecimal amount,
                                      TransactionType type, Integer cityId, String metadata)
            throws SQLException {
        if (amount.signum() <= 0) {
            return Result.failure("AMOUNT_NOT_POSITIVE", "economy.amount-not-positive");
        }
        BigDecimal credit = Money.floor(amount);

        synchronized (lockFor(player)) {
            Optional<PlayerRow> row = players.findByUuid(connection, player);
            if (row.isEmpty()) {
                return Result.failure("NO_PLAYER_RECORD", "economy.no-account");
            }
            if (row.get().frozen()) {
                return Result.failure("FROZEN", "economy.frozen");
            }

            BigDecimal after = row.get().balance().add(credit);
            Result<BigDecimal> ceiling = Money.checkCeiling(after, configs);
            if (ceiling instanceof Result.Failure<BigDecimal> failure) {
                return Result.propagate(failure);
            }

            players.updateBalance(connection, player, after);
            writeLedger(connection, type, player, cityId, credit, after, metadata);
            balances.put(player, after);
            return Result.success(after);
        }
    }

    @Override
    public Result<BigDecimal> balanceOf(Connection connection, UUID player) throws SQLException {
        return players.findByUuid(connection, player)
                .map(row -> Result.success(row.balance()))
                .orElseGet(() -> Result.failure("NO_PLAYER_RECORD", "economy.no-account"));
    }

    // ==================================================================================
    // Transfers, SPEC 9.1 /pay
    // ==================================================================================

    /**
     * Moves money from one player to another, SPEC 9.1.
     *
     * <p>Both locks are taken in UUID order so that two simultaneous transfers in opposite
     * directions cannot each hold what the other needs. Both movements are in one
     * transaction: money must never exist in neither wallet, nor in both.
     */
    public CompletableFuture<Result<BigDecimal>> pay(UUID from, UUID to, BigDecimal amount) {
        return transfer(from, to, amount, TransactionType.PLAYER_PAY, null);
    }

    /**
     * Moves money between two players under a given ledger type.
     *
     * <p>The same guarantees as {@link #pay}: one transaction, both locks taken in UUID
     * order. The type is a parameter because a player shop sale is a transfer too (SPEC 4.5)
     * and must be searchable as {@code PLAYER_SHOP} rather than hidden among ordinary
     * payments.
     *
     * @param context extra metadata merged into both ledger rows, or null
     * @return the sender's balance afterwards
     */
    public CompletableFuture<Result<BigDecimal>> transfer(UUID from, UUID to, BigDecimal amount,
                                                          TransactionType type, String context) {
        if (from.equals(to)) {
            // SPEC 17.3 case 25.
            return CompletableFuture.completedFuture(
                    Result.failure("PAY_SELF", "economy.pay-self"));
        }

        UUID first = from.compareTo(to) <= 0 ? from : to;
        UUID second = from.compareTo(to) <= 0 ? to : from;

        return db.transaction(connection -> {
            synchronized (lockFor(first)) {
                synchronized (lockFor(second)) {
                    Result<BigDecimal> taken = withdrawLocked(connection, from, amount,
                            type, null, metadataFor(to, context));
                    if (taken instanceof Result.Failure<BigDecimal> failure) {
                        return Result.<BigDecimal>propagate(failure);
                    }
                    Result<BigDecimal> given = depositLocked(connection, to, amount,
                            type, null, metadataFor(from, context));
                    if (given instanceof Result.Failure<BigDecimal> failure) {
                        // Rolls back the withdrawal too, because both are one transaction.
                        return Result.<BigDecimal>propagate(failure);
                    }
                    return taken;
                }
            }
        });
    }

    /**
     * The body of {@link #withdraw} without taking the lock, for callers that already hold it.
     *
     * <p>Java monitors are reentrant, so calling the public method would also work; this
     * exists to make it obvious at the call site that the lock is already held.
     */
    private Result<BigDecimal> withdrawLocked(Connection connection, UUID player,
                                              BigDecimal amount, TransactionType type,
                                              Integer cityId, String metadata) throws SQLException {
        return withdraw(connection, player, amount, type, cityId, metadata);
    }

    private Result<BigDecimal> depositLocked(Connection connection, UUID player,
                                             BigDecimal amount, TransactionType type,
                                             Integer cityId, String metadata) throws SQLException {
        return deposit(connection, player, amount, type, cityId, metadata);
    }

    // ==================================================================================
    // Async convenience for callers with no transaction of their own
    // ==================================================================================

    /** Credits a player, opening a transaction. */
    public CompletableFuture<Result<BigDecimal>> give(UUID player, BigDecimal amount,
                                                      TransactionType type, Integer cityId,
                                                      String metadata) {
        return db.transaction(connection ->
                deposit(connection, player, amount, type, cityId, metadata));
    }

    /** Debits a player, opening a transaction. */
    public CompletableFuture<Result<BigDecimal>> take(UUID player, BigDecimal amount,
                                                      TransactionType type, Integer cityId,
                                                      String metadata) {
        return db.transaction(connection ->
                withdraw(connection, player, amount, type, cityId, metadata));
    }

    /** Total money in player wallets, half of SPEC 4.8's circulation figure. */
    /**
     * SPEC 9.4.4's {@code /ca eco set}: an absolute balance rather than a relative change.
     *
     * <p>The distinction matters when two admins act at once. Two gives of 1,000 leave the
     * player 2,000 better off; two sets to 1,000 leave them at 1,000 however many ran. That is
     * why SPEC lists all three operations rather than only give and take.
     *
     * <p>The ledger records the movement, not the target, because a ledger of absolute
     * positions could not be summed to reconcile anything.
     */
    public CompletableFuture<Result<BigDecimal>> setBalance(UUID player, BigDecimal target,
                                                             TransactionType type,
                                                             String reason) {
        BigDecimal wanted = Money.floor(target);
        if (wanted.signum() < 0) {
            return CompletableFuture.completedFuture(
                    Result.failure("AMOUNT_NOT_POSITIVE", "economy.amount-not-positive"));
        }

        return db.transaction(connection -> {
            synchronized (lockFor(player)) {
                Optional<PlayerRow> row = players.findByUuid(connection, player);
                if (row.isEmpty()) {
                    return Result.<BigDecimal>failure("NO_PLAYER_RECORD", "economy.no-account");
                }
                BigDecimal before = row.get().balance();
                Result<BigDecimal> ceiling = Money.checkCeiling(wanted, configs);
                if (ceiling instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<BigDecimal>propagate(failure);
                }

                players.updateBalance(connection, player, wanted);
                writeLedger(connection, type, player, null, wanted.subtract(before), wanted,
                        reason == null ? null : "{\"reason\":\"" + reason.replace('"', '\'')
                                + "\"}");
                balances.put(player, wanted);
                return Result.success(wanted);
            }
        });
    }

    /**
     * SPEC 9.4.4's {@code /ca eco freeze}: "Player cannot send or receive money."
     *
     * <p>A toggle rather than two commands, because SPEC lists one. Both {@link #withdraw} and
     * {@link #deposit} already refuse a frozen account, so nothing new is enforced here.
     *
     * @return whether the account is now frozen
     */
    public CompletableFuture<Result<Boolean>> toggleFrozen(UUID player) {
        return db.transaction(connection -> {
            Optional<PlayerRow> row = players.findByUuid(connection, player);
            if (row.isEmpty()) {
                return Result.<Boolean>failure("NO_PLAYER_RECORD", "economy.no-account");
            }
            boolean frozen = !row.get().frozen();
            players.updateFrozen(connection, player, frozen);
            return Result.success(frozen);
        });
    }

    public CompletableFuture<BigDecimal> totalInWallets() {
        return players.totalCirculation();
    }

    // ==================================================================================
    // Internals
    // ==================================================================================

    private Object lockFor(UUID player) {
        return locks.computeIfAbsent(player, key -> new Object());
    }

    /**
     * SPEC 17.3 case 24: a negative result is a critical error, not a state to persist.
     *
     * <p>Throwing here rolls the transaction back, so the balance stays whatever it was
     * before rather than becoming a debt nobody can explain.
     */
    private void requireNonNegative(UUID player, BigDecimal proposed) {
        if (proposed.signum() < 0) {
            logger.log(Level.SEVERE,
                    "Balance for {0} would have gone negative to {1}. The transaction was "
                            + "rolled back. This is a bug; please report it with the ledger "
                            + "entries around this time.",
                    new Object[] {player, proposed.toPlainString()});
            throw new StorageException("Refused to write a negative balance for " + player);
        }
    }

    private void writeLedger(Connection connection, TransactionType type, UUID player,
                             Integer cityId, BigDecimal signedAmount, BigDecimal balanceAfter,
                             String metadata) throws SQLException {
        ledger.insert(connection, new LedgerRow(0, System.currentTimeMillis(), type.name(),
                player, null, cityId, signedAmount, balanceAfter, metadata));
    }

    /**
     * The JSON metadata both sides of a transfer carry.
     *
     * @param context a caller-supplied JSON body such as {@code "item":"WHEAT"}, or null
     */
    private static String metadataFor(UUID counterparty, String context) {
        String counterpartyField = "\"counterparty\":\"" + counterparty + "\"";
        return context == null || context.isBlank()
                ? "{" + counterpartyField + "}"
                : "{" + counterpartyField + "," + context + "}";
    }
}
