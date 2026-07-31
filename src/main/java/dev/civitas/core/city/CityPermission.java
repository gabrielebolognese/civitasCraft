package dev.civitas.core.city;

import java.util.Locale;
import java.util.Optional;

/**
 * The 22 permission flags a city rank may hold, SPEC 5.4.
 *
 * <p>The bit index of each flag is part of the stored data: {@code city_ranks.permissions} is
 * a bitmask, so reordering this enum or reusing an index would silently change what every
 * existing rank can do. Add new flags at the end, never in the middle.
 */
public enum CityPermission {

    /** Place and break blocks in claims. */
    BUILD(0),
    /** Open chests, furnaces and hoppers, and take from them. */
    CONTAINER(1),
    /** Open containers but not remove items. */
    CONTAINER_READONLY(2),
    /** Doors, buttons, levers, beds. */
    INTERACT(3),
    /** Claim new chunks, spending the treasury. */
    CLAIM(4),
    /** Unclaim chunks. */
    UNCLAIM(5),
    /** Invite players. */
    INVITE(6),
    /** Kick players. */
    KICK(7),
    /** Create, edit and assign ranks. */
    MANAGE_RANKS(8),
    /** Deposit into the treasury. */
    DEPOSIT(9),
    /** Withdraw from the treasury. */
    WITHDRAW(10),
    /** Move the city spawn. */
    SET_SPAWN(11),
    /** Create and delete outposts. */
    OUTPOST_MANAGE(12),
    /** Teleport to outposts. */
    OUTPOST_TP(13),
    /** Declare war. */
    DECLARE_WAR(14),
    /** Alliances and truces. */
    MANAGE_DIPLOMACY(15),
    /** Buy and place defense units. */
    MANAGE_DEFENSE(16),
    /** Purchase city upgrades. */
    MANAGE_UPGRADES(17),
    /** MOTD, open join, display name. */
    EDIT_SETTINGS(18),
    /** Submit the city's entry to a contest. */
    CONTEST_SUBMIT(19),
    /** Transfer mayorship. */
    TRANSFER(20),
    /** Disband the city. */
    DISBAND(21);

    private final int bitIndex;
    private final long mask;

    CityPermission(int bitIndex) {
        this.bitIndex = bitIndex;
        this.mask = 1L << bitIndex;
    }

    public int bitIndex() {
        return bitIndex;
    }

    public long mask() {
        return mask;
    }

    /**
     * @param name a flag name, case-insensitive, hyphens or underscores
     * @return the flag, or empty if the name matches none
     */
    public static Optional<CityPermission> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalised = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (CityPermission permission : values()) {
            if (permission.name().equals(normalised)) {
                return Optional.of(permission);
            }
        }
        return Optional.empty();
    }
}
