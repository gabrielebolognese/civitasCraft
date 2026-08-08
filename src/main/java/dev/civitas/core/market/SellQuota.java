package dev.civitas.core.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.Money;
import dev.civitas.storage.dao.SellQuotaDao;
import dev.civitas.storage.row.SellQuotaRow;

/**
 * The SPEC 21.5 daily sell quota.
 *
 * <p>SPEC calls this "the single most important mechanism in the revised economy, because it is
 * <b>exploit-agnostic</b>. It bounds money creation regardless of how clever the exploit is."
 * Every other defence in Part II names a specific vector; this one does not have to.
 *
 * <h2>A soft cap, and why that matters</h2>
 *
 * <p>Past the quota a player may still sell, at {@code over-quota-multiplier} of the value.
 * SPEC 21.5: "Hard blocks feel like punishment and generate support tickets. Soft caps feel
 * like diminishing returns and generate shrugs."
 *
 * <h2>What the quota is measured in</h2>
 *
 * <p>Value, not item count, "so it cannot be gamed by switching items". Specifically the gross
 * a sale is worth <b>before tax and after every multiplier</b>, which is the money the sale
 * creates. SPEC 21.5 says the newcomer multiplier "applies <b>within</b> the quota, not to the
 * quota itself": a bonus makes each item worth more and still fills the same 25,000, rather
 * than handing anyone a larger quota. Counting a pre-bonus figure instead would let a bonused
 * player create more money than SPEC 21.5's own table budgets for, which is the one property
 * the whole mechanism exists to guarantee.
 *
 * <p>Worth knowing: the SPEC 4.2 newcomer multiplier is <b>not currently applied to market
 * sales at all</b>. {@code IncomeMultipliers} reaches the stipend, the daily login and quests,
 * and never the market, so SPEC 21.5's sentence describes an interaction that does not yet
 * happen. Recorded in {@code OPEN_QUESTIONS.md} rather than fixed here: adding an income
 * multiplier to the market changes the money supply and belongs to a milestone that owns
 * income. The ordering above is already the one that sentence asks for, so whichever milestone
 * adds it gets the right behaviour without touching this class.
 *
 * <h2>Player shops are exempt</h2>
 *
 * <p>Deliberately, and it is the point rather than an oversight. SPEC 21.5: "Peer trade moves
 * money, it does not create it. This makes the peer economy strictly more attractive than the
 * server market for high-volume producers, which is exactly the behaviour we want."
 */
public final class SellQuota {

    private final ConfigManager configs;
    private final SellQuotaDao dao;
    private final ZoneId zone;

    /**
     * Per-player locks, the same shape {@code EconomyService} uses.
     *
     * <p>SPEC 21.10.3: the counter "must be exact under concurrency, so it goes through the same
     * synchronised service method as balance mutation". Two sales in the same tick that both
     * read 24,000 and both write their own total would let a player sell twice at full price
     * past the cap.
     */
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public SellQuota(ConfigManager configs, SellQuotaDao dao, ZoneId zone) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.dao = Objects.requireNonNull(dao, "dao");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    // ==================================================================================
    // Configuration
    // ==================================================================================

    /** SPEC 21.11 {@code market.daily-sell-quota}, default 25,000 C. */
    public BigDecimal dailyQuota() {
        return BigDecimal.valueOf(configs.get(ConfigFile.ECONOMY)
                .getDouble("market.daily-sell-quota", 25_000));
    }

    /** SPEC 21.11 {@code market.over-quota-multiplier}, default 0.2. */
    public BigDecimal overQuotaMultiplier() {
        return BigDecimal.valueOf(configs.get(ConfigFile.ECONOMY)
                .getDouble("market.over-quota-multiplier", 0.2));
    }

    /** SPEC 21.11 {@code market.quota-reset-hour}, default 0, so 00:00 server time. */
    public int resetHour() {
        int hour = configs.get(ConfigFile.ECONOMY).getInt("market.quota-reset-hour", 0);
        return hour < 0 || hour > 23 ? 0 : hour;
    }

    /**
     * Whether the quota is applied at all.
     *
     * <p>Present so a server can be run without it, but it defaults on: SPEC 21.5's money
     * creation table is only true while it is.
     */
    public boolean enabled() {
        return configs.get(ConfigFile.ECONOMY).getBoolean("market.quota-enabled", true)
                && dailyQuota().signum() > 0;
    }

    // ==================================================================================
    // The day
    // ==================================================================================

    /**
     * The reset boundary on or before {@code now}.
     *
     * <p>With the default hour of 0 this is midnight server time, which SPEC 21.5 pairs with
     * the quest reset: "Quota resets at 00:00 server time along with quests."
     */
    public long periodStart(long now) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long start = today.atStartOfDay(zone).plusHours(resetHour()).toInstant().toEpochMilli();
        if (start > now) {
            // Before today's reset hour, so the current period began yesterday.
            return today.minusDays(1).atStartOfDay(zone).plusHours(resetHour())
                    .toInstant().toEpochMilli();
        }
        return start;
    }

    /**
     * When the current quota expires, for {@code /quota} and the warning messages.
     *
     * <p>A day after the current period began, added in the zone rather than as 86,400,000
     * milliseconds, so the hour survives a daylight saving change instead of drifting by one.
     */
    public long nextReset(long now) {
        return Instant.ofEpochMilli(periodStart(now)).atZone(zone).plusDays(1)
                .toInstant().toEpochMilli();
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    /** How much of the quota a player has spent today, zero once the day has turned. */
    public BigDecimal usedSync(Connection connection, UUID player, long now)
            throws SQLException {
        Optional<SellQuotaRow> row = dao.findSync(connection, player);
        if (row.isEmpty() || row.get().periodStart() < periodStart(now)) {
            return BigDecimal.ZERO;
        }
        return row.get().used();
    }

    /** The whole picture for one player, for {@code /quota}. */
    public CompletableFuture<Status> status(UUID player, long now) {
        return dao.find(player).thenApply(row -> {
            BigDecimal used = row.isEmpty() || row.get().periodStart() < periodStart(now)
                    ? BigDecimal.ZERO
                    : row.get().used();
            return statusOf(used, now);
        });
    }

    private Status statusOf(BigDecimal used, long now) {
        BigDecimal quota = dailyQuota();
        BigDecimal remaining = quota.subtract(used).max(BigDecimal.ZERO);
        return new Status(used, quota, remaining, nextReset(now),
                remaining.signum() > 0 ? BigDecimal.ONE : overQuotaMultiplier(),
                percentUsed(used, quota));
    }

    private int percentUsed(BigDecimal used, BigDecimal quota) {
        if (quota.signum() <= 0) {
            return 100;
        }
        return used.multiply(BigDecimal.valueOf(100))
                .divide(quota, 0, RoundingMode.FLOOR)
                .min(BigDecimal.valueOf(999))
                .intValue();
    }

    // ==================================================================================
    // Charging
    // ==================================================================================

    /**
     * Applies the quota to a sale and records what it consumed, in one transaction.
     *
     * <p>A sale that <b>straddles</b> the boundary is split at it: the part inside the quota is
     * paid in full and only the remainder is reduced. SPEC 21.5 does not say which, and the
     * alternative — reducing a whole sale because its last coin crossed the line — makes the
     * quota a puzzle about batch sizes, where a player who splits one stack into four gets
     * meaningfully more money than one who sells the stack. Splitting is the only reading under
     * which selling in one go and selling in pieces pay the same, which is what a cap on value
     * ought to mean.
     *
     * @param gross what the sale is worth at full price, after any newcomer bonus
     * @return what the player is actually paid, and what that did to their quota
     */
    public Charge chargeSync(Connection connection, UUID player, BigDecimal gross, long now)
            throws SQLException {
        if (!enabled() || gross.signum() <= 0) {
            return Charge.unlimited(gross);
        }
        synchronized (lockFor(player)) {
            long period = periodStart(now);
            BigDecimal used = usedSync(connection, player, now);
            BigDecimal quota = dailyQuota();
            BigDecimal headroom = quota.subtract(used).max(BigDecimal.ZERO);

            BigDecimal atFullPrice = gross.min(headroom);
            BigDecimal reduced = gross.subtract(atFullPrice);
            BigDecimal payable = Money.floor(
                    atFullPrice.add(reduced.multiply(overQuotaMultiplier())));

            // The counter tracks value sold at full rate, so a player past the cap does not
            // burn a further five coins of quota for every one they are paid.
            BigDecimal consumed = atFullPrice;
            BigDecimal nowUsed = used.add(consumed);
            dao.upsertSync(connection, new SellQuotaRow(player, period, nowUsed));

            boolean crossed = headroom.signum() > 0 && reduced.signum() > 0;
            boolean over = headroom.signum() <= 0;
            return new Charge(payable, reduced, nowUsed, quota.subtract(nowUsed)
                    .max(BigDecimal.ZERO), crossed, over);
        }
    }

    /** Clears one player's counter, for {@code /ca quota reset}. */
    public CompletableFuture<Integer> reset(UUID player) {
        return dao.delete(player);
    }

    /** Housekeeping: drops counters from days that have turned. */
    public CompletableFuture<Integer> pruneOldPeriods(long now) {
        return dao.pruneBefore(periodStart(now));
    }

    private Object lockFor(UUID player) {
        return locks.computeIfAbsent(player, key -> new Object());
    }

    // ==================================================================================
    // Shapes
    // ==================================================================================

    /**
     * What the quota did to one sale.
     *
     * @param payable   what the player is paid, before tax
     * @param reduced   the part of {@code gross} that was cut to the over-quota rate
     * @param used      their quota total after this sale
     * @param remaining what is left of the quota
     * @param crossed   this sale is the one that ran out of quota, so warn once
     * @param over      the quota was already gone before this sale
     */
    public record Charge(BigDecimal payable, BigDecimal reduced, BigDecimal used,
                         BigDecimal remaining, boolean crossed, boolean over) {

        /**
         * A sale the quota did not touch: a purchase, or a server running without one.
         *
         * <p>{@code remaining} is the full amount rather than zero, so a caller that renders it
         * says "unbounded" by saying a large number, never "you have none left".
         */
        public static Charge unlimited(BigDecimal gross) {
            return new Charge(gross, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.valueOf(Long.MAX_VALUE), false, false);
        }

        /** Whether any of this sale was paid at the reduced rate. */
        public boolean wasReduced() {
            return reduced.signum() > 0;
        }
    }

    /**
     * What {@code /quota} reports.
     *
     * @param multiplier what the next coin of value would be paid at, 1 or the over-quota rate
     * @param percent    how much of the quota is gone, floored, for the SPEC 21.5 80% warning
     */
    public record Status(BigDecimal used, BigDecimal quota, BigDecimal remaining,
                         long resetsAt, BigDecimal multiplier, int percent) {
    }
}
