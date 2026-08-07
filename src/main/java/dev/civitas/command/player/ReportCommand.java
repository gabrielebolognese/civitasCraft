package dev.civitas.command.player;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code /report}, SPEC 15.3.
 *
 * <h2>The reply says nothing about what happens next</h2>
 * A player who reports somebody is told their report was filed, and nothing more. Telling them
 * it is being looked at, or that the target was warned, would leak a moderation decision to
 * the person least entitled to it and turn the queue into a scoreboard. SPEC 15.3 describes a
 * queue for admins, not a conversation.
 */
public final class ReportCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public ReportCommand(Supplier<CivitasServices> services, LangManager lang,
                         Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("report")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(dev.civitas.command.Suggest.onlinePlayers())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(this::report)))
                .build();
    }

    private int report(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(context.getSource().getSender(), "plugin.starting");
            return Command.SINGLE_SUCCESS;
        }
        if (!(context.getSource().getSender() instanceof Player player)) {
            lang.send(context.getSource().getSender(), Msg.COMMAND_PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }

        String typed = StringArgumentType.getString(context, "player");
        String reason = StringArgumentType.getString(context, "reason");

        current.lookup().resolve(typed).whenComplete((resolved, error) ->
                scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(player, "command.error");
                        return;
                    }
                    if (resolved == null || resolved.isEmpty()) {
                        lang.send(player, "player.unknown", Replies.p("player", typed));
                        return;
                    }
                    Replies.reply(current.reports().file(player.getUniqueId(),
                                    resolved.get().uuid(), reason, System.currentTimeMillis()),
                            player, lang, scheduler, logger,
                            filed -> {
                                lang.send(player, "report.filed",
                                        Replies.p("player", resolved.get().name()));
                                notifyStaff(current, player, resolved.get().name());
                            });
                }));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Tells online staff a report arrived.
     *
     * <p>Without this the queue is only seen by an admin who thinks to look, and a report
     * filed during an incident is most useful during the incident.
     */
    private void notifyStaff(CivitasServices current, Player reporter, String target) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("civitas.admin.info") && !online.equals(reporter)) {
                lang.send(online, "report.staff-notice",
                        Replies.p("reporter", reporter.getName()),
                        Replies.p("player", target));
            }
        }
    }
}
