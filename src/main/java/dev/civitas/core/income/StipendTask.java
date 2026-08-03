package dev.civitas.core.income;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.LedgerDao;
import dev.civitas.storage.dao.PlayerDao;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.Result;

/**
 * The SPEC 4.2 playtime stipend, and the accrual of {@code active_playtime_ms} behind it.
 *
 * <p>Every fifteen minutes each online player's interval is closed. If they passed the SPEC
 * 4.2.1 check they are paid, and the interval is added to their filtered active playtime; if
 * they did not, neither happens. That second half matters as much as the first: active
 * playtime is what the SPEC 5.1 city-founding gate and the SPEC 6.2 member divisor are
 * measured in, so an AFK machine must not accumulate it either. M2 shipped a placeholder that
 * credited unfiltered session time and said so; this replaces it.
 *
 * <h2>The daily cap</h2>
 * SPEC 4.2 caps the stipend at 640 C a day. Like the SPEC 8.5 withdrawal cap, it is derived
 * from the ledger rather than counted separately, so the cap and the audit trail cannot
 * disagree and a restart cannot reset it.
 */
public final class StipendTask implements Runnable {

    private final DatabaseManager db;
    private final PlayerDao players;
    private final LedgerDao ledger;
    private final EconomyService economy;
    private final ActivityTracker activity;
    private final IncomeMultipliers multipliers;
    private final ConfigManager configs;
    private final OnlinePlayers online;
    private final Notifier notifier;
    private final Logger logger;

    public StipendTask(DatabaseManager db, PlayerDao players, LedgerDao ledger,
                       EconomyService economy, ActivityTracker activity,
                       IncomeMultipliers multipliers, ConfigManager configs,
                       OnlinePlayers online, Notifier notifier, Logger logger) {
        this.db = Objects.requireNonNull(db, "db");
        this.players = Objects.requireNonNull(players, "players");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.multipliers = Objects.requireNonNull(multipliers, "multipliers");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.online = Objects.requireNonNull(online, "online");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void run() {
        if (!enabled()) {
            return;
        }
        try {
            sweep(System.currentTimeMillis());
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "The stipend sweep failed", e);
        }
    }

    /**
     * Closes the interval for every online player.
     *
     * @return how many were paid
     */
    public int sweep(long now) {
        int paid = 0;
        for (UUID player : online.uuids()) {
            try {
                if (settle(player, now)) {
                    paid++;
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Stipend failed for " + player, e);
            }
        }
        return paid;
    }

    /**
     * One player's interval.
     *
     * @return whether any money changed hands
     */
    public boolean settle(UUID player, long now) {
        boolean active = activity.rollOver(player);
        if (!active) {
            // Not active: no playtime credited, no money. Both halves matter.
            return false;
        }

        long interval = intervalMillis();
        PlayerRow row = creditActivePlaytime(player, interval);
        if (row == null) {
            return false;
        }

        BigDecimal base = amountPerInterval();
        BigDecimal payable = multipliers.apply(base, row, row.activePlaytimeMs(), now);
        if (payable.signum() <= 0) {
            // SPEC 17.6 case 70: playtime accrues, money does not, until the floor is met.
            return false;
        }

        BigDecimal remaining = remainingToday(player, now);
        if (remaining.signum() <= 0) {
            return false;
        }
        BigDecimal amount = payable.min(remaining);

        Result<BigDecimal> result = await(economy.give(player, amount,
                TransactionType.PLAYTIME_STIPEND, null, null));
        if (result instanceof Result.Failure<BigDecimal>) {
            return false;
        }

        notifier.tell(player, "income.stipend.paid",
                LangManager.placeholder("amount", amount.toPlainString()));
        return true;
    }

    // ==================================================================================
    // Playtime
    // ==================================================================================

    /**
     * Adds one qualifying interval to {@code active_playtime_ms}.
     *
     * @return the row as it now stands, or null if the player has no record
     */
    private PlayerRow creditActivePlaytime(UUID player, long interval) {
        return await(db.transaction(connection -> {
            // SQL arithmetic on the one column, so nothing else touching this row is lost.
            if (players.addActivePlaytime(connection, player, interval) == 0) {
                return null;
            }
            return players.findByUuid(connection, player).orElse(null);
        }));
    }

    // ==================================================================================
    // The daily cap
    // ==================================================================================

    /** What is left of today's SPEC 4.2 cap, read from the ledger. */
    public BigDecimal remainingToday(UUID player, long now) {
        BigDecimal cap = dailyCap();
        if (cap.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal alreadyPaid = await(ledger.sumByActorAndType(player,
                TransactionType.PLAYTIME_STIPEND.name(), startOfDay(now)));
        return cap.subtract(alreadyPaid).max(BigDecimal.ZERO);
    }

    /**
     * Midnight, server time.
     *
     * <p>The same boundary SPEC 13.1 resets quests on, so a player's day means one thing
     * across the whole plugin.
     */
    private static long startOfDay(long now) {
        return java.time.Instant.ofEpochMilli(now)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    // ==================================================================================
    // Config
    // ==================================================================================

    public boolean enabled() {
        return configs.get(ConfigFile.ECONOMY).getBoolean("income.stipend.enabled", true);
    }

    public long intervalMillis() {
        return configs.get(ConfigFile.ECONOMY)
                .getLong("income.stipend.interval-minutes", 15) * 60_000L;
    }

    public BigDecimal amountPerInterval() {
        return new BigDecimal(configs.get(ConfigFile.ECONOMY)
                .getString("income.stipend.amount", "40"));
    }

    public BigDecimal dailyCap() {
        return new BigDecimal(configs.get(ConfigFile.ECONOMY)
                .getString("income.stipend.daily-cap", "640"));
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) {
        return future.join();
    }

    /** Who is online. An interface so the sweep can be run without a server. */
    @FunctionalInterface
    public interface OnlinePlayers {
        java.util.Collection<UUID> uuids();
    }

    /** How a player is told they were paid. Same seam as the upkeep sweep's, for the same reason. */
    @FunctionalInterface
    public interface Notifier {

        void tell(UUID player, String messageKey,
                  net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra);

        /** Sends if they are online, and drops the message otherwise. */
        static Notifier online(LangManager lang) {
            Objects.requireNonNull(lang, "lang");
            return (player, key, extra) -> {
                org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayer(player);
                if (target != null) {
                    lang.send(target, key, extra);
                }
            };
        }
    }
}
