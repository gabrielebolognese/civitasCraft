package dev.civitas.storage.row;

/** A row of {@code contests}, SPEC 3.9. One biweekly cycle, SPEC 13.4. */
public record ContestRow(
        int id,
        String theme,
        long startsAt,
        long endsAt,
        String state) {
}
