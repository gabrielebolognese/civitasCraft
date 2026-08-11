package dev.civitas.core.admin;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.ServerStatsRow;

/**
 * SPEC 36.6's daily server statistics.
 *
 * <p>SPEC 36.6 says what they are for, and it is not vanity: a server owner sees these "so they
 * can see trends, which is the <b>only way to notice retention problems before they are
 * terminal</b>."
 *
 * <p>So the numbers here are chosen to answer one question — is this server growing or dying —
 * rather than to be impressive. Active-in-7-days against active-in-30 is the ratio that shows a
 * server bleeding regulars while its registration count still climbs, which is exactly the failure
 * SPEC describes as slow and confusing to diagnose.
 */
public final class ServerStatsService {

    private static final long DAY = 24L * 60 * 60 * 1000;

    private final DaoRegistry daos;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final Logger logger;

    public ServerStatsService(DaoRegistry daos, CityRegistry cities, ClaimRegistry claims,
                              Logger logger) {
        this.daos = Objects.requireNonNull(daos, "daos");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Takes today's reading.
     *
     * <p>Keyed on midnight, so a second sweep on the same day replaces the first rather than
     * adding a second reading of the same thing — which would make any differencing over the
     * table wrong in a way nobody would notice.
     */
    public CompletableFuture<ServerStatsRow> record(long now) {
        long dayStart = now - Math.floorMod(now, DAY);

        return daos.players().countAll().thenCompose(registered ->
                daos.players().countSeenSince(now - 7 * DAY).thenCompose(active7 ->
                        daos.players().countSeenSince(now - 30 * DAY).thenCompose(active30 -> {
                            int cityCount = cities.cities().size();
                            int claimCount = claims.allClaims().size();
                            double average = cityCount == 0 ? 0 : (double) claimCount / cityCount;

                            ServerStatsRow row = new ServerStatsRow(dayStart, registered, active7,
                                    active30, cityCount, claimCount, average, 0, 0);
                            return daos.serverStats().upsert(row).thenApply(ignored -> row);
                        })));
    }

    public CompletableFuture<List<ServerStatsRow>> history(int days) {
        return daos.serverStats().findSince(System.currentTimeMillis() - (long) days * DAY);
    }

    public CompletableFuture<java.util.Optional<ServerStatsRow>> latest() {
        return daos.serverStats().findLatest();
    }

    /** Logs rather than throwing, for the scheduled caller. */
    public void recordQuietly(long now) {
        try {
            record(now).exceptionally(error -> {
                logger.log(Level.WARNING, "Could not record the daily server statistics", error);
                return null;
            });
        } catch (RuntimeException e) {
            // db.call throws synchronously on a closed pool; exceptionally alone is not enough.
            logger.log(Level.WARNING, "Could not record the daily server statistics", e);
        }
    }
}
