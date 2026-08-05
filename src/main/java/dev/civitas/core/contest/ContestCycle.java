package dev.civitas.core.contest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.income.StipendTask.Notifier;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;

/**
 * The automatic 14-day cycle of SPEC 13.4.
 *
 * <h2>Catching up</h2>
 * The one thing this has to get right is a boundary that passed while nobody was watching.
 * SPEC 13.4's phases are wall-clock days, and a server is not obliged to be running when one
 * ends: an operator who takes the server down on day 10 and brings it up on day 15 must find
 * the contest scored and the prizes paid, not a contest still accepting submissions.
 *
 * <p>So this never asks "has the next boundary just passed". It asks what phase the clock says
 * the contest is in, and walks it through every phase between there and here, in order. One
 * missed boundary and four missed boundaries take the same path. This is the same problem SPEC
 * 17.3 case 31 poses for upkeep, in a different place.
 */
public final class ContestCycle implements Runnable {

    private static final long MILLIS_PER_DAY = TimeUnit.DAYS.toMillis(1);

    private final ContestService contests;
    private final CityRegistry cities;
    private final Notifier notifier;
    private final Logger logger;
    private final LongSupplier clock;

    /** Rotates the theme pool, so a restart does not always pick the same one. */
    private int themeCursor;

    public ContestCycle(ContestService contests, CityRegistry cities, Notifier notifier,
                        Logger logger, LongSupplier clock) {
        this.contests = Objects.requireNonNull(contests, "contests");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void run() {
        try {
            tick(clock.getAsLong());
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "The contest cycle failed; it will retry on the next sweep.", e);
        }
    }

    /**
     * Advances the contest to wherever the clock says it should be.
     *
     * <p>Package-visible and taking the time as an argument so the whole cycle can be tested
     * by moving a number, without waiting fourteen real days.
     */
    void tick(long now) {
        if (!contests.isEnabled()) {
            return;
        }

        Optional<Contest> running = contests.current();
        if (running.isEmpty()) {
            startNext(now);
            return;
        }

        Contest contest = running.get();
        ContestState due = contest.phaseAt(now);
        if (due == contest.state()) {
            return;
        }

        // Walk every phase between where it is recorded and where it should be. Skipping
        // straight to the last one would miss the announcement of each phase in between, and
        // would miss the scoring entirely if the server was down across day 14.
        ContestState state = contest.state();
        while (state != due) {
            state = next(state);
            contest = advanceTo(contest, state, now);
            if (contest == null) {
                return;
            }
        }

        if (state == ContestState.FINISHED) {
            startNext(now);
        }
    }

    private static ContestState next(ContestState state) {
        return switch (state) {
            case BUILDING -> ContestState.VOTING;
            case VOTING -> ContestState.SCORING;
            case SCORING, FINISHED -> ContestState.FINISHED;
        };
    }

    /** @return the contest in its new state, or null if the step could not complete */
    private Contest advanceTo(Contest contest, ContestState state, long now) {
        return switch (state) {
            case VOTING -> {
                contests.remember(contest.withState(ContestState.VOTING));
                record(contest.id(), ContestState.VOTING);
                announce("contest.announce.voting-open",
                        LangManager.placeholder("theme", contest.theme()));
                yield contest.withState(ContestState.VOTING);
            }
            case SCORING -> {
                contests.remember(contest.withState(ContestState.SCORING));
                record(contest.id(), ContestState.SCORING);
                yield contest.withState(ContestState.SCORING);
            }
            case FINISHED -> {
                Result<List<ContestService.Placement>> scored =
                        contests.score(contest.withState(ContestState.SCORING)).join();
                if (scored instanceof Result.Failure<List<ContestService.Placement>> failure) {
                    logger.warning("Could not score contest " + contest.id() + ": "
                            + failure.reason() + "; it will be retried.");
                    yield null;
                }
                announceResults(contest, scored.orElseThrow());
                yield contest.withState(ContestState.FINISHED);
            }
            case BUILDING -> contest;
        };
    }

    private void record(int contestId, ContestState state) {
        contests.persistState(contestId, state);
    }

    /** SPEC 13.4 step 1: a theme is announced and a new cycle begins. */
    private void startNext(long now) {
        List<String> themes = contests.themes();
        String theme = themes.get(Math.floorMod(themeCursor++, themes.size()));

        Result<Contest> started = contests.start(theme, contests.cycleDays(), now).join();
        if (started instanceof Result.Failure<Contest> failure) {
            logger.warning("Could not start a contest: " + failure.reason());
            return;
        }

        announce("contest.announce.started",
                LangManager.placeholder("theme", theme),
                LangManager.placeholder("days", String.valueOf(contests.buildDays())));
    }

    private void announceResults(Contest contest, List<ContestService.Placement> placements) {
        if (placements.isEmpty()) {
            announce("contest.announce.no-entries",
                    LangManager.placeholder("theme", contest.theme()));
            return;
        }
        announce("contest.announce.results",
                LangManager.placeholder("theme", contest.theme()));

        for (ContestService.Placement placement : placements) {
            if (placement.prize().signum() <= 0) {
                continue;
            }
            String cityName = cities.city(placement.entry().cityId())
                    .map(City::name)
                    .orElse("?");
            announce("contest.announce.placed",
                    LangManager.placeholder("place", String.valueOf(placement.place())),
                    LangManager.placeholder("city", cityName),
                    LangManager.placeholder("score", String.format("%.2f", placement.score())),
                    LangManager.placeholder("prize", placement.prize().toPlainString()));
        }
    }

    /**
     * Tells everybody in a city, for every city.
     *
     * <p>SPEC 13.4 says "broadcast server-wide". This reaches the same people through the
     * notifier the other scheduled systems already use, which knows how to skip a player who
     * is not online rather than assuming one is.
     */
    private void announce(String key,
                          net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
        for (City city : cities.cities()) {
            for (var member : city.members()) {
                notifier.tell(member.uuid(), key, extra);
            }
        }
    }

    /** How long a whole cycle lasts, for the scheduler that drives this. */
    public long cycleMillis() {
        return contests.cycleDays() * MILLIS_PER_DAY;
    }
}
