package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.dao.LedgerDao;
import dev.civitas.storage.row.MoneySupplyRow;

/**
 * SPEC 21.4 Class G: where the money is and where it came from.
 *
 * <p>"The plugin must be able to answer, at any moment, 'how much money exists and where did it
 * come from.' <b>Without this you cannot detect an exploit you did not predict.</b>"
 *
 * <p>That last sentence is the whole point. Part II's threat catalogue enumerates thirty-odd known
 * exploits and closes each one; this is the instrument for the thirty-first. An exploit that nobody
 * has thought of still shows up as money appearing faster than it should, under some ledger type,
 * held by somebody — and those are exactly the three questions {@code /ca eco supply},
 * {@code sources} and {@code top} answer.
 *
 * <h2>Stocks are stored, flows are derived</h2>
 *
 * <p>SPEC 21.4 asks the hourly snapshot to record both. Only the stocks are: a balance is the sum
 * of every ledger row that ever touched it, and summing forty million rows to draw a graph is not a
 * query anybody runs twice. The flows are read back out of the ledger, which already holds every
 * one of them, which SPEC 3.6 never deletes from, and which SPEC 1.5 makes authoritative. Copying
 * them would fork the record — and the copy is the one an investigation would be reading.
 */
public final class MoneySupplyService {

    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

    private final DaoRegistry daos;
    private final EconomyService economy;
    private final TreasuryService treasury;
    private final ConfigManager configs;
    private final Logger logger;

    public MoneySupplyService(DaoRegistry daos, EconomyService economy, TreasuryService treasury,
                              ConfigManager configs, Logger logger) {
        this.daos = Objects.requireNonNull(daos, "daos");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // The hourly snapshot
    // ==================================================================================

    /**
     * Takes one reading and stores it.
     *
     * <p>Three stocks, and escrow is the one that is easy to leave out. Money held by SPEC 11.3's
     * war escrow or SPEC 4.7's bounties is in neither a wallet nor a treasury, so a supply figure
     * without it appears to shrink the moment a war is declared and to grow when it resolves —
     * which is exactly the shape of a leak, arriving from a system working correctly.
     */
    public CompletableFuture<MoneySupplyRow> record(long now) {
        return economy.totalInWallets()
                .thenCombine(treasury.totalInTreasuries(),
                        (wallets, treasuries) -> new BigDecimal[] {wallets, treasuries})
                .thenCompose(pair -> escrowed().thenApply(escrow ->
                        new MoneySupplyRow(0, now, pair[0], pair[1], escrow)))
                .thenCompose(reading -> daos.moneySupply().insert(reading)
                        .thenApply(id -> reading))
                .thenApply(reading -> {
                    logger.fine(() -> "Money supply: " + reading.circulation().toPlainString()
                            + " in circulation (" + reading.escrowTotal().toPlainString()
                            + " escrowed).");
                    return reading;
                });
    }

    /** Money that exists and belongs to nobody right now. */
    public CompletableFuture<BigDecimal> escrowed() {
        return daos.wars().totalEscrowed()
                .thenCombine(daos.bounties().totalOpen(), BigDecimal::add);
    }

    /** Prunes readings past the retention window, so the table stays proportional to the graph. */
    public CompletableFuture<Integer> prune(long now) {
        long cutoff = now - (long) keepDays() * MILLIS_PER_DAY;
        return daos.moneySupply().deleteBefore(cutoff);
    }

    public int keepDays() {
        return configs.get(ConfigFile.ECONOMY).getInt("supply.keep-days", 90);
    }

    public int intervalMinutes() {
        return configs.get(ConfigFile.ECONOMY).getInt("supply.interval-minutes", 60);
    }

    // ==================================================================================
    // SPEC 22.7.1's three questions
    // ==================================================================================

    /** What {@code /ca eco supply} prints: the stocks over time and the flows behind them. */
    public record Supply(
            Optional<MoneySupplyRow> now,
            Optional<MoneySupplyRow> then,
            List<LedgerDao.FlowRow> flows,
            int readings) {

        /** Net change in circulation over the window, or empty with nothing to compare against. */
        public Optional<BigDecimal> change() {
            if (now.isEmpty() || then.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(now.get().circulation().subtract(then.get().circulation()));
        }

        /** Percentage change, which is the figure SPEC 4.8 and SPEC 21.7 set thresholds on. */
        public Optional<BigDecimal> percentChange() {
            return change().flatMap(delta -> {
                BigDecimal before = then.orElseThrow().circulation();
                if (before.signum() == 0) {
                    return Optional.empty();
                }
                return Optional.of(delta.multiply(BigDecimal.valueOf(100))
                        .divide(before, 2, RoundingMode.HALF_UP));
            });
        }

        public BigDecimal created() {
            return flows.stream().map(LedgerDao.FlowRow::in)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal destroyed() {
            return flows.stream().map(LedgerDao.FlowRow::out)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** The types that added the most, largest first — where an unexplained gain would show. */
        public List<LedgerDao.FlowRow> topSources(int limit) {
            return flows.stream()
                    .filter(flow -> flow.in().signum() > 0)
                    .sorted((a, b) -> b.in().compareTo(a.in()))
                    .limit(limit)
                    .toList();
        }

        /** The types that removed the most, largest first. */
        public List<LedgerDao.FlowRow> topSinks(int limit) {
            return flows.stream()
                    .filter(flow -> flow.out().signum() > 0)
                    .sorted((a, b) -> b.out().compareTo(a.out()))
                    .limit(limit)
                    .toList();
        }
    }

    public CompletableFuture<Supply> supplyOver(int days, long now) {
        long since = now - (long) Math.max(1, days) * MILLIS_PER_DAY;

        return daos.moneySupply().findSince(since).thenCompose(readings ->
                daos.moneySupply().findLatestBefore(since).thenCompose(before ->
                        daos.ledger().flowsSince(since).thenApply(flows -> {
                            Optional<MoneySupplyRow> latest = readings.isEmpty()
                                    ? Optional.empty()
                                    : Optional.of(readings.get(readings.size() - 1));
                            // The reading closest to the start of the window, falling back to the
                            // oldest inside it — otherwise a freshly pruned table would report no
                            // change at all rather than a change it cannot measure.
                            Optional<MoneySupplyRow> earliest = before.isPresent()
                                    ? before
                                    : (readings.isEmpty()
                                            ? Optional.empty() : Optional.of(readings.get(0)));
                            return new Supply(latest, earliest, flows, readings.size());
                        })));
    }

    /** SPEC 22.7.1's {@code /ca eco sources}: where one player's money came from. */
    public CompletableFuture<List<LedgerDao.FlowRow>> sourcesFor(UUID player, int days, long now) {
        long since = now - (long) Math.max(1, days) * MILLIS_PER_DAY;
        return daos.ledger().incomeByTypeFor(player, since);
    }

    /**
     * SPEC 22.7.1's {@code /ca eco top}: the richest, and what share of everything they hold.
     *
     * <p>SPEC 22.1 rates this Medium severity and says why it matters: "Wealth concentration is the
     * first thing to check for an exploit."
     */
    public record Holder(String name, BigDecimal balance, BigDecimal percentOfCirculation) {
    }

    public CompletableFuture<List<Holder>> topPlayers(int count) {
        return daos.moneySupply().findLatest().thenCompose(latest ->
                daos.players().findTopByBalance(count).thenApply(rows -> {
                    BigDecimal total = latest.map(MoneySupplyRow::circulation)
                            .orElse(BigDecimal.ZERO);
                    return rows.stream()
                            .map(row -> new Holder(row.lastKnownName(), row.balance(),
                                    share(row.balance(), total)))
                            .toList();
                }));
    }

    public CompletableFuture<List<Holder>> topCities(int count) {
        return daos.moneySupply().findLatest().thenCompose(latest ->
                daos.cities().topTreasuries(count).thenApply(rows -> {
                    BigDecimal total = latest.map(MoneySupplyRow::circulation)
                            .orElse(BigDecimal.ZERO);
                    return rows.stream()
                            .map(row -> new Holder(row.name(), row.total(), share(row.total(),
                                    total)))
                            .toList();
                }));
    }

    private static BigDecimal share(BigDecimal amount, BigDecimal total) {
        if (total.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }

    /** Logs a failure rather than letting a scheduled sweep die silently. */
    public void recordQuietly(long now) {
        try {
            record(now).exceptionally(error -> {
                logger.log(Level.WARNING, "Could not record the money supply", error);
                return null;
            });
        } catch (RuntimeException e) {
            // db.call throws synchronously on a closed pool, so exceptionally alone is not
            // enough. The same trap this project has now hit five times.
            logger.log(Level.WARNING, "Could not record the money supply", e);
        }
    }
}
