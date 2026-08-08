package dev.civitas.core.combat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.world.WorldRegistry;

/**
 * Whether one player may damage another, SPEC 33.
 *
 * <p>The single authority. {@code ProtectionService} used to answer this for claims and vanilla
 * answered it everywhere else, which meant two rules and a large gap between them. SPEC 33
 * replaces Part I 5.5 and 11.6 in full, so there is now one function and every caller asks it.
 *
 * <h2>The contradiction this class had to resolve</h2>
 *
 * <p>SPEC disagrees with itself about whether peacetime PvP exists. SPEC 33.1, 33.3 and 33.10
 * enable it in unclaimed land with keepInventory on; SPEC 37 and SPEC 38's milestone row say
 * "peacetime PvP disabled globally". Both are in Part IV.
 *
 * <p>Shipped disabled, as the conservative reading: Part I's pillar 1.4 says "outside of
 * declared wars, the world is fully protected" and SPEC 1 makes the pillars decide ambiguous
 * calls, nobody is killed unexpectedly, and enabling it later adds something where disabling it
 * later takes something away. It is one config key, {@code pvp.peacetime}, so the other reading
 * costs an edit rather than a rewrite. Recorded in {@code OPEN_QUESTIONS.md}.
 *
 * <h2>Order matters, and zones come before wars</h2>
 *
 * <p>A sanctuary that a war could override would not be a sanctuary. SPEC 32.7 makes spawn
 * peaceful "under all circumstances including active wars" and SPEC 33.5 says the same of the
 * resource worlds, so those are checked before anything asks whether a war is on.
 *
 * <p>No Bukkit types cross this boundary, the same rule {@code ProtectionService} follows: the
 * listener turns an event into a world name and chunk coordinates, and this decides.
 */
public final class PvpPolicy {

    /** Where the server spawn is, as a chunk. Absent before the world has loaded. */
    @FunctionalInterface
    public interface SpawnChunk {

        /** {@code [world, chunkX, chunkZ]}, or empty if there is no spawn to speak of. */
        Optional<Object[]> get();
    }

    /** A question about one chunk. */
    @FunctionalInterface
    public interface ChunkTest {

        boolean test(String world, int chunkX, int chunkZ);
    }

    /** Whether two players are on opposing sides of a war whose zone covers this chunk. */
    @FunctionalInterface
    public interface WarCheck {

        boolean allows(UUID attacker, UUID victim, String world, int chunkX, int chunkZ);
    }

    private final ConfigManager configs;
    private final WorldRegistry worlds;

    private SpawnChunk spawnChunk = Optional::empty;
    /** Admin-protected chunks. Injected rather than held, so this class needs no storage. */
    private ChunkTest adminProtected = (world, x, z) -> false;
    /** Mining claims, SPEC 32.6. Answers no until they exist. */
    private ChunkTest miningClaims = (world, x, z) -> false;
    /** The war half of SPEC 33. Answers no until it is built. */
    private WarCheck warCheck = (a, v, world, x, z) -> false;

    /** When each player last joined or respawned, for the SPEC 37 grace periods. */
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> respawnedAt = new ConcurrentHashMap<>();

    public PvpPolicy(ConfigManager configs, WorldRegistry worlds) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
    }

    /** Tells the policy where spawn is. The plugin supplies the main world's spawn chunk. */
    public void useSpawnChunk(SpawnChunk source) {
        this.spawnChunk = Objects.requireNonNull(source, "source");
    }

    /** Hands the policy the admin-protected chunk registry. */
    public void useAdminProtection(ChunkTest protectedChunks) {
        this.adminProtected = Objects.requireNonNull(protectedChunks, "protectedChunks");
    }

    /**
     * Hands the policy the mining claims, SPEC 32.6.
     *
     * <p>The single call the mining-claim milestone makes here.
     */
    public void useMiningClaims(ChunkTest claims) {
        this.miningClaims = Objects.requireNonNull(claims, "claims");
    }

    /**
     * Hands the policy the war rules, the other half of SPEC 33.
     *
     * <p>The single call the war-PvP milestone makes here. Everything else in this class —
     * the zones, the resource worlds, the grace periods — already behaves correctly once a
     * war can say yes, because each of them is checked before this is asked.
     */
    public void useWarCheck(WarCheck check) {
        this.warCheck = Objects.requireNonNull(check, "check");
    }

    // ==================================================================================
    // The decision
    // ==================================================================================

    /**
     * Whether {@code attacker} may damage {@code victim} at this position.
     *
     * <p>Evaluated at the <b>victim's</b> location, per SPEC 33.9 case 117: "Evaluated at the
     * moment damage is applied, using the victim's location." That also settles case 118, a
     * splash potion thrown from wilderness that lands inside a claim.
     */
    public PvpDecision check(UUID attacker, UUID victim, String world, int chunkX, int chunkZ,
                             long now) {
        if (attacker == null || victim == null || attacker.equals(victim)) {
            // Hurting yourself is not PvP, and neither is damage with no player behind it.
            return PvpDecision.ALLOWED;
        }

        // Grace first, because it is about the players rather than the place and SPEC 33.9
        // case 123 makes it symmetric: "Immunity is not a one-way shield."
        if (isInGrace(attacker, now)) {
            return PvpDecision.deny("ATTACKER_IN_GRACE", "combat.pvp-denied-your-grace");
        }
        if (isInGrace(victim, now)) {
            return PvpDecision.deny("VICTIM_IN_GRACE", "combat.pvp-denied-their-grace");
        }

        // Then the places that are never a battlefield, before anything asks about a war.
        Optional<PvpZone> zone = zoneAt(world, chunkX, chunkZ);
        if (zone.isPresent()) {
            return PvpDecision.deny("ZONE_" + zone.get().name(), zone.get().messageKey());
        }
        if (!resourceWorldPvp() && worlds.allowsMiningClaims(world)) {
            // SPEC 33.5. Not an exclusion zone in SPEC 37's list, but SPEC 33.1's table puts
            // the resource worlds off in every column including the war ones.
            return PvpDecision.deny("RESOURCE_WORLD", "combat.pvp-denied-resource");
        }

        if (isWarPvpAllowed(attacker, victim, world, chunkX, chunkZ)) {
            return PvpDecision.ALLOWED;
        }
        if (peacetimeEnabled()) {
            return PvpDecision.ALLOWED;
        }
        return PvpDecision.deny("PEACETIME", "combat.pvp-denied-peacetime");
    }

    /** Which sanctuary covers this chunk, if any. */
    public Optional<PvpZone> zoneAt(String world, int chunkX, int chunkZ) {
        Set<PvpZone> active = exclusionZones();
        if (active.contains(PvpZone.SPAWN) && isSpawn(world, chunkX, chunkZ)) {
            return Optional.of(PvpZone.SPAWN);
        }
        if (active.contains(PvpZone.ADMIN_PROTECTED)
                && adminProtected.test(world, chunkX, chunkZ)) {
            return Optional.of(PvpZone.ADMIN_PROTECTED);
        }
        if (active.contains(PvpZone.MINING_CLAIM) && isMiningClaim(world, chunkX, chunkZ)) {
            return Optional.of(PvpZone.MINING_CLAIM);
        }
        return Optional.empty();
    }

    // ==================================================================================
    // Grace, SPEC 37
    // ==================================================================================

    /** Records that a player has just joined. */
    public void onJoin(UUID player, long now) {
        joinedAt.put(player, now);
    }

    /** Records that a player has just respawned. */
    public void onRespawn(UUID player, long now) {
        respawnedAt.put(player, now);
    }

    /** Forgets a player's grace, so a rejoin starts it again rather than resuming it. */
    public void forget(UUID player) {
        joinedAt.remove(player);
        respawnedAt.remove(player);
    }

    /** Whether this player is inside either grace window. */
    public boolean isInGrace(UUID player, long now) {
        return within(joinedAt.get(player), now, joinGraceMillis())
                || within(respawnedAt.get(player), now, respawnGraceMillis());
    }

    /** How much grace is left, for the message and the tests. */
    public long graceRemaining(UUID player, long now) {
        long fromJoin = remaining(joinedAt.get(player), now, joinGraceMillis());
        long fromRespawn = remaining(respawnedAt.get(player), now, respawnGraceMillis());
        return Math.max(fromJoin, fromRespawn);
    }

    private static boolean within(Long stamp, long now, long window) {
        return stamp != null && window > 0 && now - stamp < window;
    }

    private static long remaining(Long stamp, long now, long window) {
        return stamp == null ? 0 : Math.max(0, window - (now - stamp));
    }

    // ==================================================================================
    // Seams
    // ==================================================================================

    /**
     * Whether these two are on opposing sides of a war whose zone covers this chunk.
     *
     * <p>Answers no until {@link #useWarCheck} is called, which is the war milestone's single
     * touchpoint here — the same shape {@code ProtectionService.isAtWar} and
     * {@code DiplomacyService.isAtWar} already use.
     */
    public boolean isWarPvpAllowed(UUID attacker, UUID victim, String world, int chunkX,
                                   int chunkZ) {
        return warCheck.allows(attacker, victim, world, chunkX, chunkZ);
    }

    /**
     * Whether this chunk is somebody's personal mining claim, SPEC 32.6.
     *
     * <p>Answers no until {@link #useMiningClaims} is called.
     */
    public boolean isMiningClaim(String world, int chunkX, int chunkZ) {
        return miningClaims.test(world, chunkX, chunkZ);
    }

    private boolean isSpawn(String world, int chunkX, int chunkZ) {
        Optional<Object[]> spawn = spawnChunk.get();
        if (spawn.isEmpty()) {
            return false;
        }
        Object[] at = spawn.get();
        if (!String.valueOf(at[0]).equalsIgnoreCase(world)) {
            return false;
        }
        int radius = spawnRadiusChunks();
        return Math.abs(chunkX - (int) at[1]) <= radius
                && Math.abs(chunkZ - (int) at[2]) <= radius;
    }

    // ==================================================================================
    // Configuration
    // ==================================================================================

    /** SPEC 33's contested key. False ships; see the class note and {@code combat.yml}. */
    public boolean peacetimeEnabled() {
        return configs.get(ConfigFile.COMBAT).getBoolean("pvp.peacetime", false);
    }

    /** SPEC 33.5: PvP in the resource worlds, off even during a war. */
    public boolean resourceWorldPvp() {
        return configs.get(ConfigFile.COMBAT).getBoolean("pvp.resource-worlds", false);
    }

    public Set<PvpZone> exclusionZones() {
        Set<PvpZone> zones = EnumSet.noneOf(PvpZone.class);
        configs.get(ConfigFile.COMBAT).getStringList("pvp.exclusion-zones")
                .forEach(name -> PvpZone.byName(name).ifPresent(zones::add));
        return zones;
    }

    public int spawnRadiusChunks() {
        return configs.get(ConfigFile.COMBAT).getInt("pvp.spawn-radius-chunks", 8);
    }

    public long joinGraceMillis() {
        return configs.get(ConfigFile.COMBAT).getLong("pvp.join-grace-seconds", 10) * 1000L;
    }

    public long respawnGraceMillis() {
        return configs.get(ConfigFile.COMBAT).getLong("pvp.respawn-grace-seconds", 10) * 1000L;
    }

    // ==================================================================================
    // Shapes
    // ==================================================================================

    /**
     * Whether the damage may land, and why not.
     *
     * @param reason     a stable code, for the tests and for {@code /ca} diagnostics
     * @param messageKey what the attacker is told; empty when allowed
     */
    public record PvpDecision(boolean allowed, String reason, String messageKey,
                              Map<String, String> placeholders) {

        public static final PvpDecision ALLOWED =
                new PvpDecision(true, "ALLOWED", "", Map.of());

        static PvpDecision deny(String reason, String messageKey) {
            return new PvpDecision(false, reason, messageKey, Map.of());
        }

        public boolean denied() {
            return !allowed;
        }
    }
}
