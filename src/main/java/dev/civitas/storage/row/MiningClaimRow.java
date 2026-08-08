package dev.civitas.storage.row;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One personal mining claim, SPEC 32.6.
 *
 * @param costPaid        what was paid, so a refund is of the price paid rather than of the
 *                        current price — the SPEC 21.4 F2 rule that a discount cannot be
 *                        laundered into a full-price refund
 * @param delinquentSince when upkeep first went unpaid, or null while it is current
 */
public record MiningClaimRow(long id, UUID uuid, String world, int chunkX, int chunkZ,
                             long claimedAt, BigDecimal costPaid, Long delinquentSince) {

    public MiningClaimRow {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid is required");
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world is required");
        }
        if (costPaid == null || costPaid.signum() < 0) {
            throw new IllegalArgumentException("costPaid must be present and not negative");
        }
    }

    /** Whether upkeep is unpaid. */
    public boolean isDelinquent() {
        return delinquentSince != null;
    }

    /**
     * Whether the SPEC 32.6 grace period has run out.
     *
     * <p>"7-day grace, then released. Blocks are not removed." A claim past its grace is
     * released rather than destroyed, which is the same choice SPEC 6.4 makes for city land:
     * "a city that shrinks leaves ruins, which is thematically good and mechanically simpler".
     */
    public boolean graceExpired(long now, long graceMillis) {
        return delinquentSince != null && now - delinquentSince >= graceMillis;
    }
}
