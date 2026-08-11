package dev.civitas.core.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dev.civitas.core.progression.LeaderboardType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 35's season, and the one structural finding this milestone produced.
 *
 * <p>{@link WhatResets} is the substance. SPEC 35.2 says a season resets "leaderboard rankings
 * only" and lists balances, treasuries, claims and member rosters among the things it never
 * touches — and for four of the nine boards those are <b>the same number</b>. A season cannot
 * reset Wealth without resetting a balance, because Wealth <em>is</em> a balance.
 */
class SeasonTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long START = 1_700_000_000_000L;

    private static Season running() {
        return new Season(1, "Foundations", "Exploration", START, START + 90 * DAY,
                Season.State.RUNNING, null);
    }

    @Nested
    @DisplayName("what a season resets, and what it structurally cannot")
    class WhatResets {

        @Test
        @DisplayName("the five flow boards reset")
        void flowsReset() {
            // These count things that happened, so there is a window to measure them over.
            assertTrue(Season.resets(LeaderboardType.CONTRIBUTION));
            assertTrue(Season.resets(LeaderboardType.BUILDER));
            assertTrue(Season.resets(LeaderboardType.FARMER));
            assertTrue(Season.resets(LeaderboardType.CONTEST_CHAMPIONS));
            assertTrue(Season.resets(LeaderboardType.WAR_RECORD));
        }

        @Test
        @DisplayName("the four stock boards do not, because resetting one would take something")
        void stocksDoNot() {
            // Wealth IS a balance; City Treasury IS a treasury; City Size IS a claim count; City
            // Population IS a member roster. SPEC 35.2 lists all four among the things a season
            // never touches, in bold. A stock has no "since" — it is what it is right now.
            assertFalse(Season.resets(LeaderboardType.WEALTH));
            assertFalse(Season.resets(LeaderboardType.CITY_TREASURY));
            assertFalse(Season.resets(LeaderboardType.CITY_SIZE));
            assertFalse(Season.resets(LeaderboardType.CITY_POPULATION));
        }

        @Test
        @DisplayName("every board is classified, so a tenth cannot be forgotten")
        void everyBoardIsDecided() {
            // The switch is exhaustive over the enum, so adding a board is a compile error until
            // somebody decides which kind it is. Asserted anyway, because the count is the thing
            // a reader wants: five of nine.
            List<LeaderboardType> seasonal = Season.seasonalBoards();

            assertEquals(5, seasonal.size());
            assertEquals(LeaderboardType.values().length,
                    seasonal.size() + (int) java.util.Arrays.stream(LeaderboardType.values())
                            .filter(board -> !Season.resets(board)).count());
        }
    }

    @Nested
    @DisplayName("the clock")
    class Clock {

        @Test
        @DisplayName("day one is day one, not day zero")
        void dayOne() {
            assertEquals(1, running().dayOf(START));
            assertEquals(1, running().dayOf(START + DAY - 1));
            assertEquals(2, running().dayOf(START + DAY));
            assertEquals(90, running().dayOf(START + 89 * DAY));
        }

        @Test
        @DisplayName("a season expires when its clock runs out, and not before")
        void expiry() {
            assertFalse(running().hasExpired(START + 89 * DAY));
            assertTrue(running().hasExpired(START + 90 * DAY));
        }

        @Test
        @DisplayName("a finished season never expires again")
        void finishedNeverExpires() {
            Season finished = new Season(1, "Foundations", "Exploration", START, START + 90 * DAY,
                    Season.State.FINISHED, START + 90 * DAY);

            assertFalse(finished.hasExpired(START + 900 * DAY));
        }

        @Test
        @DisplayName("time remaining floors at zero rather than going negative")
        void remainingFloors() {
            assertEquals(0, running().millisRemaining(START + 200 * DAY));
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        @DisplayName("a season cannot end before it starts")
        void backwards() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Season(1, "Bad", "Theme", START, START - DAY,
                            Season.State.RUNNING, null));
        }
    }
}
