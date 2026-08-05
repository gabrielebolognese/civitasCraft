package dev.civitas.core.contest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The entry cuboid of SPEC 13.4 step 2. */
class PlotRegionTest {

    @Test
    @DisplayName("corners marked in either order give the same region")
    void cornersNormalise() {
        PlotRegion one = PlotRegion.between("world", 10, 70, -5, 0, 64, -20);
        PlotRegion other = PlotRegion.between("world", 0, 64, -20, 10, 70, -5);

        assertEquals(one, other);
        assertEquals(0, one.minX());
        assertEquals(10, one.maxX());
        assertEquals(-20, one.minZ());
    }

    @Test
    @DisplayName("size counts both corners, so one block measures one")
    void sizeIsInclusive() {
        PlotRegion single = PlotRegion.between("world", 5, 64, 5, 5, 64, 5);

        assertEquals(1, single.width());
        assertEquals(1, single.height());
        assertEquals(1, single.depth());
        assertEquals(1, single.longestEdge());
    }

    @Test
    @DisplayName("the longest edge is what the SPEC 13.4 limit is measured against")
    void longestEdge() {
        PlotRegion flat = PlotRegion.between("world", 0, 64, 0, 63, 67, 9);

        assertEquals(64, flat.width());
        assertEquals(4, flat.height());
        assertEquals(10, flat.depth());
        assertEquals(64, flat.longestEdge());
    }

    @Test
    @DisplayName("chunk bounds cover every chunk the region touches")
    void chunkBounds() {
        // Straddles the boundary at x=16, so it touches chunks 0 and 1.
        PlotRegion region = PlotRegion.between("world", 15, 64, 0, 17, 65, 3);

        assertEquals(0, region.minChunkX());
        assertEquals(1, region.maxChunkX());
        assertEquals(0, region.minChunkZ());
        assertEquals(0, region.maxChunkZ());
    }

    @Test
    @DisplayName("negative coordinates land in the right chunk")
    void negativeChunkBounds() {
        PlotRegion region = PlotRegion.between("world", -1, 64, -17, -1, 64, -17);

        assertEquals(-1, region.minChunkX());
        assertEquals(-2, region.minChunkZ());
    }

    @Test
    @DisplayName("a region survives a round trip through its stored form")
    void roundTrip() {
        PlotRegion region = PlotRegion.between("nether", -40, 8, 300, 5, 90, 260);

        PlotRegion read = PlotRegion.parse(region.serialise()).orElseThrow();

        assertEquals(region, read);
    }

    @Test
    @DisplayName("text that is not a region reads as nothing rather than throwing")
    void parseRejectsRubbish() {
        // The column is plain text and an admin can edit it, so every caller has to cope.
        assertTrue(PlotRegion.parse(null).isEmpty());
        assertTrue(PlotRegion.parse("").isEmpty());
        assertTrue(PlotRegion.parse("world").isEmpty());
        assertTrue(PlotRegion.parse("world:1,2:3,4,5").isEmpty());
        assertTrue(PlotRegion.parse("world:a,b,c:1,2,3").isEmpty());
    }

    @Test
    @DisplayName("contains respects the world as well as the coordinates")
    void containsChecksWorld() {
        PlotRegion region = PlotRegion.between("world", 0, 60, 0, 10, 70, 10);

        assertTrue(region.contains("world", 5, 65, 5));
        assertTrue(region.contains("world", 0, 60, 0));
        assertTrue(region.contains("world", 10, 70, 10));
        assertFalse(region.contains("world", 11, 65, 5));
        assertFalse(region.contains("nether", 5, 65, 5));
    }
}
