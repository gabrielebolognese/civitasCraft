package dev.civitas.storage.row;

import java.math.BigDecimal;

/**
 * One line of a ranked query over cities: which city, and how much.
 *
 * <p>The city counterpart of {@link RankedTotalRow}, and it carries the same warning: the
 * total is a {@link BigDecimal} whatever kind of quantity it holds, so each DAO must read its
 * own column with the right reader. A money column goes through {@code money(rs, ...)}; a
 * rating or a count does not, because on SQLite the money reader divides by a hundred.
 */
public record RankedCityRow(
        int cityId,
        String name,
        BigDecimal total) {
}
