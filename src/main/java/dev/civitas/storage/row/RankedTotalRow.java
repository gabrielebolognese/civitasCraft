package dev.civitas.storage.row;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a ranked query: who, and how much.
 *
 * <p>Shared by the leaderboards that rank players by a summed figure. The total is a
 * {@link BigDecimal} whichever kind of quantity it holds, so the leaderboard cache has one
 * shape to work with, but <strong>each DAO is responsible for reading its own column with
 * the right reader</strong>: a money column goes through {@code money(rs, ...)} so SQLite's
 * minor units are converted, and a plain count is read with {@code getLong} and must not.
 * Reading a count as money would silently divide it by a hundred.
 *
 * @param name the player's last known name, for display without a second lookup
 */
public record RankedTotalRow(
        UUID uuid,
        String name,
        BigDecimal total) {
}
