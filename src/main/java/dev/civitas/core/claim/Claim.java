package dev.civitas.core.claim;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import dev.civitas.storage.row.ClaimRow;

/**
 * One owned chunk, SPEC 3.4.
 *
 * <p>Immutable except for its type and outpost link, which change when an outpost is
 * swallowed by the city body (SPEC 7.4). Everything else about a claim is fixed at purchase,
 * including {@code costPaid}, which the SPEC 6.4 refund is calculated from.
 */
public final class Claim {

    private final long id;
    private final int cityId;
    private final String world;
    private final int chunkX;
    private final int chunkZ;
    private final long claimedAt;
    private final UUID claimedBy;
    private final BigDecimal costPaid;
    private ClaimType type;
    private Integer outpostId;

    public Claim(long id, int cityId, String world, int chunkX, int chunkZ, long claimedAt,
                 UUID claimedBy, BigDecimal costPaid, ClaimType type, Integer outpostId) {
        this.id = id;
        this.cityId = cityId;
        this.world = Objects.requireNonNull(world, "world");
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.claimedAt = claimedAt;
        this.claimedBy = Objects.requireNonNull(claimedBy, "claimedBy");
        this.costPaid = Objects.requireNonNull(costPaid, "costPaid");
        this.type = Objects.requireNonNull(type, "type");
        this.outpostId = outpostId;
    }

    /**
     * @throws IllegalStateException if the stored type is not one this version understands,
     *                               which would otherwise silently downgrade a core chunk to
     *                               an ordinary one and let it be unclaimed
     */
    public static Claim fromRow(ClaimRow row) {
        ClaimType type = ClaimType.parse(row.type()).orElseThrow(() ->
                new IllegalStateException("Claim " + row.id() + " has unknown type '"
                        + row.type() + "'"));
        return new Claim(row.id(), row.cityId(), row.world(), row.chunkX(), row.chunkZ(),
                row.claimedAt(), row.claimedBy(), row.costPaid(), type, row.outpostId());
    }

    public ClaimRow toRow() {
        return new ClaimRow(id, cityId, world, chunkX, chunkZ, claimedAt, claimedBy,
                costPaid, type.name(), outpostId);
    }

    public long id() {
        return id;
    }

    public int cityId() {
        return cityId;
    }

    public String world() {
        return world;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public long claimedAt() {
        return claimedAt;
    }

    public UUID claimedBy() {
        return claimedBy;
    }

    /** What was actually paid, which the SPEC 6.4 and 5.3 refunds are a percentage of. */
    public BigDecimal costPaid() {
        return costPaid;
    }

    public ClaimType type() {
        return type;
    }

    public Integer outpostId() {
        return outpostId;
    }

    /** Applied when SPEC 7.4 converts a swallowed outpost into an ordinary claim. */
    void convertTo(ClaimType type, Integer outpostId) {
        this.type = Objects.requireNonNull(type, "type");
        this.outpostId = outpostId;
    }

    public boolean isCore() {
        return type == ClaimType.CORE;
    }

    public boolean isOutpost() {
        return type == ClaimType.OUTPOST;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Claim claim
                && claim.world.equals(world)
                && claim.chunkX == chunkX
                && claim.chunkZ == chunkZ;
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, chunkX, chunkZ);
    }

    @Override
    public String toString() {
        return "Claim[" + world + " " + chunkX + "," + chunkZ + " city=" + cityId
                + " " + type + "]";
    }
}
