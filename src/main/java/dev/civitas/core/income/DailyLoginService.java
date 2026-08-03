package dev.civitas.core.income;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.PlayerDao;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.Result;

/**
 * The SPEC 4.2 daily login reward.
 *
 * <p>250 C, plus 125 for each day of an unbroken streak, to a ceiling of 1,000 C. The streak
 * breaks after 48 hours away rather than at midnight, deliberately: a player who logs in at
 * 23:50 and again at 00:10 the next day has been away twenty minutes, and punishing that as a
 * missed day would make the streak a scheduling exercise rather than a reason to come back.
 *
 * <p>Whether a day has already been claimed is a separate question from the streak, and is
 * asked against the calendar date, so a player cannot claim twice by logging out and in.
 */
public final class DailyLoginService {

    private final DatabaseManager db;
    private final PlayerDao players;
    private final EconomyService economy;
    private final IncomeMultipliers multipliers;
    private final ConfigManager configs;
    private final ZoneId zone;

    public DailyLoginService(DatabaseManager db, PlayerDao players, EconomyService economy,
                             IncomeMultipliers multipliers, ConfigManager configs, ZoneId zone) {
        this.db = Objects.requireNonNull(db, "db");
        this.players = Objects.requireNonNull(players, "players");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.multipliers = Objects.requireNonNull(multipliers, "multipliers");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    // ==================================================================================
    // Claiming
    // ==================================================================================

    /**
     * Pays today's login reward, if it is owed.
     *
     * @return the amount paid, or a failure saying why not
     */
    public CompletableFuture<Result<Claim>> claim(UUID player, long now) {
        if (!enabled()) {
            return completed(Result.failure("DISABLED", "income.daily.disabled"));
        }

        return db.transaction(connection -> {
            Optional<PlayerRow> found = players.findByUuid(connection, player);
            if (found.isEmpty()) {
                return Result.<Claim>failure("NO_PLAYER_RECORD", "economy.no-account");
            }
            PlayerRow row = found.get();

            if (alreadyClaimedToday(row, now)) {
                return Result.<Claim>failure("ALREADY_CLAIMED", "income.daily.already",
                        Map.of("when", String.valueOf(row.lastDailyClaim())));
            }

            // SPEC 17.6 case 70: a fresh alt gets nothing, and does not build a streak either.
            if (!multipliers.mayEarn(row.activePlaytimeMs())) {
                return Result.<Claim>failure("TOO_NEW", "income.too-new",
                        Map.of("minutes",
                                String.valueOf(multipliers.minimumPlaytimeMillis() / 60_000L)));
            }

            int streak = nextStreak(row, now);
            BigDecimal base = rewardFor(streak);
            BigDecimal amount = multipliers.apply(base, row, row.activePlaytimeMs(), now);

            Result<BigDecimal> paid = economy.deposit(connection, player, amount,
                    TransactionType.DAILY_LOGIN, null, "{\"streak\":" + streak + "}");
            if (paid instanceof Result.Failure<BigDecimal> failure) {
                return Result.<Claim>propagate(failure);
            }

            // Only the two columns this changes. A whole-row write here would carry the
            // balance read before the deposit and quietly undo it.
            players.updateDailyClaim(connection, player, streak, now);

            return Result.success(new Claim(amount, streak, paid.orElseThrow()));
        });
    }

    // ==================================================================================
    // The arithmetic
    // ==================================================================================

    /** Whether today's reward has already been taken, by calendar date in the server's zone. */
    public boolean alreadyClaimedToday(PlayerRow row, long now) {
        if (row.lastDailyClaim() <= 0) {
            return false;
        }
        LocalDate last = Instant.ofEpochMilli(row.lastDailyClaim()).atZone(zone).toLocalDate();
        return last.equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
    }

    /**
     * What the streak becomes if they claim now.
     *
     * <p>Continues if the last claim was inside the reset window, and starts again at one if
     * it was not, which is also what a first-ever claim gets.
     */
    public int nextStreak(PlayerRow row, long now) {
        if (row.lastDailyClaim() <= 0) {
            return 1;
        }
        long away = now - row.lastDailyClaim();
        return away <= resetMillis() ? row.dailyStreak() + 1 : 1;
    }

    /**
     * The SPEC 4.2 reward for a streak length.
     *
     * @param streak 1 for the first day
     */
    public BigDecimal rewardFor(int streak) {
        BigDecimal base = money("income.daily-login.base", "250");
        BigDecimal bonus = money("income.daily-login.streak-bonus", "125");
        BigDecimal max = money("income.daily-login.max", "1000");

        BigDecimal reward = base.add(bonus.multiply(BigDecimal.valueOf(Math.max(0, streak - 1))));
        return reward.min(max);
    }

    /** How long a player may be away before the streak starts over. */
    public long resetMillis() {
        return configs.get(ConfigFile.ECONOMY)
                .getLong("income.daily-login.streak-reset-hours", 48) * 3_600_000L;
    }

    public boolean enabled() {
        return configs.get(ConfigFile.ECONOMY).getBoolean("income.daily-login.enabled", true);
    }

    private BigDecimal money(String path, String fallback) {
        return new BigDecimal(configs.get(ConfigFile.ECONOMY).getString(path, fallback));
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /**
     * What a claim paid.
     *
     * @param amount  after the SPEC 15.1 newcomer multiplier
     * @param streak  the streak this claim established
     * @param balance the wallet afterwards
     */
    public record Claim(BigDecimal amount, int streak, BigDecimal balance) { }
}
