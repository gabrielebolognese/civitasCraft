package dev.civitas.core.admin;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import dev.civitas.storage.row.LedgerRow;

/**
 * SPEC 9.4.1's {@code /ca ledger export}: "Dumps to a CSV in
 * {@code plugins/CivitasCraft/exports/}".
 *
 * <h2>Why a file rather than more chat</h2>
 * A dispute worth exporting is one with hundreds of rows in it, and chat holds a hundred lines
 * badly. The point of the CSV is that it leaves the game: an admin opens it in a spreadsheet,
 * sorts it, and shows somebody the row that settles the argument.
 *
 * <h2>Escaping is not optional here</h2>
 * The metadata column holds JSON, which contains commas and quotes, and a player-supplied name
 * can reach it. A naive join on commas produces a file that looks fine until the one row that
 * matters is the one that breaks the parse. Every field goes through RFC 4180 quoting.
 */
public final class LedgerExport {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String HEADER =
            "id,timestamp_utc,epoch_millis,type,actor,target,city,amount,balance_after,metadata";

    private final File exportsDirectory;

    public LedgerExport(File dataFolder) {
        this.exportsDirectory = new File(Objects.requireNonNull(dataFolder, "dataFolder"),
                "exports");
    }

    /**
     * Writes rows to a new file and returns it.
     *
     * <p>Off the server thread: it writes a file, which SPEC 2.1's reasoning about the database
     * applies to equally. The caller is responsible for being on the right thread; the command
     * dispatches this the same way it dispatches a query.
     *
     * @param label what was exported, used in the filename
     */
    public File write(String label, List<LedgerRow> rows, long now) throws IOException {
        if (!exportsDirectory.isDirectory() && !exportsDirectory.mkdirs()) {
            throw new IOException("Could not create " + exportsDirectory);
        }

        File target = new File(exportsDirectory, filename(label, now));
        try (Writer writer = Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.write('\n');
            for (LedgerRow row : rows) {
                writer.write(line(row));
                writer.write('\n');
            }
        }
        return target;
    }

    /**
     * A filename that sorts chronologically and cannot escape the exports directory.
     *
     * <p>The label comes from a command argument, so it is reduced to characters that mean
     * nothing to a path. A target called {@code ../../server.properties} would otherwise be a
     * way to write a CSV over something that matters.
     */
    static String filename(String label, long now) {
        String safe = label == null ? "ledger" : label.replaceAll("[^A-Za-z0-9_-]", "");
        if (safe.isEmpty()) {
            safe = "ledger";
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(now));
        return safe + "-" + stamp + ".csv";
    }

    static String line(LedgerRow row) {
        return String.join(",",
                String.valueOf(row.id()),
                quote(TIMESTAMP.format(Instant.ofEpochMilli(row.timestamp()))),
                String.valueOf(row.timestamp()),
                quote(row.type()),
                quote(row.actorUuid() == null ? "" : row.actorUuid().toString()),
                quote(row.targetUuid() == null ? "" : row.targetUuid().toString()),
                row.cityId() == null ? "" : String.valueOf(row.cityId()),
                row.amount() == null ? "" : row.amount().toPlainString(),
                row.balanceAfter() == null ? "" : row.balanceAfter().toPlainString(),
                quote(row.metadata() == null ? "" : row.metadata()));
    }

    /** RFC 4180: wrap in quotes, and double any quote inside. */
    static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public File directory() {
        return exportsDirectory;
    }
}
