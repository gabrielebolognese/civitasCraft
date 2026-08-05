package dev.civitas.storage.row;

/**
 * A row of {@code server_events}, added by V10 for SPEC 13.5.
 *
 * @param endedAt   null while the event is still open. What tells a restart the difference
 *                  between an event that ended cleanly and one the server was down for
 * @param announced whether the SPEC 13.5 advance warning has gone out
 */
public record ServerEventRow(
        int id,
        String eventKey,
        long startsAt,
        long endsAt,
        Long endedAt,
        boolean announced) {
}
