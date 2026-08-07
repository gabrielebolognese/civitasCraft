package dev.civitas.core.war;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.dao.WarEntitySnapshotDao;
import dev.civitas.storage.row.WarEntitySnapshotRow;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;

/**
 * SPEC 11.8.3's living entities: snapshotted at war start, brought back if they were killed.
 *
 * <h2>Why the snapshot has to be taken at the start</h2>
 * A block records its own old state at the moment it changes, which is what makes M17's diff
 * log work. A mob cannot: by the time it dies there is nothing left to ask what it was, what it
 * was called, or what it traded. So the whole population of the zone is recorded when the
 * fighting begins, and the deaths are matched against that record afterwards.
 *
 * <p>This is the one part of the rollback that is a snapshot rather than a diff, and the reason
 * SPEC 11.8.1 gives for preferring diffs does not apply: a war zone holds a few hundred animals
 * and a handful of villagers, not the hundreds of megabytes a block snapshot would take.
 *
 * <h2>What is covered, and what deliberately is not</h2>
 * SPEC 11.8.3's row is "villagers, animals in the war zone". Hostile mobs are not restored:
 * they respawn on their own and nobody mourns a zombie. Defense units are not restored either,
 * and SPEC 11.8.3 says so in the next row down — they are "consumed resources" and their loss
 * is what makes a war cost a defender something real. Players and dropped items are excluded
 * for the same reason SPEC gives.
 *
 * <p>A villager's trades are the part that matters most: a cured librarian with Mending is
 * worth more than the house around it, and losing one to a stray arrow in somebody else's war
 * is exactly the "the plugin ate my thing" outcome SPEC 1.2 exists to prevent.
 */
public final class WarEntitySnapshots {

    private final WarEntitySnapshotDao dao;
    private final dev.civitas.util.Scheduler scheduler;
    private final Logger logger;

    /**
     * Which entity belongs to which war, so a death is matched without a query.
     *
     * <p>A death is a hot-path event: it fires for every mob on the server, including every
     * zombie burning at dawn a thousand blocks from any war.
     */
    private final java.util.Map<UUID, Integer> watched = new ConcurrentHashMap<>();

    public WarEntitySnapshots(WarEntitySnapshotDao dao, dev.civitas.util.Scheduler scheduler,
                              Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Records every restorable entity inside a war's zone.
     *
     * <p>On the server thread: it reads entities. The write is async.
     *
     * @return how many were recorded
     */
    public CompletableFuture<Integer> capture(War war, long now) {
        List<WarEntitySnapshotRow> rows = new ArrayList<>();

        for (String worldName : war.zone().worlds()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            for (Entity entity : world.getEntities()) {
                if (!isRestorable(entity)) {
                    continue;
                }
                Location at = entity.getLocation();
                if (!war.zone().containsBlock(worldName, at.getBlockX(), at.getBlockZ())) {
                    continue;
                }
                rows.add(new WarEntitySnapshotRow(0, war.id(), entity.getUniqueId(),
                        entity.getType().name(), worldName, at.getX(), at.getY(), at.getZ(),
                        describe(entity), null, now));
                watched.put(entity.getUniqueId(), war.id());
            }
        }

        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        try {
            return dao.insertAll(rows).exceptionally(error -> {
                logger.log(Level.WARNING, "Could not snapshot " + rows.size()
                        + " entities for war " + war.id() + ". They will not be restored.", error);
                return 0;
            });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not snapshot entities for war " + war.id(), e);
            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * Notes that a watched entity has died.
     *
     * @return whether this entity was one being watched
     */
    public boolean died(UUID entity, long when) {
        Integer warId = watched.remove(entity);
        if (warId == null) {
            return false;
        }
        try {
            CompletableFuture<Integer> write = dao.markDead(warId, entity, when)
                    .exceptionally(error -> {
                        logger.log(Level.WARNING, "Could not record the death of " + entity
                                + " in war " + warId, error);
                        return 0;
                    });
            // Chained rather than collected, so awaiting the last one awaits them all. The
            // restore reads this table, and a death still in flight when the rollback starts
            // is an animal that quietly does not come back.
            synchronized (deathLock) {
                deathWrites = deathWrites.thenCombine(write, (ignored, count) -> null);
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not record the death of " + entity, e);
        }
        return true;
    }

    private final Object deathLock = new Object();
    private CompletableFuture<Void> deathWrites = CompletableFuture.completedFuture(null);

    /**
     * Waits for every recorded death to reach the database.
     *
     * <p>Called before the restore reads the table, and by tests. A death still in flight is
     * an animal that would quietly not come back.
     */
    public void awaitDeaths() {
        CompletableFuture<Void> outstanding;
        synchronized (deathLock) {
            outstanding = deathWrites;
        }
        try {
            outstanding.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException e) {
            logger.log(Level.WARNING, "Death records did not all reach the database.", e);
        }
    }

    /** Whether this entity is one a war is watching. */
    public boolean isWatched(UUID entity) {
        return watched.containsKey(entity);
    }

    public int watchedCount() {
        return watched.size();
    }

    /**
     * Brings back everything that died, at the end of a rollback.
     *
     * <p>Called after the blocks are back, deliberately: a cow respawned first would be
     * standing inside whatever the replay was about to put where it stood.
     *
     * @return how many were restored
     */
    public CompletableFuture<Integer> restoreDead(int warId) {
        CompletableFuture<Integer> done = new CompletableFuture<>();
        deadOf(warId)
                // Back onto the server thread before anything is spawned. The read is storage
                // work and the spawn is world work, and doing the second one on the database
                // thread is the main-thread rule in reverse: Bukkit is no more thread-safe
                // than the connection pool is.
                .thenAccept(rows -> scheduler.runOnMain(() -> done.complete(respawnAll(rows))));
        return done;
    }

    /**
     * Reads what died, without spawning anything.
     *
     * <p>Separate from {@link #respawnAll} because the two belong on different threads, and a
     * single method would hide which. Waits for outstanding death records first: one still in
     * flight is an animal that would quietly not come back.
     */
    public CompletableFuture<List<WarEntitySnapshotRow>> deadOf(int warId) {
        awaitDeaths();
        try {
            return dao.findDead(warId).exceptionally(error -> {
                logger.log(Level.WARNING, "Could not read the entity snapshot of war "
                        + warId, error);
                return List.of();
            });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not read the entity snapshot of war " + warId, e);
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /**
     * Puts them all back. <b>Server thread only.</b>
     *
     * @return how many were restored
     */
    public int respawnAll(List<WarEntitySnapshotRow> rows) {
        int restored = 0;
        for (WarEntitySnapshotRow row : rows) {
            try {
                if (respawn(row)) {
                    restored++;
                }
            } catch (RuntimeException | LinkageError e) {
                // One animal must not take the rest of the herd with it.
                logger.log(Level.WARNING, "Could not restore " + row.entityType()
                        + " of war " + row.warId(), e);
            }
        }
        return restored;
    }

    /** Drops a finished war's watch list. */
    public void forget(int warId) {
        watched.values().removeIf(id -> id == warId);
    }

    // ==================================================================================
    // What counts
    // ==================================================================================

    /**
     * SPEC 11.8.3: villagers and animals.
     *
     * <p>Tamed animals are included and matter most after villagers: somebody's wolf is not
     * replaceable by walking into a forest. A defense unit is excluded here as well as by
     * type, because SPEC 12.3 makes a dead unit a permanent loss and restoring one would give
     * a defender their garrison back for free.
     */
    public static boolean isRestorable(Entity entity) {
        if (!(entity instanceof LivingEntity living) || living.isDead()) {
            return false;
        }
        if (entity instanceof org.bukkit.entity.Player) {
            return false;
        }
        if (isDefenseUnit(entity)) {
            return false;
        }
        return entity instanceof Animals || entity instanceof AbstractVillager;
    }

    /** A unit carries its database id in its persistent data; M12 put it there. */
    private static boolean isDefenseUnit(Entity entity) {
        for (org.bukkit.NamespacedKey key : entity.getPersistentDataContainer().getKeys()) {
            if ("defense_unit".equals(key.getKey())) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================================
    // Serialization
    // ==================================================================================

    /**
     * What an entity is, in the shape the payload column takes.
     *
     * <p>The same YAML form the tile codec and the hanging listener use. Fields Bukkit does not
     * expose are not captured and are not pretended about; what is here is what SPEC 11.8.3
     * names and the API can actually answer: name, age, profession, level and trades.
     */
    static byte[] describe(Entity entity) {
        YamlConfiguration payload = new YamlConfiguration();
        payload.set("kind", "living");
        payload.set("type", entity.getType().name());

        optional(() -> {
            if (entity.customName() != null) {
                payload.set("name", net.kyori.adventure.text.serializer.gson
                        .GsonComponentSerializer.gson().serialize(entity.customName()));
            }
            payload.set("name-visible", entity.isCustomNameVisible());
        });

        if (entity instanceof LivingEntity living) {
            optional(() -> payload.set("health", living.getHealth()));
            optional(() -> payload.set("remove-when-far-away", living.getRemoveWhenFarAway()));
        }
        if (entity instanceof Ageable ageable) {
            optional(() -> {
                payload.set("adult", ageable.isAdult());
                payload.set("age", ageable.getAge());
            });
        }
        optional(() -> {
            if (entity instanceof Tameable tameable && tameable.isTamed()
                    && tameable.getOwnerUniqueId() != null) {
                payload.set("owner", tameable.getOwnerUniqueId().toString());
            }
        });
        if (entity instanceof Villager villager) {
            optional(() -> payload.set("profession", villager.getProfession().key().asString()));
            optional(() -> payload.set("villager-type",
                    villager.getVillagerType().key().asString()));
            optional(() -> payload.set("level", villager.getVillagerLevel()));
            optional(() -> payload.set("experience", villager.getVillagerExperience()));
        }
        if (entity instanceof AbstractVillager trader) {
            optional(() -> {
                List<java.util.Map<String, Object>> recipes = new ArrayList<>();
                for (org.bukkit.inventory.MerchantRecipe recipe : trader.getRecipes()) {
                    YamlConfiguration one = new YamlConfiguration();
                    one.set("result", recipe.getResult());
                    one.set("ingredients", recipe.getIngredients());
                    one.set("uses", recipe.getUses());
                    one.set("max-uses", recipe.getMaxUses());
                    one.set("experience-reward", recipe.hasExperienceReward());
                    one.set("villager-experience", recipe.getVillagerExperience());
                    one.set("price-multiplier", recipe.getPriceMultiplier());
                    recipes.add(one.getValues(false));
                }
                payload.set("recipes", recipes);
            });
        }
        return payload.saveToString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Captures one field, or gives up on that field alone.
     *
     * <p>The same guard M17's tile codec needed, for the same reason. An accessor that throws
     * — a server build where it is unsupported, or a test double where it is unimplemented —
     * would otherwise take the whole payload with it, and a villager would come back with no
     * trades because its name could not be read. Losing one field is a scratch; losing the
     * payload is the thing SPEC 1.2 promises will not happen.
     */
    private static void optional(Runnable capture) {
        try {
            capture.run();
        } catch (RuntimeException | LinkageError ignored) {
            // This field is not available here. The rest of the payload still is.
        }
    }

    /**
     * Puts one entity back where it was.
     *
     * @return whether it was restored
     */
    @SuppressWarnings("unchecked")
    boolean respawn(WarEntitySnapshotRow row) {
        World world = Bukkit.getWorld(row.world());
        if (world == null) {
            return false;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(row.entityType());
        } catch (IllegalArgumentException e) {
            return false;
        }

        YamlConfiguration payload = new YamlConfiguration();
        if (row.payload() != null && row.payload().length > 0) {
            try {
                payload.load(new StringReader(new String(row.payload(), StandardCharsets.UTF_8)));
            } catch (java.io.IOException | InvalidConfigurationException e) {
                logger.log(Level.WARNING, "Unreadable entity payload for " + row.entityUuid(), e);
            }
        }

        Location at = new Location(world, row.x(), row.y(), row.z());
        Entity spawned;
        try {
            spawned = world.spawnEntity(at, type);
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, "Could not respawn " + type + " at " + at, e);
            return false;
        }

        // Each field is applied on its own. An accessor this build does not support must not
        // take the animal with it: a cow back with the wrong age is a cow, and a cow that
        // failed to spawn is the thing SPEC 1.2 promises will not happen.
        optional(() -> applyCommon(spawned, payload));
        if (spawned instanceof Villager villager) {
            optional(() -> applyVillager(villager, payload));
        }
        if (spawned instanceof AbstractVillager trader && payload.isList("recipes")) {
            optional(() -> {
                List<org.bukkit.inventory.MerchantRecipe> recipes = new ArrayList<>();
                for (Object raw : payload.getList("recipes", List.of())) {
                    if (raw instanceof java.util.Map<?, ?> map) {
                        recipeOf((java.util.Map<String, Object>) map).ifPresent(recipes::add);
                    }
                }
                if (!recipes.isEmpty()) {
                    trader.setRecipes(recipes);
                }
            });
        }
        return true;
    }

    private void applyCommon(Entity spawned, YamlConfiguration payload) {
        String name = payload.getString("name");
        if (name != null) {
            try {
                spawned.customName(net.kyori.adventure.text.serializer.gson
                        .GsonComponentSerializer.gson().deserialize(name));
                spawned.setCustomNameVisible(payload.getBoolean("name-visible", false));
            } catch (RuntimeException ignored) {
                // A name this build cannot parse. The animal comes back unnamed rather than
                // not at all.
            }
        }
        if (spawned instanceof LivingEntity living && payload.contains("health")) {
            optional(() -> {
                var attribute = living.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                double max = attribute == null ? Double.MAX_VALUE : attribute.getValue();
                living.setHealth(Math.max(1.0, Math.min(max, payload.getDouble("health"))));
            });
            optional(() -> living.setRemoveWhenFarAway(
                    payload.getBoolean("remove-when-far-away", true)));
        }
        if (spawned instanceof Ageable ageable) {
            optional(() -> {
                if (payload.getBoolean("adult", true)) {
                    ageable.setAdult();
                } else {
                    ageable.setBaby();
                }
                ageable.setAge(payload.getInt("age", ageable.getAge()));
            });
        }
        String owner = payload.getString("owner");
        if (owner != null && spawned instanceof Tameable tameable) {
            try {
                tameable.setOwner(Bukkit.getOfflinePlayer(UUID.fromString(owner)));
            } catch (IllegalArgumentException ignored) {
                // Not a uuid. It comes back untamed.
            }
        }
    }

    private void applyVillager(Villager villager, YamlConfiguration payload) {
        optional(() -> professionOf(payload.getString("profession"))
                .ifPresent(villager::setProfession));
        optional(() -> villagerTypeOf(payload.getString("villager-type"))
                .ifPresent(villager::setVillagerType));
        optional(() -> {
            int level = payload.getInt("level", 0);
            if (level > 0) {
                villager.setVillagerLevel(Math.min(5, level));
            }
        });
        optional(() -> villager.setVillagerExperience(
                Math.max(0, payload.getInt("experience", 0))));
    }

    private static java.util.Optional<Villager.Profession> professionOf(String key) {
        return fromRegistry(key, io.papermc.paper.registry.RegistryKey.VILLAGER_PROFESSION);
    }

    private static java.util.Optional<Villager.Type> villagerTypeOf(String key) {
        return fromRegistry(key, io.papermc.paper.registry.RegistryKey.VILLAGER_TYPE);
    }

    /** Through the registry: the {@code valueOf} forms on these are marked for removal. */
    private static <T extends org.bukkit.Keyed> java.util.Optional<T> fromRegistry(
            String key, io.papermc.paper.registry.RegistryKey<T> registryKey) {
        if (key == null) {
            return java.util.Optional.empty();
        }
        org.bukkit.NamespacedKey parsed = org.bukkit.NamespacedKey.fromString(key);
        if (parsed == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(io.papermc.paper.registry.RegistryAccess
                .registryAccess().getRegistry(registryKey).get(parsed));
    }

    @SuppressWarnings("unchecked")
    private java.util.Optional<org.bukkit.inventory.MerchantRecipe> recipeOf(
            java.util.Map<String, Object> map) {
        if (!(map.get("result") instanceof org.bukkit.inventory.ItemStack result)) {
            return java.util.Optional.empty();
        }
        int uses = map.get("uses") instanceof Integer value ? value : 0;
        int maxUses = map.get("max-uses") instanceof Integer value ? value : 1;

        org.bukkit.inventory.MerchantRecipe recipe =
                new org.bukkit.inventory.MerchantRecipe(result, uses, maxUses,
                        Boolean.TRUE.equals(map.get("experience-reward")),
                        map.get("villager-experience") instanceof Integer value ? value : 0,
                        map.get("price-multiplier") instanceof Number value
                                ? value.floatValue() : 1.0f);
        if (map.get("ingredients") instanceof List<?> ingredients) {
            List<org.bukkit.inventory.ItemStack> stacks = new ArrayList<>();
            for (Object ingredient : ingredients) {
                if (ingredient instanceof org.bukkit.inventory.ItemStack stack) {
                    stacks.add(stack);
                }
            }
            if (stacks.isEmpty()) {
                return java.util.Optional.empty();
            }
            recipe.setIngredients(stacks);
        }
        return java.util.Optional.of(recipe);
    }

    /** Entity types this class will restore, for tests and for documentation. */
    public static Set<String> describedFields() {
        return Set.of("name", "health", "adult", "age", "owner", "profession",
                "villager-type", "level", "experience", "recipes");
    }
}
