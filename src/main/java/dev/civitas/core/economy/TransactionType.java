package dev.civitas.core.economy;

import java.util.Locale;
import java.util.Optional;

/**
 * Every kind of ledger entry, SPEC 4.6.
 *
 * <p>SPEC 4.6 states the list is exhaustive and must be kept in sync with this enum. It is
 * the vocabulary admins search the ledger by ({@code /ca ledger type ...}), so a new money
 * movement that does not fit an existing value needs a new value here and a matching line in
 * SPEC 4.6, not a reused approximation.
 */
public enum TransactionType {

    STARTING_BALANCE,
    PLAYTIME_STIPEND,
    DAILY_LOGIN,
    QUEST_REWARD,
    CHALLENGE_REWARD,
    CONTEST_PRIZE,
    MARKET_SELL,
    MARKET_BUY,
    MARKET_TAX,
    PLAYER_SHOP,
    PLAYER_PAY,
    CITY_CREATE_FEE,
    CITY_RENAME_FEE,
    CHUNK_CLAIM,
    CHUNK_UNCLAIM_REFUND,
    OUTPOST_CREATE,
    OUTPOST_TELEPORT_FEE,
    UPKEEP_CHARGE,
    UPKEEP_FAILED,
    TREASURY_DEPOSIT,
    TREASURY_WITHDRAW,
    DEFENSE_PURCHASE,
    DEFENSE_UPKEEP,
    UPGRADE_PURCHASE,
    WAR_WAGER_ESCROW,
    WAR_WAGER_PAYOUT,
    WAR_WAGER_REFUND,
    BOUNTY_PLACE,
    BOUNTY_CLAIM,
    BOUNTY_REFUND,
    EVENT_REWARD,
    ADMIN_GIVE,
    ADMIN_TAKE,
    ADMIN_SET,
    ADMIN_ROLLBACK;

    /** @param name a stored {@code ledger.type} value, case-insensitive */
    public static Optional<TransactionType> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalised = name.trim().toUpperCase(Locale.ROOT);
        for (TransactionType type : values()) {
            if (type.name().equals(normalised)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
