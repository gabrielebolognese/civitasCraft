package dev.civitas.storage.row;

/** A row of {@code seasons}, SPEC 35.2. */
public record SeasonRow(int id, String name, String theme, long startsAt, long endsAt,
                        String state, Long endedAt) {
}
