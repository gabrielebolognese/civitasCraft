package dev.civitas.core.city;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * An immutable set of {@link CityPermission} flags, stored as the {@code long} bitmask in
 * {@code city_ranks.permissions}.
 *
 * <p>A value type rather than a bare {@code long}: the bitmask is compared, subtracted and
 * tested for containment all over the rank rules, and doing that with raw bit twiddling is
 * where "cannot grant what you lack" quietly stops being true.
 *
 * @param bits the raw bitmask as persisted
 */
public record PermissionSet(long bits) {

    /** No permissions at all. */
    public static final PermissionSet NONE = new PermissionSet(0L);

    /** Every flag defined in {@link CityPermission}, and nothing else. */
    public static final PermissionSet ALL = new PermissionSet(allBits());

    private static long allBits() {
        long bits = 0L;
        for (CityPermission permission : CityPermission.values()) {
            bits |= permission.mask();
        }
        return bits;
    }

    /**
     * Discards bits that match no defined flag, so a mask written by an older or newer
     * version of the plugin cannot grant a permission that does not exist.
     */
    public PermissionSet {
        bits &= allBits();
    }

    public static PermissionSet of(CityPermission... permissions) {
        long bits = 0L;
        for (CityPermission permission : permissions) {
            bits |= permission.mask();
        }
        return new PermissionSet(bits);
    }

    public static PermissionSet of(Collection<CityPermission> permissions) {
        long bits = 0L;
        for (CityPermission permission : permissions) {
            bits |= permission.mask();
        }
        return new PermissionSet(bits);
    }

    /** Every flag except the given ones. Used for the Co-Mayor default rank, SPEC 5.4. */
    public static PermissionSet allExcept(CityPermission... excluded) {
        return ALL.without(excluded);
    }

    public boolean has(CityPermission permission) {
        return (bits & permission.mask()) != 0L;
    }

    public PermissionSet with(CityPermission... permissions) {
        return new PermissionSet(bits | of(permissions).bits());
    }

    public PermissionSet without(CityPermission... permissions) {
        return new PermissionSet(bits & ~of(permissions).bits());
    }

    /** Grants or revokes a single flag. */
    public PermissionSet set(CityPermission permission, boolean granted) {
        return granted ? with(permission) : without(permission);
    }

    /** @return true if this set holds every flag in {@code other} */
    public boolean containsAll(PermissionSet other) {
        return (bits & other.bits()) == other.bits();
    }

    /** @return the flags present in {@code other} but missing here */
    public PermissionSet missingFrom(PermissionSet other) {
        return new PermissionSet(other.bits() & ~bits);
    }

    public boolean isEmpty() {
        return bits == 0L;
    }

    public int size() {
        return Long.bitCount(bits);
    }

    public Set<CityPermission> toSet() {
        EnumSet<CityPermission> permissions = EnumSet.noneOf(CityPermission.class);
        for (CityPermission permission : CityPermission.values()) {
            if (has(permission)) {
                permissions.add(permission);
            }
        }
        return permissions;
    }

    @Override
    public String toString() {
        return toSet().toString();
    }
}
