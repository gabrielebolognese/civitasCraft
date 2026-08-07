package dev.civitas.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.row.LedgerRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 9.4.1's CSV export.
 *
 * <p>The tests that matter here are the escaping ones. The metadata column holds JSON, which is
 * full of commas and quotes, and a player-supplied name can reach it — so a naive join produces
 * a file that parses fine until the one row somebody is arguing about is the row that breaks
 * it. The filename test matters for a different reason: the label is a command argument, and a
 * path that escaped the exports directory would let an admin overwrite something that matters.
 */
class LedgerExportTest {

    @TempDir
    Path directory;

    private static final long NOW = 1_700_000_000_000L;
    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private LedgerExport export;

    @BeforeEach
    void setUp() {
        export = new LedgerExport(directory.toFile());
    }

    private static LedgerRow row(String metadata) {
        return new LedgerRow(7, NOW, TransactionType.PLAYER_PAY.name(), ALICE, null, 3,
                new BigDecimal("-1500.50"), new BigDecimal("8499.50"), metadata);
    }

    private List<String> linesOf(File file) throws IOException {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a file is written with a header and one line per row")
    void writesAFile() throws IOException {
        File written = export.write("Roma", List.of(row(null), row(null)), NOW);

        assertTrue(written.isFile());
        List<String> lines = linesOf(written);
        assertEquals(3, lines.size(), "a header and two rows");
        assertTrue(lines.get(0).startsWith("id,timestamp_utc"), lines.get(0));
    }

    @Test
    @DisplayName("the directory is created if it does not exist")
    void createsTheDirectory() throws IOException {
        assertFalse(export.directory().exists());

        export.write("Roma", List.of(row(null)), NOW);

        assertTrue(export.directory().isDirectory());
    }

    @Test
    @DisplayName("a quote inside metadata is doubled rather than breaking the row")
    void escapesQuotes() throws IOException {
        // RFC 4180. Without this the row ends early and every column after it shifts.
        File written = export.write("Roma",
                List.of(row("{\"note\":\"he said \\\"hello\\\"\"}")), NOW);

        String line = linesOf(written).get(1);
        assertTrue(line.endsWith("\""), "the metadata field is closed: " + line);
        assertTrue(line.contains("\"\""), "an inner quote is doubled: " + line);
    }

    @Test
    @DisplayName("a comma inside metadata does not split the row")
    void escapesCommas() throws IOException {
        File written = export.write("Roma", List.of(row("{\"a\":1,\"b\":2}")), NOW);

        String line = linesOf(written).get(1);
        // Ten columns; a naive join would have produced eleven.
        assertEquals(10, countFields(line), line);
    }

    @Test
    @DisplayName("an empty metadata column is empty rather than the word null")
    void nullMetadataIsBlank() throws IOException {
        String line = linesOf(export.write("Roma", List.of(row(null)), NOW)).get(1);

        assertTrue(line.endsWith(",\"\""), line);
    }

    @Test
    @DisplayName("the money keeps its cents")
    void moneyIsExact() throws IOException {
        String line = linesOf(export.write("Roma", List.of(row(null)), NOW)).get(1);

        assertTrue(line.contains("-1500.50"), line);
        assertTrue(line.contains("8499.50"), line);
    }

    @Test
    @DisplayName("a label that looks like a path cannot escape the exports directory")
    void filenameIsSafe() {
        // The label is a command argument. Without this, exporting a "player" called
        // ../../server.properties would write a CSV over the server's configuration.
        String name = LedgerExport.filename("../../server", NOW);

        assertFalse(name.contains(".."), name);
        assertFalse(name.contains("/"), name);
        assertFalse(name.contains("\\"), name);
        assertTrue(name.endsWith(".csv"), name);
    }

    @Test
    @DisplayName("a label with nothing usable in it still produces a filename")
    void emptyLabelStillWorks() {
        assertTrue(LedgerExport.filename("///", NOW).startsWith("ledger-"));
        assertTrue(LedgerExport.filename(null, NOW).startsWith("ledger-"));
    }

    @Test
    @DisplayName("the filename carries a timestamp, so two exports do not collide")
    void filenamesAreDistinct() {
        assertFalse(LedgerExport.filename("Roma", NOW)
                .equals(LedgerExport.filename("Roma", NOW + 60_000L)));
    }

    /** Counts RFC 4180 fields, respecting quotes. */
    private static int countFields(String line) {
        int fields = 1;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields++;
            }
        }
        return fields;
    }
}
