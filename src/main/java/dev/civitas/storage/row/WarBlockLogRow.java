package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code war_block_log}, SPEC 3.8. The rollback engine's entire input.
 *
 * @param sequence     monotonic per war; rollback replays in descending order
 * @param oldBlockData the state to restore, from {@code BlockData.getAsString()}
 * @param newBlockData what it became, kept for the audit trail
 * @param oldNbt       serialized tile-entity NBT, null for a plain block
 */
public record WarBlockLogRow(
        long id,
        int warId,
        long sequence,
        String world,
        int x,
        int y,
        int z,
        String oldBlockData,
        String newBlockData,
        byte[] oldNbt,
        UUID actorUuid,
        long timestamp) {
}
