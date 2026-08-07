package dev.civitas.core.admin;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.dao.UpkeepMultiplierDao;
import dev.civitas.storage.row.UpkeepMultiplierRow;

/**
 * SPEC 9.4.2's {@code /ca city setupkeep}: a per-city upkeep multiplier.
 *
 * <p>SPEC gives the example of "a returning-player grace period", which is the shape of the
 * whole feature: a city that stopped playing and came back to a debt it cannot clear is a city
 * that quits, and SPEC 1.3 cares about that more than about collecting the upkeep.
 *
 * <p>Cache-first for the same reason as everything else on this path: the upkeep sweep runs
 * over every city and may not touch storage per city.
 */
public final class UpkeepOverrides {

    private final UpkeepMultiplierDao dao;
    private final Logger logger;

    private final Map<Integer, UpkeepMultiplierRow> overrides = new ConcurrentHashMap<>();

    public UpkeepOverrides(UpkeepMultiplierDao dao, Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<Integer> loadAll() {
        return dao.findAll().thenApply(rows -> {
            overrides.clear();
            rows.forEach(row -> overrides.put(row.cityId(), row));
            return rows.size();
        }).exceptionally(error -> {
            // Failing to the ordinary rate is the safe direction: a city is charged what SPEC
            // 4.3 says, which is never worse than what it agreed to when it founded.
            logger.log(Level.WARNING, "Could not load upkeep overrides; every city will be "
                    + "charged the ordinary rate until this is resolved.", error);
            return 0;
        });
    }

    /**
     * The multiplier for a city right now.
     *
     * <p>One for a city with no override, and one for an override that has expired: SPEC calls
     * this temporary, and an expiry that needed a sweep to take effect would not be.
     */
    public BigDecimal multiplierFor(int cityId, long now) {
        UpkeepMultiplierRow row = overrides.get(cityId);
        if (row == null || !row.isActive(now)) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(row.multiplier());
    }

    public CompletableFuture<Integer> set(int cityId, double multiplier, UUID by) {
        return set(cityId, multiplier, by, null, null);
    }

    public CompletableFuture<Integer> set(int cityId, double multiplier, UUID by,
                                          Long expiresAt, String reason) {
        UpkeepMultiplierRow row = new UpkeepMultiplierRow(cityId, multiplier, by,
                System.currentTimeMillis(), expiresAt, reason);
        return dao.set(row).thenApply(written -> {
            overrides.put(cityId, row);
            return written;
        });
    }

    public CompletableFuture<Integer> clear(int cityId) {
        return dao.clear(cityId).thenApply(removed -> {
            overrides.remove(cityId);
            return removed;
        });
    }

    /** Dropped when a city is disbanded, so a reused id inherits nothing. */
    public void forgetCity(int cityId) {
        overrides.remove(cityId);
    }

    public int count() {
        return overrides.size();
    }
}
