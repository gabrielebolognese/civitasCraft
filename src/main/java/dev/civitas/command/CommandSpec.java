package dev.civitas.command;

import java.util.List;

/**
 * A root command declared by the plugin.
 *
 * @param name        the literal, without a leading slash
 * @param permission  the Bukkit permission node from SPEC.md Section 10
 * @param milestone   the PLAN.md milestone that makes this command functional
 * @param aliases     alternative literals registered alongside {@code name}
 * @param description shown in Paper's command listing
 */
public record CommandSpec(
        String name,
        String permission,
        int milestone,
        List<String> aliases,
        String description) {

    public CommandSpec {
        aliases = List.copyOf(aliases);
    }

    public static CommandSpec of(String name, String permission, int milestone, String description) {
        return new CommandSpec(name, permission, milestone, List.of(), description);
    }

    public static CommandSpec of(String name, String permission, int milestone, String description,
                                 String... aliases) {
        return new CommandSpec(name, permission, milestone, List.of(aliases), description);
    }
}
