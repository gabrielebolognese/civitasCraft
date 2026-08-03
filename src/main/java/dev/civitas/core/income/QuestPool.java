package dev.civitas.core.income;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The weighted pool SPEC 13.1 draws daily quests from, and the SPEC 13.1 difficulty scale.
 *
 * <h2>Why the scale multiplies target and reward by the same number</h2>
 * SPEC 13.1 asks for two things that pull against each other: difficulty should rise with
 * playtime "so veterans do not trivially clear beginner quests", and "the effort-to-reward
 * ratio stays flat". Scaling both by one factor satisfies the second by construction rather
 * than by tuning, so no future change to the curve can accidentally make veteran quests a
 * better or worse deal per unit of work than beginner ones. SPEC gives no formula, so the
 * curve is a config key: a linear ramp from 1x at zero playtime to a configurable ceiling.
 *
 * <h2>Why the daily draw is seeded</h2>
 * A player's three quests are drawn from a seed made of their UUID and the date. A restart,
 * a relog, or two servers reading the same database all produce the same three quests, and
 * nobody can reroll a quest they dislike by disconnecting.
 */
public final class QuestPool {

    private final ConfigManager configs;
    private final Logger logger;

    private final Map<String, QuestDefinition> definitions = new LinkedHashMap<>();

    public QuestPool(ConfigManager configs, Logger logger) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Loading
    // ==================================================================================

    /**
     * Reads the pool from {@code economy.yml}.
     *
     * @param path the section to read, so quests and challenges share this class
     * @return how many entries loaded
     */
    public int load(String path) {
        definitions.clear();
        FileConfiguration economy = configs.get(ConfigFile.ECONOMY);
        ConfigurationSection section = economy.getConfigurationSection(path);
        if (section == null) {
            logger.warning(() -> "No " + path + " section; nothing will be handed out.");
            return 0;
        }

        for (String id : section.getKeys(false)) {
            try {
                definitions.put(id, parse(economy, path + "." + id, id));
            } catch (IllegalArgumentException e) {
                // One bad line costs that quest, not the whole pool.
                warn(id, e.getMessage());
            }
        }
        return definitions.size();
    }

    /**
     * Reads one entry by full path from the root configuration.
     *
     * <p>By path rather than through a nested {@link ConfigurationSection}, and that is not a
     * style choice. Bukkit backs a configuration with the packaged defaults, and a section
     * fetched out of an on-disk file that predates a new block resolves its <em>keys</em>
     * from the defaults but its <em>values</em> against the on-disk tree, where they are
     * absent. An operator upgrading the plugin would get a pool of quests with no metric and
     * no target, which is exactly what the first boot after this was written produced. Full
     * paths go through the defaults properly.
     */
    private QuestDefinition parse(FileConfiguration economy, String path, String id) {
        String metricName = economy.getString(path + ".metric", "");
        QuestMetric metric;
        try {
            metric = QuestMetric.valueOf(metricName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("names an unknown metric '" + metricName + "'");
        }

        return new QuestDefinition(id,
                economy.getString(path + ".category", "general"),
                metric,
                economy.getLong(path + ".target", 0),
                new BigDecimal(economy.getString(path + ".reward-min", "0")),
                new BigDecimal(economy.getString(path + ".reward-max", "0")),
                economy.getDouble(path + ".weight", 1.0));
    }

    private void warn(String id, String problem) {
        logger.warning(() -> "Quest '" + id + "' " + problem + "; it will not be handed out.");
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    public Optional<QuestDefinition> byId(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public List<QuestDefinition> all() {
        return List.copyOf(definitions.values());
    }

    public boolean isEmpty() {
        return definitions.isEmpty();
    }

    public int size() {
        return definitions.size();
    }

    // ==================================================================================
    // Drawing
    // ==================================================================================

    /**
     * Draws {@code count} distinct quests, weighted.
     *
     * <p>Distinct because three copies of "harvest wheat" is not three quests. If the pool
     * holds fewer than asked for, everything in it is returned rather than repeating one.
     *
     * @param seed makes the draw reproducible for a given player and day
     */
    public List<QuestDefinition> draw(int count, long seed) {
        List<QuestDefinition> remaining = new ArrayList<>(definitions.values());
        List<QuestDefinition> drawn = new ArrayList<>(Math.min(count, remaining.size()));
        Random random = new Random(seed);

        while (drawn.size() < count && !remaining.isEmpty()) {
            double total = remaining.stream().mapToDouble(QuestDefinition::weight).sum();
            double roll = random.nextDouble() * total;

            int index = remaining.size() - 1;
            double running = 0;
            for (int candidate = 0; candidate < remaining.size(); candidate++) {
                running += remaining.get(candidate).weight();
                if (roll < running) {
                    index = candidate;
                    break;
                }
            }
            drawn.add(remaining.remove(index));
        }
        return drawn;
    }

    // ==================================================================================
    // The SPEC 13.1 difficulty scale
    // ==================================================================================

    /**
     * The factor applied to both target and reward.
     *
     * <p>Linear from 1 at no playtime to {@code max-scale} at {@code scale-hours}, and flat
     * after that, so a veteran's quests stop growing rather than becoming a second job.
     */
    public double scaleFor(long activePlaytimeMs) {
        double hours = activePlaytimeMs / 3_600_000.0;
        double rampHours = configs.get(ConfigFile.ECONOMY)
                .getDouble("income.quests.scale-hours", 100.0);
        double maxScale = configs.get(ConfigFile.ECONOMY)
                .getDouble("income.quests.max-scale", 2.5);

        if (rampHours <= 0 || maxScale <= 1.0) {
            return 1.0;
        }
        double progress = Math.min(1.0, hours / rampHours);
        return 1.0 + (maxScale - 1.0) * progress;
    }

    /** The scaled target for a quest handed to a player with this much playtime. */
    public long targetFor(QuestDefinition definition, long activePlaytimeMs) {
        return Math.max(1L, Math.round(definition.baseTarget() * scaleFor(activePlaytimeMs)));
    }

    /**
     * The scaled reward.
     *
     * <p>Picked from the definition's band by the same seed as the draw, then scaled by the
     * same factor as the target. SPEC 13.1's bold rule holds by construction: nothing here
     * reads a balance, so no reward can scale with wealth.
     */
    public BigDecimal rewardFor(QuestDefinition definition, long activePlaytimeMs, long seed) {
        BigDecimal span = definition.rewardMax().subtract(definition.rewardMin());
        BigDecimal offset = span.signum() <= 0
                ? BigDecimal.ZERO
                : span.multiply(BigDecimal.valueOf(new Random(seed).nextDouble()));

        BigDecimal base = definition.rewardMin().add(offset);
        return dev.civitas.core.economy.Money.floor(
                base.multiply(BigDecimal.valueOf(scaleFor(activePlaytimeMs))));
    }
}
