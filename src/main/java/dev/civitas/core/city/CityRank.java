package dev.civitas.core.city;

import java.util.Objects;

import dev.civitas.storage.row.CityRankRow;

/**
 * A permission group inside a city, SPEC 5.4.
 *
 * <p>Weight is what makes the hierarchy work: a member may only act on ranks strictly below
 * their own. The Mayor rank is always weight {@link #MAYOR_WEIGHT}, which nothing else may
 * reach, so the mayor can never be outranked inside their own city.
 */
public final class CityRank {

    /** The mayor's weight. Fixed by SPEC 5.4 and not editable. */
    public static final int MAYOR_WEIGHT = 100;

    private final int id;
    private final int cityId;
    private String name;
    private int weight;
    private PermissionSet permissions;
    private boolean defaultRank;

    public CityRank(int id, int cityId, String name, int weight, PermissionSet permissions,
                    boolean defaultRank) {
        this.id = id;
        this.cityId = cityId;
        this.name = Objects.requireNonNull(name, "name");
        this.weight = weight;
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.defaultRank = defaultRank;
    }

    public static CityRank fromRow(CityRankRow row) {
        return new CityRank(row.id(), row.cityId(), row.name(), row.weight(),
                new PermissionSet(row.permissions()), row.isDefault());
    }

    public CityRankRow toRow() {
        return new CityRankRow(id, cityId, name, weight, permissions.bits(), defaultRank);
    }

    public int id() {
        return id;
    }

    public int cityId() {
        return cityId;
    }

    public String name() {
        return name;
    }

    void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public int weight() {
        return weight;
    }

    void setWeight(int weight) {
        this.weight = weight;
    }

    public PermissionSet permissions() {
        return permissions;
    }

    void setPermissions(PermissionSet permissions) {
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    /** Whether new joiners receive this rank. Exactly one rank per city has it. */
    public boolean isDefault() {
        return defaultRank;
    }

    void setDefault(boolean defaultRank) {
        this.defaultRank = defaultRank;
    }

    public boolean isMayorRank() {
        return weight >= MAYOR_WEIGHT;
    }

    public boolean has(CityPermission permission) {
        return permissions.has(permission);
    }

    /**
     * Whether a holder of this rank may act on {@code target}.
     *
     * <p>Strictly greater, per SPEC 5.4: equal weight is not enough, so two Co-Mayors cannot
     * demote each other into a loop.
     */
    public boolean outranks(CityRank target) {
        return weight > target.weight();
    }

    @Override
    public String toString() {
        return "CityRank[" + name + " w=" + weight + "]";
    }
}
