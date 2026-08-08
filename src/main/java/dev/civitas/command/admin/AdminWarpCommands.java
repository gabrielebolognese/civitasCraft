package dev.civitas.command.admin;

import java.util.Objects;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.storage.row.WarpRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * {@code /ca warp set|delete|list}, which SPEC does not define.
 *
 * <p>SPEC 32.7 lists {@code /warp <name>} as "admin-defined public warps" and no section defines
 * the command that defines one — not SPEC 9.4's admin tree, not SPEC 22.7's additions to it. A
 * warp system with no way to create a warp is inert, which is the same reasoning that shipped
 * {@code /toggle} alongside its preference store. Recorded in {@code OPEN_QUESTIONS.md}.
 *
 * <p>Under {@code civitas.admin.system} rather than a new node, because SPEC 10's node list is
 * closed and inventing a permission is a larger liberty than inventing the subcommand.
 */
public final class AdminWarpCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;

    public AdminWarpCommands(Supplier<CivitasServices> services, LangManager lang,
                             Scheduler scheduler) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("warp")
                .requires(source -> source.getSender()
                        .hasPermission("civitas.admin.system"))
                .then(Commands.literal("set")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> set(context,
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestWarps)
                                .executes(context -> delete(context,
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("list")
                        .executes(this::list));
    }

    // ==================================================================================
    // Actions
    // ==================================================================================

    private int set(CommandContext<CommandSourceStack> context, String name) {
        Audience audience = context.getSource().getSender();
        if (notReady(audience)) {
            return Command.SINGLE_SUCCESS;
        }
        if (!(context.getSource().getSender() instanceof Player admin)) {
            // A warp is set where the setter is standing, so there is nowhere for the console
            // to put one.
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }

        services.get().warps()
                .set(name, admin.getLocation(), admin.getUniqueId(), null,
                        System.currentTimeMillis())
                .whenComplete((result, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(admin, "command.error");
                        return;
                    }
                    if (result instanceof Result.Failure<WarpRow> failure) {
                        Replies.sendFailure(admin, lang, failure);
                        return;
                    }
                    lang.send(admin, "warp.set",
                            Replies.p("name", result.orElseThrow().name()));
                }));
        return Command.SINGLE_SUCCESS;
    }

    private int delete(CommandContext<CommandSourceStack> context, String name) {
        Audience audience = context.getSource().getSender();
        if (notReady(audience)) {
            return Command.SINGLE_SUCCESS;
        }
        services.get().warps().delete(name)
                .whenComplete((result, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(audience, "command.error");
                        return;
                    }
                    if (result instanceof Result.Failure<String> failure) {
                        Replies.sendFailure(audience, lang, failure);
                        return;
                    }
                    lang.send(audience, "warp.deleted",
                            Replies.p("name", result.orElseThrow()));
                }));
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> context) {
        Audience audience = context.getSource().getSender();
        if (notReady(audience)) {
            return Command.SINGLE_SUCCESS;
        }
        var warps = services.get().warps().all(System.currentTimeMillis());
        if (warps.isEmpty()) {
            lang.send(audience, "warp.none");
            return Command.SINGLE_SUCCESS;
        }
        lang.sendRaw(audience, "warp.header");
        for (WarpRow warp : warps) {
            lang.sendRaw(audience, "warp.entry",
                    Replies.p("name", warp.name()),
                    Replies.p("world", warp.world()),
                    Replies.p("x", String.valueOf((int) warp.x())),
                    Replies.p("z", String.valueOf((int) warp.z())));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // Plumbing
    // ==================================================================================

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestWarps(CommandContext<CommandSourceStack> context,
                         com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CivitasServices ready = services.get();
        if (ready != null) {
            ready.warps().names(System.currentTimeMillis()).stream()
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT)
                            .startsWith(builder.getRemaining()
                                    .toLowerCase(java.util.Locale.ROOT)))
                    .forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    private boolean notReady(Audience audience) {
        if (services.get() == null) {
            lang.send(audience, "plugin.starting");
            return true;
        }
        return false;
    }
}
