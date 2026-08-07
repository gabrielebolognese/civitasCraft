package dev.civitas.core.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.row.LedgerRow;

/**
 * SPEC 17.6 case 79's six patterns, as pure functions over ledger rows.
 *
 * <h2>These are flags, not verdicts</h2>
 * SPEC 9.4.4 words the command as "{@code /ca audit suspicious} … runs the fraud heuristics in
 * Section 18.9 and reports hits". Every one of these rules has an innocent explanation: a city
 * founder legitimately withdraws most of the treasury to buy an upgrade, a returning player
 * legitimately receives a large gift, a good trading day legitimately beats a lifetime of
 * casual play. What they identify is where an admin should <em>look</em>, and the wording of
 * every message says so.
 *
 * <p>That distinction is not cosmetic. A heuristic presented as proof gets somebody banned for
 * playing well, and SPEC 1.5's whole reason for the ledger is so that disputes are settled by
 * evidence rather than suspicion.
 *
 * <h2>Pure, and therefore testable</h2>
 * Nothing here reads a database or a clock. The caller supplies the rows and the moment, which
 * is what lets every threshold be tested at its boundary rather than approximately.
 */
public final class FraudHeuristics {

    private final ConfigManager configs;

    public FraudHeuristics(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * One thing worth looking at.
     *
     * @param rule    which heuristic fired, for grouping in a report
     * @param subject the player or city the admin should look at
     * @param detail  a lang key's placeholders, already resolved to plain values
     */
    public record Hit(String rule, String subject, Map<String, String> detail) { }

    // ==================================================================================
    // The six rules
    // ==================================================================================

    /**
     * "Single withdrawals over 40% of treasury."
     *
     * <p>Measured against the treasury as it stood <em>before</em> the withdrawal, which the
     * ledger records: {@code balance_after} plus the amount taken. Measuring against the
     * current treasury would flag every withdrawal from a city that has since been emptied.
     */
    public List<Hit> largeWithdrawals(List<LedgerRow> rows) {
        double threshold = percent("single-withdrawal-percent-of-treasury", 40);
        List<Hit> hits = new ArrayList<>();

        for (LedgerRow row : rows) {
            if (!TransactionType.TREASURY_WITHDRAW.name().equals(row.type())
                    || row.amount().signum() >= 0) {
                continue;
            }
            BigDecimal taken = row.amount().abs();
            BigDecimal before = row.balanceAfter().add(taken);
            if (before.signum() <= 0) {
                continue;
            }
            BigDecimal share = taken.multiply(BigDecimal.valueOf(100))
                    .divide(before, 2, RoundingMode.HALF_UP);
            if (share.doubleValue() >= threshold) {
                hits.add(new Hit("large-withdrawal", nameOf(row.actorUuid()), Map.of(
                        "percent", share.toPlainString(),
                        "amount", taken.toPlainString(),
                        "city", String.valueOf(row.cityId()))));
            }
        }
        return hits;
    }

    /**
     * "More than 5 transfers to the same player in 1 hour."
     *
     * <p>The shape of an alt account being fed, or of a real player being extorted. Counted per
     * ordered pair, because A paying B six times is the pattern and A and B paying each other
     * three times apiece is trade.
     */
    public List<Hit> repeatedTransfers(List<LedgerRow> rows, long now) {
        int threshold = configs.get(ConfigFile.ECONOMY)
                .getInt("audit.transfers-to-same-player-per-hour", 5);
        long window = 60L * 60L * 1000L;

        Map<String, List<LedgerRow>> byPair = new HashMap<>();
        for (LedgerRow row : rows) {
            if (!TransactionType.PLAYER_PAY.name().equals(row.type())
                    || row.actorUuid() == null || row.targetUuid() == null
                    || row.amount().signum() >= 0) {
                // The paying side is the negative row; counting both would double everything.
                continue;
            }
            if (now - row.timestamp() > window) {
                continue;
            }
            byPair.computeIfAbsent(row.actorUuid() + "->" + row.targetUuid(),
                    key -> new ArrayList<>()).add(row);
        }

        List<Hit> hits = new ArrayList<>();
        byPair.forEach((pair, transfers) -> {
            if (transfers.size() > threshold) {
                BigDecimal total = transfers.stream()
                        .map(row -> row.amount().abs())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                hits.add(new Hit("repeated-transfers", pair, Map.of(
                        "count", String.valueOf(transfers.size()),
                        "total", total.toPlainString())));
            }
        });
        return hits;
    }

    /**
     * "A player receiving more than 3x their lifetime earnings in 24h."
     *
     * <p>The one rule that needs history rather than a window, and the one most likely to catch
     * a genuine transfer of a hacked or duplicated balance: a player's own earning rate is the
     * only baseline that means anything, because a large number is not suspicious on a server
     * where everybody has large numbers.
     */
    public List<Hit> suddenWealth(UUID player, BigDecimal receivedInDay, BigDecimal lifetime) {
        double multiple = configs.get(ConfigFile.ECONOMY)
                .getDouble("audit.income-multiple-of-lifetime-earnings-24h", 3);
        if (lifetime.signum() <= 0 || receivedInDay.signum() <= 0) {
            return List.of();
        }
        BigDecimal limit = lifetime.multiply(BigDecimal.valueOf(multiple));
        if (receivedInDay.compareTo(limit) <= 0) {
            return List.of();
        }
        return List.of(new Hit("sudden-wealth", nameOf(player), Map.of(
                "received", receivedInDay.toPlainString(),
                "lifetime", lifetime.toPlainString())));
    }

    /** "A new account receiving over 100k C." */
    public List<Hit> newAccountWindfall(UUID player, BigDecimal received, long accountAgeMillis) {
        BigDecimal threshold = new BigDecimal(configs.get(ConfigFile.ECONOMY)
                .getString("audit.new-account-receive-threshold", "100000"));
        long newFor = java.util.concurrent.TimeUnit.DAYS.toMillis(
                configs.get(ConfigFile.ECONOMY).getLong("audit.new-account-days", 7));

        if (accountAgeMillis > newFor || received.compareTo(threshold) < 0) {
            return List.of();
        }
        return List.of(new Hit("new-account-windfall", nameOf(player), Map.of(
                "received", received.toPlainString(),
                "age-days", String.valueOf(accountAgeMillis / 86_400_000L))));
    }

    /**
     * "Treasury dropping over 60% in under 10 minutes."
     *
     * <p>The signature of a treasury being drained rather than spent. SPEC 8.5's 25% daily cap
     * makes this hard for one non-mayor to do alone, which is exactly why it is worth flagging
     * when it happens: it means either the mayor, or several members acting together.
     */
    public List<Hit> treasuryDrain(int cityId, List<LedgerRow> cityRows, long now) {
        double percent = percent("treasury-drop-percent", 60);
        long window = configs.get(ConfigFile.ECONOMY)
                .getLong("audit.treasury-drop-window-minutes", 10) * 60_000L;

        List<LedgerRow> recent = cityRows.stream()
                .filter(row -> row.cityId() != null && row.cityId() == cityId)
                .filter(row -> now - row.timestamp() <= window)
                .sorted(Comparator.comparingLong(LedgerRow::timestamp))
                .toList();
        if (recent.size() < 2) {
            return List.of();
        }

        BigDecimal opening = recent.get(0).balanceAfter().subtract(recent.get(0).amount());
        BigDecimal closing = recent.get(recent.size() - 1).balanceAfter();
        if (opening.signum() <= 0) {
            return List.of();
        }
        BigDecimal dropped = opening.subtract(closing);
        if (dropped.signum() <= 0) {
            return List.of();
        }
        BigDecimal share = dropped.multiply(BigDecimal.valueOf(100))
                .divide(opening, 2, RoundingMode.HALF_UP);
        if (share.doubleValue() < percent) {
            return List.of();
        }
        return List.of(new Hit("treasury-drain", "#" + cityId, Map.of(
                "percent", share.toPlainString(),
                "from", opening.toPlainString(),
                "to", closing.toPlainString())));
    }

    /**
     * "Any player whose income rate exceeds the 99th percentile by more than 3x."
     *
     * <p>The only rule that compares players to each other rather than to themselves, and the
     * only one that needs the whole server's numbers to mean anything. With too few players it
     * says nothing useful, so below a floor it declines to answer rather than flagging the
     * richest of four people.
     */
    public List<Hit> outlierIncome(Map<UUID, BigDecimal> incomeByPlayer) {
        double multiple = configs.get(ConfigFile.ECONOMY)
                .getDouble("audit.income-rate-percentile-multiple", 3);
        int floor = configs.get(ConfigFile.ECONOMY).getInt("audit.income-percentile-minimum", 20);
        if (incomeByPlayer.size() < floor) {
            return List.of();
        }

        // The baseline excludes the highest earner, and that is not a detail.
        //
        // With nearest-rank on any realistic player count, the 99th percentile *is* the top
        // value, so a lone outlier is compared against itself and can never exceed itself by
        // three times. The rule would look implemented and detect nothing. Dropping the top
        // value makes the baseline "the field", which is what SPEC 17.6 case 79 means by
        // comparing a player to the 99th percentile.
        List<BigDecimal> sorted = new ArrayList<>(incomeByPlayer.values());
        sorted.sort(Comparator.naturalOrder());
        List<BigDecimal> field = sorted.subList(0, sorted.size() - 1);

        BigDecimal percentile99 = field.get((int) Math.floor(field.size() * 0.99));
        if (percentile99.signum() <= 0) {
            return List.of();
        }
        BigDecimal limit = percentile99.multiply(BigDecimal.valueOf(multiple));

        List<Hit> hits = new ArrayList<>();
        incomeByPlayer.forEach((player, income) -> {
            if (income.compareTo(limit) > 0) {
                hits.add(new Hit("outlier-income", nameOf(player), Map.of(
                        "income", income.toPlainString(),
                        "percentile", percentile99.toPlainString())));
            }
        });
        return hits;
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private double percent(String key, double fallback) {
        return configs.get(ConfigFile.ECONOMY).getDouble("audit." + key, fallback);
    }

    /**
     * A readable subject.
     *
     * <p>The uuid rather than a name lookup: this runs over a whole server's ledger and a name
     * lookup per row would be thousands of database reads. The command resolves names once,
     * for the hits it actually prints.
     */
    private static String nameOf(UUID player) {
        return player == null ? "-" : player.toString();
    }

    /** Whether the operator has asked for a reason on every admin economy command. */
    public boolean strictReasons() {
        return configs.get(ConfigFile.ECONOMY).getBoolean("audit.strict-admin-reasons", true);
    }
}
