package dev.civitas.command.admin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.admin.AuditService;
import dev.civitas.core.war.RollbackJob;
import dev.civitas.core.war.RollbackStatus;
import dev.civitas.core.war.War;
import dev.civitas.core.war.WarState;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.row.WarRollbackIssueRow;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * SPEC 9.4.5, the war admin commands.
 *
 * <h2>Why this subsection came first</h2>
 * SPEC 18.3 step 9 requires {@code /ca war verify} and {@code /ca war rollbackstatus} to report
 * cleanly before a server may launch. Without them the manual protocol M20 wrote cannot be
 * signed off, so these are not conveniences: they are the last thing standing between the war
 * system and being launch-ready.
 *
 * <h2>Two of these are dangerous and say so</h2>
 * {@code cancel} and {@code forceend} both terminate a war people are fighting, move real
 * money, and trigger a rollback. Both require a reason, both write to the audit log before
 * anything else happens, and neither is undoable. SPEC 9.4.5 asks for the reason on
 * {@code cancel}; it is required on {@code forceend} as well, because a war ended by an admin
 * with no stated reason is exactly the dispute SPEC 1.5 exists to settle.
 */
public final class AdminWarCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public AdminWarCommands(Supplier<CivitasServices> services, LangManager lang,
                            Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** The {@code war} branch of {@code /ca}. */
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("war")
                .requires(source -> source.getSender().hasPermission("civitas.admin.war"))
                .then(Commands.literal("list")
                        .executes(context -> {
                            list(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            cancel(context.getSource().getSender(),
                                                    IntegerArgumentType.getInteger(context, "id"),
                                                    StringArgumentType.getString(context, "reason"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("forceend")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .then(Commands.argument("result", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            builder.suggest("attacker");
                                            builder.suggest("defender");
                                            builder.suggest("draw");
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("reason",
                                                        StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    forceEnd(context.getSource().getSender(),
                                                            IntegerArgumentType.getInteger(context, "id"),
                                                            StringArgumentType.getString(context, "result"),
                                                            StringArgumentType.getString(context, "reason"));
                                                    return Command.SINGLE_SUCCESS;
                                                })))))
                .then(Commands.literal("extend")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1, 720))
                                        .executes(context -> {
                                            extend(context.getSource().getSender(),
                                                    IntegerArgumentType.getInteger(context, "id"),
                                                    IntegerArgumentType.getInteger(context, "hours"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("rollback")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    rollback(context.getSource().getSender(),
                                            IntegerArgumentType.getInteger(context, "id"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("rollbackstatus")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    rollbackStatus(context.getSource().getSender(),
                                            IntegerArgumentType.getInteger(context, "id"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("verify")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    verify(context.getSource().getSender(),
                                            IntegerArgumentType.getInteger(context, "id"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("immunity")
                        .then(Commands.argument("city", StringArgumentType.word())
                                .suggests(this::suggestCities)
                                .then(Commands.argument("hours", IntegerArgumentType.integer(0, 8760))
                                        .executes(context -> {
                                            immunity(context.getSource().getSender(),
                                                    StringArgumentType.getString(context, "city"),
                                                    IntegerArgumentType.getInteger(context, "hours"));
                                            return Command.SINGLE_SUCCESS;
                                        }))));
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    /** SPEC 9.4.5: "All wars in any state." */
    private void list(Audience audience) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        List<War> all = current.wars().registry().all().stream().toList();
        if (all.isEmpty()) {
            lang.send(audience, "admin.war.none");
            return;
        }
        lang.sendRaw(audience, "admin.war.list-header",
                Replies.p("count", String.valueOf(all.size())));
        for (War war : all) {
            lang.sendRaw(audience, "admin.war.list-entry",
                    Replies.p("id", String.valueOf(war.id())),
                    Replies.p("attacker", cityName(current, war.attackerCityId())),
                    Replies.p("defender", cityName(current, war.defenderCityId())),
                    Replies.p("state", war.state().key()),
                    Replies.p("attacker-score", String.valueOf(war.attackerScore())),
                    Replies.p("defender-score", String.valueOf(war.defenderScore())));
        }
    }

    /**
     * SPEC 9.4.5: "Progress: blocks restored / total, ETA, errors."
     *
     * <p>Reads the live job if one is running and the recorded issues either way, so it answers
     * both "how far has it got" during a restore and "what went wrong" after one.
     */
    private void rollbackStatus(Audience audience, int warId) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }

        current.rollback().jobFor(warId).ifPresentOrElse(
                job -> reportProgress(audience, job),
                () -> lang.send(audience, "admin.war.rollback-not-running",
                        Replies.p("id", String.valueOf(warId))));

        current.daos().warRollbackIssues().findByWar(warId, 50)
                .whenComplete((issues, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(audience, "command.error");
                        return;
                    }
                    reportIssues(audience, warId, issues);
                }));
    }

    private void reportProgress(Audience audience, RollbackJob job) {
        long done = job.applied() + job.skipped();
        lang.sendRaw(audience, "admin.war.rollback-progress",
                Replies.p("id", String.valueOf(job.warId())),
                Replies.p("status", job.status().name()),
                Replies.p("applied", String.valueOf(job.applied())),
                Replies.p("skipped", String.valueOf(job.skipped())),
                Replies.p("done", String.valueOf(done)));
        if (job.status() == RollbackStatus.FAILED && job.failureReason() != null) {
            lang.sendRaw(audience, "admin.war.rollback-failed",
                    Replies.p("reason", job.failureReason()));
        }
    }

    private void reportIssues(Audience audience, int warId, List<WarRollbackIssueRow> issues) {
        if (issues.isEmpty()) {
            // SPEC 18.3 step 9's "zero mismatches", which is the sentence an operator is
            // looking for before they launch.
            lang.send(audience, "admin.war.verify-clean", Replies.p("id", String.valueOf(warId)));
            return;
        }
        lang.sendRaw(audience, "admin.war.verify-issues",
                Replies.p("id", String.valueOf(warId)),
                Replies.p("count", String.valueOf(issues.size())));
        for (WarRollbackIssueRow issue : issues) {
            lang.sendRaw(audience, "admin.war.verify-issue",
                    Replies.p("kind", issue.kind()),
                    Replies.p("world", issue.world()),
                    Replies.p("x", String.valueOf(issue.x())),
                    Replies.p("y", issue.y() == null ? "-" : String.valueOf(issue.y())),
                    Replies.p("z", String.valueOf(issue.z())),
                    Replies.p("detail", issue.detail() == null ? "" : issue.detail()));
        }
    }

    /**
     * SPEC 9.4.5: "Dry-run integrity check of the block log, reports any entries that would
     * fail to restore and why."
     *
     * <p>A dry run in the strict sense: it reads every entry and asks whether it <em>could</em>
     * be applied, and writes nothing to the world. The value is in running it before a rollback
     * rather than discovering the answer during one.
     */
    private void verify(Audience audience, int warId) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        lang.send(audience, "admin.war.verify-started", Replies.p("id", String.valueOf(warId)));

        current.rollback().verifyLog(warId)
                .whenComplete((report, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        logger.log(Level.SEVERE, "War log verification failed", error);
                        lang.send(audience, "command.error");
                        return;
                    }
                    lang.sendRaw(audience, "admin.war.verify-report",
                            Replies.p("id", String.valueOf(warId)),
                            Replies.p("entries", String.valueOf(report.entries())),
                            Replies.p("unreadable", String.valueOf(report.unreadable())),
                            Replies.p("missing-world", String.valueOf(report.missingWorlds())));
                    for (String problem : report.problems()) {
                        lang.sendRaw(audience, "admin.war.verify-problem",
                                Replies.p("detail", problem));
                    }
                    if (report.isClean()) {
                        lang.send(audience, "admin.war.verify-clean",
                                Replies.p("id", String.valueOf(warId)));
                    }
                }));
    }

    // ==================================================================================
    // Mutating
    // ==================================================================================

    /** SPEC 9.4.5: "Cancels a war, refunds both wagers in full, triggers immediate rollback." */
    private void cancel(Audience audience, int warId, String reason) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        audit(audience, current, "WAR_CANCEL", String.valueOf(warId), reason);

        Replies.reply(current.wars().adminCancel(actorOf(audience), warId, reason,
                        System.currentTimeMillis()),
                audience, lang, scheduler, logger,
                war -> lang.send(audience, "admin.war.cancelled",
                        Replies.p("id", String.valueOf(war.id()))));
    }

    /** SPEC 9.4.5: "Ends a war early with a specified result, runs rollback." */
    private void forceEnd(Audience audience, int warId, String result, String reason) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        String choice = result.toLowerCase(Locale.ROOT);
        if (!choice.equals("attacker") && !choice.equals("defender") && !choice.equals("draw")) {
            lang.send(audience, "admin.war.bad-result");
            return;
        }
        audit(audience, current, "WAR_FORCE_END", String.valueOf(warId), reason,
                Map.of("result", choice));

        Replies.reply(current.wars().adminForceEnd(actorOf(audience), warId, choice, reason,
                        System.currentTimeMillis()),
                audience, lang, scheduler, logger,
                war -> lang.send(audience, "admin.war.force-ended",
                        Replies.p("id", String.valueOf(war.id())),
                        Replies.p("result", choice)));
    }

    /** SPEC 9.4.5: "Extends the war window." */
    private void extend(Audience audience, int warId, int hours) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        audit(audience, current, "WAR_EXTEND", String.valueOf(warId), null,
                Map.of("hours", String.valueOf(hours)));

        Replies.reply(current.wars().adminExtend(warId, TimeUnit.HOURS.toMillis(hours)),
                audience, lang, scheduler, logger,
                war -> lang.send(audience, "admin.war.extended",
                        Replies.p("id", String.valueOf(war.id())),
                        Replies.p("hours", String.valueOf(hours))));
    }

    /** SPEC 9.4.5: "Manually triggers or re-triggers rollback for a war." */
    private void rollback(Audience audience, int warId) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        if (current.rollback().isRunning(warId)) {
            lang.send(audience, "admin.war.rollback-already",
                    Replies.p("id", String.valueOf(warId)));
            return;
        }
        audit(audience, current, "WAR_ROLLBACK", String.valueOf(warId), null);

        current.wars().registry().war(warId).ifPresentOrElse(war -> {
            current.rollbackTrigger().accept(war);
            lang.send(audience, "admin.war.rollback-started",
                    Replies.p("id", String.valueOf(warId)));
        }, () -> lang.send(audience, "war.unknown"));
    }

    /** SPEC 9.4.5: "Grants war immunity." */
    private void immunity(Audience audience, String cityName, int hours) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.registry().cityByName(cityName).ifPresentOrElse(city -> {
            audit(audience, current, "WAR_IMMUNITY", city.name(), null,
                    Map.of("hours", String.valueOf(hours)));
            long until = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours);
            Replies.reply(current.wars().adminGrantImmunity(city, until),
                    audience, lang, scheduler, logger,
                    granted -> lang.send(audience, "admin.war.immunity-granted",
                            Replies.p("city", city.name()),
                            Replies.p("hours", String.valueOf(hours))));
        }, () -> lang.send(audience, "city.unknown"));
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private CivitasServices ready(Audience audience) {
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(audience, "plugin.starting");
        }
        return current;
    }

    private void audit(Audience audience, CivitasServices current, String action, String target,
                       String reason) {
        audit(audience, current, action, target, reason, Map.of());
    }

    /**
     * Written before the action runs, not after.
     *
     * <p>An admin action that crashed halfway is the one most worth having a record of, and a
     * log written only on success would not have it.
     */
    private void audit(Audience audience, CivitasServices current, String action, String target,
                       String reason, Map<String, String> metadata) {
        AuditService auditService = current.audit();
        auditService.record(actorOf(audience), action, target, reason, metadata);
    }

    private static java.util.UUID actorOf(Audience audience) {
        return audience instanceof Player player ? player.getUniqueId() : null;
    }

    private static String cityName(CivitasServices current, int cityId) {
        return current.registry().city(cityId)
                .map(dev.civitas.core.city.City::name)
                .orElse("#" + cityId);
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestCities(CommandContext<CommandSourceStack> context,
                          com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CivitasServices current = services.get();
        if (current != null) {
            String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
            current.registry().cities().stream()
                    .map(dev.civitas.core.city.City::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    /** Kept so the war states are visible where the list is rendered. */
    static String describe(WarState state) {
        return state.key();
    }
}
