package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code war_entity_snapshots}, SPEC 11.8.3.
 *
 * <p>Taken at war start, because a mob killed in a war is gone before anything can ask it what
 * it was. {@code diedAt} is set when it dies, so the rollback knows which entities to bring
 * back rather than duplicating the ones that survived.
 */
public record WarEntitySnapshotRow(
        long id,
        int warId,
        UUID entityUuid,
        String entityType,
        String world,
        double x,
        double y,
        double z,
        byte[] payload,
        Long diedAt,
        long snapshotAt) {
}
