package dev.civitas.lang;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the plugin's own source to find out which placeholders each message key is given.
 *
 * <p>Needed because an angle-bracketed name in a language file is ambiguous: {@code <amount>}
 * in {@code "you need <amount> C"} is a value the code substitutes, while {@code <material>}
 * in {@code "/sell all <material>"} is command syntax being shown to the player, and both look
 * identical in the file. MiniMessage cannot tell them apart either — the second renders as
 * literal text only because nothing passes a resolver called {@code material}.
 *
 * <p>Only the call site knows which is which, so that is what this reads. It is the same
 * approach {@link LangKeyUsageTest} takes to find which keys exist, extended to capture the
 * resolvers passed in the same call.
 *
 * <p>Deliberately conservative. A call whose key is not a literal, or whose resolvers are built
 * somewhere else and passed in as a variable, contributes nothing rather than a guess: this
 * feeds an assertion, and a scanner that invents a placeholder would fail the build over
 * something that is not there.
 */
final class LangCallSites {

    /** {@code lang.send(audience, "key"}, {@code lang.sendRaw(...)}, {@code lang.get("key"}. */
    private static final Pattern LANG_CALL = Pattern.compile(
            "\\blang\\.(?:send|sendRaw|get)\\s*\\(\\s*(?:[A-Za-z_][\\w.()]*\\s*,\\s*)?\"([^\"]+)\"");

    /** {@code Result.failure("REASON", "key"}, whose placeholders come as a Map. */
    private static final Pattern RESULT_FAILURE = Pattern.compile(
            "Result\\.(?:<[^>]+>)?failure\\s*\\(\\s*\"[A-Z0-9_]+\"\\s*,\\s*\"([^\"]+)\"");

    /**
     * A resolver being built: {@code Replies.p("name", ...)},
     * {@code LangManager.placeholder("name", ...)}, {@code placeholder("name", ...)},
     * or a {@code Map.of("name", ...)} entry inside a failure.
     */
    private static final Pattern RESOLVER = Pattern.compile(
            "(?:\\bp|\\bplaceholder|\\bplaceholders|Map\\.of|Map\\.entry)\\s*\\(\\s*\"([a-z][a-z0-9_-]*)\"");

    /** A bare {@code "name", something} pair, for the continuation of a multi-entry Map.of. */
    private static final Pattern MAP_PAIR =
            Pattern.compile("\"([a-z][a-z0-9_-]*)\"\\s*,\\s*(?:String\\.valueOf|\\w)");

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    private LangCallSites() {
    }

    /**
     * Message key to the placeholder names the code passes with it.
     *
     * <p>Unioned across every call site of a key, because the same key may be sent from
     * several places and a value must satisfy all of them.
     */
    static Map<String, TreeSet<String>> suppliedPlaceholders() {
        Map<String, TreeSet<String>> supplied = new LinkedHashMap<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            collect(source, LANG_CALL, supplied);
            collect(source, RESULT_FAILURE, supplied);
        }
        supplied.values().removeIf(TreeSet::isEmpty);
        return supplied;
    }

    private static void collect(String source, Pattern call,
                                Map<String, TreeSet<String>> into) {
        Matcher matcher = call.matcher(source);
        while (matcher.find()) {
            String key = matcher.group(1);
            String arguments = argumentsAfter(source, matcher.end());
            TreeSet<String> names = into.computeIfAbsent(key, ignored -> new TreeSet<>());
            names.addAll(resolverNames(arguments));
        }
    }

    /**
     * The rest of the call's argument list, from just after the key literal to the paren that
     * closes the call.
     *
     * <p>Balanced rather than "up to the next {@code )}", because a resolver argument is
     * itself a call: {@code Replies.p("amount", format(value))} contains two closing parens
     * before the one that ends the message.
     */
    private static String argumentsAfter(String source, int from) {
        int depth = 1;
        boolean inString = false;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '(' -> depth++;
                case ')' -> {
                    depth--;
                    if (depth == 0) {
                        return source.substring(from, i);
                    }
                }
                default -> { }
            }
        }
        return "";
    }

    private static TreeSet<String> resolverNames(String arguments) {
        TreeSet<String> names = new TreeSet<>();
        Matcher resolver = RESOLVER.matcher(arguments);
        while (resolver.find()) {
            names.add(resolver.group(1));
        }
        if (arguments.contains("Map.of") || arguments.contains("Map.entry")) {
            // Map.of takes its pairs flat, so only the first key matches RESOLVER. The rest
            // are bare "name", value pairs.
            Matcher pair = MAP_PAIR.matcher(arguments);
            while (pair.find()) {
                names.add(pair.group(1));
            }
        }
        return names;
    }

    private static Iterable<Path> javaFiles() {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
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
}
