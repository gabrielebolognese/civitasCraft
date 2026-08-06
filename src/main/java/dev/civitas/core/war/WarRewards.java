package dev.civitas.core.war;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import dev.civitas.storage.dao.WarDao;
import dev.civitas.storage.row.WarRow;

/**
 * SPEC 11.9's winner bonus, held in memory because of where it is read.
 *
 * <p>"Winner gets a 7-day +10% market sell price bonus for its members." That multiplier is
 * consulted on every market sale, and SPEC 2.1 forbids a database round trip there, so the
 * answer lives in a map. The map is rebuilt at startup from the wars themselves rather than
 * from a separate column: the war already records who won and when its rollback finished, and
 * a second copy of that could disagree with the first.
 */
public final class WarRewards {

    private final WarDao dao;

    /** City id to the moment its market bonus expires. */
    private final Map<Integer, Long> marketBonusUntil = new ConcurrentHashMap<>();

    public WarRewards(WarDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    /**
     * Rebuilds the bonuses from recently won wars.
     *
     * <p>Called at startup. A restart in the middle of a winner's seven days must not quietly
     * end them: the city earned that bonus by winning.
     */
    public CompletableFuture<Integer> load(long now, long bonusDays) {
        long window = TimeUnit.DAYS.toMillis(bonusDays);
        return dao.findWonSince(now - window).thenApply(rows -> {
            for (WarRow row : rows) {
                if (row.winnerCityId() == null) {
                    continue;
                }
                // From when the war ended, matching the grant at resolution time.
                grant(row.winnerCityId(), row.warEndsAt() + window);
            }
            return marketBonusUntil.size();
        });
    }

    /** Starts a winner's seven days. */
    public void grant(int cityId, long until) {
        marketBonusUntil.merge(cityId, until, Math::max);
    }

    /** Whether this city's members are currently selling at the SPEC 11.9 bonus rate. */
    public boolean hasMarketBonus(int cityId, long now) {
        Long until = marketBonusUntil.get(cityId);
        return until != null && until > now;
    }

    /** Clears expired bonuses, so the map does not grow with every war ever fought. */
    public int prune(long now) {
        int before = marketBonusUntil.size();
        marketBonusUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        return before - marketBonusUntil.size();
    }

    public int size() {
        return marketBonusUntil.size();
    }
}
