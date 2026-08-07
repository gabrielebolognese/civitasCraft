package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code admin_protected_chunks}, SPEC 9.4.3.
 *
 * <p>Unclaimable, unbuildable and war-immune. SPEC 11.6 lists admin-protected chunks among the
 * three things that stay protected even during a war, alongside the City Hall and defense unit
 * spawners.
 */
public record ProtectedChunkRow(
        String world,
        int chunkX,
        int chunkZ,
        UUID protectedBy,
        long protectedAt,
        String reason) {
}
