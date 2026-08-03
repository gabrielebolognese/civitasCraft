package dev.civitas.core.income;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of the SPEC 13.1 quest pool, or of the SPEC 13.2 challenge list.
 *
 * @param id         the key it is stored under, and how progress finds it again
 * @param category   which SPEC 13.1 heading it sits under, for display and for weighting
 * @param metric     what it counts
 * @param baseTarget how much of it, before the SPEC 13.1 playtime scale
 * @param rewardMin  the low end of its reward band
 * @param rewardMax  the high end
 * @param weight     how often it comes up relative to the others
 */
public record QuestDefinition(
        String id,
        String category,
        QuestMetric metric,
        long baseTarget,
        BigDecimal rewardMin,
        BigDecimal rewardMax,
        double weight) {

    public QuestDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(rewardMin, "rewardMin");
        Objects.requireNonNull(rewardMax, "rewardMax");

        if (baseTarget <= 0) {
            throw new IllegalArgumentException("target must be positive for " + id);
        }
        if (rewardMin.signum() <= 0 || rewardMax.compareTo(rewardMin) < 0) {
            throw new IllegalArgumentException("reward band is inverted or empty for " + id);
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive for " + id);
        }
    }

    /** The language key for this quest's description. */
    public String messageKey() {
        return "quest." + id;
    }
}
