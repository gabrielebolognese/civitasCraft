package dev.civitas.core.contest;

import java.util.Locale;
import java.util.Optional;

/**
 * The cuboid a city enters into a contest, SPEC 13.4 step 2.
 *
 * <p>Marked by two corners and normalised on construction, so the two ways a player can mark
 * the same box produce the same region and the same stored text. Stored in
 * {@code contest_entries.plot_region} as {@code world:x,y,z:x,y,z}, which is readable in a
 * database dump; an admin resolving a dispute about a build should not need this class to see
 * where it was.
 */
public record PlotRegion(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public PlotRegion {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("corners are not normalised");
        }
    }

    /** Builds a region from two corners in any order. */
    public static PlotRegion between(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new PlotRegion(world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** Blocks along each axis. Inclusive of both corners, so a single block measures 1. */
    public int width() {
        return maxX - minX + 1;
    }

    public int height() {
        return maxY - minY + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    /** The longest edge, which is what SPEC 13.4's 64x64x64 limit caps. */
    public int longestEdge() {
        return Math.max(width(), Math.max(height(), depth()));
    }

    public boolean contains(String otherWorld, int x, int y, int z) {
        return world.equals(otherWorld)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /** Chunk coordinates the region touches, for the "is it inside my claims" test. */
    public int minChunkX() {
        return minX >> 4;
    }

    public int maxChunkX() {
        return maxX >> 4;
    }

    public int minChunkZ() {
        return minZ >> 4;
    }

    public int maxChunkZ() {
        return maxZ >> 4;
    }

    /** The middle of the box, which is where a visitor is shown it from. */
    public double centreX() {
        return minX + width() / 2.0;
    }

    public double centreZ() {
        return minZ + depth() / 2.0;
    }

    /** {@code world:x,y,z:x,y,z}. */
    public String serialise() {
        return world + ":" + minX + "," + minY + "," + minZ + ":" + maxX + "," + maxY + "," + maxZ;
    }

    /**
     * Reads a stored region back.
     *
     * @return empty if the text is not a region this build understands, which a caller must
     *         handle rather than assume: the column is plain text and an admin can edit it
     */
    public static Optional<PlotRegion> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] parts = text.split(":");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String[] first = parts[1].split(",");
        String[] second = parts[2].split(",");
        if (first.length != 3 || second.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(between(parts[0],
                    Integer.parseInt(first[0].trim()), Integer.parseInt(first[1].trim()),
                    Integer.parseInt(first[2].trim()),
                    Integer.parseInt(second[0].trim()), Integer.parseInt(second[1].trim()),
                    Integer.parseInt(second[2].trim())));
        } catch (IllegalArgumentException e) {
            // Covers both a coordinate that is not a number and a world name that is blank;
            // NumberFormatException is an IllegalArgumentException.
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return serialise().toLowerCase(Locale.ROOT);
    }
}
