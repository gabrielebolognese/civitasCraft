package dev.civitas.command.admin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.config.ConfigFile;
import dev.civitas.core.admin.PerfReport;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.row.ReportRow;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * SPEC 9.4.6 and SPEC 15.3's admin half.
 *
 * <h2>{@code /ca perf} reports what is measured and says what is not</h2>
 * SPEC 9.4.6 asks for "avg claim lookup, block-log write rate, GUI open time, DB pool status".
 * Two of those are real numbers this plugin keeps; two are not instrumented anywhere. Printing
 * a plausible figure for the other two would be worse than useless — an operator diagnosing a
 * stall would chase a number nobody measured. So the unmeasured ones say so.
 */
public final class AdminSystemCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;
    private final Runnable reloadHook;

    public AdminSystemCommands(Supplier<CivitasServices> services, LangManager lang,
                               Scheduler scheduler, Logger logger, Runnable reloadHook) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.reloadHook = Objects.requireNonNull(reloadHook, "reloadHook");
    }

    /** Every 9.4.6 branch plus {@code reports}, added to the {@code /ca} root. */
    public List<LiteralArgumentBuilder<CommandSourceStack>> build() {
        return List.of(reload(), backup(), world(), debug(), perf(), migrate(), event(),
                contest(), reports());
    }

    // ==================================================================================
    // SPEC 9.4.6
    // ==================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> reload() {
        return Commands.literal("reload")
                .requires(source -> source.getSender().hasPermission("civitas.admin.system"))
                .executes(context -> {
                    reloadAll(context.getSource().getSender(), "all");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("module", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("all");
                            for (ConfigFile file : ConfigFile.values()) {
                                builder.suggest(file.name().toLowerCase(Locale.ROOT));
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            reloadAll(context.getSource().getSender(),
                                    StringArgumentType.getString(context, "module"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private void reloadAll(Audience audience, String module) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.audit().record(actorOf(audience), "SYSTEM_RELOAD", module, null);
        // Everything, whichever module was named. The configuration files reference each
        // other — a defence unit's cost is in defense.yml and its upkeep is charged by the
        // economy — and reloading one of a pair leaves the plugin holding two halves of two
        // different configurations. SPEC 9.4.6 lists modules; this reads that as naming what
        // an operator is interested in rather than promising isolation the files do not have.
        reloadHook.run();
        lang.send(audience, "plugin.reloaded");
    }

    private LiteralArgumentBuilder<CommandSourceStack> backup() {
        return Commands.literal("backup")
                .requires(source -> source.getSender().hasPermission("civitas.admin.system"))
                .then(backupStatus())
                .executes(context -> {
                    Audience audience = context.getSource().getSender();
                    CivitasServices current = ready(audience);
                    if (current == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    current.audit().record(actorOf(audience), "SYSTEM_BACKUP", null, null);
                    lang.send(audience, "admin.system.backup-started");

                    current.backups().backupNow(current.backupKeepCount())
                            .whenComplete((file, error) -> scheduler.runOnMain(() -> {
                                if (error != null) {
                                    logger.log(Level.SEVERE, "Backup failed", error);
                                    lang.send(audience, "admin.system.backup-failed");
                                    return;
                                }
                                file.ifPresentOrElse(
                                        written -> lang.send(audience, "admin.system.backup-done",
                                                Replies.p("file", written.getName())),
                                        () -> lang.send(audience,
                                                "admin.system.backup-unsupported"));
                            }));
                    return Command.SINGLE_SUCCESS;
                });
    }

    /** SPEC 32.8's reporting: world size, region count, last full, last incremental, growth. */
    private LiteralArgumentBuilder<CommandSourceStack> backupStatus() {
        return Commands.literal("status")
                .requires(source -> source.getSender().hasPermission("civitas.admin.system"))
                .executes(context -> {
                    Audience audience = context.getSource().getSender();
                    CivitasServices current = ready(audience);
                    if (current == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    reportBackupStatus(audience, current);
                    return Command.SINGLE_SUCCESS;
                });
    }

    private void reportBackupStatus(Audience audience, CivitasServices current) {
        var backups = current.worldBackups();
        var worlds = current.worlds().allManagedWorlds();

        // Sizing a world means stat-ing every region file it has, which on a mature server is
        // thousands. Off the main thread, then printed back on it.
        async(() -> {
            var status = backups.status(worlds);
            scheduler.runOnMain(() -> {
                lang.send(audience, "admin.backup.status-header");
                lang.send(audience, "admin.backup.status-world",
                        Replies.p("size", megabytes(status.worldBytes())),
                        Replies.p("files", String.valueOf(status.regionFileCount())),
                        Replies.p("worlds", String.valueOf(worlds.size())));
                lang.send(audience, "admin.backup.status-full",
                        Replies.p("when", describe(status.lastFull())),
                        Replies.p("keep", String.valueOf(backups.settings().fullKeepCount())));
                lang.send(audience, "admin.backup.status-incremental",
                        Replies.p("when", describe(status.lastIncremental())),
                        Replies.p("days",
                                String.valueOf(backups.settings().incrementalKeepDays())));
                lang.send(audience, "admin.backup.status-wars",
                        Replies.p("count", String.valueOf(status.warSnapshots())));
                lang.send(audience, "admin.backup.status-disk",
                        Replies.p("free", String.valueOf(status.freeGb())),
                        Replies.p("required", String.valueOf(backups.settings().minFreeGb())));
                lang.send(audience, "admin.backup.status-growth",
                        Replies.p("projected", megabytes(status.projectedYearlyBytes())));
            });
        });
    }

    private static String megabytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String describe(java.util.Optional<java.time.Instant> when) {
        return when.map(instant -> java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(instant))
                .orElseGet(() -> lang.plain("admin.backup.never"));
    }

    /**
     * SPEC 32.8's {@code /ca world restore war <id>}, with the war id typed twice.
     *
     * <p>The double confirmation is not ceremony. A region file holds 32x32 chunks, so restoring a
     * war snapshot rewinds every chunk in every region the fighting touched — including ground
     * outside the war zone that happened to share a file with it, and including anything built
     * there since. It is a rewind, not an undo.
     */
    private LiteralArgumentBuilder<CommandSourceStack> world() {
        return Commands.literal("world")
                .requires(source -> source.getSender().hasPermission("civitas.admin.system"))
                .then(Commands.literal("restore")
                        .then(Commands.literal("war")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes(context -> {
                                            lang.send(context.getSource().getSender(),
                                                    "admin.world.restore-confirm",
                                                    Replies.p("id", String.valueOf(
                                                            IntegerArgumentType.getInteger(
                                                                    context, "id"))));
                                            return Command.SINGLE_SUCCESS;
                                        })
                                        .then(Commands.argument("confirm",
                                                        IntegerArgumentType.integer(1))
                                                .executes(context -> {
                                                    restoreWar(context.getSource().getSender(),
                                                            IntegerArgumentType.getInteger(
                                                                    context, "id"),
                                                            IntegerArgumentType.getInteger(
                                                                    context, "confirm"));
                                                    return Command.SINGLE_SUCCESS;
                                                })))));
    }

    private void restoreWar(Audience audience, int warId, int confirm) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        if (warId != confirm) {
            lang.send(audience, "admin.world.restore-mismatch");
            return;
        }
        current.audit().record(actorOf(audience), "WORLD_RESTORE_WAR", null,
                "{" + '"' + "war" + '"' + ":" + warId + "}");
        lang.send(audience, "admin.world.restore-started", Replies.p("id", String.valueOf(warId)));

        async(() -> {
            var restored = current.worldBackups().restoreWarSnapshot(warId);
            scheduler.runOnMain(() -> restored.ifPresentOrElse(
                    count -> lang.send(audience, "admin.world.restore-done",
                            Replies.p("files", String.valueOf(count))),
                    () -> lang.send(audience, "admin.world.restore-missing")));
        });
    }

    /**
     * Runs heavy file work off the server thread.
     *
     * <p>Its own thread rather than Bukkit's async pool, because this class has no plugin handle
     * and both callers are one-shot admin commands rather than anything that could pile up.
     */
    private static void async(Runnable work) {
        Thread worker = new Thread(work, "civitas-world-backup");
        worker.setDaemon(true);
        worker.start();
    }

    private LiteralArgumentBuilder<CommandSourceStack> debug() {
        return Commands.literal("debug")
                .requires(source -> source.getSender().hasPermission("civitas.admin.system"))
                .then(Commands.argument("state", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("on");
                            builder.suggest("off");
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            Audience audience = context.getSource().getSender();
                            CivitasServices current = ready(audience);
                            if (current == null) {
                                return Command.SINGLE_SUCCESS;
                            }
                            boolean on = "on".equalsIgnoreCase(
                                    StringArgumentType.getString(context, "state"));
                            current.audit().record(actorOf(audience), "SYSTEM_DEBUG",
                                    on ? "on" : "off", null);
                            logger.setLevel(on ? Level.ALL : Level.INFO);
                            lang.send(audience, on ? "admin.system.debug-on"
                                    : "admin.system.debug-off");
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    /** SPEC 9.4.6: "Timings: avg claim lookup, block-log write rate, GUI open time, DB pool." */
    private LiteralArgumentBuilder<CommandSourceStack> perf() {
        return Commands.literal("perf")
                .requires(source -> source.getSender().hasPermission("civitas.admin.system"))
                .executes(context -> {
                    Audience audience = context.getSource().getSender();
                    CivitasServices current = ready(audience);
                    if (current == null) {
                        return Command.SINGLE_SUCCESS;
                    }

                    PerfReport perf = current.perf();
                    lang.sendRaw(audience, "admin.system.perf-header");
                    lang.sendRaw(audience, "admin.system.perf-claims",
                            Replies.p("count", String.valueOf(perf.claimsCached())),
                            Replies.p("cities", String.valueOf(perf.cities())));

                    // SPEC 9.4.6's four named figures. M21 could print two of them and said
                    // so; M23 measures the other two rather than leaving the admission in.
                    lang.sendRaw(audience, "admin.system.perf-lookup",
                            Replies.p("micros", micros(perf.claimLookup())),
                            Replies.p("samples", String.valueOf(perf.claimLookup().samples())));
                    lang.sendRaw(audience, "admin.system.perf-gui",
                            Replies.p("micros", micros(perf.guiOpen())),
                            Replies.p("samples", String.valueOf(perf.guiOpen().samples())));
                    lang.sendRaw(audience, "admin.system.perf-warlog",
                            Replies.p("rate", String.format(java.util.Locale.ROOT, "%.1f",
                                    perf.warLogRate())),
                            Replies.p("buffered", String.valueOf(perf.warLogBuffered())),
                            Replies.p("wars", String.valueOf(perf.warsTracked())));
                    lang.sendRaw(audience, "admin.system.perf-pool",
                            Replies.p("active", String.valueOf(perf.pool().active())),
                            Replies.p("idle", String.valueOf(perf.pool().idle())),
                            Replies.p("max", String.valueOf(perf.pool().max())),
                            Replies.p("awaiting", String.valueOf(perf.pool().awaiting())));

                    lang.sendRaw(audience, "admin.system.perf-protection",
                            Replies.p("protected", String.valueOf(perf.protectedChunks())));
                    if (!perf.timingsEnabled()) {
                        // Sampling is off, so the two timing lines above read "-". Saying why
                        // beats letting an operator conclude the plugin is idle.
                        lang.sendRaw(audience, "admin.system.perf-timings-off");
                    }
                    return Command.SINGLE_SUCCESS;
                });
    }

    /**
     * A sampled average, or a dash.
     *
     * <p>A dash rather than {@code 0.0}: an average of zero is a claim about the code, and
     * "nothing has been sampled yet" is a claim about the measurement. Printing the first when
     * the second is true is how M21's unmeasured metrics would have looked if it had guessed.
     */
    private static String micros(dev.civitas.util.Timings.Snapshot snapshot) {
        java.util.OptionalDouble average = PerfReport.averageMicros(snapshot);
        return average.isPresent()
                ? String.format(java.util.Locale.ROOT, "%.3f", average.getAsDouble())
                : "-";
    }

    private LiteralArgumentBuilder<CommandSourceStack> migrate() {
        return Commands.literal("migrate")
                .requires(source -> source.getSender().hasPermission("civitas.admin.system"))
                .then(Commands.literal("check")
                        .executes(context -> {
                            Audience audience = context.getSource().getSender();
                            CivitasServices current = ready(audience);
                            if (current == null) {
                                return Command.SINGLE_SUCCESS;
                            }
                            List<String> waiting = current.pendingMigrations();
                            if (waiting.isEmpty()) {
                                lang.send(audience, "admin.system.migrate-none");
                            } else {
                                lang.sendRaw(audience, "admin.system.migrate-pending",
                                        Replies.p("count", String.valueOf(waiting.size())));
                                waiting.forEach(name -> lang.sendRaw(audience,
                                        "admin.system.migrate-entry", Replies.p("name", name)));
                            }
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> event() {
        return Commands.literal("event")
                .requires(source -> source.getSender().hasPermission("civitas.admin.event"))
                .then(Commands.literal("start")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (var type : dev.civitas.core.events.ServerEventType.values()) {
                                        builder.suggest(type.key());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    startEvent(context.getSource().getSender(),
                                            StringArgumentType.getString(context, "key"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("stop")
                        .executes(context -> {
                            stopEvent(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private void startEvent(Audience audience, String key) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        dev.civitas.core.events.ServerEventType.parse(key).ifPresentOrElse(type -> {
            current.audit().record(actorOf(audience), "EVENT_START", type.key(), null);
            Replies.reply(current.events().start(type, System.currentTimeMillis()),
                    audience, lang, scheduler, logger,
                    started -> lang.send(audience, "admin.system.event-started",
                            Replies.p("event", type.key())));
        }, () -> lang.send(audience, "admin.system.event-unknown", Replies.p("event", key)));
    }

    private void stopEvent(Audience audience) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.audit().record(actorOf(audience), "EVENT_STOP", null, null);
        Replies.reply(current.events().stop(System.currentTimeMillis()),
                audience, lang, scheduler, logger,
                stopped -> lang.send(audience, "admin.system.event-stopped"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> contest() {
        return Commands.literal("contest")
                .requires(source -> source.getSender().hasPermission("civitas.admin.contest"))
                .then(Commands.literal("start")
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 90))
                                .then(Commands.argument("theme",
                                                StringArgumentType.greedyString())
                                        .executes(context -> {
                                            startContest(context.getSource().getSender(),
                                                    StringArgumentType.getString(context, "theme"),
                                                    IntegerArgumentType.getInteger(context, "days"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("end")
                        .executes(context -> {
                            endContest(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("disqualify")
                        .then(Commands.argument("entry", IntegerArgumentType.integer(1))
                                .then(Commands.argument("reason",
                                                StringArgumentType.greedyString())
                                        .executes(context -> {
                                            disqualify(context.getSource().getSender(),
                                                    IntegerArgumentType.getInteger(context, "entry"),
                                                    StringArgumentType.getString(context, "reason"));
                                            return Command.SINGLE_SUCCESS;
                                        }))));
    }

    private void startContest(Audience audience, String theme, int days) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.audit().record(actorOf(audience), "CONTEST_START", theme, null,
                Map.of("days", String.valueOf(days)));
        Replies.reply(current.contests().start(theme, days, System.currentTimeMillis()),
                audience, lang, scheduler, logger,
                contest -> lang.send(audience, "admin.system.contest-started",
                        Replies.p("theme", theme),
                        Replies.p("days", String.valueOf(days))));
    }

    /**
     * SPEC 9.4.6: "End and score the current contest."
     *
     * <p>Through the same scoring path a natural ending takes, so a forced end and a timed one
     * cannot produce different results. A second code path here would be a second set of
     * prize payouts to keep in step.
     */
    private void endContest(Audience audience) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.contests().current().ifPresentOrElse(contest -> {
            current.audit().record(actorOf(audience), "CONTEST_END", contest.theme(), null);
            Replies.reply(current.contests().score(contest),
                    audience, lang, scheduler, logger,
                    placements -> lang.send(audience, "admin.system.contest-ended",
                            Replies.p("count", String.valueOf(placements.size()))));
        }, () -> lang.send(audience, "contest.none"));
    }

    private void disqualify(Audience audience, int entryId, String reason) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.audit().record(actorOf(audience), "CONTEST_DISQUALIFY",
                String.valueOf(entryId), reason);
        Replies.reply(current.contests().disqualify(entryId, reason),
                audience, lang, scheduler, logger,
                entry -> lang.send(audience, "admin.system.contest-disqualified",
                        Replies.p("entry", String.valueOf(entryId))));
    }

    // ==================================================================================
    // SPEC 15.3
    // ==================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> reports() {
        return Commands.literal("reports")
                .requires(source -> source.getSender().hasPermission("civitas.admin.info"))
                .executes(context -> {
                    listReports(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("id", LongArgumentType.longArg(1))
                        .executes(context -> {
                            showReport(context.getSource().getSender(),
                                    LongArgumentType.getLong(context, "id"));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("resolve")
                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            handle(context.getSource().getSender(),
                                                    LongArgumentType.getLong(context, "id"), true,
                                                    StringArgumentType.getString(context, "note"));
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(Commands.literal("dismiss")
                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            handle(context.getSource().getSender(),
                                                    LongArgumentType.getLong(context, "id"), false,
                                                    StringArgumentType.getString(context, "note"));
                                            return Command.SINGLE_SUCCESS;
                                        }))));
    }

    private void listReports(Audience audience) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.reports().queue().whenComplete((rows, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                lang.send(audience, "command.error");
                return;
            }
            if (rows.isEmpty()) {
                lang.send(audience, "admin.reports.empty");
                return;
            }
            lang.sendRaw(audience, "admin.reports.header",
                    Replies.p("count", String.valueOf(rows.size())));
            for (ReportRow row : rows) {
                lang.sendRaw(audience, "admin.reports.entry",
                        Replies.p("id", String.valueOf(row.id())),
                        Replies.p("target", nameOf(row.targetUuid())),
                        Replies.p("reporter", nameOf(row.reporterUuid())),
                        Replies.p("reason", row.reason()));
            }
        }));
    }

    /** SPEC 15.3's context, attached when the report is read. */
    private void showReport(Audience audience, long id) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.reports().queue().thenCompose(rows -> rows.stream()
                        .filter(row -> row.id() == id)
                        .findFirst()
                        .map(row -> current.reports().detail(row, System.currentTimeMillis()))
                        .orElseGet(() -> java.util.concurrent.CompletableFuture
                                .completedFuture(null)))
                .whenComplete((detail, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(audience, "command.error");
                        return;
                    }
                    if (detail == null) {
                        lang.send(audience, "admin.reports.unknown");
                        return;
                    }
                    lang.sendRaw(audience, "admin.reports.detail-header",
                            Replies.p("id", String.valueOf(detail.report().id())),
                            Replies.p("target", nameOf(detail.report().targetUuid())),
                            Replies.p("reporter", nameOf(detail.report().reporterUuid())));
                    lang.sendRaw(audience, "admin.reports.detail-reason",
                            Replies.p("reason", detail.report().reason()));
                    lang.sendRaw(audience, "admin.reports.detail-context",
                            Replies.p("ledger", String.valueOf(detail.ledger().size())),
                            Replies.p("kills", String.valueOf(detail.kills().size())));
                }));
    }

    private void handle(Audience audience, long id, boolean resolved, String note) {
        CivitasServices current = ready(audience);
        if (current == null) {
            return;
        }
        current.audit().record(actorOf(audience), resolved ? "REPORT_RESOLVE" : "REPORT_DISMISS",
                String.valueOf(id), note);

        Replies.reply(current.reports().handle(id, actorOf(audience), resolved, note,
                        System.currentTimeMillis()),
                audience, lang, scheduler, logger,
                changed -> lang.send(audience, resolved ? "admin.reports.resolved"
                                : "admin.reports.dismissed",
                        Replies.p("id", String.valueOf(id))));
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

    private static java.util.UUID actorOf(Audience audience) {
        return audience instanceof Player player ? player.getUniqueId() : null;
    }

    private static String nameOf(java.util.UUID uuid) {
        if (uuid == null) {
            return "-";
        }
        String name = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }
}
