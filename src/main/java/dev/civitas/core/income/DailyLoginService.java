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

    /**
     * SPEC 21.4 F12's per-day baseline table.
     *
     * <p>Optional: null means the rule cannot run and the daily reward falls back to the
     * lifetime gate alone. That is the same failing-open choice M15 made for the contest IP
     * check — a missing table must not stop legitimate players being paid, and the lifetime
     * gate still stops the case F12 cares most about, which is a brand new alt.
     */
    private dev.civitas.storage.dao.DailyActivityDao dailyActivity;

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
    /** Hands the service the baseline table, so SPEC 21.4 F12's rule can run. */
    public void useDailyActivity(dev.civitas.storage.dao.DailyActivityDao dao) {
        this.dailyActivity = java.util.Objects.requireNonNull(dao, "dao");
    }

    /**
     * How much active playtime this player has accrued today, SPEC 21.4 F12.
     *
     * <p>The stored row is the lifetime figure as it stood when the day turned, so today is
     * the difference. Meeting a player whose baseline belongs to an earlier day rewrites it to
     * their current lifetime figure and returns zero, which is what makes the first login of a
     * day start the clock rather than inheriting yesterday's total.
     */
    private long activeTodaySync(java.sql.Connection connection, PlayerRow row, long now)
            throws java.sql.SQLException {
        if (dailyActivity == null) {
            return Long.MAX_VALUE;
        }
        var stored = dailyActivity.findSync(connection, row.uuid());
        if (stored.isEmpty() || stored.get().dayStart() != startOfDay(now)) {
            return 0L;
        }
        // Never negative: a baseline above the live counter would mean active playtime went
        // backwards, which only an admin edit can do, and reading that as "a lot of playtime
        // today" would hand out the reward it is meant to gate.
        return Math.max(0L, row.activePlaytimeMs() - stored.get().baselineMs());
    }

    /**
     * Stamps today's baseline if it is missing or belongs to an earlier day.
     *
     * <p>Deliberately <b>outside</b> the claim transaction, and this is not a style choice.
     * {@code DatabaseManager.transaction} rolls back on a returned {@code Result.Failure} as
     * well as on an exception, and the common outcome of a claim is a refusal — already
     * claimed, too new, not active enough. A baseline written inside that transaction is
     * discarded with it, so every call would re-stamp the baseline to the current lifetime
     * figure and "active playtime today" would be zero forever.
     */
    private CompletableFuture<Integer> ensureBaseline(UUID player, long now) {
        if (dailyActivity == null) {
            return CompletableFuture.completedFuture(0);
        }
        long dayStart = startOfDay(now);
        return db.call(connection -> {
            Optional<PlayerRow> found = players.findByUuid(connection, player);
            if (found.isEmpty()) {
                return 0;
            }
            var stored = dailyActivity.findSync(connection, player);
            if (stored.isPresent() && stored.get().dayStart() == dayStart) {
                return 0;
            }
            return dailyActivity.upsertSync(connection,
                    new dev.civitas.storage.row.DailyActivityRow(player, dayStart,
                            found.get().activePlaytimeMs()));
        });
    }

    /** 00:00 server time of the day containing {@code now}. */
    public long startOfDay(long now) {
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** SPEC 21.11 {@code anti-abuse.daily-login-requires-active-minutes}, default 30. */
    public long requiredActiveTodayMillis() {
        return configs.get(ConfigFile.ECONOMY)
                .getLong("anti-abuse.daily-login-requires-active-minutes", 30) * 60_000L;
    }

    public CompletableFuture<Result<Claim>> claim(UUID player, long now) {
        if (!enabled()) {
            return completed(Result.failure("DISABLED", "income.daily.disabled"));
        }

        return ensureBaseline(player, now).thenCompose(ignored -> db.transaction(connection -> {
            Optional<PlayerRow> found = players.findByUuid(connection, player);
            if (found.isEmpty()) {
                return Result.<Claim>failure("NO_PLAYER_RECORD", "economy.no-account");
            }
            PlayerRow row = found.get();

            if (alreadyClaimedToday(row, now)) {
                // No placeholder: the only thing to hand over is a raw epoch, and the
                // message never showed it. M9 already made this refusal silent in practice.
                return Result.<Claim>failure("ALREADY_CLAIMED", "income.daily.already");
            }

            // SPEC 17.6 case 70, strengthened by SPEC 21.4 F12 to sixty minutes: a fresh alt
            // gets nothing, and does not build a streak either.
            if (!multipliers.mayEarn(row.activePlaytimeMs())) {
                return Result.<Claim>failure("TOO_NEW", "income.too-new",
                        Map.of("minutes",
                                String.valueOf(multipliers.minimumPlaytimeMillis() / 60_000L)));
            }

            // SPEC 21.4 F12's second half: "daily login rewards require 30 minutes of active
            // playtime that day before paying out." The lifetime gate above stops an alt on
            // its first day; this stops a farm of established alts logging in for a second
            // each morning, which the lifetime gate lets through forever after day one.
            long activeToday = activeTodaySync(connection, row, now);
            if (activeToday < requiredActiveTodayMillis()) {
                return Result.<Claim>failure("NOT_ACTIVE_TODAY", "income.daily.not-active-today",
                        Map.of("minutes",
                                String.valueOf(requiredActiveTodayMillis() / 60_000L),
                                "so-far", String.valueOf(activeToday / 60_000L)));
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
        }));
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
