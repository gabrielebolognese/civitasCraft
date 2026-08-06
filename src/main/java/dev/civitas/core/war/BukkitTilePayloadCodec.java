package dev.civitas.core.war;



import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Banner;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.EntityBlockStorage;
import org.bukkit.block.Furnace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The per-type tile capture, built on what Bukkit actually exposes.
 *
 * <h2>Format</h2>
 * A payload is a YAML document, UTF-8 encoded. Not because YAML is fast, but because it is the
 * one serialization Bukkit guarantees across versions for {@link ItemStack}: an item written by
 * this build must still be readable after a server update, and a hand-rolled binary layout
 * would not survive one. Payloads are also rare relative to plain blocks, so the cost falls on
 * chests and furnaces rather than on the dirt an explosion throws around.
 *
 * <p>Every reader is defensive. A payload that cannot be parsed, or that names a type this
 * build no longer has, restores nothing and says so, because a rollback that aborts on one bad
 * chest leaves the rest of the city broken.
 *
 * <h2>What this cannot do</h2>
 * See {@link #knownLimitations()}. The short version: bees inside a hive are counted but not
 * recoverable, because {@code EntityBlockStorage} offers {@code getEntityCount} and
 * {@code releaseEntities} and no way to read what is in there.
 */
public final class BukkitTilePayloadCodec implements TilePayloadCodec {

    /** Bumped if the layout ever changes, so an old payload can still be recognised. */
    private static final int FORMAT = 1;

    private static final String KEY_FORMAT = "format";
    private static final String KEY_KIND = "kind";
    private static final String KEY_MATERIAL = "material";

    private final Logger logger;

    public BukkitTilePayloadCodec(Logger logger) {
        this.logger = java.util.Objects.requireNonNull(logger, "logger");
    }

    @Override
    public boolean hasPayload(BlockState state) {
        return state instanceof Container
                || state instanceof Sign
                || state instanceof Banner
                || state instanceof CreatureSpawner
                || state instanceof EntityBlockStorage<?>;
    }

    // ==================================================================================
    // Capture
    // ==================================================================================

    @Override
    public byte[] capture(BlockState state) {
        if (state == null || !hasPayload(state)) {
            return null;
        }

        YamlConfiguration payload = new YamlConfiguration();
        payload.set(KEY_FORMAT, FORMAT);
        payload.set(KEY_MATERIAL, state.getType().name());

        try {
            if (state instanceof Furnace furnace) {
                // Before Container, because a Furnace is one and the burn timings are the part
                // SPEC 18.3 checks with "a furnace mid-smelt".
                payload.set(KEY_KIND, "furnace");
                writeInventory(payload, furnace.getSnapshotInventory());
                payload.set("burn-time", (int) furnace.getBurnTime());
                payload.set("cook-time", (int) furnace.getCookTime());
                payload.set("cook-time-total", furnace.getCookTimeTotal());
            } else if (state instanceof Container container) {
                payload.set(KEY_KIND, "container");
                writeInventory(payload, container.getSnapshotInventory());
            } else if (state instanceof Sign sign) {
                payload.set(KEY_KIND, "sign");
                writeSign(payload, sign);
            } else if (state instanceof Banner banner) {
                payload.set(KEY_KIND, "banner");
                writeBanner(payload, banner);
            } else if (state instanceof CreatureSpawner spawner) {
                payload.set(KEY_KIND, "spawner");
                // getSpawnedType on BaseSpawner, not the deprecated getCreatureTypeName.
                payload.set("spawned-type", spawner.getSpawnedType() == null
                        ? null : spawner.getSpawnedType().name());
                payload.set("delay", spawner.getDelay());
                payload.set("min-delay", spawner.getMinSpawnDelay());
                payload.set("max-delay", spawner.getMaxSpawnDelay());
                payload.set("spawn-count", spawner.getSpawnCount());
                payload.set("max-nearby", spawner.getMaxNearbyEntities());
                payload.set("required-player-range", spawner.getRequiredPlayerRange());
                payload.set("spawn-range", spawner.getSpawnRange());
            } else if (state instanceof EntityBlockStorage<?> hive) {
                // The count round-trips; the occupants do not. See knownLimitations().
                payload.set(KEY_KIND, "entity-storage");
                payload.set("entity-count", hive.getEntityCount());
                payload.set("max-entities", hive.getMaxEntities());
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not capture tile state at "
                    + state.getLocation() + "; it will restore as a plain block.", e);
            return null;
        }

        return payload.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeInventory(YamlConfiguration payload, Inventory inventory) {
        Map<String, Object> slots = new LinkedHashMap<>();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && item.getType() != Material.AIR) {
                slots.put(String.valueOf(slot), item);
            }
        }
        payload.set("size", contents.length);
        payload.createSection("items", slots);
    }

    private static void writeSign(YamlConfiguration payload, Sign sign) {
        for (Side side : Side.values()) {
            var signSide = sign.getSide(side);
            List<String> lines = new ArrayList<>(4);
            for (int line = 0; line < 4; line++) {
                lines.add(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                        .serialize(signSide.line(line)));
            }
            String prefix = side.name().toLowerCase(java.util.Locale.ROOT);
            payload.set(prefix + ".lines", lines);
            optional(() -> payload.set(prefix + ".glowing", signSide.isGlowingText()));
            optional(() -> {
                if (signSide.getColor() != null) {
                    payload.set(prefix + ".color", signSide.getColor().name());
                }
            });
        }
        optional(() -> payload.set("waxed", sign.isWaxed()));
    }

    /**
     * Reads one optional field, tolerating a server that does not implement it.
     *
     * <p>The text on a sign matters; whether it is waxed is decoration. Losing the whole
     * payload because one accessor is unsupported would trade the part that matters for the
     * part that does not, and the failure would be silent: the block would come back bare.
     */
    private static void optional(Runnable field) {
        try {
            field.run();
        } catch (RuntimeException ignored) {
            // Any "not supported here" signal is tolerated, and deliberately broadly: server
            // implementations disagree about which of these fields exist and about what they
            // throw when one does not. Everything else on the block is still captured, which
            // is the trade this method exists to make.
        }
    }

    private static void writeBanner(YamlConfiguration payload, Banner banner) {
        if (banner.getBaseColor() != null) {
            payload.set("base-color", banner.getBaseColor().name());
        }
        List<Map<String, Object>> patterns = new ArrayList<>();
        for (var pattern : banner.getPatterns()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("color", pattern.getColor().name());
            entry.put("pattern", patternKey(pattern.getPattern()));
            patterns.add(entry);
        }
        payload.set("patterns", patterns);
    }

    // ==================================================================================
    // Restore
    // ==================================================================================

    @Override
    public boolean restore(Block block, byte[] payload) {
        if (block == null || payload == null || payload.length == 0) {
            return false;
        }

        YamlConfiguration parsed = read(payload);
        if (parsed == null) {
            return false;
        }

        String expected = parsed.getString(KEY_MATERIAL);
        if (expected != null && !expected.equals(block.getType().name())) {
            // The block is not the one this payload came from. Replay runs in reverse, so an
            // earlier entry has not landed yet; silently doing nothing is correct.
            return false;
        }

        BlockState state = block.getState();
        try {
            String kind = parsed.getString(KEY_KIND, "");
            boolean restored = switch (kind) {
                case "furnace" -> restoreFurnace(state, parsed);
                case "container" -> restoreContainer(state, parsed);
                case "sign" -> restoreSign(state, parsed);
                case "banner" -> restoreBanner(state, parsed);
                case "spawner" -> restoreSpawner(state, parsed);
                case "entity-storage" -> false;
                default -> false;
            };
            if (restored) {
                state.update(true, false);
            }
            return restored;
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not restore tile state at "
                    + block.getLocation() + "; the block itself is intact.", e);
            return false;
        }
    }

    /**
     * A banner pattern's identity, through Paper's registry.
     *
     * <p>Both {@code PatternType.name()} and its {@code getKey()} are marked for removal in
     * 1.21, so the registry is the only accessor with a future. The same applies to painting
     * art in {@code WarHangingListener}.
     */
    private static String patternKey(org.bukkit.block.banner.PatternType type) {
        var key = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.BANNER_PATTERN)
                .getKey(type);
        return key == null ? null : key.toString();
    }

    /** The reverse of {@link #patternKey}. */
    private static org.bukkit.block.banner.PatternType patternByName(String key) {
        org.bukkit.NamespacedKey parsed = org.bukkit.NamespacedKey.fromString(key);
        if (parsed == null) {
            return null;
        }
        return io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.BANNER_PATTERN)
                .get(parsed);
    }

    private YamlConfiguration read(byte[] payload) {
        try {
            YamlConfiguration parsed = new YamlConfiguration();
            parsed.loadFromString(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            return parsed;
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            logger.log(Level.WARNING, "A tile payload could not be parsed; skipping it.", e);
            return null;
        }
    }

    private static boolean restoreFurnace(BlockState state, YamlConfiguration payload) {
        if (!(state instanceof Furnace furnace)) {
            return false;
        }
        readInventory(payload, furnace.getSnapshotInventory());
        furnace.setBurnTime((short) payload.getInt("burn-time"));
        furnace.setCookTime((short) payload.getInt("cook-time"));
        furnace.setCookTimeTotal(payload.getInt("cook-time-total"));
        return true;
    }

    private static boolean restoreContainer(BlockState state, YamlConfiguration payload) {
        if (!(state instanceof Container container)) {
            return false;
        }
        readInventory(payload, container.getSnapshotInventory());
        return true;
    }

    private static void readInventory(YamlConfiguration payload, Inventory inventory) {
        inventory.clear();
        var items = payload.getConfigurationSection("items");
        if (items == null) {
            return;
        }
        for (String key : items.getKeys(false)) {
            int slot;
            try {
                slot = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                continue;
            }
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            Object value = items.get(key);
            if (value instanceof ItemStack item) {
                inventory.setItem(slot, item);
            }
        }
    }

    private static boolean restoreSign(BlockState state, YamlConfiguration payload) {
        if (!(state instanceof Sign sign)) {
            return false;
        }
        for (Side side : Side.values()) {
            String prefix = side.name().toLowerCase(java.util.Locale.ROOT);
            List<String> lines = payload.getStringList(prefix + ".lines");
            var signSide = sign.getSide(side);
            for (int line = 0; line < Math.min(4, lines.size()); line++) {
                signSide.line(line,
                        net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                                .deserialize(lines.get(line)));
            }
            optional(() -> signSide.setGlowingText(payload.getBoolean(prefix + ".glowing")));
            String color = payload.getString(prefix + ".color");
            if (color != null) {
                try {
                    optional(() -> signSide.setColor(DyeColor.valueOf(color)));
                } catch (IllegalArgumentException ignored) {
                    // A dye colour this build does not have; leave the default.
                }
            }
        }
        optional(() -> sign.setWaxed(payload.getBoolean("waxed")));
        return true;
    }

    private static boolean restoreBanner(BlockState state, YamlConfiguration payload) {
        if (!(state instanceof Banner banner)) {
            return false;
        }
        String base = payload.getString("base-color");
        if (base != null) {
            try {
                banner.setBaseColor(DyeColor.valueOf(base));
            } catch (IllegalArgumentException ignored) {
                // Unknown colour; the banner keeps whatever it has.
            }
        }
        List<org.bukkit.block.banner.Pattern> patterns = new ArrayList<>();
        for (Object raw : payload.getList("patterns", List.of())) {
            if (!(raw instanceof Map<?, ?> entry)) {
                continue;
            }
            Object color = entry.get("color");
            Object type = entry.get("pattern");
            if (color == null || type == null) {
                continue;
            }
            try {
                org.bukkit.block.banner.PatternType patternType = patternByName(type.toString());
                if (patternType != null) {
                    patterns.add(new org.bukkit.block.banner.Pattern(
                            DyeColor.valueOf(color.toString()), patternType));
                }
            } catch (IllegalArgumentException ignored) {
                // A pattern or colour this build does not have; drop that layer only.
            }
        }
        banner.setPatterns(patterns);
        return true;
    }

    private static boolean restoreSpawner(BlockState state, YamlConfiguration payload) {
        if (!(state instanceof CreatureSpawner spawner)) {
            return false;
        }
        String type = payload.getString("spawned-type");
        if (type != null) {
            try {
                spawner.setSpawnedType(org.bukkit.entity.EntityType.valueOf(type));
            } catch (IllegalArgumentException ignored) {
                // An entity type this build does not have; the spawner keeps its own.
            }
        }
        spawner.setDelay(payload.getInt("delay"));
        spawner.setMinSpawnDelay(payload.getInt("min-delay"));
        spawner.setMaxSpawnDelay(payload.getInt("max-delay"));
        spawner.setSpawnCount(payload.getInt("spawn-count"));
        spawner.setMaxNearbyEntities(payload.getInt("max-nearby"));
        spawner.setRequiredPlayerRange(payload.getInt("required-player-range"));
        spawner.setSpawnRange(payload.getInt("spawn-range"));
        return true;
    }

    // ==================================================================================
    // What this cannot do
    // ==================================================================================

    @Override
    public List<String> knownLimitations() {
        return List.of(
                "Bees inside a beehive or bee nest are counted but not restored: Bukkit's "
                        + "EntityBlockStorage exposes getEntityCount and releaseEntities and no "
                        + "way to read the occupants. A hive rolls back empty.",
                "Any vanilla tile data with no Bukkit accessor is not captured, because "
                        + "paper-api exposes no NBT API and SPEC 2.1 forbids NMS unless "
                        + "unavoidable.");
    }
}
