package dev.civitas.core.contest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.ContestEntryRow;
import dev.civitas.storage.row.ContestRow;
import dev.civitas.storage.row.ContestVoteRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Building contests, SPEC 13.4.
 *
 * <h2>What a contest is for</h2>
 * SPEC 13.3's design note explains why this exists: a server where wealth is the only ladder
 * becomes toxic. A contest is the one ladder a two-week-old city with good builders can climb
 * past a rich one, which is why the anti-abuse rules in SPEC 13.4 are not decoration. If
 * voting can be rigged, the ladder is worth nothing and the toxicity comes back.
 *
 * <h2>What cannot be checked yet</h2>
 * SPEC 13.4 requires entries to be "verified against block placement logs". No such log exists
 * outside a war: SPEC 11.8.1's logger is war-scoped and belongs to M17. {@link
 * #canVerifyBuildWindow()} therefore answers false, and the operator is told once at startup
 * rather than being left to believe a check is running.
 */
public final class ContestService {

    private static final long MILLIS_PER_DAY = TimeUnit.DAYS.toMillis(1);

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final TreasuryService treasury;
    private final VoteWeighting weighting;
    private final ConfigManager configs;
    private final Scheduler scheduler;

    /** The running contest, or null. Replaced wholesale, never mutated. */
    private final AtomicReference<Contest> current = new AtomicReference<>();

    /** Corners a player has marked but not yet turned into a region, SPEC 13.4 step 2. */
    private final Map<UUID, PlotCorner> pendingCorners = new java.util.concurrent.ConcurrentHashMap<>();

    /** One marked corner, waiting for its pair. */
    private record PlotCorner(String world, int x, int y, int z) { }

    public ContestService(DatabaseManager db, DaoRegistry daos, CityRegistry cities,
                          ClaimRegistry claims, TreasuryService treasury, VoteWeighting weighting,
                          ConfigManager configs, Scheduler scheduler) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.weighting = Objects.requireNonNull(weighting, "weighting");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    // ==================================================================================
    // The running contest
    // ==================================================================================

    public Optional<Contest> current() {
        return Optional.ofNullable(current.get());
    }

    public VoteWeighting weighting() {
        return weighting;
    }

    /**
     * Loads the contest the cycle still has work to do on.
     *
     * <p>Deliberately the most recent <em>unfinished</em> contest rather than the one whose
     * window contains now. A contest whose day 14 passed while the server was down has votes
     * that were never tallied and prizes that were never paid, and it is the one case that
     * most needs picking up.
     */
    public CompletableFuture<Optional<Contest>> load() {
        return daos.contests().findUnfinished().thenApply(row -> {
            Optional<Contest> loaded = row.map(this::toContest);
            current.set(loaded.orElse(null));
            return loaded;
        });
    }

    /**
     * SPEC 40.1's contest visit warps.
     *
     * <h3>Why a warp and not the direct teleport that already worked</h3>
     *
     * <p>SPEC 40.1 exists because SPEC 32.3 removed the world border: "An entry four hundred
     * thousand blocks out would receive zero votes, and the city that built it would be
     * structurally excluded from a core system." {@code /contest visit} already answers that, so
     * the warp adds something else — a <b>named, discoverable</b> route that shows up in
     * {@code /warp} tab completion beside every other public warp, for a player who never learns
     * the contest commands.
     *
     * <p>Temporary by construction: the warp carries the voting window's end as its expiry, so a
     * contest that is never formally closed still stops advertising its entries. That is
     * {@code WarpService}'s expiry column doing the job M3b built it for.
     */
    @FunctionalInterface
    public interface Warps {

        /**
         * Publishes a warp, or removes it when {@code at} is null.
         *
         * @param expiresAt when the warp should stop working
         */
        void publish(String name, String world, double x, double y, double z, Long expiresAt);
    }

    private Warps warps;

    public void useWarps(Warps publisher) {
        this.warps = Objects.requireNonNull(publisher, "publisher");
    }

    /**
     * The warp name for an entry.
     *
     * <p>Prefixed so the sweep at contest end can find every one of them without a table of its
     * own, and so an operator reading {@code /warp} can tell a contest warp from one they made.
     */
    public static String warpNameFor(String cityName) {
        return WARP_PREFIX + cityName.toLowerCase(java.util.Locale.ROOT);
    }

    /** What every contest warp starts with. */
    public static final String WARP_PREFIX = "contest-";

    /** Replaces the cached contest. Used by the cycle after it advances a state. */
    void remember(Contest contest) {
        current.set(contest);
    }

    /**
     * Writes a phase change to storage.
     *
     * <p>Blocking, because the cycle that calls it runs on its own async thread and must not
     * announce a phase it has not managed to record: a crash between the two would leave the
     * contest accepting submissions after the deadline was announced.
     */
    void persistState(int contestId, ContestState state) {
        daos.contests().updateState(contestId, state.key()).join();
    }

    private Contest toContest(ContestRow row) {
        return Contest.of(row, buildDays() * MILLIS_PER_DAY, votingDays() * MILLIS_PER_DAY);
    }

    /**
     * Opens a contest, SPEC 13.4 step 1.
     *
     * <p>Refused while one is running: SPEC 13.4 describes a single cycle at a time, and two
     * overlapping contests would leave {@code /contest submit} with no way to know which one
     * a city meant.
     */
    public CompletableFuture<Result<Contest>> start(String theme, int days, long now) {
        if (current.get() != null && !current.get().isOver(now)) {
            return completed(Result.failure("CONTEST_RUNNING", "contest.already-running"));
        }
        if (theme == null || theme.isBlank()) {
            return completed(Result.failure("NO_THEME", "contest.no-theme"));
        }

        long length = Math.max(1, days) * MILLIS_PER_DAY;
        ContestRow row = new ContestRow(0, theme, now, now + length, ContestState.BUILDING.key());

        return daos.contests().insert(row).thenApply(id -> {
            Contest contest = toContest(new ContestRow(id, theme, now, now + length,
                    ContestState.BUILDING.key()));
            current.set(contest);
            return Result.success(contest);
        });
    }

    // ==================================================================================
    // Marking a region, SPEC 13.4 step 2
    // ==================================================================================

    /**
     * Marks one corner. The second call completes the region.
     *
     * @return a success carrying the finished region, or carrying null when this was the first
     *         of the two corners
     */
    public CompletableFuture<Result<PlotRegion>> mark(UUID actor, City city, String world,
                                                      int x, int y, int z, long now) {
        Result<Contest> phase = requirePhase(now, ContestState.BUILDING, "contest.not-building");
        if (phase instanceof Result.Failure<Contest> failure) {
            return completed(Result.propagate(failure));
        }
        Result<Void> permitted = require(city, actor, CityPermission.CONTEST_SUBMIT);
        if (permitted instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        PlotCorner first = pendingCorners.remove(actor);
        if (first == null || !first.world().equals(world)) {
            // A corner in a different world replaces rather than pairs: a region cannot span
            // two worlds, and silently pairing them would produce nonsense coordinates.
            pendingCorners.put(actor, new PlotCorner(world, x, y, z));
            return completed(Result.success(null));
        }

        PlotRegion region = PlotRegion.between(world, first.x(), first.y(), first.z(), x, y, z);

        Result<PlotRegion> checked = checkRegion(city, region);
        if (checked instanceof Result.Failure<PlotRegion> failure) {
            return completed(Result.propagate(failure));
        }

        return saveRegion(city, phase.orElseThrow(), region);
    }

    /** Forgets a half-marked region, so a player can start again. */
    public void clearMark(UUID actor) {
        pendingCorners.remove(actor);
    }

    public boolean hasPendingCorner(UUID actor) {
        return pendingCorners.containsKey(actor);
    }

    /**
     * SPEC 13.4's two rules about where an entry may be: inside the city's own claims, and no
     * larger than 64x64x64.
     */
    Result<PlotRegion> checkRegion(City city, PlotRegion region) {
        int max = maxRegionSize();
        if (region.longestEdge() > max) {
            return Result.failure("REGION_TOO_LARGE", "contest.region-too-large",
                    Map.of("max", String.valueOf(max),
                            "width", String.valueOf(region.width()),
                            "height", String.valueOf(region.height()),
                            "depth", String.valueOf(region.depth())));
        }

        for (int chunkX = region.minChunkX(); chunkX <= region.maxChunkX(); chunkX++) {
            for (int chunkZ = region.minChunkZ(); chunkZ <= region.maxChunkZ(); chunkZ++) {
                Optional<Integer> owner = claims.ownerOf(region.world(), chunkX, chunkZ);
                if (owner.isEmpty() || owner.get() != city.id()) {
                    return Result.failure("REGION_OUTSIDE_CLAIMS", "contest.region-outside-claims",
                            Map.of("chunk-x", String.valueOf(chunkX),
                                    "chunk-z", String.valueOf(chunkZ)));
                }
            }
        }
        return Result.success(region);
    }

    private CompletableFuture<Result<PlotRegion>> saveRegion(City city, Contest contest,
                                                             PlotRegion region) {
        return daos.contestEntries().findByCity(contest.id(), city.id()).thenCompose(existing -> {
            if (existing.isPresent()) {
                if (existing.get().submittedAt() != null) {
                    return completed(Result.<PlotRegion>failure("ALREADY_SUBMITTED",
                            "contest.already-submitted"));
                }
                return daos.contestEntries().updateRegion(existing.get().id(), region.serialise())
                        .thenApply(ignored -> Result.success(region));
            }
            return daos.contestEntries().insert(new ContestEntryRow(0, contest.id(), city.id(),
                            region.serialise(), null, 0.0, false, null, null))
                    .thenApply(ignored -> Result.success(region));
        });
    }

    // ==================================================================================
    // Submitting, SPEC 13.4 step 3
    // ==================================================================================

    /** Finalises a city's entry. After this the region cannot be moved. */
    public CompletableFuture<Result<ContestEntryRow>> submit(UUID actor, City city, long now) {
        Result<Contest> phase = requirePhase(now, ContestState.BUILDING, "contest.not-building");
        if (phase instanceof Result.Failure<Contest> failure) {
            return completed(Result.propagate(failure));
        }
        Result<Void> permitted = require(city, actor, CityPermission.CONTEST_SUBMIT);
        if (permitted instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        Contest contest = phase.orElseThrow();

        return daos.contestEntries().findByCity(contest.id(), city.id()).thenCompose(existing -> {
            if (existing.isEmpty()) {
                return completed(Result.<ContestEntryRow>failure("NO_REGION", "contest.no-region"));
            }
            ContestEntryRow entry = existing.get();
            if (entry.submittedAt() != null) {
                return completed(Result.<ContestEntryRow>failure("ALREADY_SUBMITTED",
                        "contest.already-submitted"));
            }
            if (PlotRegion.parse(entry.plotRegion()).isEmpty()) {
                return completed(Result.<ContestEntryRow>failure("BAD_REGION", "contest.no-region"));
            }
            return daos.contestEntries().markSubmitted(entry.id(), now)
                    .thenApply(ignored -> {
                        // SPEC 40.1, after the row is marked rather than before: a warp to an
                        // entry that failed to submit would point at a build nobody may vote on.
                        publishWarp(city, entry, contest);
                        return Result.success(new ContestEntryRow(entry.id(),
                                entry.contestId(), entry.cityId(), entry.plotRegion(), now,
                                entry.score(), false, null, null));
                    });
        });
    }

    /** The entries in the running, for the visit list and the voting screen. */
    public CompletableFuture<List<ContestEntryRow>> submittedEntries() {
        Contest contest = current.get();
        if (contest == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return daos.contestEntries().findSubmitted(contest.id());
    }

    // ==================================================================================
    // Voting, SPEC 13.4 step 4
    // ==================================================================================

    /**
     * Records a vote, applying every SPEC 13.4 anti-abuse rule.
     *
     * <p>A self-city vote is refused and the voter told, because SPEC 13.4 words that rule as
     * a prohibition. A vote from an account sharing a connection with a member of the entered
     * city is accepted and stored at zero weight, because SPEC 13.4 words that one as
     * discarding, and because telling the voter would report on somebody else's connection.
     * See {@link VoteWeighting} for the whole of that reasoning.
     */
    public CompletableFuture<Result<Vote>> vote(UUID voter, int entryId,
                                                Map<VoteAxis, Integer> scores, long now) {
        Result<Contest> phase = requirePhase(now, ContestState.VOTING, "contest.not-voting");
        if (phase instanceof Result.Failure<Contest> failure) {
            return completed(Result.propagate(failure));
        }
        Contest contest = phase.orElseThrow();

        for (VoteAxis axis : VoteAxis.all()) {
            Integer score = scores.get(axis);
            if (score == null || !weighting.isScoreInRange(score)) {
                return completed(Result.failure("SCORE_OUT_OF_RANGE", "contest.score-out-of-range",
                        Map.of("axis", axis.key(),
                                "min", String.valueOf(weighting.minScore()),
                                "max", String.valueOf(weighting.maxScore()))));
            }
        }

        return daos.contestEntries().findById(entryId).thenCompose(found -> {
            if (found.isEmpty() || found.get().contestId() != contest.id()) {
                return completed(Result.<Vote>failure("NO_ENTRY", "contest.unknown-entry"));
            }
            ContestEntryRow entry = found.get();
            if (entry.submittedAt() == null || entry.disqualified()) {
                return completed(Result.<Vote>failure("ENTRY_NOT_IN_RUNNING",
                        "contest.unknown-entry"));
            }

            Integer voterCity = cities.cityOf(voter).map(City::id).orElse(null);
            if (weighting.isSelfVote(voterCity, entry.cityId())) {
                return completed(Result.<Vote>failure("SELF_VOTE", "contest.self-vote"));
            }

            return weightFor(voter, entry.cityId()).thenCompose(weight -> {
                Vote vote = new Vote(voter, entryId, scores, weight);
                return daos.contestVotes().upsert(contest.id(), voter, entryId,
                                vote.score(VoteAxis.CREATIVITY),
                                vote.score(VoteAxis.TECHNICAL_SKILL),
                                vote.score(VoteAxis.THEME_FIT),
                                vote.combined(), weight)
                        .thenApply(ignored -> Result.success(vote));
            });
        });
    }

    /**
     * What SPEC 13.4 says this voter's ballot is worth.
     *
     * <p>Two lookups: the voter's playtime, and whether their connection matches any member of
     * the city they are voting for.
     */
    private CompletableFuture<Double> weightFor(UUID voter, int entryCityId) {
        CompletableFuture<Long> playtime = daos.players().findByUuid(voter)
                .thenApply(row -> row.map(PlayerRow::activePlaytimeMs).orElse(0L));

        CompletableFuture<Boolean> sharesLogin = sharesLoginWithCity(voter, entryCityId);

        return playtime.thenCombine(sharesLogin, weighting::weigh);
    }

    private CompletableFuture<Boolean> sharesLoginWithCity(UUID voter, int cityId) {
        if (!weighting.discardsSharedLogins()) {
            return CompletableFuture.completedFuture(false);
        }
        Optional<City> city = cities.city(cityId);
        if (city.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        List<UUID> members = city.get().members().stream()
                .map(dev.civitas.core.city.CityMember::uuid)
                .toList();
        if (members.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return daos.playerLogins().find(voter).thenCompose(row -> {
            if (row.isEmpty()) {
                // No fingerprint on file: the rule fails open rather than discarding a vote on
                // a guess. A missing hash is not evidence of anything.
                return CompletableFuture.completedFuture(false);
            }
            return daos.playerLogins().anyShares(members, row.get().loginHash());
        });
    }

    // ==================================================================================
    // Scoring, SPEC 13.4 step 5
    // ==================================================================================

    /**
     * Tallies the votes, records placements, and pays the prizes into the winning treasuries.
     *
     * <p>All of it in one transaction, so a contest is either scored and paid or neither.
     * Paying a first prize and then failing to record that the contest finished would pay it
     * again on the next sweep.
     *
     * @return the placed entries, best first
     */
    public CompletableFuture<Result<List<Placement>>> score(Contest contest) {
        return db.transaction(connection -> {
            List<ContestEntryRow> entries = daos.contestEntries()
                    .findSubmitted(connection, contest.id());

            List<Scored> scored = new ArrayList<>();
            for (ContestEntryRow entry : entries) {
                double value = tally(daos.contestVotes().findByEntry(connection, entry.id()));
                daos.contestEntries().updateScore(connection, entry.id(), value);
                scored.add(new Scored(entry, value));
            }

            // Highest score first; an earlier submission wins a tie, which is the one
            // tiebreak that cannot be arranged after the votes are in.
            scored.sort((a, b) -> {
                int byScore = Double.compare(b.score(), a.score());
                if (byScore != 0) {
                    return byScore;
                }
                long left = a.entry().submittedAt() == null ? Long.MAX_VALUE : a.entry().submittedAt();
                long right = b.entry().submittedAt() == null ? Long.MAX_VALUE : b.entry().submittedAt();
                return Long.compare(left, right);
            });

            List<Placement> placements = new ArrayList<>();
            for (int index = 0; index < scored.size(); index++) {
                Scored item = scored.get(index);
                int place = index + 1;
                BigDecimal prize = prizeFor(place);

                // An entry nobody voted for placed, but has not won anything: paying a prize
                // for a score of zero would make entering unopposed a way to farm the treasury.
                boolean paid = prize.signum() > 0 && item.score() > 0.0;

                daos.contestEntries().updatePlacement(connection, item.entry().id(),
                        paid ? place : null);

                if (paid) {
                    Optional<City> city = cities.city(item.entry().cityId());
                    if (city.isPresent()) {
                        Result<BigDecimal> awarded = treasury.adjust(connection, city.get(), prize,
                                TransactionType.CONTEST_PRIZE, null,
                                "{\"contest\":" + contest.id() + ",\"place\":" + place + "}");
                        if (awarded instanceof Result.Failure<BigDecimal> failure) {
                            return Result.<List<Placement>>propagate(failure);
                        }
                    }
                }

                placements.add(new Placement(item.entry(), item.score(), place,
                        paid ? prize : BigDecimal.ZERO));
            }

            daos.contests().updateState(connection, contest.id(), ContestState.FINISHED.key());
            return Result.success(List.copyOf(placements));
        }).thenApply(result -> {
            if (result instanceof Result.Success<List<Placement>>(List<Placement> placed)) {
                scheduler.runOnMain(() -> current.set(contest.withState(ContestState.FINISHED)));
                // SPEC 40.1: "The warp is deleted when the contest closes." Every entry, not only
                // the winners — a losing entry's warp is just as stale.
                for (Placement placement : placed) {
                    cities.city(placement.entry().cityId()).ifPresent(city ->
                            removeWarp(city.name()));
                }
            }
            return result;
        });
    }

    /**
     * Publishes SPEC 40.1's warp for one entry.
     *
     * <p>Above the build, at the same height {@code /contest visit} uses, so the two routes arrive
     * in the same place. Silent when the world is not loaded: an entry in an unloaded world cannot
     * be visited by either route, and refusing the submission over it would punish the entrant for
     * an operator's world list.
     */
    private void publishWarp(City city, ContestEntryRow entry, Contest contest) {
        if (warps == null || !visitWarpsEnabled()) {
            return;
        }
        PlotRegion.parse(entry.plotRegion()).ifPresent(region -> {
            double x = Math.floor(region.centreX()) + 0.5;
            double z = Math.floor(region.centreZ()) + 0.5;
            double y = region.maxY() + viewHeight();
            warps.publish(warpNameFor(city.name()), region.world(), x, y, z, contest.endsAt());
        });
    }

    private void removeWarp(String cityName) {
        if (warps != null) {
            warps.publish(warpNameFor(cityName), null, 0, 0, 0, null);
        }
    }

    /** How far above the build a visitor arrives, matching {@code /contest visit}. */
    private int viewHeight() {
        int size = maxRegionSize();
        return size > 0 ? Math.min(16, size) : 16;
    }

    public boolean visitWarpsEnabled() {
        return configs.get(ConfigFile.EVENTS).getBoolean("contests.visit-warps", true);
    }

    /**
     * The weighted mean of an entry's votes.
     *
     * <p>Zero-weight votes contribute nothing to either side of the division, so a discarded
     * ballot cannot drag a score down any more than it can push one up.
     */
    static double tally(List<ContestVoteRow> votes) {
        double weighted = 0.0;
        double weights = 0.0;
        for (ContestVoteRow vote : votes) {
            if (vote.weight() <= 0.0) {
                continue;
            }
            weighted += vote.score() * vote.weight();
            weights += vote.weight();
        }
        return weights == 0.0 ? 0.0 : weighted / weights;
    }

    /** An entry with the score its votes produced. */
    private record Scored(ContestEntryRow entry, double score) { }

    /** Where an entry finished and what it won. */
    public record Placement(ContestEntryRow entry, double score, int place, BigDecimal prize) { }

    // ==================================================================================
    // Administration, SPEC 9.4.6. The commands themselves are M21.
    // ==================================================================================

    /**
     * Removes an entry from the running, SPEC 9.4.6 {@code /ca contest disqualify}.
     *
     * <p>The row and its votes are kept. SPEC 1.5 makes every admin action auditable, and an
     * action that deleted the thing it acted on would be the exception.
     */
    public CompletableFuture<Result<ContestEntryRow>> disqualify(int entryId, String reason) {
        if (reason == null || reason.isBlank()) {
            return completed(Result.failure("NO_REASON", "contest.disqualify-no-reason"));
        }
        return daos.contestEntries().findById(entryId).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(Result.<ContestEntryRow>failure("NO_ENTRY", "contest.unknown-entry"));
            }
            return daos.contestEntries().disqualify(entryId, reason)
                    .thenApply(ignored -> Result.success(found.get()));
        });
    }

    // ==================================================================================
    // Configuration, SPEC 13.4
    // ==================================================================================

    public boolean isEnabled() {
        return events().getBoolean("contests.enabled", true);
    }

    public int cycleDays() {
        return events().getInt("contests.cycle-days", 14);
    }

    public int buildDays() {
        return events().getInt("contests.build-days", 11);
    }

    public int votingDays() {
        return events().getInt("contests.voting-days", 2);
    }

    public int maxRegionSize() {
        return events().getInt("contests.max-region-size", 64);
    }

    public List<String> themes() {
        List<String> themes = events().getStringList("contests.themes");
        return themes.isEmpty() ? List.of("Free Build") : themes;
    }

    /** The SPEC 4.2 prize table, which lives in {@code economy.yml} with the other income. */
    public BigDecimal prizeFor(int place) {
        FileConfiguration economy = configs.get(ConfigFile.ECONOMY);
        String key = switch (place) {
            case 1 -> "income.contest-prizes.first";
            case 2 -> "income.contest-prizes.second";
            case 3 -> "income.contest-prizes.third";
            default -> null;
        };
        if (key == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(economy.getDouble(key, 0.0));
    }

    // ==================================================================================
    // Seams for milestones that do not exist yet
    // ==================================================================================

    /**
     * SPEC 13.4: "Entries must be built during the contest window (verified against block
     * placement logs)."
     *
     * <p>False, and not because the check is unimportant. There is no block placement log
     * outside a war: the one SPEC 11.8.1 specifies is scoped to a war zone and belongs to M17.
     * Answering false here, and saying so at startup, is the honest version of not having it;
     * quietly passing every entry would let an operator believe the check runs.
     */
    public boolean canVerifyBuildWindow() {
        return false;
    }

    /** Whether the operator asked for a check this build cannot perform. */
    public boolean wantsUnavailableVerification() {
        return weighting.verifiesBuildWindow() && !canVerifyBuildWindow();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private Result<Contest> requirePhase(long now, ContestState required, String messageKey) {
        Contest contest = current.get();
        if (contest == null) {
            return Result.failure("NO_CONTEST", "contest.none");
        }
        if (contest.state() != required || contest.phaseAt(now) != required) {
            return Result.failure("WRONG_PHASE", messageKey);
        }
        return Result.success(contest);
    }

    private Result<Void> require(City city, UUID actor, CityPermission permission) {
        if (city.isFrozen()) {
            return Result.failure("CITY_FROZEN", "city.frozen");
        }
        if (!city.hasPermission(actor, permission)) {
            return Result.failure("NO_PERMISSION", "city.no-permission",
                    Map.of("permission", permission.name()));
        }
        return Result.ok();
    }

    private FileConfiguration events() {
        return configs.get(ConfigFile.EVENTS);
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /** Every axis at the same score, for a quick vote and for the tests. */
    public static Map<VoteAxis, Integer> uniform(int score) {
        Map<VoteAxis, Integer> scores = new EnumMap<>(VoteAxis.class);
        for (VoteAxis axis : VoteAxis.all()) {
            scores.put(axis, score);
        }
        return scores;
    }
}
