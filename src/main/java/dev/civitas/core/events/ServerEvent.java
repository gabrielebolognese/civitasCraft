package dev.civitas.core.events;

import java.util.Objects;

import dev.civitas.storage.row.ServerEventRow;

/**
 * One run of an event, SPEC 13.5.
 *
 * @param announced whether the SPEC 13.5 advance warning has gone out; stored, so a restart
 *                  between the announcement and the start does not announce it twice
 */
public record ServerEvent(
        int id,
        ServerEventType type,
        long startsAt,
        long endsAt,
        boolean announced) {

    public ServerEvent {
        Objects.requireNonNull(type, "type");
    }

    /** Reads a stored row, or empty if it names an event this build does not know. */
    public static java.util.Optional<ServerEvent> of(ServerEventRow row) {
        return ServerEventType.parse(row.eventKey()).map(type ->
                new ServerEvent(row.id(), type, row.startsAt(), row.endsAt(), row.announced()));
    }

    /** Whether the event is inside its window at {@code now}. */
    public boolean isRunning(long now) {
        return now >= startsAt && now < endsAt;
    }

    /** Whether its window has closed, whether or not anything noticed at the time. */
    public boolean hasEnded(long now) {
        return now >= endsAt;
    }

    /** Whether it is announced but has not started. */
    public boolean isPending(long now) {
        return now < startsAt;
    }

    public long millisRemaining(long now) {
        return Math.max(0L, endsAt - now);
    }

    public long durationMillis() {
        return Math.max(0L, endsAt - startsAt);
    }

    /** How far through the event we are, 0 to 1, for the SPEC 13.5 boss bar. */
    public float progress(long now) {
        long duration = durationMillis();
        if (duration <= 0L) {
            return 0f;
        }
        float remaining = millisRemaining(now) / (float) duration;
        return Math.max(0f, Math.min(1f, remaining));
    }

    public ServerEvent withAnnounced() {
        return new ServerEvent(id, type, startsAt, endsAt, true);
    }
}
