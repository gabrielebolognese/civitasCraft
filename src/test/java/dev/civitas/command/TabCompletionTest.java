package dev.civitas.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SPEC 19's "tab completion everywhere", made durable.
 *
 * <p>M23 swept the command tree and found twenty-two string arguments that offered a player
 * nothing. Ten of them should have: six admin commands taking a player name, the outposts a
 * city already owns, the mixed namespace {@code /ca ledger export} accepts. The rest are
 * values a player invents — an amount, a wager, the name of a city that does not exist yet —
 * and no completion is possible or wanted.
 *
 * <p>A sweep is worth nothing if the next command re-opens the gap, so this test holds the
 * line: every {@code StringArgumentType.word()} argument must either carry suggestions or
 * appear in {@link #FREE_FORM} below. Adding a completable argument without completion fails
 * the build; adding a genuinely free-form one is one line and a decision somebody made.
 *
 * <p>Deliberately a source scan rather than a walk of a built command tree. Building the tree
 * needs a running server and a live {@code CivitasServices}, and the question here — "does
 * this argument have a suggestion provider attached" — is answered perfectly well by reading
 * the code that attaches it.
 */
class TabCompletionTest {

    /** {@code Commands.argument("name", StringArgumentType.word())}. */
    private static final Pattern WORD_ARGUMENT = Pattern.compile(
            "Commands\\.argument\\(\\s*\"(\\w+)\"\\s*,\\s*StringArgumentType\\.word\\(\\)\\s*\\)");

    /** How far past the argument to look for its {@code .suggests(...)}. */
    private static final int LOOKAHEAD = 220;

    private static final Path COMMAND_ROOT = Path.of("src/main/java/dev/civitas/command");

    /**
     * Argument names that name a value the player invents, where there is nothing to offer.
     *
     * <p>Every entry is a deliberate exemption, not a backlog. {@code amount}, {@code wager},
     * {@code base} and {@code value} are numbers; {@code name} and {@code new} are used where
     * the thing being named does not exist yet (founding a city, creating or renaming an
     * outpost). The same argument name elsewhere — an outpost being deleted, say — does have
     * completion, which is why this list cannot simply be "these names are exempt everywhere"
     * and each site is checked on its own.
     */
    private static final Set<String> FREE_FORM =
            new TreeSet<>(List.of("amount", "base", "name", "new", "value", "wager"));

    private static List<Path> commandSources() {
        try (Stream<Path> files = Files.walk(COMMAND_ROOT)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("every completable string argument offers completions")
    void everyCompletableArgumentCompletes() {
        List<String> uncompleted = new ArrayList<>();

        for (Path file : commandSources()) {
            String source = read(file);
            Matcher matcher = WORD_ARGUMENT.matcher(source);
            while (matcher.find()) {
                String argument = matcher.group(1);
                String following = source.substring(matcher.end(),
                        Math.min(source.length(), matcher.end() + LOOKAHEAD));
                if (following.contains(".suggests(") || FREE_FORM.contains(argument)) {
                    continue;
                }
                uncompleted.add(COMMAND_ROOT.relativize(file) + ":"
                        + (source.substring(0, matcher.start()).split("\n", -1).length)
                        + " <" + argument + ">");
            }
        }

        assertTrue(uncompleted.isEmpty(),
                "these arguments name something that exists but offer no completion. Attach a "
                        + "suggestion provider, or add the argument name to FREE_FORM if the "
                        + "player really does have to invent the value:\n  "
                        + String.join("\n  ", uncompleted));
    }

    @Test
    @DisplayName("player names complete through the one shared provider")
    void playerNamesShareAProvider() {
        // Six admin commands took a player name and offered nothing until M23. The fix was a
        // shared provider rather than a seventh copy, and this is what keeps it that way: a
        // <player> argument that hand-rolls its own suggestion is how the copies start again.
        List<String> handRolled = new ArrayList<>();

        for (Path file : commandSources()) {
            String source = read(file);
            Matcher matcher = WORD_ARGUMENT.matcher(source);
            while (matcher.find()) {
                if (!matcher.group(1).equals("player")) {
                    continue;
                }
                String following = source.substring(matcher.end(),
                        Math.min(source.length(), matcher.end() + LOOKAHEAD));
                if (following.contains(".suggests(")
                        && !following.contains("Suggest.onlinePlayers()")
                        && !following.contains("cityMembers()")
                        && !following.contains("onlinePlayers()")) {
                    handRolled.add(COMMAND_ROOT.relativize(file) + " <player>");
                }
            }
        }

        assertTrue(handRolled.isEmpty(),
                "these complete player names their own way: " + handRolled);
    }

    @Test
    @DisplayName("the scan finds arguments at all, or it is guarding nothing")
    void scanIsNotVacuous() {
        // A regex that quietly stopped matching would make both tests above pass forever.
        int found = 0;
        for (Path file : commandSources()) {
            Matcher matcher = WORD_ARGUMENT.matcher(read(file));
            while (matcher.find()) {
                found++;
            }
        }

        assertTrue(found > 30,
                "only found " + found + " word arguments, so the pattern is broken rather "
                        + "than the tree being small");
    }

    @Test
    @DisplayName("no exemption is left in FREE_FORM once nothing uses it")
    void exemptionsAreAllUsed() {
        // An exemption nobody needs is a claim that some argument cannot be completed, left
        // behind after the argument was removed or given completion. It should not outlive
        // the thing it excused.
        Set<String> unused = new TreeSet<>(FREE_FORM);

        for (Path file : commandSources()) {
            String source = read(file);
            Matcher matcher = WORD_ARGUMENT.matcher(source);
            while (matcher.find()) {
                String following = source.substring(matcher.end(),
                        Math.min(source.length(), matcher.end() + LOOKAHEAD));
                if (!following.contains(".suggests(")) {
                    unused.remove(matcher.group(1));
                }
            }
        }

        assertTrue(unused.isEmpty(), "FREE_FORM exempts arguments nothing declares: " + unused);
    }
}
