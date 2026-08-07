package dev.civitas.core.upgrade;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.CityUpgradeDao;
import dev.civitas.storage.row.CityUpgradeRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * Buying and reading the SPEC 5.7 upgrades.
 *
 * <h2>Levels are cached, and read on hot paths</h2>
 * Four systems ask for a level constantly: the member cap on every join, the upkeep sweep on
 * every city, the outpost cap on every menu redraw, and the market tax on every sale. None of
 * those may touch the database, so levels live in memory and the database is written through.
 *
 * <h2>Buying is one level at a time, in order</h2>
 * SPEC 5.7 lists five costs per track and nothing about skipping, so level 3 costs what
 * level 3 costs and cannot be reached without paying for 1 and 2 first. That also makes the
 * total cost of a track the sum of its column, which is how the numbers were designed to be
 * read.
 */
public final class UpgradeService {

    private final DatabaseManager db;
    private final CityUpgradeDao upgrades;
    private final TreasuryService treasury;
    private final ConfigManager configs;
    private final Scheduler scheduler;

    /** City id to its levels. Absent means every track is at zero. */
    private final Map<Integer, Map<UpgradeType, Integer>> levels = new ConcurrentHashMap<>();

    public UpgradeService(DatabaseManager db, CityUpgradeDao upgrades, TreasuryService treasury,
                          ConfigManager configs, Scheduler scheduler) {
        this.db = Objects.requireNonNull(db, "db");
        this.upgrades = Objects.requireNonNull(upgrades, "upgrades");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    // ==================================================================================
    // Loading and reading
    // ==================================================================================

    /** @return how many upgrade rows exist */
    public CompletableFuture<Integer> loadAll() {
        return upgrades.findAll().thenApply(rows -> {
            levels.clear();
            for (CityUpgradeRow row : rows) {
                UpgradeType.parse(row.upgradeKey()).ifPresent(type ->
                        levels.computeIfAbsent(row.cityId(),
                                        key -> new ConcurrentHashMap<>())
                                .put(type, clamp(row.level())));
            }
            return rows.size();
        });
    }

    /** This city's level on one track, 0 to 5. */
    public int levelOf(int cityId, UpgradeType type) {
        Map<UpgradeType, Integer> mine = levels.get(cityId);
        return mine == null ? 0 : mine.getOrDefault(type, 0);
    }

    public int levelOf(City city, UpgradeType type) {
        return levelOf(city.id(), type);
    }

    /** Every track's level, for the menu and for {@code /ca info}. */
    public Map<UpgradeType, Integer> levelsOf(int cityId) {
        Map<UpgradeType, Integer> all = new EnumMap<>(UpgradeType.class);
        for (UpgradeType type : UpgradeType.values()) {
            all.put(type, levelOf(cityId, type));
        }
        return all;
    }

    /** How many levels this city has bought in total, out of the thirty available. */
    public int totalLevels(int cityId) {
        int total = 0;
        for (UpgradeType type : UpgradeType.values()) {
            total += levelOf(cityId, type);
        }
        return total;
    }

    public void forgetCity(int cityId) {
        levels.remove(cityId);
    }

    // ==================================================================================
    // Costs
    // ==================================================================================

    /**
     * What one level costs, from {@code cities.yml}.
     *
     * @param level 1 to 5
     * @return the price, or empty if that level does not exist
     */
    public Optional<BigDecimal> costOf(UpgradeType type, int level) {
        if (level < 1 || level > UpgradeType.MAX_LEVEL) {
            return Optional.empty();
        }
        List<?> costs = configs.get(ConfigFile.CITIES).getList(type.configPath() + ".costs");
        if (costs == null || costs.size() < level) {
            return Optional.empty();
        }
        Object value = costs.get(level - 1);
        return value == null
                ? Optional.empty()
                : Optional.of(new BigDecimal(String.valueOf(value)));
    }

    /** What the next level would cost this city, or empty if the track is maxed. */
    public Optional<BigDecimal> nextCost(City city, UpgradeType type) {
        return costOf(type, levelOf(city, type) + 1);
    }

    /** The effect number per level, such as 5 members or 4 percent. */
    public double effectPerLevel(UpgradeType type, double fallback) {
        return configs.get(ConfigFile.CITIES)
                .getDouble(type.configPath() + ".effect-per-level", fallback);
    }

    public String displayName(UpgradeType type) {
        return configs.get(ConfigFile.CITIES)
                .getString(type.configPath() + ".display-name", type.key());
    }

    // ==================================================================================
    // Buying
    // ==================================================================================

    /**
     * Buys the next level of a track.
     *
     * <p>SPEC 11.11 blocks this during a war: a city that could buy Fortification
     * mid-siege would make the SPEC 11.5 preparation phase pointless.
     *
     * @return the level now held
     */
    public CompletableFuture<Result<Purchase>> purchase(UUID actor, City city, UpgradeType type) {
        if (!city.hasPermission(actor, CityPermission.MANAGE_UPGRADES)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.MANAGE_UPGRADES.name())));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }
        if (city.isDelinquent()) {
            return completed(Result.failure("CITY_DELINQUENT", "claim.delinquent"));
        }
        if (isCityAtWar(city)) {
            return completed(Result.failure("CITY_AT_WAR", "upgrade.at-war"));
        }

        int current = levelOf(city, type);
        if (current >= UpgradeType.MAX_LEVEL) {
            return completed(Result.failure("ALREADY_MAX", "upgrade.maxed",
                    Map.of("upgrade", displayName(type))));
        }

        int wanted = current + 1;
        Optional<BigDecimal> cost = costOf(type, wanted);
        if (cost.isEmpty()) {
            return completed(Result.failure("NO_SUCH_LEVEL", "upgrade.no-cost",
                    Map.of("upgrade", displayName(type), "level", String.valueOf(wanted))));
        }

        return db.transaction(connection -> {
            // Re-read inside the transaction: two officers clicking at once must not both
            // buy level 3, and the stored level is the only thing that can settle it.
            int stored = upgrades.findLevel(connection, city.id(), type.key());
            if (stored != current) {
                return Result.<Purchase>failure("LEVEL_MOVED", "upgrade.changed");
            }

            Result<BigDecimal> paid = treasury.adjust(connection, city, cost.get().negate(),
                    TransactionType.UPGRADE_PURCHASE, actor,
                    "{\"upgrade\":\"" + type.key() + "\",\"level\":" + wanted + "}");
            if (paid instanceof Result.Failure<BigDecimal> failure) {
                return Result.<Purchase>propagate(failure);
            }

            upgrades.setLevel(connection, city.id(), type.key(), wanted);
            return Result.success(new Purchase(type, wanted, cost.get(), paid.orElseThrow()));
        }).thenApply(result -> {
            if (result instanceof Result.Success<Purchase>(Purchase purchase)) {
                scheduler.runOnMain(() -> levels
                        .computeIfAbsent(city.id(), key -> new ConcurrentHashMap<>())
                        .put(type, purchase.level()));
            }
            return result;
        });
    }

    /**
     * SPEC 11.11: "Purchasing city upgrades" is blocked during PREP and ACTIVE.
     *
     * <p>Fortification raises the defense-unit cap and Population raises the member cap, so a
     * city buying either mid-war would be changing the terms of a fight already under way.
     */
    private boolean isCityAtWar(City city) {
        return wars != null && wars.blocksUpgrades(city.id());
    }

    private dev.civitas.core.war.WarRestrictions wars;

    /** SPEC 11.11, wired by M19. */
    public void useWars(dev.civitas.core.war.WarRestrictions restrictions) {
        this.wars = restrictions;
    }

    private static int clamp(int level) {
        return Math.max(0, Math.min(UpgradeType.MAX_LEVEL, level));
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /**
     * What a purchase did.
     *
     * @param level    the level now held
     * @param cost     what it cost
     * @param treasury the treasury afterwards
     */
    public record Purchase(UpgradeType type, int level, BigDecimal cost, BigDecimal treasury) { }
}
