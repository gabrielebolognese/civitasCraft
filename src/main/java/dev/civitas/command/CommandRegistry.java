package dev.civitas.command;

import java.util.List;
import java.util.Objects;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers the plugin's root commands through Brigadier and Paper's Lifecycle API,
 * per SPEC.md Section 2.1.
 *
 * <p>M0 registers the tree only. Every command is permission-gated and replies that it is
 * not implemented yet; the milestone that fills each one in is recorded on its
 * {@link CommandSpec} and shown in the reply. Subcommands, argument types and tab
 * completion arrive with the milestone that implements the behaviour, so that validation
 * is written once against real arguments rather than twice.
 */
public final class CommandRegistry {

    /**
     * Root commands still waiting for the milestone that implements them.
     *
     * <p><b>Empty since M23.</b> Every command in SPEC 9 now has a real implementation and is
     * registered through {@link #registerAll(List)} instead. The list, the stub builder and
     * {@link CommandSpec} are kept rather than deleted: they are the mechanism by which a
     * command can be declared before it works, which is how this tree was built one milestone
     * at a time, and a future command wants the same treatment. {@code CommandRegistryTest}
     * asserts it is empty, so a stub added later is a deliberate act rather than a leftover.
     */
    private static final List<CommandSpec> COMMANDS = List.of();

    /**
     * Aliases for commands that have a real implementation.
     *
     * <p>A stubbed command carries its aliases in its {@link CommandSpec}; an implemented one
     * is a Brigadier node, which has no notion of an alias, so they live here. SPEC 9.4 gives
     * {@code /cityadmin} the alias {@code /ca} and uses the short form throughout, so a server
     * without it would not match its own documentation.
     */
    private static final java.util.Map<String, List<String>> ALIASES = java.util.Map.of(
            "cityadmin", List.of("ca"),
            // SPEC 9.1 lists these two as one command.
            "money", List.of("balance"),
            "citychat", List.of("cc"));

    private final JavaPlugin plugin;
    private final LangManager lang;

    public CommandRegistry(JavaPlugin plugin, LangManager lang) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    /** The declared root commands, exposed for tests. */
    public static List<CommandSpec> declaredCommands() {
        return COMMANDS;
    }

    /**
     * Hooks the Lifecycle API. Safe to call once, from {@code onEnable}.
     *
     * @param implemented fully built command trees to register alongside the stubs
     */
    public void registerAll(List<LiteralCommandNode<CommandSourceStack>> implemented) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            for (LiteralCommandNode<CommandSourceStack> node : implemented) {
                registrar.register(node, "CivitasCraft command.",
                        ALIASES.getOrDefault(node.getLiteral(), List.of()));
            }
            for (CommandSpec spec : COMMANDS) {
                registrar.register(build(spec), spec.description(), spec.aliases());
            }
        });
    }

    private LiteralCommandNode<CommandSourceStack> build(CommandSpec spec) {
        return Commands.literal(spec.name())
                .requires(source -> source.getSender().hasPermission(spec.permission()))
                .executes(context -> notImplemented(context, spec))
                // Accept and ignore any arguments so that, for example, /city create Roma
                // reports "not implemented" instead of a Brigadier syntax error.
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> notImplemented(context, spec)))
                .build();
    }

    private int notImplemented(CommandContext<CommandSourceStack> context, CommandSpec spec) {
        lang.send(context.getSource().getSender(), Msg.COMMAND_NOT_IMPLEMENTED,
                LangManager.placeholder("command", spec.name()),
                LangManager.placeholder("milestone", "M" + spec.milestone()));
        return Command.SINGLE_SUCCESS;
    }
}
