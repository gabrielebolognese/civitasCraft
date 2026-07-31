package dev.civitas.storage.row;

import java.util.UUID;

/** A row of {@code city_invites}, SPEC 3.9. Expires after {@code members.invite-expiry-minutes}. */
public record CityInviteRow(
        int cityId,
        UUID inviteeUuid,
        UUID inviterUuid,
        long expiresAt) {
}
