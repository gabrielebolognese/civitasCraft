package dev.civitas.storage.migration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.civitas.storage.StorageException;

/**
 * One versioned schema change, named {@code V<version>__<name>.sql}.
 *
 * @param version      ascending, unique, and never reused once released
 * @param name         human-readable label recorded in {@code schema_version}
 * @param resourcePath classpath path to the script
 */
public record Migration(int version, String name, String resourcePath) implements Comparable<Migration> {

    private static final Pattern FILE_NAME = Pattern.compile("^V(\\d+)__(.+)\\.sql$");

    /**
     * @param folder   classpath folder holding the script
     * @param fileName the {@code V<n>__<name>.sql} file name
     * @throws StorageException if the file name does not follow the convention
     */
    public static Migration parse(String folder, String fileName) {
        Matcher matcher = FILE_NAME.matcher(fileName);
        if (!matcher.matches()) {
            throw new StorageException("Migration file '" + fileName
                    + "' does not follow the V<version>__<name>.sql convention");
        }
        return new Migration(
                Integer.parseInt(matcher.group(1)),
                matcher.group(2),
                folder + "/" + fileName);
    }

    @Override
    public int compareTo(Migration other) {
        return Integer.compare(version, other.version);
    }
}
