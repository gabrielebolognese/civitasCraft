package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code staff_notes}, SPEC 22.7.2.
 *
 * @param authorUuid null for a note left by the console, which is how an automated one would land
 */
public record StaffNoteRow(long id, UUID targetUuid, UUID authorUuid, String note,
                           long createdAt) {
}
