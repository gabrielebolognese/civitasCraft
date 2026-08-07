package dev.civitas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN.md}'s build queue must be walkable from the top.
 *
 * <p>The {@code /next-milestone} command takes "the first milestone with status TODO whose
 * dependencies are all DONE", so the order of the rows <b>is</b> the build order. That works
 * only while every row's dependencies appear above it. If one drifts below something it needs,
 * the command silently skips it and hands over a different milestone — which looks exactly
 * like the plan working.
 *
 * <p>Worth a test rather than care, because the file is edited by hand at the end of every
 * milestone, when attention is lowest. SPEC's four Parts also number their milestones
 * independently and interleave, so a row's correct position is rarely its obvious one: SPEC 41
 * lists {@code 3a} in the last Part while saying it should be built early, and SPEC 38 says
 * {@code 19b} must land beside M19, which sits in Part I.
 */
class PlanOrderTest {

    private static final Path PLAN = Path.of("PLAN.md");

    /** {@code | 1 | 6a | Name | 24 | M6 | TODO | ...} */
    private static final Pattern QUEUE_ROW = Pattern.compile(
            "^\\|\\s*(\\d+)\\s*\\|\\s*([0-9]+[a-z]?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*(\\d+)\\s*\\|"
                    + "\\s*([^|]+?)\\s*\\|\\s*(\\w+)\\s*\\|", Pattern.MULTILINE);

    /** {@code | 14 | Leaderboards | M5 | DONE | ...}, Part I's table, which has no index. */
    private static final Pattern PART_ONE_ROW = Pattern.compile(
            "^\\|\\s*(\\d+)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([A-Z]+)\\s*\\|",
            Pattern.MULTILINE);

    private record Queued(int index, String id, String name, List<String> dependencies,
                          String status) { }

    private static String plan() {
        try {
            return Files.readString(PLAN, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Queued> queue() {
        List<Queued> rows = new ArrayList<>();
        Matcher matcher = QUEUE_ROW.matcher(plan());
        while (matcher.find()) {
            List<String> dependencies = new ArrayList<>();
            for (String raw : matcher.group(5).split(",")) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) {
                    dependencies.add(trimmed);
                }
            }
            rows.add(new Queued(Integer.parseInt(matcher.group(1)), matcher.group(2),
                    matcher.group(3), dependencies, matcher.group(6)));
        }
        return rows;
    }

    /** Part I milestone numbers that are DONE, which every queued row may depend on. */
    private static Set<String> completedPartOne() {
        Set<String> done = new TreeSet<>();
        Matcher matcher = PART_ONE_ROW.matcher(plan());
        while (matcher.find()) {
            if (matcher.group(4).equals("DONE")) {
                done.add("M" + matcher.group(1));
            }
        }
        return done;
    }

    @Test
    @DisplayName("finished rows keep their place rather than being deleted")
    void doneRowsStay() {
        // A finished milestone keeps its row, its index and its note. Removing it would lose
        // the record of what was decided and renumber everything below it, which is the one
        // edit that would break the index check for a reason that is not a mistake.
        List<Queued> queue = queue();

        assertTrue(queue.stream().allMatch(row -> row.status().equals("TODO")
                        || row.status().equals("DONE") || row.status().equals("SUPERSEDED")),
                "a queued row has a status this file does not use");
    }

    @Test
    @DisplayName("the queue is present and the size matches what SPEC added")
    void queueExists() {
        List<Queued> queue = queue();

        assertEquals(34, queue.size(),
                "SPEC's Parts II to V add 34 milestones; the queue has " + queue.size());
    }

    @Test
    @DisplayName("the index column counts from one with no gaps")
    void indicesAreSequential() {
        // The index is what a human reads to know where they are. A gap or a repeat means two
        // rows were edited independently and one of them is in the wrong place.
        List<Queued> queue = queue();

        for (int i = 0; i < queue.size(); i++) {
            assertEquals(i + 1, queue.get(i).index(),
                    "row " + queue.get(i).id() + " is numbered " + queue.get(i).index());
        }
    }

    @Test
    @DisplayName("no milestone id appears twice")
    void idsAreUnique() {
        Set<String> seen = new LinkedHashSet<>();

        for (Queued row : queue()) {
            assertTrue(seen.add(row.id()), row.id() + " is queued twice");
        }
    }

    @Test
    @DisplayName("every dependency of every row appears above it")
    void queueIsWalkableFromTheTop() {
        // The property /next-milestone actually depends on. Walk the queue marking each row
        // done as it is reached; a row whose dependency is not yet marked would be skipped by
        // the command, and the plan would quietly stop being a sequence.
        Set<String> available = new TreeSet<>(completedPartOne());
        List<String> unreachable = new ArrayList<>();

        for (Queued row : queue()) {
            for (String dependency : row.dependencies()) {
                if (dependency.equalsIgnoreCase("all above")) {
                    continue;   // satisfied by construction: everything before it is marked
                }
                // Accepted either way round: Part I rows are cited as "M14", queued rows as
                // "12f", and the table uses whichever reads naturally in that column.
                if (!available.contains(dependency) && !available.contains("M" + dependency)) {
                    unreachable.add("#" + row.index() + " " + row.id() + " (" + row.name()
                            + ") needs " + dependency + ", which is not above it");
                }
            }
            available.add(row.id());
            available.add("M" + row.id());
        }

        assertTrue(unreachable.isEmpty(),
                "/next-milestone walks this table top to bottom and would skip these rows, "
                        + "handing over a different milestone without saying so:\n  "
                        + String.join("\n  ", unreachable));
    }

    @Test
    @DisplayName("Part I's superseded milestones are marked, not deleted")
    void supersededRowsSurvive() {
        // M10 and M12 shipped designs that SPEC 39 and SPEC 25 retire. Their rows stay so that
        // the replacement milestones inherit the job of deleting the code — a removed row
        // would leave that work owned by nobody.
        String plan = plan();

        assertTrue(plan.contains("| 10 | Outposts | M3, M5 | SUPERSEDED |"),
                "the Part I outpost row should still be present, marked SUPERSEDED");
        assertTrue(plan.contains("| 12 | Custom mobs | M5, M8 | SUPERSEDED |"),
                "the Part I defense row should still be present, marked SUPERSEDED");

        assertTrue(queue().stream().anyMatch(row -> row.id().equals("10")),
                "and the rebuild must be queued");
        assertTrue(queue().stream().anyMatch(row -> row.id().equals("12a")),
                "and so must the defense rebuild");
    }

    @Test
    @DisplayName("the next milestone named at the top is the first TODO in the queue")
    void statedNextMatchesTheQueue() {
        // The header is what a human reads before running the command. If it disagrees with
        // the table, one of them is stale and there is no way to tell which.
        // The first TODO, not the first row: a finished row keeps its place in the queue, so
        // the two stop agreeing the moment anything is done. That distinction is exactly what
        // /next-milestone means by "the first milestone with status TODO".
        String plan = plan();
        String firstTodo = queue().stream()
                .filter(row -> row.status().equals("TODO"))
                .map(Queued::id)
                .findFirst()
                .orElse(null);

        Matcher header = Pattern.compile("\\*\\*Next milestone:\\*\\*[^\n]*")
                .matcher(plan);
        assertTrue(header.find(), "PLAN.md states no next milestone");
        if (firstTodo == null) {
            assertTrue(header.group().toLowerCase(java.util.Locale.ROOT).contains("none"),
                    "nothing is left TODO, so the header should say so: " + header.group());
            return;
        }
        assertTrue(header.group().contains("M" + firstTodo),
                "the header says \"" + header.group() + "\" but the first TODO is "
                        + firstTodo);
    }
}
