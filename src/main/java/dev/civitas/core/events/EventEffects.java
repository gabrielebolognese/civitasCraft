package dev.civitas.core.events;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * What the running event changes, if anything.
 *
 * <h2>Why this is one class</h2>
 * SPEC 13.5's events reach into nine places: market prices, the market tax, claim cost, the
 * city creation fee, upkeep, two quest categories, crop growth and ore drops. The dangerous
 * failure is not an effect that fails to apply, which somebody notices within the hour. It is
 * an effect that fails to <em>stop</em>: a Market Boom whose +40% never comes off is a
 * permanent, silent change to the economy that nobody can attribute to anything.
 *
 * <p>So every one of those nine sites asks this class, and this class derives its answer from
 * the running event each time rather than from a flag set when the event began. There is no
 * state here to get stuck. When nothing is running, every method returns the neutral value by
 * construction rather than by remembering to reset.
 */
public final class EventEffects {

    /** No change: a multiplier of exactly one. */
    public static final BigDecimal NEUTRAL = BigDecimal.ONE;

    private final ConfigManager configs;
    private final Supplier<Optional<ServerEvent>> running;

    /**
     * @param running what is running now, asked afresh on every lookup so this class cannot
     *                hold a stale answer
     */
    public EventEffects(ConfigManager configs, Supplier<Optional<ServerEvent>> running) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.running = Objects.requireNonNull(running, "running");
    }

    /** Effects with nothing ever running, for tests and for a server with events disabled. */
    public static EventEffects none(ConfigManager configs) {
        return new EventEffects(configs, Optional::empty);
    }

    /** Whether this exact event is the one running. */
    public boolean isActive(ServerEventType type) {
        return running.get().map(event -> event.type() == type).orElse(false);
    }

    public Optional<ServerEventType> activeType() {
        return running.get().map(ServerEvent::type);
    }

    // ==================================================================================
    // Market, SPEC 13.5 Market Boom, Market Crash and Tax Holiday
    // ==================================================================================

    /** What the market pays, multiplied. */
    public BigDecimal sellPriceMultiplier() {
        if (isActive(ServerEventType.MARKET_BOOM)) {
            return percent(ServerEventType.MARKET_BOOM, "sell-price-percent");
        }
        if (isActive(ServerEventType.MARKET_CRASH)) {
            return percent(ServerEventType.MARKET_CRASH, "sell-price-percent");
        }
        return NEUTRAL;
    }

    /**
     * What the market charges, multiplied.
     *
     * <p>Only Market Crash moves this. A boom that raised what the market pays <em>and</em>
     * what it charges would leave the SPEC 4.4 spread untouched and the event pointless.
     */
    public BigDecimal buyPriceMultiplier() {
        if (isActive(ServerEventType.MARKET_CRASH)) {
            return percent(ServerEventType.MARKET_CRASH, "buy-price-percent");
        }
        return NEUTRAL;
    }

    /**
     * The market tax during a Tax Holiday, or empty when the normal SPEC 4.3 rate applies.
     *
     * <p>An override rather than a multiplier, because SPEC 13.5 states the holiday rate as a
     * number ("market tax 0%") and because multiplying by zero would silently discard the
     * SPEC 5.7 Market Access discount a city had already bought.
     */
    public Optional<Double> taxPercentOverride() {
        if (!isActive(ServerEventType.TAX_HOLIDAY)) {
            return Optional.empty();
        }
        return Optional.of(definition(ServerEventType.TAX_HOLIDAY)
                .getDouble("market-tax-percent", 0.0));
    }

    // ==================================================================================
    // Land, SPEC 13.5 Founders' Week
    // ==================================================================================

    /** What a chunk costs, multiplied. */
    public BigDecimal claimCostMultiplier() {
        if (isActive(ServerEventType.FOUNDERS_WEEK)) {
            return percent(ServerEventType.FOUNDERS_WEEK, "claim-cost-percent");
        }
        return NEUTRAL;
    }

    /** SPEC 13.5: "city creation free" during Founders' Week. */
    public boolean isCityCreationFree() {
        return isActive(ServerEventType.FOUNDERS_WEEK)
                && definition(ServerEventType.FOUNDERS_WEEK).getBoolean("city-creation-free", true);
    }

    // ==================================================================================
    // Upkeep, SPEC 13.5 Double Upkeep
    // ==================================================================================

    /**
     * Daily upkeep, multiplied.
     *
     * <p>Applied on top of the SPEC 5.7 Treasury Interest reduction rather than instead of it:
     * a city that paid for cheaper upkeep keeps its discount and pays double the discounted
     * figure, which is what both features promise read together.
     */
    public BigDecimal upkeepMultiplier() {
        if (isActive(ServerEventType.DOUBLE_UPKEEP)) {
            return BigDecimal.valueOf(definition(ServerEventType.DOUBLE_UPKEEP)
                    .getDouble("upkeep-multiplier", 2.0));
        }
        return NEUTRAL;
    }

    // ==================================================================================
    // Progression, SPEC 13.5 Harvest Festival and Gold Rush
    // ==================================================================================

    /**
     * A quest's reward, multiplied, for quests of one SPEC 13.1 category.
     *
     * <p>Keyed on the category already in {@code economy.yml} rather than on the metric, so
     * "farming quest rewards x2" means every farming quest and not only the crop one.
     */
    public BigDecimal questRewardMultiplier(String category) {
        if (category == null) {
            return NEUTRAL;
        }
        String normalised = category.trim().toLowerCase(java.util.Locale.ROOT);
        if ("farming".equals(normalised) && isActive(ServerEventType.HARVEST_FESTIVAL)) {
            return BigDecimal.valueOf(definition(ServerEventType.HARVEST_FESTIVAL)
                    .getDouble("farming-quest-reward-multiplier", 2.0));
        }
        if ("mining".equals(normalised) && isActive(ServerEventType.GOLD_RUSH)) {
            return BigDecimal.valueOf(definition(ServerEventType.GOLD_RUSH)
                    .getDouble("mining-quest-reward-multiplier", 2.0));
        }
        return NEUTRAL;
    }

    /** How many extra growth ticks a crop gets, SPEC 13.5 Harvest Festival. */
    public double cropGrowthMultiplier() {
        if (isActive(ServerEventType.HARVEST_FESTIVAL)) {
            return definition(ServerEventType.HARVEST_FESTIVAL)
                    .getDouble("crop-growth-multiplier", 2.0);
        }
        return 1.0;
    }

    /**
     * How much more an ore block drops, SPEC 13.5 Gold Rush.
     *
     * <p>SPEC 13.5 words this as an "ore generation bonus via a temporary loot modifier".
     * Generation cannot be what changes: the chunks a player mines were generated long before
     * the event started, and Paper offers no supported hook into world generation or loot
     * tables without NMS, which SPEC 2.1 forbids unless unavoidable. Multiplying what an ore
     * block drops is the observable effect a player would describe as a gold rush, and it is
     * the part that can actually be delivered. Recorded in OPEN_QUESTIONS.md.
     */
    public double oreDropMultiplier() {
        if (isActive(ServerEventType.GOLD_RUSH)) {
            return definition(ServerEventType.GOLD_RUSH).getDouble("ore-drop-multiplier", 2.0);
        }
        return 1.0;
    }

    // ==================================================================================
    // Invasion, SPEC 13.5
    // ==================================================================================

    public boolean isInvasionActive() {
        return isActive(ServerEventType.INVASION);
    }

    /** What a city's treasury earns for one invasion mob killed inside its claims. */
    public BigDecimal invasionRewardPerMob() {
        return BigDecimal.valueOf(definition(ServerEventType.INVASION)
                .getDouble("treasury-reward-per-mob", 25.0));
    }

    public int invasionMobsPerWave() {
        return definition(ServerEventType.INVASION).getInt("mobs-per-wave", 20);
    }

    public long invasionWaveIntervalMinutes() {
        return definition(ServerEventType.INVASION).getLong("wave-interval-minutes", 20);
    }

    public int invasionSpawnRadius() {
        return definition(ServerEventType.INVASION).getInt("spawn-radius-from-city-border", 32);
    }

    public java.util.List<String> invasionMobTypes() {
        java.util.List<String> configured =
                definition(ServerEventType.INVASION).getStringList("mob-types");
        return configured.isEmpty()
                ? java.util.List.of("ZOMBIE", "SKELETON", "SPIDER")
                : configured;
    }

    // ==================================================================================
    // Reading the definitions
    // ==================================================================================

    /** A percentage in config, as a multiplier: 140 becomes 1.4. */
    private BigDecimal percent(ServerEventType type, String key) {
        double value = definition(type).getDouble(key, 100.0);
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(100), 6,
                java.math.RoundingMode.HALF_UP);
    }

    /** One event's settings block, never null: a missing block reads as all defaults. */
    org.bukkit.configuration.ConfigurationSection definition(ServerEventType type) {
        FileConfiguration events = configs.get(ConfigFile.EVENTS);
        org.bukkit.configuration.ConfigurationSection section =
                events.getConfigurationSection(type.configPath());
        return section != null ? section : events.createSection(type.configPath() + ".missing");
    }

    /** How long this event runs, from its own config. */
    public long durationMillis(ServerEventType type) {
        double hours = definition(type).getDouble("duration-hours", 6.0);
        return (long) (hours * 3_600_000L);
    }

    /**
     * How far ahead this event is announced.
     *
     * <p>SPEC 13.5 says 30 minutes for events in general, and singles out Double Upkeep as
     * "announced 48h in advance". An event may therefore override the global figure with its
     * own {@code announce-hours-before}.
     */
    public long announceLeadMillis(ServerEventType type) {
        org.bukkit.configuration.ConfigurationSection definition = definition(type);
        if (definition.contains("announce-hours-before")) {
            return (long) (definition.getDouble("announce-hours-before") * 3_600_000L);
        }
        return configs.get(ConfigFile.EVENTS)
                .getLong("events.announce-minutes-before", 30) * 60_000L;
    }
}
