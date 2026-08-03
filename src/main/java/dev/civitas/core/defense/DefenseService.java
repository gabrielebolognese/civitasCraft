package dev.civitas.core.defense;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.economy.Money;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.upgrade.UpgradeType;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.DefenseUnitDao;
import dev.civitas.storage.row.DefenseUnitRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Buying, placing and losing defense units, SPEC 12.
 *
 * <h2>Buying and placing are two steps</h2>
 * SPEC 12.4 says a purchase gives the player a spawn egg "which must be placed inside a
 * claim, so placement is deliberate and visible". That is the whole reason this is not one
 * action: a city's defenses end up where somebody chose to stand, in front of whoever was
 * watching, rather than materialising from a menu.
 *
 * <h2>Units are consumed, not owned</h2>
 * SPEC 12.1 and 12.3: a dead unit is gone, its upkeep stops, and nothing is refunded. That is
 * what makes a war cost a defender something real even though SPEC 11.2 restores every block.
 */
public final class DefenseService {

    /** The key stamped on a purchased egg, so only a real purchase can place a unit. */
    public static final String EGG_KEY = "defense_egg";

    private final DatabaseManager db;
    private final DefenseUnitDao units;
    private final DefenseRegistry registry;
    private final DefenseCatalogue catalogue;
    private final DefenseSpawner spawner;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final TreasuryService treasury;
    private final UpgradeService upgrades;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final NamespacedKey eggKey;

    public DefenseService(Plugin plugin, DatabaseManager db, DefenseUnitDao units,
                          DefenseRegistry registry, DefenseCatalogue catalogue,
                          DefenseSpawner spawner, CityRegistry cities, ClaimRegistry claims,
                          TreasuryService treasury, UpgradeService upgrades, LangManager lang,
                          Scheduler scheduler) {
        this.db = Objects.requireNonNull(db, "db");
        this.units = Objects.requireNonNull(units, "units");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.upgrades = Objects.requireNonNull(upgrades, "upgrades");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.eggKey = new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), EGG_KEY);
    }

    public DefenseRegistry registry() {
        return registry;
    }

    public DefenseCatalogue catalogue() {
        return catalogue;
    }

    public DefenseSpawner spawner() {
        return spawner;
    }

    // ==================================================================================
    // Buying
    // ==================================================================================

    /** What a unit costs this city right now, doubled during a war per SPEC 12.4. */
    public BigDecimal costFor(City city, DefenseUnitType type) {
        BigDecimal base = type.cost();
        return isCityAtWar(city)
                ? Money.floor(base.multiply(BigDecimal.valueOf(catalogue.wartimeMultiplier())))
                : base;
    }

    /** SPEC 12.4: five active units, plus more per Fortification level. */
    public int maxUnits(City city) {
        return catalogue.baseMaxUnits()
                + catalogue.unitsPerFortificationLevel()
                        * upgrades.levelOf(city, UpgradeType.FORTIFICATION);
    }

    /**
     * Buys a unit and gives the player its egg.
     *
     * <p>The money moves now and the unit appears later, which is deliberate: SPEC 12.4 wants
     * placement to be a decision, and a purchase that could be reversed by not placing it
     * would make the wartime double-price meaningless.
     */
    public CompletableFuture<Result<ItemStack>> purchase(UUID actor, City city,
                                                         DefenseUnitType type) {
        if (!catalogue.enabled()) {
            return completed(Result.failure("DEFENSE_DISABLED", "defense.disabled"));
        }
        if (!city.hasPermission(actor, CityPermission.MANAGE_DEFENSE)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.MANAGE_DEFENSE.name())));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }
        int max = maxUnits(city);
        if (registry.activeCount(city.id()) >= max) {
            return completed(Result.failure("UNIT_LIMIT", "defense.limit",
                    Map.of("limit", String.valueOf(max))));
        }

        BigDecimal cost = costFor(city, type);
        return db.transaction(connection -> treasury.adjust(connection, city, cost.negate(),
                        TransactionType.DEFENSE_PURCHASE, actor,
                        "{\"unit\":\"" + type.key() + "\"}"))
                .thenApply(result -> {
                    if (result instanceof Result.Failure<BigDecimal> failure) {
                        return Result.<ItemStack>propagate(failure);
                    }
                    return Result.success(egg(type, city));
                });
    }

    /**
     * The item a purchase hands over.
     *
     * <p>A vanilla spawn egg carrying the unit key and the city id. Both are needed: the key
     * says what to place and the city id stops an egg bought by one city being placed in
     * another, which would otherwise be a way to put a Siege Golem inside somebody else's
     * walls.
     */
    public ItemStack egg(DefenseUnitType type, City city) {
        ItemStack stack = new ItemStack(eggMaterialFor(type));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.displayName(lang.get("defense.egg-name",
                        LangManager.placeholder("unit", type.displayName()))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                lang.get("defense.egg-lore", LangManager.placeholder("city", city.name()))
                        .decoration(TextDecoration.ITALIC, false)));

        meta.getPersistentDataContainer().set(eggKey, PersistentDataType.STRING, type.key());
        meta.getPersistentDataContainer().set(cityKey(), PersistentDataType.INTEGER, city.id());
        stack.setItemMeta(meta);
        return stack;
    }

    /** What is stamped on an egg, if it is one. */
    public Optional<EggStamp> readEgg(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        var container = stack.getItemMeta().getPersistentDataContainer();
        String type = container.get(eggKey, PersistentDataType.STRING);
        Integer cityId = container.get(cityKey(), PersistentDataType.INTEGER);
        if (type == null || cityId == null) {
            return Optional.empty();
        }
        return Optional.of(new EggStamp(type, cityId));
    }

    // ==================================================================================
    // Placing, SPEC 12.4
    // ==================================================================================

    /**
     * Whether a unit may be placed here.
     *
     * <p>Inside a claim of the buying city, and no more than three per chunk. The claim check
     * is the one that matters: SPEC 12.4 says units go in your own land, and without it a
     * city could ring a neighbour's walls with guards.
     */
    public Result<DefenseUnitType> checkPlacement(UUID actor, City city, DefenseUnitType type,
                                                  String world, double x, double y, double z) {
        if (!city.hasPermission(actor, CityPermission.MANAGE_DEFENSE)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.MANAGE_DEFENSE.name()));
        }

        int chunkX = (int) Math.floor(x) >> 4;
        int chunkZ = (int) Math.floor(z) >> 4;

        boolean ownLand = claims.at(world, chunkX, chunkZ)
                .filter(claim -> claim.cityId() == city.id())
                .isPresent();
        if (!ownLand) {
            return Result.failure("NOT_YOUR_LAND", "defense.not-your-land");
        }

        int max = maxUnits(city);
        if (registry.activeCount(city.id()) >= max) {
            return Result.failure("UNIT_LIMIT", "defense.limit",
                    Map.of("limit", String.valueOf(max)));
        }

        int perChunk = catalogue.maxUnitsPerChunk();
        if (registry.countInChunk(city.id(), world, chunkX, chunkZ) >= perChunk) {
            return Result.failure("CHUNK_FULL", "defense.chunk-full",
                    Map.of("limit", String.valueOf(perChunk)));
        }
        return Result.success(type);
    }

    /** Writes the unit and spawns it. */
    public CompletableFuture<Result<DefenseUnit>> place(UUID actor, City city,
                                                        DefenseUnitType type, String world,
                                                        double x, double y, double z) {
        Result<DefenseUnitType> checked =
                checkPlacement(actor, city, type, world, x, y, z);
        if (checked instanceof Result.Failure<DefenseUnitType> failure) {
            return completed(Result.propagate(failure));
        }

        return db.transaction(connection -> {
            int id = units.insert(connection, new DefenseUnitRow(0, city.id(), type.key(),
                    world, x, y, z, type.upkeepPerDay(), true));
            return Result.success(new DefenseUnit(id, city.id(), type.key(), world, x, y, z,
                    type.upkeepPerDay(), true));
        }).thenApply(result -> {
            if (result instanceof Result.Success<DefenseUnit>(DefenseUnit unit)) {
                scheduler.runOnMain(() -> {
                    registry.put(unit);
                    spawnNow(unit, type, city);
                });
            }
            return result;
        });
    }

    /** Spawns a unit that is stored but not standing, and links the entity to its row. */
    public Optional<LivingEntity> spawnNow(DefenseUnit unit, DefenseUnitType type, City city) {
        Optional<LivingEntity> spawned = spawner.spawn(unit, type,
                city, upgrades.levelOf(city, UpgradeType.FORTIFICATION));
        spawned.ifPresent(entity -> registry.link(entity.getUniqueId(), unit.id()));
        return spawned;
    }

    // ==================================================================================
    // Losing
    // ==================================================================================

    /**
     * A unit has died, SPEC 12.3.
     *
     * <p>Removed permanently, upkeep stops, nothing is refunded. SPEC 17.4 case 56 adds that
     * a unit killed by its own city's member is the same: no refund and no score, which is
     * exactly what this does by not caring who killed it.
     */
    public CompletableFuture<Integer> onDeath(DefenseUnit unit, UUID entity) {
        registry.remove(unit.id());
        registry.unlink(entity);
        return units.delete(unit.id());
    }

    /** Removes a unit deliberately, refunding nothing. */
    public CompletableFuture<Result<DefenseUnit>> dismiss(UUID actor, City city,
                                                          DefenseUnit unit) {
        if (!city.hasPermission(actor, CityPermission.MANAGE_DEFENSE)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.MANAGE_DEFENSE.name())));
        }
        if (unit.cityId() != city.id()) {
            return completed(Result.failure("NOT_YOUR_UNIT", "defense.not-yours"));
        }

        return units.delete(unit.id()).thenApply(deleted -> {
            scheduler.runOnMain(() -> {
                despawn(unit);
                registry.remove(unit.id());
            });
            return Result.success(unit);
        });
    }

    // ==================================================================================
    // Upkeep, SPEC 12.3
    // ==================================================================================

    /**
     * Turns a city's units off, or back on.
     *
     * <p>SPEC 12.3: "Upkeep unpaid: units deactivate (despawn but persist in DB) and
     * reactivate when upkeep is paid." The row surviving is the point. A city that falls
     * behind for a day and catches up gets its army back rather than having to buy it again,
     * which would turn one missed payment into a second bill of hundreds of thousands.
     */
    public CompletableFuture<Integer> setActive(City city, boolean active) {
        return units.setActiveByCity(city.id(), active).thenApply(changed -> {
            scheduler.runOnMain(() -> {
                for (DefenseUnit unit : registry.of(city.id())) {
                    registry.put(unit.withActive(active));
                    if (active) {
                        catalogue.byKey(unit.type())
                                .ifPresent(type -> spawnNow(unit.withActive(true), type, city));
                    } else {
                        despawn(unit);
                    }
                }
            });
            return changed;
        });
    }

    /** Removes the live entity of a unit, leaving its row alone. */
    public void despawn(DefenseUnit unit) {
        unit.location().ifPresent(location -> {
            if (!location.getWorld().isChunkLoaded(unit.chunkX(), unit.chunkZ())) {
                return;
            }
            location.getWorld().getNearbyEntities(location, 4, 4, 4).stream()
                    .filter(entity -> spawner.unitIdOf(entity)
                            .filter(id -> id == unit.id()).isPresent())
                    .forEach(entity -> {
                        registry.unlink(entity.getUniqueId());
                        entity.remove();
                    });
        });
    }

    /** Everything a disbanding city had, SPEC 12.3. */
    public CompletableFuture<Integer> removeCity(City city) {
        registry.of(city.id()).forEach(this::despawn);
        registry.forgetCity(city.id());
        return units.deleteByCity(city.id());
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /** SPEC 11.6, once wars exist in M19. */
    private boolean isCityAtWar(City city) {
        return false;
    }

    private NamespacedKey cityKey() {
        return new NamespacedKey(eggKey.getNamespace(), "defense_egg_city");
    }

    /**
     * Which spawn egg represents a unit.
     *
     * <p>The matching vanilla egg where one exists, so a Warhound is a wolf egg and reads as
     * one in the inventory. A mob with no egg falls back to a zombie egg rather than failing
     * the purchase.
     */
    private static Material eggMaterialFor(DefenseUnitType type) {
        Material egg = Material.matchMaterial(type.mob().name() + "_SPAWN_EGG");
        return egg == null ? Material.ZOMBIE_SPAWN_EGG : egg;
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /** What a purchased egg carries. */
    public record EggStamp(String typeKey, int cityId) { }
}
