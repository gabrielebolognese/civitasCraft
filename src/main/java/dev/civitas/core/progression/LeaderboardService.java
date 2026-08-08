package dev.civitas.core.progression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.dao.ContestEntryDao;
import dev.civitas.storage.dao.LedgerDao;
import dev.civitas.storage.dao.PlayerDao;
import dev.civitas.storage.dao.PlayerStatDao;
import dev.civitas.storage.row.RankedCityRow;
import dev.civitas.storage.row.RankedTotalRow;

/**
 * The SPEC 13.3 boards, computed off the main thread and served from a snapshot.
 *
 * <h2>Why a snapshot rather than a query</h2>
 * Every one of these is an aggregate over a whole table or a whole registry. SPEC 19 names
 * caching as part of this milestone, and the reason is the hard rule: a player typing
 * {@code /leaderboard} must not start a {@code GROUP BY} on the server thread, and even off
 * it, one query per player per keystroke is a way to make the database the slowest thing on
 * the server. So the boards are recomputed on a timer and the command reads whatever the last
 * sweep produced.
 *
 * <p>A consequence worth stating plainly, because players notice it: the numbers are as old
 * as the refresh interval. Someone who deposits into the treasury does not jump up the
 * Contribution board that second. That is the trade SPEC 19 asks for.
 *
 * <h2>Boards whose system does not exist yet</h2>
 * War Record needs M19. It is listed, it reports itself unavailable, and it does not pretend
 * to be an empty board: "no city has won a war" and "wars do not exist on this server yet"
 * are different statements, and showing the first when the second is true is how a player
 * concludes the feature is broken. Contest Champions was in the same position until M15 gave
 * it a source.
 */
public final class LeaderboardService {

    private static final int DEFAULT_SIZE = 25;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DEFAULT_REFRESH_MINUTES = 5;

    private final PlayerDao players;
    private final LedgerDao ledger;
    private final PlayerStatDao stats;
    private final ContestEntryDao contestEntries;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final ClaimService claimService;
    private final ConfigManager configs;
    private final Logger logger;

    /** Swapped wholesale by {@link #refresh}; never mutated in place. */
    private volatile Map<LeaderboardType, List<LeaderboardEntry>> snapshot = Map.of();

    /** When the snapshot was taken, or 0 if no sweep has finished yet. */
    private volatile long refreshedAt;

    public LeaderboardService(PlayerDao players, LedgerDao ledger, PlayerStatDao stats,
                              ContestEntryDao contestEntries, CityRegistry cities,
                              ClaimRegistry claims, ClaimService claimService,
                              ConfigManager configs, Logger logger) {
        this.players = Objects.requireNonNull(players, "players");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.contestEntries = Objects.requireNonNull(contestEntries, "contestEntries");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.claimService = Objects.requireNonNull(claimService, "claimService");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    /** Whether a sweep has finished, so there is anything at all to show. */
    public boolean isReady() {
        return refreshedAt > 0L;
    }

    public long refreshedAt() {
        return refreshedAt;
    }

    /**
     * Whether the system behind a board exists on this build.
     *
     * <p>False for the two boards SPEC 13.3 names whose data comes from a later milestone.
     */
    public boolean isAvailable(LeaderboardType type) {
        return switch (type) {
            case WAR_RECORD -> hasWars();
            default -> true;
        };
    }

    /**
     * The current standings for one board.
     *
     * @return empty if no sweep has finished yet, which is distinct from a board that ran and
     *         found nobody
     */
    public Optional<List<LeaderboardEntry>> board(LeaderboardType type) {
        Objects.requireNonNull(type, "type");
        if (!isReady()) {
            return Optional.empty();
        }
        return Optional.of(snapshot.getOrDefault(type, List.of()));
    }

    /** One page of a board, 1-indexed. Empty if the page is past the end. */
    public List<LeaderboardEntry> page(LeaderboardType type, int page) {
        List<LeaderboardEntry> all = board(type).orElse(List.of());
        int size = pageSize();
        int from = Math.max(0, (page - 1) * size);
        if (from >= all.size()) {
            return List.of();
        }
        return List.copyOf(all.subList(from, Math.min(all.size(), from + size)));
    }

    public int pageCount(LeaderboardType type) {
        int entries = board(type).orElse(List.of()).size();
        return Math.max(1, (int) Math.ceil(entries / (double) pageSize()));
    }

    // ==================================================================================
    // Refreshing
    // ==================================================================================

    /**
     * Recomputes every board.
     *
     * <p>Runs off the main thread. The city boards read the in-memory registries, which are
     * concurrent maps, in the same way the upkeep and diplomacy sweeps already do.
     *
     * @return the number of boards that produced at least one entry
     */
    public CompletableFuture<Integer> refresh(long now) {
        int limit = size();

        CompletableFuture<List<LeaderboardEntry>> wealth = players.findTopByBalance(limit)
                .thenApply(rows -> rank(rows.stream()
                        .map(row -> new Ranked(row.lastKnownName(), row.balance(), null))
                        .toList()));

        CompletableFuture<List<LeaderboardEntry>> contribution =
                ledger.topByTypeGroupedByActor(TransactionType.TREASURY_DEPOSIT.name(), limit)
                        .thenApply(this::rankRows);

        CompletableFuture<List<LeaderboardEntry>> builder =
                stats.topByStat(PlayerStat.BLOCKS_PLACED.key(), limit).thenApply(this::rankRows);

        CompletableFuture<List<LeaderboardEntry>> farmer =
                stats.topByStat(PlayerStat.CROPS_HARVESTED.key(), limit).thenApply(this::rankRows);

        // SPEC 13.3 Contest Champions, which M15 gave a data source.
        CompletableFuture<List<LeaderboardEntry>> champions =
                contestEntries.topByCumulativeScore(limit).thenApply(this::rankCityRows);

        return CompletableFuture.allOf(wealth, contribution, builder, farmer, champions)
                .thenApply(ignored -> {
                    Map<LeaderboardType, List<LeaderboardEntry>> built =
                            new EnumMap<>(LeaderboardType.class);

                    built.put(LeaderboardType.WEALTH, wealth.join());
                    built.put(LeaderboardType.CONTRIBUTION, contribution.join());
                    built.put(LeaderboardType.BUILDER, builder.join());
                    built.put(LeaderboardType.FARMER, farmer.join());

                    built.put(LeaderboardType.CITY_TREASURY,
                            cityBoard(city -> city.treasury(), limit));
                    built.put(LeaderboardType.CITY_SIZE,
                            cityBoard(city -> BigDecimal.valueOf(claims.countOf(city.id())), limit));
                    built.put(LeaderboardType.CITY_POPULATION,
                            cityBoard(city -> BigDecimal.valueOf(
                                    claimService.rawActiveMemberCount(city)), limit));

                    built.put(LeaderboardType.CONTEST_CHAMPIONS, champions.join());
                    // SPEC 13.3 names it; the system that feeds it is M19.
                    built.put(LeaderboardType.WAR_RECORD, warRecords());

                    this.snapshot = Map.copyOf(built);
                    this.refreshedAt = now;
                    return (int) built.values().stream().filter(list -> !list.isEmpty()).count();
                })
                .exceptionally(error -> {
                    // Keep serving the previous snapshot. A stale board beats an empty one,
                    // and beats a stack trace in front of whoever typed the command.
                    logger.log(Level.WARNING, "Leaderboard refresh failed; keeping the last "
                            + "snapshot.", error);
                    return 0;
                });
    }

    /** Ranks every city by one figure, highest first, ties broken by name so order is stable. */
    private List<LeaderboardEntry> cityBoard(java.util.function.Function<City, BigDecimal> value,
                                             int limit) {
        List<Ranked> ranked = new ArrayList<>();
        for (City city : cities.cities()) {
            ranked.add(new Ranked(city.name(), value.apply(city), null));
        }
        ranked.sort(Comparator
                .comparing((Ranked entry) -> entry.value, Comparator.reverseOrder())
                .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER));
        return rank(ranked.size() > limit ? ranked.subList(0, limit) : ranked);
    }

    private List<LeaderboardEntry> rankRows(List<RankedTotalRow> rows) {
        return rank(rows.stream()
                .map(row -> new Ranked(row.name(), row.total(), null))
                .toList());
    }

    private List<LeaderboardEntry> rankCityRows(List<RankedCityRow> rows) {
        return rank(rows.stream()
                .map(row -> new Ranked(row.name(), row.total(), null))
                .toList());
    }

    /** Numbers a list that is already in order. */
    private List<LeaderboardEntry> rank(List<Ranked> ordered) {
        List<LeaderboardEntry> entries = new ArrayList<>(ordered.size());
        int position = 1;
        for (Ranked entry : ordered) {
            entries.add(new LeaderboardEntry(position++, entry.name, entry.value, entry.secondary));
        }
        return List.copyOf(entries);
    }

    /** A row on its way to becoming an entry, before it knows its position. */
    private record Ranked(String name, BigDecimal value, BigDecimal secondary) { }

    // ==================================================================================
    // Configuration
    // ==================================================================================

    /** How many entries a board keeps. */
    public int size() {
        return Math.max(1, configs.get(ConfigFile.EVENTS)
                .getInt("leaderboards.size", DEFAULT_SIZE));
    }

    /** How many entries print on one page of {@code /leaderboard <type>}. */
    public int pageSize() {
        return Math.max(1, configs.get(ConfigFile.EVENTS)
                .getInt("leaderboards.page-size", DEFAULT_PAGE_SIZE));
    }

    public boolean isEnabled() {
        return configs.get(ConfigFile.EVENTS).getBoolean("leaderboards.enabled", true);
    }

    public long refreshIntervalMinutes() {
        return Math.max(1L, configs.get(ConfigFile.EVENTS)
                .getLong("leaderboards.refresh-interval-minutes", DEFAULT_REFRESH_MINUTES));
    }

    // ==================================================================================
    // Seams for milestones that do not exist yet
    // ==================================================================================

    /** SPEC 11's war record. Available once the war system is wired, which M19 does. */
    private boolean hasWars() {
        return wars != null;
    }

    private dev.civitas.storage.dao.WarDao wars;

    /** SPEC 13.3's War Record board, wired by M19. */
    public void useWars(dev.civitas.storage.dao.WarDao warDao) {
        this.wars = warDao;
    }

    /**
     * SPEC 13.3 War Record: wins, with losses as the tiebreaker.
     *
     * <p>M19 fills this in from {@code wars.winner_city_id}. The ordering it will need is
     * already settled and tested in {@link #warRecordOrder()}: more wins first, then fewer
     * losses, then name.
     */
    /**
     * SPEC 21.11 {@code anti-abuse.war-leaderboard-min-loser-score-percent}, default 25.
     *
     * <p>SPEC 21.4 F4: a war where the loser scored less than this share of the winner's score
     * is "recorded but not ranked", because two friendly cities trading walkovers is otherwise
     * a way to farm the board that the 21-day cooldown only slows down.
     */
    private int minLoserScorePercent() {
        return configs.get(dev.civitas.config.ConfigFile.ECONOMY)
                .getInt("anti-abuse.war-leaderboard-min-loser-score-percent", 25);
    }

    private List<LeaderboardEntry> warRecords() {
        if (wars == null) {
            return List.of();
        }
        try {
            // Already ordered by the query: wins descending, then fewest losses, which is
            // exactly what warRecordOrder describes and what SPEC 13.3 asks for.
            return rank(wars.findRecords(size(), minLoserScorePercent()).join().stream()
                    .map(row -> new Ranked(row.name(),
                            BigDecimal.valueOf(row.wins()),
                            BigDecimal.valueOf(row.losses())))
                    .toList());
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not read war records for the leaderboard.", e);
            return List.of();
        }
    }

    /**
     * How War Record sorts, SPEC 13.3: "Wins, with losses as a tiebreaker."
     *
     * <p>Exposed and tested now, before M19 has anything to sort, so that the rule is settled
     * while it is being read out of the specification rather than reconstructed later.
     */
    public static Comparator<LeaderboardEntry> warRecordOrder() {
        return Comparator
                .comparing(LeaderboardEntry::value, Comparator.reverseOrder())
                .thenComparing(entry -> entry.secondary() == null ? BigDecimal.ZERO : entry.secondary())
                .thenComparing(LeaderboardEntry::name, String.CASE_INSENSITIVE_ORDER);
    }
}
