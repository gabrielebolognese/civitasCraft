package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.EconomySnapshotDao;
import dev.civitas.storage.row.EconomySnapshotRow;

/**
 * Circulation tracking, SPEC 4.8.
 *
 * <p>"The plugin tracks total circulating currency and logs it hourly. If circulation grows
 * more than 15% week-over-week, an admin warning is broadcast to console."
 *
 * <p>A warning and nothing else, deliberately. SPEC 4.8 is explicit that the only automatic
 * controls are the market tax and upkeep, "because silent balance reduction destroys player
 * trust". So this measures and reports; it never takes anything.
 */
public final class InflationTracker {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final EconomyService economy;
    private final TreasuryService treasury;
    private final EconomySnapshotDao snapshots;
    private final ConfigManager configs;
    private final Logger logger;

    public InflationTracker(EconomyService economy, TreasuryService treasury,
                            EconomySnapshotDao snapshots, ConfigManager configs, Logger logger) {
        this.economy = Objects.requireNonNull(economy, "economy");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Takes one reading, records it, and compares it with a week ago.
     *
     * @return the reading that was recorded
     */
    public CompletableFuture<EconomySnapshotRow> record(long now) {
        return economy.totalInWallets().thenCombine(treasury.totalInTreasuries(),
                        (wallets, treasuries) -> new EconomySnapshotRow(0, now, wallets, treasuries))
                .thenCompose(reading -> snapshots.insert(reading)
                        .thenCompose(id -> compareWithLastWeek(reading))
                        .thenApply(ignored -> reading))
                .thenApply(reading -> {
                    logger.info(() -> "Circulation: " + reading.circulation().toPlainString()
                            + " (" + reading.playerTotal().toPlainString() + " in wallets, "
                            + reading.treasuryTotal().toPlainString() + " in treasuries).");
                    return reading;
                });
    }

    /** Warns on console if growth has passed the configured threshold. */
    private CompletableFuture<Void> compareWithLastWeek(EconomySnapshotRow reading) {
        long weekAgo = reading.timestamp() - 7 * MILLIS_PER_DAY;

        return snapshots.findLatestBefore(weekAgo).thenAccept(previous -> {
            if (previous.isEmpty()) {
                // Nothing to compare against yet; a server in its first week is not inflating.
                return;
            }
            Optional<BigDecimal> growth = percentGrowth(previous.get().circulation(),
                    reading.circulation());
            if (growth.isEmpty()) {
                return;
            }

            double threshold = configs.get(ConfigFile.ECONOMY)
                    .getDouble("inflation.weekly-growth-warn-percent", 15.0);
            if (growth.get().doubleValue() <= threshold) {
                return;
            }

            logger.log(Level.WARNING,
                    "Circulation has grown {0}% in the last week, past the {1}% warning "
                            + "threshold. It was {2} and is now {3}. Check the ledger for an "
                            + "income source paying more than intended.",
                    new Object[] {growth.get().toPlainString(), threshold,
                            previous.get().circulation().toPlainString(),
                            reading.circulation().toPlainString()});
        });
    }

    /**
     * Growth from one figure to another, as a percentage.
     *
     * @return empty when the earlier figure is zero, since everything is infinite growth
     *         from nothing and warning about it would be noise on a new server
     */
    public static Optional<BigDecimal> percentGrowth(BigDecimal before, BigDecimal after) {
        if (before.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(after.subtract(before)
                .multiply(BigDecimal.valueOf(100))
                .divide(before, 2, RoundingMode.HALF_UP));
    }

    /** Drops readings older than the retention window, so the table cannot grow forever. */
    public CompletableFuture<Integer> prune(long now) {
        int keepDays = configs.get(ConfigFile.ECONOMY).getInt("inflation.keep-days", 60);
        return snapshots.deleteBefore(now - (long) keepDays * MILLIS_PER_DAY);
    }

    /** Circulation right now, without recording it. For {@code /ca economy stats} in M21. */
    public CompletableFuture<BigDecimal> currentCirculation() {
        return economy.totalInWallets().thenCombine(treasury.totalInTreasuries(),
                (wallets, treasuries) -> wallets.add(treasuries).max(SqlDialect.zero()));
    }
}
