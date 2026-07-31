package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.LedgerDao;
import dev.civitas.storage.dao.PlayerDao;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.Result;

/**
 * The M2 implementation of {@link Funds}, straight over {@code players} and {@code ledger}.
 *
 * <p>Deliberately minimal. It exists so city creation can charge its fee inside the same
 * transaction that inserts the city, and no more than that; the caps, taxes, multipliers and
 * caching belong to M5's economy service, which will implement the same interface.
 *
 * <p>Two rules are enforced here rather than deferred, because both are corruption risks
 * rather than features: a withdrawal may never take a balance negative (SPEC 17.3 case 24),
 * and a deposit may never breach {@code economy.max-balance} (SPEC 17.3 case 27).
 */
public final class StorageFunds implements Funds {

    private final PlayerDao players;
    private final LedgerDao ledger;
    private final ConfigManager configs;

    public StorageFunds(PlayerDao players, LedgerDao ledger, ConfigManager configs) {
        this.players = Objects.requireNonNull(players, "players");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    @Override
    public Result<BigDecimal> withdraw(Connection connection, UUID player, BigDecimal amount,
                                       TransactionType type, Integer cityId, String metadata)
            throws SQLException {
        if (amount.signum() <= 0) {
            return Result.failure("AMOUNT_NOT_POSITIVE", "economy.amount-not-positive");
        }

        Optional<PlayerRow> row = players.findByUuid(connection, player);
        if (row.isEmpty()) {
            return Result.failure("NO_PLAYER_RECORD", "economy.no-account");
        }
        if (row.get().frozen()) {
            return Result.failure("FROZEN", "economy.frozen");
        }

        BigDecimal charge = SqlDialect.normalise(amount);
        BigDecimal balance = row.get().balance();
        if (balance.compareTo(charge) < 0) {
            return Result.failure("INSUFFICIENT_FUNDS", "economy.insufficient-funds",
                    Map.of("required", charge.toPlainString(),
                            "balance", balance.toPlainString(),
                            "short", charge.subtract(balance).toPlainString()));
        }

        BigDecimal after = balance.subtract(charge);
        players.updateBalance(connection, player, after);
        writeLedger(connection, type, player, cityId, charge.negate(), after, metadata);
        return Result.success(after);
    }

    @Override
    public Result<BigDecimal> deposit(Connection connection, UUID player, BigDecimal amount,
                                      TransactionType type, Integer cityId, String metadata)
            throws SQLException {
        if (amount.signum() <= 0) {
            return Result.failure("AMOUNT_NOT_POSITIVE", "economy.amount-not-positive");
        }

        Optional<PlayerRow> row = players.findByUuid(connection, player);
        if (row.isEmpty()) {
            return Result.failure("NO_PLAYER_RECORD", "economy.no-account");
        }
        if (row.get().frozen()) {
            return Result.failure("FROZEN", "economy.frozen");
        }

        BigDecimal credit = SqlDialect.normalise(amount);
        BigDecimal after = row.get().balance().add(credit);
        BigDecimal max = maxBalance();
        if (after.compareTo(max) > 0) {
            return Result.failure("MAX_BALANCE", "economy.max-balance",
                    Map.of("max", max.toPlainString()));
        }

        players.updateBalance(connection, player, after);
        writeLedger(connection, type, player, cityId, credit, after, metadata);
        return Result.success(after);
    }

    @Override
    public Result<BigDecimal> balanceOf(Connection connection, UUID player) throws SQLException {
        return players.findByUuid(connection, player)
                .map(row -> Result.success(row.balance()))
                .orElseGet(() -> Result.failure("NO_PLAYER_RECORD", "economy.no-account"));
    }

    private void writeLedger(Connection connection, TransactionType type, UUID player,
                             Integer cityId, BigDecimal signedAmount, BigDecimal balanceAfter,
                             String metadata) throws SQLException {
        ledger.insert(connection, new LedgerRow(0, System.currentTimeMillis(), type.name(),
                player, null, cityId, signedAmount, balanceAfter, metadata));
    }

    private BigDecimal maxBalance() {
        return new BigDecimal(
                configs.get(ConfigFile.ECONOMY).getString("max-balance", "1000000000000"));
    }
}
