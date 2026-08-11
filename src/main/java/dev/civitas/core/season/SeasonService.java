package dev.civitas.core.season;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.progression.LeaderboardEntry;
import dev.civitas.core.progression.LeaderboardService;
import dev.civitas.core.progression.LeaderboardType;
import dev.civitas.storage.dao.SeasonDao;
import dev.civitas.storage.row.SeasonResultRow;
import dev.civitas.storage.row.SeasonRow;
import dev.civitas.util.Result;

/**
 * SPEC 35's seasons: a ninety-day scoreboard cycle that takes nothing away.
 *
 * <h2>What this exists to fix</h2>
 *
 * <p>SPEC 35.1: "Six months in, the founding cities hold every top slot and a player who joins in
 * month seven cannot ever appear on any of them. The multi-axis leaderboard design in Part I 13.3
 * existed specifically to give newcomers a path to visible status. Permanent accumulation removes
 * that path and <b>quietly breaks pillar 1.3</b>."
 *
 * <h2>Nothing is ever taken away</h2>
 *
 * <p>SPEC 35.2 says so in bold and says why: "'season' on most servers means 'your stuff is
 * deleted' and players will assume the worst." There is no method in this class that removes a
 * city, a claim, a balance, an upgrade or a block, and none that could — the only thing a season
 * writes is a baseline and a row in the Hall of Fame.
 */
public final class SeasonService {

    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

    private final SeasonDao dao;
    private final LeaderboardService leaderboards;
    private final ConfigManager configs;
    private final Logger logger;

    /** The running season, cached because every leaderboard read asks for it. */
    private final AtomicReference<Season> current = new AtomicReference<>();

    public SeasonService(SeasonDao dao, LeaderboardService leaderboards, ConfigManager configs,
                         Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.leaderboards = Objects.requireNonNull(leaderboards, "leaderboards");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean enabled() {
        return configs.get(ConfigFile.EVENTS).getBoolean("seasons.enabled", true);
    }

    public int lengthDays() {
        return configs.get(ConfigFile.EVENTS).getInt("seasons.length-days", 90);
    }

    public int announceDaysBeforeEnd() {
        return configs.get(ConfigFile.EVENTS).getInt("seasons.announce-days-before-end", 7);
    }

    /** SPEC 35.3's cap, so a seasonal payout cannot distort SPEC 21.4's money supply. */
    public BigDecimal maxCurrencyPrize() {
        return new BigDecimal(configs.get(ConfigFile.EVENTS)
                .getString("seasons.max-currency-prize", "100000"));
    }

    public Optional<Season> current() {
        return Optional.ofNullable(current.get());
    }

    public CompletableFuture<Optional<Season>> load() {
        return dao.findRunning().thenApply(row -> {
            Optional<Season> season = row.map(SeasonService::toSeason);
            current.set(season.orElse(null));
            season.ifPresent(value -> logger.info(() -> "Season " + value.name() + " is on day "
                    + value.dayOf(System.currentTimeMillis()) + " of " + lengthDays() + "."));
            return season;
        });
    }

    static Season toSeason(SeasonRow row) {
        return new Season(row.id(), row.name(), row.theme(), row.startsAt(), row.endsAt(),
                Season.State.valueOf(row.state()), row.endedAt());
    }

    // ==================================================================================
    // Starting one
    // ==================================================================================

    /**
     * Opens a season and records what every counter read at that moment.
     *
     * <p>The baseline is the whole mechanism. A season score is the lifetime figure minus what it
     * was when the season opened, so the boards reset without any counter being touched — and a
     * player's lifetime Builder total, which SPEC 35.2 lists among the things a season never takes
     * away, is exactly where it was.
     */
    public CompletableFuture<Result<Season>> start(String name, String theme, long now) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(
                    Result.failure("SEASONS_DISABLED", "season.disabled"));
        }
        if (current.get() != null) {
            return CompletableFuture.completedFuture(
                    Result.failure("ALREADY_RUNNING", "season.already-running"));
        }

        long endsAt = now + (long) lengthDays() * MILLIS_PER_DAY;
        return snapshotBaselines().thenCompose(baselines -> dao.transaction(connection -> {
            int id = dao.insertSync(connection, name, theme, now, endsAt,
                    Season.State.RUNNING.name());

            for (var board : baselines.entrySet()) {
                for (var subject : board.getValue().entrySet()) {
                    dao.insertBaseline(connection, id, board.getKey().name(), subject.getKey(),
                            subject.getValue());
                }
            }
            return Result.success(new Season(id, name, theme, now, endsAt,
                    Season.State.RUNNING, null));
        })).thenApply(result -> {
            if (result instanceof Result.Success<Season>(Season season)) {
                current.set(season);
            }
            return result;
        });
    }

    /**
     * What each seasonal board reads right now, per subject.
     *
     * <p>Read from the leaderboards rather than from the DAOs directly, so a board added later is
     * baselined by being a board rather than by somebody remembering to add it here.
     */
    private CompletableFuture<Map<LeaderboardType, Map<String, Long>>> snapshotBaselines() {
        return leaderboards.refresh(System.currentTimeMillis()).thenApply(ignored -> {
            Map<LeaderboardType, Map<String, Long>> baselines = new java.util.LinkedHashMap<>();
            for (LeaderboardType board : Season.seasonalBoards()) {
                Map<String, Long> perSubject = new java.util.LinkedHashMap<>();
                leaderboards.board(board).ifPresent(entries -> {
                    for (LeaderboardEntry entry : entries) {
                        perSubject.put(subjectOf(entry), entry.value().longValue());
                    }
                });
                baselines.put(board, perSubject);
            }
            return baselines;
        });
    }

    /**
     * How a baseline is keyed.
     *
     * <p>The displayed name, because {@link LeaderboardEntry} carries no id — a board is built for
     * display and never needed one. The consequence, recorded rather than hidden: a player who
     * changes their Minecraft name mid-season loses their baseline and their season score restarts
     * from that rename. A city cannot be renamed without paying SPEC 5.7's fee, so the same applies
     * there and is rarer.
     */
    private static String subjectOf(LeaderboardEntry entry) {
        return entry.name();
    }

    // ==================================================================================
    // Ending one
    // ==================================================================================

    /**
     * Scores a season, writes the Hall of Fame and closes it.
     *
     * <p>The standings are rendered into the result rows rather than stored as numbers, because
     * the underlying figures keep moving after the season closes — a Hall of Fame that changed
     * when somebody kept playing would not be a record of anything.
     *
     * @return the finished season and its standings
     */
    public CompletableFuture<Result<Standings>> end(long now) {
        Season season = current.get();
        if (season == null) {
            return CompletableFuture.completedFuture(
                    Result.failure("NO_SEASON", "season.none-running"));
        }

        return standingsFor(season).thenCompose(standings -> dao.transaction(connection -> {
            for (var board : standings.byBoard().entrySet()) {
                int position = 1;
                for (LeaderboardEntry entry : board.getValue()) {
                    dao.insertResult(connection, new SeasonResultRow(0, season.id(),
                            board.getKey().name(), position++, null, entry.name(),
                            entry.value().toPlainString()));
                }
            }
            dao.finishSync(connection, season.id(), now);
            return Result.success(standings);
        })).thenApply(result -> {
            if (result instanceof Result.Success<Standings>) {
                current.set(null);
            }
            return result;
        });
    }

    /** SPEC 35.5's {@code /ca season extend}. */
    public CompletableFuture<Result<Season>> extend(int days) {
        Season season = current.get();
        if (season == null) {
            return CompletableFuture.completedFuture(
                    Result.failure("NO_SEASON", "season.none-running"));
        }
        long newEnd = season.endsAt() + (long) days * MILLIS_PER_DAY;
        return dao.extend(season.id(), newEnd).thenApply(rows -> {
            Season extended = new Season(season.id(), season.name(), season.theme(),
                    season.startsAt(), newEnd, season.state(), null);
            current.set(extended);
            return Result.success(extended);
        });
    }

    // ==================================================================================
    // Standings
    // ==================================================================================

    /** A season's boards, each already rebased against its baseline and re-sorted. */
    public record Standings(Season season, Map<LeaderboardType, List<LeaderboardEntry>> byBoard) {
    }

    /**
     * The five seasonal boards as they stand today, measured from the season's own start.
     *
     * <p>Rebasing changes the order, not just the numbers: a founding city that had 900,000 blocks
     * placed before the season began and 100 since ranks below a newcomer with 5,000 since. That
     * reordering is the entire feature.
     */
    public CompletableFuture<Standings> standingsFor(Season season) {
        Map<LeaderboardType, List<LeaderboardEntry>> boards = new java.util.LinkedHashMap<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (LeaderboardType board : Season.seasonalBoards()) {
            chain = chain.thenCompose(ignored -> dao.findBaselines(season.id(), board.name())
                    .thenAccept(baselines ->
                            boards.put(board, rebase(board, baselines))));
        }
        return chain.thenApply(ignored -> new Standings(season, boards));
    }

    private List<LeaderboardEntry> rebase(LeaderboardType board, Map<String, Long> baselines) {
        List<LeaderboardEntry> rebased = new ArrayList<>();
        leaderboards.board(board).ifPresent(entries -> {
            for (LeaderboardEntry entry : entries) {
                long baseline = baselines.getOrDefault(subjectOf(entry), 0L);
                BigDecimal since = entry.value().subtract(BigDecimal.valueOf(baseline));
                if (since.signum() > 0) {
                    rebased.add(new LeaderboardEntry(0, entry.name(), since, entry.secondary()));
                }
            }
        });
        rebased.sort((a, b) -> b.value().compareTo(a.value()));

        // Ranks are assigned after the re-sort, because the whole point is that the order
        // changed: a founding city with 900,000 blocks placed before the season and 100 since
        // ranks below a newcomer with 5,000 since.
        List<LeaderboardEntry> ranked = new ArrayList<>(rebased.size());
        for (int i = 0; i < rebased.size(); i++) {
            LeaderboardEntry entry = rebased.get(i);
            ranked.add(new LeaderboardEntry(i + 1, entry.name(), entry.value(),
                    entry.secondary()));
        }
        return List.copyOf(ranked);
    }

    // ==================================================================================
    // The sweep
    // ==================================================================================

    /**
     * Ends a season whose clock has run out.
     *
     * <p>Automatic rather than waiting for an admin, because SPEC 35.2 gives a season a length and
     * an announcement window: a season that quietly ran past its end date would make both
     * meaningless.
     */
    public CompletableFuture<Optional<Standings>> sweep(long now) {
        Season season = current.get();
        if (season == null || !season.hasExpired(now)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return end(now).thenApply(result -> result instanceof Result.Success<Standings>(
                Standings standings) ? Optional.of(standings) : Optional.empty());
    }

    /** Whether the end is close enough to be announcing it, SPEC 35.2's seven days. */
    public boolean isInAnnouncementWindow(long now) {
        Season season = current.get();
        return season != null && season.isRunning()
                && season.millisRemaining(now)
                        <= (long) announceDaysBeforeEnd() * MILLIS_PER_DAY;
    }

    public CompletableFuture<List<Season>> history(int limit) {
        return dao.findFinished(limit).thenApply(rows ->
                rows.stream().map(SeasonService::toSeason).toList());
    }

    public CompletableFuture<List<SeasonResultRow>> results(int seasonId) {
        return dao.findResults(seasonId);
    }

    /** Logs rather than throwing, for the scheduled caller. */
    public void sweepQuietly(long now, java.util.function.Consumer<Standings> then) {
        try {
            sweep(now).whenComplete((standings, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "The season sweep failed", error);
                    return;
                }
                standings.ifPresent(then);
            });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "The season sweep failed", e);
        }
    }
}
