package dev.civitas.storage.row;

/**
 * A row of {@code war_chunk_hashes}, added by V11 for SPEC 11.8.4.
 *
 * @param hashBefore the chunk's checksum at war start
 * @param hashAfter  the same chunk after the rollback, or null until one has run. A pair that
 *                   disagrees means something changed the world that no listener saw, which
 *                   SPEC 11.8.4 exists to make visible rather than to fix
 */
public record WarChunkHashRow(
        int warId,
        String world,
        int chunkX,
        int chunkZ,
        long hashBefore,
        Long hashAfter) {

    /** Whether the chunk came back to what it was. Unknown until the rollback has run. */
    public boolean matches() {
        return hashAfter != null && hashAfter == hashBefore;
    }
}
