package dev.civitas.storage.row;

import java.util.UUID;

/**
 * One notification preference a player has changed from its default, SPEC 23.6.
 *
 * @param uuid     the player
 * @param category the SPEC 23.6 category key
 * @param enabled  what they set it to
 */
public record PlayerToggleRow(UUID uuid, String category, boolean enabled) {

    public PlayerToggleRow {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid is required");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category is required");
        }
    }
}
