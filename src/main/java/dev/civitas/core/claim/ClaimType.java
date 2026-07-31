package dev.civitas.core.claim;

import java.util.Locale;
import java.util.Optional;

/**
 * What kind of claim a chunk is, SPEC 3.4.
 *
 * <p>The three types differ in which rules they are exempt from, which is why this is an
 * enum rather than a boolean: the core is exempt from adjacency because it is the seed, and
 * outposts are exempt from both adjacency and contiguity because being detached is their
 * entire purpose (SPEC 6.1, 7.1).
 */
public enum ClaimType {

    /** The founding chunk. Cannot be unclaimed, exempt from adjacency. */
    CORE,
    /** An ordinary chunk. Must be edge-adjacent and must not break contiguity. */
    NORMAL,
    /** A detached single chunk belonging to an outpost, SPEC 7. */
    OUTPOST;

    /** Whether this type takes part in the SPEC 6.1 contiguity check. */
    public boolean isContiguous() {
        return this != OUTPOST;
    }

    /** @param name a stored {@code claims.type} value, case-insensitive */
    public static Optional<ClaimType> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalised = name.trim().toUpperCase(Locale.ROOT);
        for (ClaimType type : values()) {
            if (type.name().equals(normalised)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
