package dev.civitas.command.admin;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/**
 * {@code /cityadmin}, alias {@code /ca}, SPEC 9.4.
 *
 * <h2>One root, six branches</h2>
 * SPEC 9.4 divides fifty-four subcommands into six sections, and each section is its own class
 * here for the obvious reason: a single class holding all of them would be unreadable, and the
 * sections have almost nothing in common beyond the permission root.
 *
 * <h2>Permissions are per-branch, not per-root</h2>
 * SPEC 9.4 gives every command {@code civitas.admin} plus a specific node, so a server can hand
 * a moderator {@code civitas.admin.info} and {@code civitas.admin.audit} without also handing
 * them {@code civitas.admin.economy}. Brigadier checks the branch's node before its children
 * are even suggested, so a moderator does not see commands they cannot run.
 */
public final class AdminCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    private final Runnable reloadHook;

    public AdminCommand(Supplier<CivitasServices> services, LangManager lang,
                        Scheduler scheduler, Logger logger, Runnable reloadHook) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.reloadHook = Objects.requireNonNull(reloadHook, "reloadHook");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        var root = Commands.literal("cityadmin")
                .requires(source -> source.getSender().hasPermission("civitas.admin"))
                .executes(context -> {
                    help(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(new AdminWarCommands(services, lang, scheduler, logger).build())
                .then(new AdminClaimCommands(services, lang, scheduler, logger).build())
                .then(new AdminCityCommands(services, lang, scheduler, logger).build())
                .then(new AdminWarpCommands(services, lang, scheduler).build());
        AdminEconomyCommands economy = new AdminEconomyCommands(services, lang, scheduler,
                logger);
        root.then(economy.build()).then(economy.buildMarket()).then(economy.buildBreaker());
        new AdminSystemCommands(services, lang, scheduler, logger, reloadHook).build()
                .forEach(root::then);
        new AdminInspectCommands(services, lang, scheduler, logger).build()
                .forEach(root::then);
        return root.build();
    }

    private void help(net.kyori.adventure.audience.Audience audience) {
        lang.sendRaw(audience, "admin.help-header");
        lang.sendRaw(audience, "admin.help-inspect");
        lang.sendRaw(audience, "admin.help-city");
        lang.sendRaw(audience, "admin.help-claim");
        lang.sendRaw(audience, "admin.help-eco");
        lang.sendRaw(audience, "admin.help-system");
        lang.sendRaw(audience, "admin.help-war");
    }
}
