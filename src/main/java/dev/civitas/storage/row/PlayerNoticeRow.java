package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A message waiting for a player who was not online to receive it, {@code player_notices}.
 *
 * @param messageKey  a {@code lang/} key, never rendered text — SPEC 2.1 keeps player-facing
 *                    strings out of Java, and out of the database for the same reason: a
 *                    notice stored in English would be unreadable to an Italian player and
 *                    unfixable after the fact
 * @param placeholders JSON of placeholder name to value, or null
 */
public record PlayerNoticeRow(
        long id,
        UUID uuid,
        String messageKey,
        String placeholders,
        long createdAt) {
}
