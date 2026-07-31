package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import dev.civitas.util.Result;

/**
 * Moving money in and out of a personal wallet, with a ledger entry for every movement.
 *
 * <p>An interface because M2 needs to charge the SPEC 5.1 city creation fee, but the economy
 * module is M5 and depends on M2, not the other way round. M2 ships a storage-backed
 * implementation; M5's {@code EconomyService} implements the same contract and replaces it,
 * so the city code never has to change and never grows its own balance handling.
 *
 * <p>Every method takes a {@link Connection} rather than returning a future, because the
 * callers that matter need the money movement to land in the <em>same</em> transaction as
 * whatever it paid for. Charging 10,000 C and then failing to insert the city would be a
 * bug players notice immediately.
 */
public interface Funds {

    /**
     * Debits a player.
     *
     * @param connection the transaction the caller is already inside
     * @param player     whose wallet
     * @param amount     positive
     * @param type       the ledger type recorded for this movement
     * @param cityId     the city this relates to, or null
     * @param metadata   JSON blob for the ledger, or null
     * @return the new balance, or a failure if funds are short or the player is frozen
     */
    Result<BigDecimal> withdraw(Connection connection, UUID player, BigDecimal amount,
                                TransactionType type, Integer cityId, String metadata)
            throws SQLException;

    /**
     * Credits a player.
     *
     * @return the new balance, or a failure if the credit would breach
     *         {@code economy.max-balance} (SPEC 17.3 case 27) or the player is frozen
     */
    Result<BigDecimal> deposit(Connection connection, UUID player, BigDecimal amount,
                               TransactionType type, Integer cityId, String metadata)
            throws SQLException;

    /**
     * Reads a balance.
     *
     * @return the balance, or a failure if the player has no row
     */
    Result<BigDecimal> balanceOf(Connection connection, UUID player) throws SQLException;
}
