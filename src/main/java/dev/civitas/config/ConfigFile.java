package dev.civitas.config;

/**
 * The configuration files shipped in the jar and copied into the plugin data folder.
 * Every numeric value in SPEC.md lives in exactly one of these, per SPEC.md Section 16.
 */
public enum ConfigFile {

    /** Storage, worlds, language and performance. SPEC 16.1. */
    CONFIG("config.yml"),
    /** Cities, claims, upkeep, members, outposts, ranks and upgrades. SPEC 16.2. */
    CITIES("cities.yml"),
    /** Income, sinks, market, player shops, bounties and audit heuristics. SPEC 4. */
    ECONOMY("economy.yml"),
    /** Declaration, phases, scoring, rewards and the rollback engine. SPEC 16.3. */
    WAR("war.yml"),
    /** The defense unit catalogue and its placement and behaviour rules. SPEC 12. */
    DEFENSE("defense.yml"),
    /** Scheduled server events and the building contest cycle. SPEC 13.4 and 13.5. */
    EVENTS("events.yml");

    private final String fileName;

    ConfigFile(String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }
}
