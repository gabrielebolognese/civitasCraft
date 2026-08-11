package dev.civitas.core.world;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Region files, SPEC 32.8. Pure arithmetic and file listing, no Bukkit.
 *
 * <h2>Why this exists as its own class</h2>
 *
 * <p>A backup of "the war zone" is a backup of the <em>region files</em> the zone touches, not of
 * its chunks: Minecraft stores 32x32 chunks in one {@code r.X.Z.mca} and there is no way to copy
 * part of one. So a 200-chunk war zone is a handful of files, which is what makes SPEC 32.8's
 * pre-war snapshot cheap enough to take before every war — and it is also why a snapshot always
 * covers <b>more</b> ground than the zone. Restoring one restores whole regions.
 *
 * <p>That over-coverage is the reason {@code /ca world restore war} is guarded by typing the war
 * id twice: it is not a surgical undo, it is a rewind of every region the fighting touched.
 */
public final class RegionFiles {

    /** {@code r.-1.3.mca} — the two numbers are region coordinates, not chunk coordinates. */
    private static final Pattern REGION_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    /** 32x32 chunks to a region, which is 512x512 blocks. */
    public static final int CHUNKS_PER_REGION = 32;

    private RegionFiles() {
    }

    /** The region a chunk lives in. Arithmetic shift, so negatives floor rather than truncate. */
    public static int regionOfChunk(int chunkCoordinate) {
        return chunkCoordinate >> 5;
    }

    public static String nameOf(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".mca";
    }

    /** @return the region coordinates a file name encodes, or empty if it is not a region file */
    public static Optional<int[]> parse(String fileName) {
        Matcher matcher = REGION_NAME.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new int[] {
                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))});
    }

    public static boolean isRegionFile(String fileName) {
        return REGION_NAME.matcher(fileName).matches();
    }

    /**
     * The region files a set of chunks occupies.
     *
     * <p>Deduplicated, because a war zone of two hundred adjacent chunks is usually one or two
     * files and copying the same one repeatedly is the difference between a snapshot that takes
     * a second and one that takes a minute.
     *
     * @param chunks {@code {chunkX, chunkZ}} pairs
     */
    public static Set<String> covering(Iterable<int[]> chunks) {
        Set<String> names = new LinkedHashSet<>();
        for (int[] chunk : chunks) {
            names.add(nameOf(regionOfChunk(chunk[0]), regionOfChunk(chunk[1])));
        }
        return names;
    }

    /**
     * Where a world keeps its regions.
     *
     * <p>Bukkit hands out the world <em>folder</em>, and where the regions sit inside it depends
     * on the dimension: an overworld keeps them in {@code region/}, a nether in
     * {@code DIM-1/region/}, an end in {@code DIM1/region/}. Probing rather than deriving,
     * because the mapping is a server-layout detail rather than an API guarantee.
     */
    public static Optional<File> regionFolderOf(File worldFolder) {
        if (worldFolder == null) {
            return Optional.empty();
        }
        for (String candidate : new String[] {"region", "DIM-1/region", "DIM1/region"}) {
            File folder = new File(worldFolder, candidate);
            if (folder.isDirectory()) {
                return Optional.of(folder);
            }
        }
        return Optional.empty();
    }

    /** Every region file in a folder, sorted so a backup listing is stable between runs. */
    public static java.util.List<Path> listRegions(Path folder) throws IOException {
        if (folder == null || !Files.isDirectory(folder)) {
            return java.util.List.of();
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries
                    .filter(path -> isRegionFile(path.getFileName().toString()))
                    .sorted()
                    .toList();
        }
    }

    /** Total bytes of every region file in a folder. */
    public static long sizeOf(Path folder) throws IOException {
        long total = 0;
        for (Path region : listRegions(folder)) {
            total += Files.size(region);
        }
        return total;
    }
}
