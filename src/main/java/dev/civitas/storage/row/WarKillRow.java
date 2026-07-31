package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code war_kills}, SPEC 3.9. Feeds the war score and the SPEC 8.8 kill feed.
 *
 * @param location free-form location description, as SPEC 3.9 specifies a single column
 */
public record WarKillRow(
        long id,
        int warId,
        UUID killerUuid,
        UUID victimUuid,
        long timestamp,
        String location) {
}
