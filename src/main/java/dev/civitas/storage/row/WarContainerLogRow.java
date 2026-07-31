package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code war_container_log}, SPEC 11.7.
 *
 * <p>Records items taken from containers during a war. Purely for the post-war report and
 * admin dispute resolution: looted items are never returned by rollback (SPEC 11.7), so
 * this log explains where they went rather than undoing it.
 */
public record WarContainerLogRow(
        long id,
        int warId,
        String world,
        int x,
        int y,
        int z,
        UUID actorUuid,
        String item,
        int quantity,
        long timestamp) {
}
