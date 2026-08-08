package dev.civitas.storage.row;

import java.util.UUID;

/**
 * One public warp, SPEC 32.7.
 *
 * @param name      what a player types. Unique and matched case-insensitively.
 * @param createdBy the admin who set it, or null for one the plugin generated
 * @param expiresAt when it disappears, or null for a permanent one. SPEC 40.1's contest
 *                  visit warps are the temporary case.
 */
public record WarpRow(String name, String world, double x, double y, double z,
                      float yaw, float pitch, UUID createdBy, long createdAt,
                      Long expiresAt) {

    public WarpRow {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world is required");
        }
    }

    /** Whether this warp has passed its expiry. A permanent warp never has. */
    public boolean hasExpired(long now) {
        return expiresAt != null && expiresAt <= now;
    }
}
