package dev.civitas.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 32.8's unit of backup.
 *
 * <p>{@link Coverage#negativeChunksFloor} is the one that matters. Region coordinates are an
 * arithmetic shift, so chunk −1 belongs to region −1 and not to region 0; getting that wrong
 * backs up the wrong file for every city west or north of origin, and the mistake is invisible
 * until somebody needs the snapshot.
 */
class RegionFilesTest {

    @TempDir
    Path directory;

    @Nested
    @DisplayName("chunk to region")
    class Coverage {

        @Test
        @DisplayName("32 chunks to a region")
        void thirtyTwoChunks() {
            assertEquals(0, RegionFiles.regionOfChunk(0));
            assertEquals(0, RegionFiles.regionOfChunk(31));
            assertEquals(1, RegionFiles.regionOfChunk(32));
            assertEquals(3, RegionFiles.regionOfChunk(127));
        }

        @Test
        @DisplayName("negative chunks floor rather than truncate")
        void negativeChunksFloor() {
            // Division would give 0 for chunk -1, which names the wrong file. A shift gives -1.
            assertEquals(-1, RegionFiles.regionOfChunk(-1));
            assertEquals(-1, RegionFiles.regionOfChunk(-32));
            assertEquals(-2, RegionFiles.regionOfChunk(-33));
        }

        @Test
        @DisplayName("a war zone collapses to a handful of files")
        void collapses() {
            // The whole reason the pre-war snapshot is affordable: 200 adjacent chunks are one
            // or two files, and copying the same one 200 times would be the difference between
            // a snapshot that takes a second and one that takes a minute.
            List<int[]> zone = new java.util.ArrayList<>();
            for (int x = 0; x < 20; x++) {
                for (int z = 0; z < 10; z++) {
                    zone.add(new int[] {x, z});
                }
            }

            Set<String> files = RegionFiles.covering(zone);

            assertEquals(200, zone.size());
            assertEquals(1, files.size());
            assertTrue(files.contains("r.0.0.mca"));
        }

        @Test
        @DisplayName("a zone straddling a boundary takes both files")
        void straddles() {
            Set<String> files = RegionFiles.covering(
                    List.of(new int[] {31, 0}, new int[] {32, 0}, new int[] {-1, -1}));

            assertEquals(Set.of("r.0.0.mca", "r.1.0.mca", "r.-1.-1.mca"), files);
        }
    }

    @Nested
    @DisplayName("file names")
    class Naming {

        @Test
        @DisplayName("recognises a region file and nothing else")
        void recognises() {
            assertTrue(RegionFiles.isRegionFile("r.0.0.mca"));
            assertTrue(RegionFiles.isRegionFile("r.-12.34.mca"));
            assertFalse(RegionFiles.isRegionFile("r.0.0.mcc"), "an entity file is not a region");
            assertFalse(RegionFiles.isRegionFile("level.dat"));
            assertFalse(RegionFiles.isRegionFile("r.0.0.mca.tmp"));
        }

        @Test
        @DisplayName("round trips through a name")
        void roundTrip() {
            assertEquals("r.-12.34.mca", RegionFiles.nameOf(-12, 34));

            int[] parsed = RegionFiles.parse("r.-12.34.mca").orElseThrow();
            assertEquals(-12, parsed[0]);
            assertEquals(34, parsed[1]);
        }
    }

    @Nested
    @DisplayName("finding a world's regions on disk")
    class Folders {

        @Test
        @DisplayName("an overworld keeps them in region/")
        void overworld() throws IOException {
            File world = directory.resolve("world").toFile();
            Files.createDirectories(world.toPath().resolve("region"));

            assertEquals(new File(world, "region"),
                    RegionFiles.regionFolderOf(world).orElseThrow());
        }

        @Test
        @DisplayName("a nether keeps them in DIM-1/region/")
        void nether() throws IOException {
            // Probed rather than derived: where a dimension puts its regions is a server-layout
            // detail, not an API guarantee, and guessing wrong backs up nothing at all.
            File world = directory.resolve("world_nether").toFile();
            Files.createDirectories(world.toPath().resolve("DIM-1").resolve("region"));

            assertEquals(new File(new File(world, "DIM-1"), "region"),
                    RegionFiles.regionFolderOf(world).orElseThrow());
        }

        @Test
        @DisplayName("a folder with no regions answers empty rather than inventing one")
        void none() throws IOException {
            File world = directory.resolve("empty").toFile();
            Files.createDirectories(world.toPath());

            assertTrue(RegionFiles.regionFolderOf(world).isEmpty());
            assertTrue(RegionFiles.regionFolderOf(null).isEmpty());
        }

        @Test
        @DisplayName("listing skips everything that is not a region file")
        void listing() throws IOException {
            Path regions = Files.createDirectories(directory.resolve("region"));
            Files.writeString(regions.resolve("r.0.0.mca"), "aaa");
            Files.writeString(regions.resolve("r.1.0.mca"), "bb");
            Files.writeString(regions.resolve("level.dat"), "ignored");
            Files.writeString(regions.resolve("r.0.0.mca.tmp"), "ignored");

            assertEquals(2, RegionFiles.listRegions(regions).size());
            assertEquals(5, RegionFiles.sizeOf(regions), "and sizes only what it lists");
        }
    }
}
