package dev.civitas.core.diplomacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.storage.dao.AllianceDao;
import dev.civitas.storage.dao.TruceDao;
import dev.civitas.storage.row.AllianceRow;
import dev.civitas.storage.row.TruceRow;

/**
 * Every relation, in memory.
 *
 * <p>Cached because SPEC 14.2's trust grant is read on the block-protection path: once two
 * cities trust each other, every block a player breaks in an allied claim asks whether they
 * are allowed to. That cannot be a database round trip, and it is the reason this registry
 * exists rather than the service reading the DAOs directly.
 */
public final class DiplomacyRegistry {

    private final AllianceDao alliances;
    private final TruceDao truces;

    /** Pair key to alliance, including broken ones, which the cooldown still needs. */
    private final Map<String, Alliance> byPair = new ConcurrentHashMap<>();

    /** Pair key to when the truce ends. */
    private final Map<String, Long> truceUntil = new ConcurrentHashMap<>();

    public DiplomacyRegistry(AllianceDao alliances, TruceDao truces) {
        this.alliances = Objects.requireNonNull(alliances, "alliances");
        this.truces = Objects.requireNonNull(truces, "truces");
    }

    /** @return how many alliance rows exist */
    public CompletableFuture<Integer> loadAll(long now) {
        return alliances.findAll().thenCompose(rows -> {
            byPair.clear();
            for (AllianceRow row : rows) {
                Alliance alliance = Alliance.from(row);
                byPair.put(alliance.key(), alliance);
            }
            return truces.findAllActive(now).thenApply(active -> {
                truceUntil.clear();
                for (TruceRow truce : active) {
                    truceUntil.put(Alliance.key(truce.cityAId(), truce.cityBId()),
                            truce.expiresAt());
                }
                return byPair.size();
            });
        });
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    public Optional<Alliance> alliance(int cityId, int otherCityId) {
        return Optional.ofNullable(byPair.get(Alliance.key(cityId, otherCityId)));
    }

    /** Whether these two are allied right now, notice period included. */
    public boolean areAllied(int cityId, int otherCityId) {
        return alliance(cityId, otherCityId).filter(Alliance::isAllied).isPresent();
    }

    /** Whether these two have turned on SPEC 14.2's reciprocal build access. */
    public boolean areTrusted(int cityId, int otherCityId) {
        return alliance(cityId, otherCityId)
                .filter(Alliance::isAllied)
                .filter(Alliance::trusted)
                .isPresent();
    }

    /** A city's alliances in any state. */
    public List<Alliance> allianceOf(int cityId) {
        List<Alliance> found = new ArrayList<>();
        for (Alliance alliance : byPair.values()) {
            if (alliance.involves(cityId)) {
                found.add(alliance);
            }
        }
        return found;
    }

    /** Only the ones that count toward the SPEC 14.2 cap of three. */
    public List<Alliance> activeAlliancesOf(int cityId) {
        return allianceOf(cityId).stream().filter(Alliance::isAllied).toList();
    }

    public int allyCount(int cityId) {
        return activeAlliancesOf(cityId).size();
    }

    /** Proposals this city has been sent and not yet answered. */
    public List<Alliance> pendingFor(int cityId) {
        return allianceOf(cityId).stream()
                .filter(alliance -> alliance.state() == AllianceState.PENDING)
                .filter(alliance -> alliance.proposedBy() != cityId)
                .toList();
    }

    // ==================================================================================
    // Truces
    // ==================================================================================

    /** When a truce between these two ends, if one is running. */
    public Optional<Long> truceUntil(int cityId, int otherCityId, long now) {
        Long until = truceUntil.get(Alliance.key(cityId, otherCityId));
        return until != null && until > now ? Optional.of(until) : Optional.empty();
    }

    public boolean hasTruce(int cityId, int otherCityId, long now) {
        return truceUntil(cityId, otherCityId, now).isPresent();
    }

    /**
     * A city's running truces, as the other city and when it ends.
     *
     * <p>The key is parsed back into its two ids rather than matched as text: "12" appears
     * inside "112:200" and a string match would report a truce the city is not part of.
     */
    public List<Truce> trucesOf(int cityId, long now) {
        List<Truce> found = new ArrayList<>();
        for (Map.Entry<String, Long> entry : truceUntil.entrySet()) {
            if (entry.getValue() <= now) {
                continue;
            }
            String[] parts = entry.getKey().split(":");
            if (parts.length != 2) {
                continue;
            }
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            if (first == cityId) {
                found.add(new Truce(second, entry.getValue()));
            } else if (second == cityId) {
                found.add(new Truce(first, entry.getValue()));
            }
        }
        found.sort((left, right) -> Long.compare(left.expiresAt(), right.expiresAt()));
        return found;
    }

    /** A truce as one city sees it. */
    public record Truce(int otherCityId, long expiresAt) { }

    // ==================================================================================
    // Writing
    // ==================================================================================

    public void put(Alliance alliance) {
        byPair.put(alliance.key(), alliance);
    }

    public void remove(int cityId, int otherCityId) {
        byPair.remove(Alliance.key(cityId, otherCityId));
    }

    public void putTruce(int cityId, int otherCityId, long expiresAt) {
        truceUntil.put(Alliance.key(cityId, otherCityId), expiresAt);
    }

    public void removeTruce(int cityId, int otherCityId) {
        truceUntil.remove(Alliance.key(cityId, otherCityId));
    }

    /** Drops truces that have run out. They are left in the table, see DiplomacyTask. */
    public int forgetExpiredTruces(long now) {
        int before = truceUntil.size();
        truceUntil.values().removeIf(until -> until <= now);
        return before - truceUntil.size();
    }

    /** Drops everything about a disbanded city. */
    public void forgetCity(int cityId) {
        byPair.values().removeIf(alliance -> alliance.involves(cityId));
        truceUntil.keySet().removeIf(key -> {
            String[] parts = key.split(":");
            return parts.length == 2
                    && (Integer.parseInt(parts[0]) == cityId
                            || Integer.parseInt(parts[1]) == cityId);
        });
    }

    /** Every alliance, for the sweep that completes notice periods. */
    public List<Alliance> all() {
        return List.copyOf(byPair.values());
    }

    public int total() {
        return byPair.size();
    }

    public int activeTruces() {
        return truceUntil.size();
    }
}
