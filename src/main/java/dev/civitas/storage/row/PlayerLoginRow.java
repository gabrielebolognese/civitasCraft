package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code player_logins}, added by V9 for SPEC 13.4's shared-connection rule.
 *
 * @param loginHash a salted hash of the connection address, never the address itself; see
 *                  {@code LoginFingerprint} for why
 */
public record PlayerLoginRow(
        UUID uuid,
        String loginHash,
        long updatedAt) {
}
