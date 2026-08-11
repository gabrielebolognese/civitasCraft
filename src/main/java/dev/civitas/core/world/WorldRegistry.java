package dev.civitas.core.world;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;

/**
 * What each world is for, SPEC 32.
 *
 * <p>Before this existed the question was answered by two {@code getStringList} calls, one in
 * {@code CityService} and one in {@code ClaimService}, which happened to agree. SPEC 32 adds
 * three more worlds and SPEC 41's later milestones add travel, mining claims and a PvP policy
 * that all have to ask the same question, so it gets one home.
 *
 * <h2>No border, and that is a decision rather than an omission</h2>
 *
 * <p>SPEC 37 ships a {@code border:} block — {@code dynamic: true}, a base radius, an expansion
 * bracket, a maximum. SPEC 32.3 rejects that design in full: "The vanilla Minecraft border
 * stands, unchanged, at roughly 30 million blocks. The plugin does not impose, expand, or manage
 * a border of any kind." SPEC 41's milestone repeats it. The later section wins, so this class
 * has no notion of a border and {@code world.yml} does not ship those keys — seven config keys
 * that nothing reads is precisely what the config sweep found nineteen of.
 *
 * <p>SPEC 32.3 also explains why: "Extremely low density is the point… Emptiness is the
 * atmosphere, and the ability to disappear into it is a feature."
 *
 * <h2>Where the lists live</h2>
 *
 * <p>Split across two files, deliberately, so no concept has two names. SPEC 16.1's
 * {@code config.yml} already owns {@code worlds.city-enabled} and {@code worlds.blacklisted},
 * which are about <b>permission</b>, and they stay there. {@code world.yml} carries the world
 * <b>identity</b> SPEC 32.2 adds — which world is the main one, which are the resource worlds.
 * SPEC 37's {@code worlds.claimable} is not shipped, because it would be a second name for
 * {@code worlds.city-enabled}.
 */
public final class WorldRegistry {

    private final ConfigManager configs;
    private final Logger logger;

    public WorldRegistry(ConfigManager configs, Logger logger) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * A registry that answers questions but never warns.
     *
     * <p>The registry holds no state — every method reads the live configuration — so a
     * service that only wants to ask "may a city claim here" builds its own rather than having
     * one threaded through a constructor that already takes nine arguments. Only the startup
     * audit logs, and only the plugin calls that.
     */
    public WorldRegistry(ConfigManager configs) {
        this(configs, Logger.getLogger(WorldRegistry.class.getName()));
    }

    // ==================================================================================
    // Asking
    // ==================================================================================

    /** What {@code world} is for. Never null: an unmentioned world is {@link WorldKind#PLAIN}. */
    public WorldKind kindOf(String world) {
        if (world == null) {
            return WorldKind.PLAIN;
        }
        // Blacklisted wins over everything, including an operator who listed the same world as
        // city-enabled. Of the two readings of that contradiction, refusing is the one that
        // cannot lose anyone their land.
        if (contains(blacklisted(), world)) {
            return WorldKind.BLACKLISTED;
        }
        if (contains(cityEnabled(), world)) {
            return WorldKind.CLAIMABLE;
        }
        if (contains(miningClaimable(), world)) {
            return WorldKind.MINING;
        }
        return WorldKind.PLAIN;
    }

    /** Whether a city may claim land in {@code world}, SPEC 6.3 precondition 4. */
    public boolean allowsCityClaims(String world) {
        return kindOf(world).allowsCityClaims();
    }

    /** Whether a personal mining claim or a city waystation may exist here, SPEC 32.6. */
    public boolean allowsMiningClaims(String world) {
        return kindOf(world).allowsMiningClaims();
    }

    // ==================================================================================
    // The configured worlds, SPEC 32.2
    // ==================================================================================

    /** Where cities live. SPEC 32.2's main overworld, and Open Decision 4's "primary world". */
    public String main() {
        return name("worlds.main", "world");
    }

    public String mainNether() {
        return name("worlds.main-nether", "world_nether");
    }

    public String mainEnd() {
        return name("worlds.main-end", "world_the_end");
    }

    public String resource() {
        return name("worlds.resource", "resource");
    }

    public String resourceNether() {
        return name("worlds.resource-nether", "resource_nether");
    }

    /** SPEC 16.1 {@code worlds.city-enabled}. Where a city may claim. */
    public List<String> cityEnabled() {
        return configs.get(ConfigFile.CONFIG).getStringList("worlds.city-enabled");
    }

    /** SPEC 16.1 {@code worlds.blacklisted}. Never claimable, whatever else says. */
    public List<String> blacklisted() {
        return configs.get(ConfigFile.CONFIG).getStringList("worlds.blacklisted");
    }

    /** SPEC 37 {@code worlds.mining-claimable}. Where SPEC 32.6's mining claims may go. */
    public List<String> miningClaimable() {
        return configs.get(ConfigFile.WORLD).getStringList("worlds.mining-claimable");
    }

    /**
     * Every world SPEC 32.8's backups cover, deduplicated and in a stable order.
     *
     * <p>All five, not only the claimable ones. A player's mine in the resource world and a base
     * in the nether are builds too, and SPEC 32.8's whole premise is that region files accumulate
     * "wherever anyone has ever travelled".
     */
    public List<String> allManagedWorlds() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(
                List.of(main(), mainNether(), mainEnd(), resource(), resourceNether()));
        names.addAll(cityEnabled());
        names.addAll(miningClaimable());
        names.removeIf(name -> name == null || name.isBlank());
        return List.copyOf(names);
    }

    // ==================================================================================
    // SPEC 32.8's backup settings
    // ==================================================================================

    public dev.civitas.core.world.WorldBackupService.Settings backupSettings() {
        var world = configs.get(ConfigFile.WORLD);
        return new dev.civitas.core.world.WorldBackupService.Settings(
                world.getBoolean("backup.enabled", true),
                world.getInt("backup.full-keep-count", 2),
                world.getInt("backup.incremental-keep-days", 14),
                world.getBoolean("backup.war-zone-snapshot", true),
                world.getInt("backup.war-snapshot-retention-days", 7),
                world.getInt("backup.min-free-gb", 10));
    }

    public int backupFullIntervalHours() {
        return configs.get(ConfigFile.WORLD).getInt("backup.full-interval-hours", 168);
    }

    public int backupIncrementalIntervalHours() {
        return configs.get(ConfigFile.WORLD).getInt("backup.incremental-interval-hours", 24);
    }

    public int backupRunHour() {
        return configs.get(ConfigFile.WORLD).getInt("backup.run-hour", 5);
    }

    // ==================================================================================
    // SPEC 17.2 case 21
    // ==================================================================================

    /**
     * Warns about worlds that hold claims and are no longer claimable.
     *
     * <p>SPEC 17.2 case 21: "World is removed from {@code city-enabled} while claims exist
     * there. Existing claims persist and remain protected. New claims blocked. <b>Warn on
     * startup.</b>" The first two halves come free — protection is driven by whether a chunk is
     * claimed, and {@code ClaimService} refuses a new claim in a world this registry rejects.
     * The warning did not exist, and without it the operator's only evidence is players
     * reporting that they cannot expand a city that is plainly there.
     *
     * @param claimedWorlds every world that currently holds at least one claim
     * @return the worlds warned about, so a test can assert the warning rather than the log
     */
    public List<String> auditClaimedWorlds(Set<String> claimedWorlds) {
        List<String> stranded = claimedWorlds.stream()
                .filter(world -> !allowsCityClaims(world))
                .sorted()
                .toList();

        for (String world : stranded) {
            logger.log(Level.WARNING,
                    "World \"{0}\" holds city claims but is {1} in the configuration. SPEC 17.2 "
                            + "case 21: those claims stay and stay protected, and no new claim "
                            + "can be made there. Add it back to worlds.city-enabled if that was "
                            + "not intended.",
                    new Object[] {world, kindOf(world).name().toLowerCase(Locale.ROOT)});
        }
        return stranded;
    }

    // ==================================================================================
    // Internals
    // ==================================================================================

    private String name(String key, String fallback) {
        String configured = configs.get(ConfigFile.WORLD).getString(key, fallback);
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    private static boolean contains(List<String> names, String world) {
        return names.stream().anyMatch(name -> name.equalsIgnoreCase(world));
    }

    /** Every world this configuration mentions, for diagnostics. */
    public Set<String> configured() {
        Set<String> names = new LinkedHashSet<>();
        names.add(main());
        names.add(mainNether());
        names.add(mainEnd());
        names.add(resource());
        names.add(resourceNether());
        names.addAll(cityEnabled());
        names.addAll(miningClaimable());
        names.addAll(blacklisted());
        return names;
    }
}
