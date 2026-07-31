package dev.civitas.core.city;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.CityBanRow;
import dev.civitas.storage.row.CityMemberRow;
import dev.civitas.storage.row.CityRankRow;
import dev.civitas.storage.row.CityRow;

/**
 * The in-memory city cache, SPEC 2.3.
 *
 * <p>Every read goes through here and never through a DAO. Membership and permission lookups
 * happen on the hot path of chat, block events and GUI clicks, so a database round trip for
 * "which city is this player in" would be felt immediately.
 *
 * <p>Loaded once at startup, then kept in step by {@link CityService}, which is the only
 * thing allowed to mutate it. Soft-deleted cities are not loaded: they are dead to the game
 * and only an admin restore brings them back.
 */
public final class CityRegistry {

    private final DaoRegistry daos;

    private final Map<Integer, City> byId = new ConcurrentHashMap<>();
    private final Map<String, Integer> byLowerName = new ConcurrentHashMap<>();
    private final Map<String, Integer> byLowerTag = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> byMember = new ConcurrentHashMap<>();

    public CityRegistry(DaoRegistry daos) {
        this.daos = Objects.requireNonNull(daos, "daos");
    }

    /**
     * Loads every live city with its ranks, members and bans.
     *
     * <p>Runs on the database pool during startup. Four queries in total rather than four per
     * city, because a server with 200 cities would otherwise open with 800 round trips.
     */
    public CompletableFuture<Integer> loadAll() {
        return daos.cities().findAllActive().thenCompose(this::loadInto);
    }

    private CompletableFuture<Integer> loadInto(List<CityRow> cityRows) {
        clear();
        for (CityRow row : cityRows) {
            register(City.fromRow(row));
        }

        CompletableFuture<Void> ranks = daos.cityRanks().findAll().thenAccept(rows -> {
            for (CityRankRow row : rows) {
                city(row.cityId()).ifPresent(city -> city.putRank(CityRank.fromRow(row)));
            }
        });

        CompletableFuture<Void> members = daos.cityMembers().findAll().thenAccept(rows -> {
            for (CityMemberRow row : rows) {
                city(row.cityId()).ifPresent(city -> {
                    city.putMember(CityMember.fromRow(row));
                    byMember.put(row.uuid(), row.cityId());
                });
            }
        });

        CompletableFuture<Void> bans = daos.cityBans().findAll().thenAccept(rows -> {
            for (CityBanRow row : rows) {
                city(row.cityId()).ifPresent(city -> city.putBan(row.bannedUuid(), row.reason()));
            }
        });

        return CompletableFuture.allOf(ranks, members, bans).thenApply(ignored -> byId.size());
    }

    // --- reads ------------------------------------------------------------------------

    public Optional<City> city(int cityId) {
        return Optional.ofNullable(byId.get(cityId));
    }

    /** Case-insensitive, matching the SPEC 5.1 uniqueness rule. */
    public Optional<City> cityByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        Integer id = byLowerName.get(name.toLowerCase(Locale.ROOT));
        return id == null ? Optional.empty() : city(id);
    }

    public Optional<City> cityByTag(String tag) {
        if (tag == null) {
            return Optional.empty();
        }
        Integer id = byLowerTag.get(tag.toLowerCase(Locale.ROOT));
        return id == null ? Optional.empty() : city(id);
    }

    /** The city a player belongs to, the single most-called lookup in the plugin. */
    public Optional<City> cityOf(UUID player) {
        Integer id = byMember.get(player);
        return id == null ? Optional.empty() : city(id);
    }

    public boolean isInAnyCity(UUID player) {
        return byMember.containsKey(player);
    }

    public Collection<City> cities() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }

    /** Whether a name is already taken, case-insensitively. */
    public boolean isNameTaken(String name) {
        return name != null && byLowerName.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public boolean isTagTaken(String tag) {
        return tag != null && byLowerTag.containsKey(tag.toLowerCase(Locale.ROOT));
    }

    // --- mutations, called only by CityService -----------------------------------------

    void register(City city) {
        byId.put(city.id(), city);
        byLowerName.put(city.name().toLowerCase(Locale.ROOT), city.id());
        if (city.tag() != null && !city.tag().isBlank()) {
            byLowerTag.put(city.tag().toLowerCase(Locale.ROOT), city.id());
        }
        for (CityMember member : city.members()) {
            byMember.put(member.uuid(), city.id());
        }
    }

    void unregister(City city) {
        byId.remove(city.id());
        byLowerName.remove(city.name().toLowerCase(Locale.ROOT));
        if (city.tag() != null) {
            byLowerTag.remove(city.tag().toLowerCase(Locale.ROOT));
        }
        for (CityMember member : city.members()) {
            byMember.remove(member.uuid());
        }
    }

    void indexMember(UUID player, int cityId) {
        byMember.put(player, cityId);
    }

    void forgetMember(UUID player) {
        byMember.remove(player);
    }

    void reindexName(City city, String oldName) {
        byLowerName.remove(oldName.toLowerCase(Locale.ROOT));
        byLowerName.put(city.name().toLowerCase(Locale.ROOT), city.id());
    }

    void clear() {
        byId.clear();
        byLowerName.clear();
        byLowerTag.clear();
        byMember.clear();
    }
}
