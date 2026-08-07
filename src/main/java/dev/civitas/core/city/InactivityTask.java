package dev.civitas.core.city;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.Result;

/**
 * SPEC 17.1 cases 1, 2 and 3, the city inactivity sweep.
 *
 * <p>This was deferred twice and then lost. M2 put it off to M4 on the grounds that cases 2
 * and 3 turn on claims becoming unprotected, which is land protection; M4 delivered the
 * protection-side half and recorded that "the sweep that <i>sets</i> dormancy is still unowned
 * and needs a home in a later milestone". It never got one. {@code cities.yml} has shipped an
 * {@code inactivity:} block carrying all four of SPEC's numbers, commented "SPEC 17.1 cases 1,
 * 2 and 3", with nothing anywhere reading a single one of them.
 *
 * <h2>The three rules</h2>
 *
 * <ul>
 *   <li><b>Case 1, 30 days.</b> An absent mayor is replaced by "the highest-weight member with
 *       the most recent login", and demoted to the rank below. See
 *       {@link #chooseSuccessor} for how those two words are ordered.</li>
 *   <li><b>Case 2, 60 days.</b> Nobody has logged in, so the city's claims stop being
 *       protected — but nothing is removed, and any member logging in restores protection
 *       instantly. Held in {@link DormancyCache}, not in a column.</li>
 *   <li><b>Case 3, 120 days.</b> The city is soft-deleted, its land released and its treasury
 *       burned, with SPEC 5.3's fourteen-day restore window still on the row.</li>
 * </ul>
 *
 * <h2>Ordering, which is not arbitrary</h2>
 *
 * <p>Expiry first, then succession, then dormancy. A city past 120 days is also past 30 and
 * 60, and handing a dead city a new mayor a moment before deleting it would write two rows and
 * an audit entry for a thing that no longer exists. Succession before dormancy for the same
 * reason in reverse: a city that gets a new mayor is still dormant if nobody has logged in,
 * and the new mayor logging in is what ends that.
 *
 * <h2>Activity means a login, not a session</h2>
 *
 * <p>Measured on {@code players.last_seen}, which is every member's most recent time on the
 * server — not the anti-AFK {@code active_playtime_ms} that SPEC 4.2.1 uses for income. These
 * three rules are about abandonment, and somebody who logs in weekly to stand in their city
 * has not abandoned it, whatever the stipend thinks of them.
 */
public final class InactivityTask implements Runnable {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final DaoRegistry daos;
    private final CityRegistry cities;
    private final CityService service;
    private final DormancyCache dormancy;
    private final ConfigManager configs;
    private final Logger logger;

    /** Stops a slow sweep being started again on top of itself, as {@code UpkeepTask} does. */
    private final AtomicBoolean running = new AtomicBoolean();

    public InactivityTask(DaoRegistry daos, CityRegistry cities, CityService service,
                          DormancyCache dormancy, ConfigManager configs, Logger logger) {
        this.daos = Objects.requireNonNull(daos, "daos");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.service = Objects.requireNonNull(service, "service");
        this.dormancy = Objects.requireNonNull(dormancy, "dormancy");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Configuration, SPEC 17.1
    // ==================================================================================

    public boolean enabled() {
        return section().getBoolean("enabled", true);
    }

    /**
     * Whether case 3 may delete a city.
     *
     * <p>Its own switch, separate from {@link #enabled}, because it is by a distance the most
     * destructive thing this plugin does without a human asking: it fires on a timer, against
     * cities whose members may simply have had a long summer. An operator who wants the mayor
     * succession and the dormancy flag but not the deletion should not have to give up all
     * three. Defaults on, because that is what SPEC 17.1 case 3 says happens.
     */
    public boolean deletionEnabled() {
        return section().getBoolean("soft-delete-enabled", true);
    }

    public long mayorTransferDays() {
        return section().getLong("mayor-transfer-days", 30);
    }

    public long dormantDays() {
        return section().getLong("dormant-days", 60);
    }

    public long softDeleteDays() {
        return section().getLong("soft-delete-days", 120);
    }

    public long checkIntervalMinutes() {
        return section().getLong("check-interval-minutes", 60);
    }

    private org.bukkit.configuration.ConfigurationSection section() {
        org.bukkit.configuration.ConfigurationSection inactivity =
                configs.get(ConfigFile.CITIES).getConfigurationSection("inactivity");
        return inactivity != null ? inactivity
                : configs.get(ConfigFile.CITIES).createSection("inactivity");
    }

    // ==================================================================================
    // The sweep
    // ==================================================================================

    @Override
    public void run() {
        if (!enabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            logger.fine("Inactivity sweep skipped: the previous one is still running.");
            return;
        }
        try {
            sweep(System.currentTimeMillis());
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "The inactivity sweep failed", e);
        } finally {
            running.set(false);
        }
    }

    /** What one sweep did, for the tests and for the log line. */
    public record Outcome(int expired, int mayorsReplaced, int dormant) {

        static Outcome nothing() {
            return new Outcome(0, 0, 0);
        }

        boolean isAnything() {
            return expired > 0 || mayorsReplaced > 0 || dormant > 0;
        }
    }

    /**
     * Applies all three rules.
     *
     * <p>One query for every player who is in a city, rather than one per city: a server with
     * two hundred cities would otherwise make two hundred round trips every interval to
     * discover that nothing had changed.
     */
    public Outcome sweep(long now) {
        if (!enabled()) {
            return Outcome.nothing();
        }
        List<PlayerRow> members;
        try {
            members = daos.players().findAllWithCity().join();
        } catch (RuntimeException e) {
            // A closed pool throws out of the call itself rather than failing the future, the
            // trap recorded at M18. Failing here leaves the cache untouched, which fails open.
            logger.log(Level.WARNING, "Inactivity sweep could not read players", e);
            return Outcome.nothing();
        }

        Map<Integer, Long> lastSeenByCity = new HashMap<>();
        Map<UUID, Long> lastSeenByPlayer = new HashMap<>();
        for (PlayerRow row : members) {
            lastSeenByPlayer.put(row.uuid(), row.lastSeen());
            if (row.cityId() != null) {
                lastSeenByCity.merge(row.cityId(), row.lastSeen(), Math::max);
            }
        }

        int expired = 0;
        int replaced = 0;
        Set<Integer> dormant = new LinkedHashSet<>();

        for (City city : List.copyOf(cities.cities())) {
            if (city.isDeleted()) {
                continue;
            }
            // A city with no members at all has never been seen. Its founding date is the only
            // activity it can claim, and treating that as "seen" is what stops a city founded
            // and instantly abandoned from being deleted before anyone could join it.
            long lastSeen = lastSeenByCity.getOrDefault(city.id(), city.foundedAt());

            try {
                if (deletionEnabled() && olderThan(lastSeen, now, softDeleteDays())) {
                    if (expire(city, now)) {
                        expired++;
                    }
                    continue;
                }
                if (replaceMayorIfAbsent(city, lastSeenByPlayer, now)) {
                    replaced++;
                }
                if (olderThan(lastSeen, now, dormantDays())) {
                    dormant.add(city.id());
                }
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Inactivity sweep failed for city " + city.name(), e);
            }
        }

        dormancy.replaceAll(dormant);

        Outcome outcome = new Outcome(expired, replaced, dormant.size());
        if (outcome.isAnything()) {
            logger.info("Inactivity sweep: " + expired + " cities expired, " + replaced
                    + " mayors replaced, " + dormant.size() + " dormant.");
        }
        return outcome;
    }

    private static boolean olderThan(long lastSeen, long now, long days) {
        return days > 0 && now - lastSeen >= days * MILLIS_PER_DAY;
    }

    // ==================================================================================
    // Case 1, the absent mayor
    // ==================================================================================

    private boolean replaceMayorIfAbsent(City city, Map<UUID, Long> lastSeen, long now) {
        long mayorSeen = lastSeen.getOrDefault(city.mayorUuid(), city.foundedAt());
        if (!olderThan(mayorSeen, now, mayorTransferDays())) {
            return false;
        }
        Optional<UUID> successor = chooseSuccessor(city, lastSeen, now);
        if (successor.isEmpty()) {
            // Nobody eligible. A one-member city has nobody to promote, and SPEC 17.1 case 4
            // already refuses to let a sole mayor leave — so it simply waits, and case 3 will
            // reach it eventually if nobody ever comes back.
            return false;
        }
        Result<City> result = service.transferInactiveMayor(city, successor.get(), now).join();
        if (result instanceof Result.Failure<City> failure) {
            logger.warning("Could not replace the inactive mayor of " + city.name() + ": "
                    + failure.reason());
            return false;
        }
        return true;
    }

    /**
     * SPEC 17.1 case 1's "the highest-weight member with the most recent login".
     *
     * <p>Read as weight first and recency as the tiebreak, which is the order SPEC writes them
     * in. It also gives the answer a city would want: the point is to hand the city to whoever
     * was most trusted with it, and among equals to whoever is most likely to be there
     * tomorrow. Reading it the other way — most recent login, then weight — would hand a
     * Recruit who logged in yesterday a city over a Co-Mayor who logged in last week.
     *
     * <p>Candidates who are themselves past the inactivity threshold are skipped, because
     * promoting one would leave the city in the state this rule exists to fix and the sweep
     * would do it all again next interval.
     */
    Optional<UUID> chooseSuccessor(City city, Map<UUID, Long> lastSeen, long now) {
        List<CityMember> candidates = new ArrayList<>();
        for (CityMember member : city.members()) {
            if (member.uuid().equals(city.mayorUuid())) {
                continue;
            }
            if (olderThan(lastSeen.getOrDefault(member.uuid(), 0L), now, mayorTransferDays())) {
                continue;
            }
            candidates.add(member);
        }
        return candidates.stream()
                .max(Comparator
                        .comparingInt((CityMember member) -> weightOf(city, member))
                        .thenComparingLong(member -> lastSeen.getOrDefault(member.uuid(), 0L)))
                .map(CityMember::uuid);
    }

    private static int weightOf(City city, CityMember member) {
        return city.rank(member.rankId()).map(CityRank::weight).orElse(0);
    }

    // ==================================================================================
    // Case 3, expiry
    // ==================================================================================

    private boolean expire(City city, long now) {
        Result<City> result = service.expireInactive(city, now).join();
        if (result instanceof Result.Failure<City> failure) {
            logger.warning("Could not expire the inactive city " + city.name() + ": "
                    + failure.reason());
            return false;
        }
        logger.info("City " + city.name() + " expired after "
                + softDeleteDays() + " days without a login. Its land is released and its "
                + "treasury burned; an admin may restore the row for "
                + configs.get(ConfigFile.CITIES).getLong("admin.restore-window-days", 14)
                + " days.");
        return true;
    }
}
