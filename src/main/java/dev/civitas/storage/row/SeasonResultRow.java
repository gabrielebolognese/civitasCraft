package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code season_results}, SPEC 35.2's Hall of Fame.
 *
 * @param value rendered at scoring time rather than stored as a number, because the underlying
 *              figure keeps moving after the season closes and a Hall of Fame that changed when
 *              somebody kept playing would not be a record of anything
 */
public record SeasonResultRow(long id, int seasonId, String board, int position,
                              UUID holderUuid, String holderName, String value) {
}
