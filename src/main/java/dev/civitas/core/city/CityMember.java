package dev.civitas.core.city;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import dev.civitas.storage.row.CityMemberRow;

/** A player's membership of a city, SPEC 3.9 {@code city_members}. */
public final class CityMember {

    private final UUID uuid;
    private final int cityId;
    private int rankId;
    private final long joinedAt;
    private BigDecimal contributedTotal;

    public CityMember(UUID uuid, int cityId, int rankId, long joinedAt, BigDecimal contributedTotal) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.cityId = cityId;
        this.rankId = rankId;
        this.joinedAt = joinedAt;
        this.contributedTotal = Objects.requireNonNull(contributedTotal, "contributedTotal");
    }

    public static CityMember fromRow(CityMemberRow row) {
        return new CityMember(row.uuid(), row.cityId(), row.rankId(), row.joinedAt(),
                row.contributedTotal());
    }

    public CityMemberRow toRow() {
        return new CityMemberRow(uuid, cityId, rankId, joinedAt, contributedTotal);
    }

    public UUID uuid() {
        return uuid;
    }

    public int cityId() {
        return cityId;
    }

    public int rankId() {
        return rankId;
    }

    void setRankId(int rankId) {
        this.rankId = rankId;
    }

    public long joinedAt() {
        return joinedAt;
    }

    /** Lifetime treasury deposits, the SPEC 13.3 Contribution metric. */
    public BigDecimal contributedTotal() {
        return contributedTotal;
    }

    void setContributedTotal(BigDecimal contributedTotal) {
        this.contributedTotal = Objects.requireNonNull(contributedTotal, "contributedTotal");
    }

    @Override
    public String toString() {
        return "CityMember[" + uuid + " city=" + cityId + " rank=" + rankId + "]";
    }
}
