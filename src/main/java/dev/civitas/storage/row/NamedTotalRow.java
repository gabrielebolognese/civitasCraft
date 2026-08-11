package dev.civitas.storage.row;

import java.math.BigDecimal;

/**
 * A name and a monetary total, for the reports that rank things without needing a UUID.
 *
 * <p>Distinct from {@link RankedTotalRow}, which carries a player id because its callers go on to
 * look the player up. A city is identified by its name everywhere a player sees it.
 */
public record NamedTotalRow(String name, BigDecimal total) {
}
