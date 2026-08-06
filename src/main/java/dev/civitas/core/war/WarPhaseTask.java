package dev.civitas.core.war;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.income.StipendTask.Notifier;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.dao.WarDao;

/**
 * Moves a war through SPEC 11.2's lifecycle.
 *
 * <h2>Catching up</h2>
 * The same shape as the contest cycle, for the same reason and with more at stake. A war's
 * phases are wall-clock windows and the server is not obliged to be running when one ends. An
 * operator who takes the server down on day three of a seven-day war and brings it up on day
 * ten must find the war over and the damage restored, not still ACTIVE with the zone open.
 *
 * <p>So this never asks whether a boundary has just passed. It asks what phase the clock says
 * the war is in and walks it through every phase in between, which makes one missed boundary
 * and four take the same path.
 *
 * <h2>The two transitions that matter</h2>
 * PREP to ACTIVE is where the zone is computed and locked (SPEC 11.4), and it is the moment
 * damage starts being logged. ACTIVE to ROLLING_BACK is where the zone closes, the log freezes
 * and M18's engine takes over. Everything the plugin promises about war rests on those two
 * happening exactly once each.
 */
public final class WarPhaseTask implements Runnable {

    private final WarService wars;
    private final WarRegistry registry;
    private final WarDao dao;
    private final CityRegistry cities;
    private final Evacuation evacuation;
    private final RollbackTrigger rollback;
    private final Notifier notifier;
    private final ConfigManager configs;
    private final Logger logger;
    private final LongSupplier clock;

    /** What starts a rollback. M18's engine behind an interface, so this stays testable. */
    @FunctionalInterface
    public interface RollbackTrigger {

        /** Begins rolling back a finished war. */
        void begin(War war);

        /** A trigger that does nothing, for tests that are not about the rollback. */
        static RollbackTrigger none() {
            return war -> { };
        }
    }

    public WarPhaseTask(WarService wars, WarRegistry registry, WarDao dao, CityRegistry cities,
                        Evacuation evacuation, RollbackTrigger rollback, Notifier notifier,
                        ConfigManager configs, Logger logger, LongSupplier clock) {
        this.wars = Objects.requireNonNull(wars, "wars");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dao = Objects.requireNonNull(dao, "dao");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.evacuation = Objects.requireNonNull(evacuation, "evacuation");
        this.rollback = Objects.requireNonNull(rollback, "rollback");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void run() {
        try {
            tick(clock.getAsLong());
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "The war phase sweep failed; it will retry.", e);
        }
    }

    /** Package-visible and taking the time, so a seven-day war can be tested in a millisecond. */
    void tick(long now) {
        long declineWindow = declineWindowMillis();

        for (War war : List.copyOf(registry.all())) {
            WarState due = war.phaseAt(now, declineWindow);
            if (due == war.state()) {
                continue;
            }

            // Walk every phase in between. Skipping to the last one would miss computing the
            // zone, which means a war that started while the server was down would log
            // nothing and roll back nothing.
            WarState state = war.state();
            int guard = 0;
            while (state != due && guard++ < WarState.values().length) {
                state = next(state);
                advanceTo(war, state, now);
            }
        }
    }

    private static WarState next(WarState state) {
        return switch (state) {
            case DECLARED -> WarState.PREP;
            case PREP -> WarState.ACTIVE;
            case ACTIVE -> WarState.ROLLING_BACK;
            default -> state;
        };
    }

    private void advanceTo(War war, WarState state, long now) {
        switch (state) {
            case PREP -> beginPrep(war);
            case ACTIVE -> beginActive(war);
            case ROLLING_BACK -> beginRollback(war, now);
            default -> { }
        }
    }

    /** SPEC 11.5: the decline window has closed and the war is really happening. */
    private void beginPrep(War war) {
        war.state(WarState.PREP);
        persist(war);
        announce(war, "war.announce.prep",
                LangManager.placeholder("hours", String.valueOf(wars.prepHours())));
    }

    /**
     * SPEC 11.6 begins, and with it the only state in which anything can be destroyed.
     *
     * <p>The zone is computed here and never again. SPEC 11.4 makes it immutable and SPEC 6.3
     * precondition 9 stops the claims underneath it moving, so a zone taken at this moment is
     * the zone for the whole war.
     */
    private void beginActive(War war) {
        war.zone(wars.computeZone(war));
        war.state(WarState.ACTIVE);
        persist(war);

        // SPEC 11.6's capture points, placed once alongside the zone and for the same reason:
        // both describe the ground the war is fought over, and neither may move under it.
        if (capturePoints != null) {
            cities.city(war.defenderCityId()).ifPresent(defender -> {
                int placed = capturePoints.generate(war, wars.claimsOf(defender.id()),
                        capturePointCount(), defender.coreChunkX(), defender.coreChunkZ()).size();
                logger.info("War " + war.id() + " has " + placed + " capture point(s).");
            });
        }

        logger.info("War " + war.id() + " is active. Zone: " + war.zone().size()
                + " chunks across " + war.zone().worlds().size() + " world(s).");
        announce(war, "war.announce.active",
                LangManager.placeholder("days", String.valueOf(wars.activeDays())));
    }

    /**
     * SPEC 11.8.2 steps 1 and 2, then the engine.
     *
     * <p>Order matters and is not negotiable: everyone is moved out before any block moves,
     * and the log is closed before the replay starts reading it. A player left inside would be
     * standing in a wall the moment the restore reached them, and an entry accepted after the
     * replay passed its position would survive the rollback meant to undo it.
     */
    private void beginRollback(War war, long now) {
        war.state(WarState.ROLLING_BACK);
        persist(war);

        // SPEC 11.9 before SPEC 11.8.2, deliberately. A war is settled the moment it ends,
        // not when the restore finishes: a player who lost should be told they lost rather
        // than waiting on a rollback, and if the rollback runs into trouble the result is
        // already on the record.
        if (resolution != null) {
            resolution.resolve(war, now).thenAccept(result -> {
                if (result instanceof dev.civitas.util.Result.Success<WarResolution.Outcome>(
                        WarResolution.Outcome outcome)) {
                    announceOutcome(war, outcome);
                }
            });
        }

        int moved = evacuation.evacuate(war);
        if (moved > 0) {
            logger.info("Evacuated " + moved + " player(s) from the zone of war " + war.id()
                    + " before restoring it.");
        }

        announce(war, "war.announce.rolling-back");
        rollback.begin(war);
    }

    private void persist(War war) {
        try {
            dao.updateState(war.id(), war.state().key()).exceptionally(error -> {
                logger.log(Level.SEVERE, "Could not record war " + war.id() + " as "
                        + war.state() + ". It will be reconciled on the next sweep.", error);
                return 0;
            });
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not record war " + war.id() + " as " + war.state(), e);
        }
    }

    /** Tells everyone in every city party to the war. */
    private void announce(War war, String key,
                          net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
        for (int cityId : war.side(true)) {
            tellCity(cityId, key, extra);
        }
        for (int cityId : war.side(false)) {
            tellCity(cityId, key, extra);
        }
    }

    private void tellCity(int cityId, String key,
                          net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
        City city = cities.city(cityId).orElse(null);
        if (city == null) {
            return;
        }
        for (var member : city.members()) {
            notifier.tell(member.uuid(), key, extra);
        }
    }

    private WarResolution resolution;
    private CapturePoints capturePoints;

    /** SPEC 11.6's capture points, generated when a war goes ACTIVE. */
    public void useCapturePoints(CapturePoints points) {
        this.capturePoints = points;
    }

    private int capturePointCount() {
        return configs.get(ConfigFile.WAR).getInt("scoring.capture-points", 3);
    }

    /** SPEC 11.9's payouts, wired after construction because it needs the treasury. */
    public void useResolution(WarResolution warResolution) {
        this.resolution = warResolution;
    }

    private void announceOutcome(War war, WarResolution.Outcome outcome) {
        if (outcome.isDraw()) {
            announce(war, "war.announce.draw",
                    LangManager.placeholder("attacker", String.valueOf(war.attackerScore())),
                    LangManager.placeholder("defender", String.valueOf(war.defenderScore())));
            return;
        }
        announce(war, "war.announce.won",
                LangManager.placeholder("attacker", String.valueOf(war.attackerScore())),
                LangManager.placeholder("defender", String.valueOf(war.defenderScore())),
                LangManager.placeholder("prize", outcome.winnerReturn().toPlainString()));
    }

    long declineWindowMillis() {
        return configs.get(ConfigFile.WAR)
                .getLong("declaration.decline-window-hours", 6) * 3_600_000L;
    }
}
