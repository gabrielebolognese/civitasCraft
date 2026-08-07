package dev.civitas.core.moderation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.ReportRow;
import dev.civitas.storage.row.WarKillRow;
import dev.civitas.util.Result;

/**
 * SPEC 15.3's moderation queue.
 *
 * <h2>The context is attached when the report is read, not when it is written</h2>
 * SPEC 15.3 asks for "automatic attachment of the reported player's last 50 ledger entries and
 * last 50 war actions, so admins have context without asking". Copying fifty ledger rows into
 * a text column at write time would fork the record SPEC 1.5 makes authoritative: the copy and
 * the ledger could then disagree, and the copy is the one a moderator would be looking at.
 *
 * <p>Reading it on demand also means the context is current. A report filed on Monday and read
 * on Friday shows what the player did all week, which is usually the more useful answer.
 *
 * <h2>Reports are never deleted</h2>
 * Handled ones are marked, not removed. A moderation decision that erased its own evidence
 * would be the single action in this plugin that nobody could review afterwards — the opposite
 * of what SPEC 1.5 builds every other record for.
 */
public final class ReportService {

    private final DaoRegistry daos;
    private final ConfigManager configs;

    public ReportService(DaoRegistry daos, ConfigManager configs) {
        this.daos = Objects.requireNonNull(daos, "daos");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /** A report with the SPEC 15.3 context an admin needs to judge it. */
    public record Detailed(ReportRow report, List<LedgerRow> ledger, List<WarKillRow> kills) { }

    // ==================================================================================
    // Filing
    // ==================================================================================

    /**
     * Files a report.
     *
     * <p>SPEC 15.3 specifies no rate limit, and a player-facing command that writes to a queue
     * needs one: without it a single player can bury the queue and make the feature useless
     * for everybody, which defeats it rather than serving it. The limit is deliberately
     * generous — nobody with a real complaint should meet it — and configurable.
     */
    public CompletableFuture<Result<ReportRow>> file(UUID reporter, UUID target, String reason,
                                                      long now) {
        if (reporter.equals(target)) {
            return completed(Result.failure("SELF_REPORT", "report.self"));
        }
        if (reason == null || reason.isBlank()) {
            return completed(Result.failure("NO_REASON", "report.no-reason"));
        }
        String trimmed = reason.strip();
        int maxLength = configs.get(ConfigFile.CONFIG).getInt("moderation.max-reason-length", 200);
        if (trimmed.length() > maxLength) {
            trimmed = trimmed.substring(0, maxLength);
        }
        String stored = trimmed;

        long window = configs.get(ConfigFile.CONFIG)
                .getLong("moderation.report-window-hours", 24) * 3_600_000L;
        int limit = configs.get(ConfigFile.CONFIG).getInt("moderation.reports-per-window", 5);

        return daos.reports().countRecentBy(reporter, now - window).thenCompose(recent -> {
            if (recent >= limit) {
                return completed(Result.<ReportRow>failure("TOO_MANY", "report.too-many",
                        Map.of("limit", String.valueOf(limit))));
            }
            ReportRow row = new ReportRow(0, reporter, target, stored, now,
                    dev.civitas.storage.dao.ReportDao.OPEN, null, null, null);
            return daos.reports().insert(row).thenApply(id -> Result.success(
                    new ReportRow(id, reporter, target, stored, now,
                            dev.civitas.storage.dao.ReportDao.OPEN, null, null, null)));
        });
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    /** The open queue, oldest first: the oldest complaint has waited longest. */
    public CompletableFuture<List<ReportRow>> queue() {
        return daos.reports().findOpen(queueSize());
    }

    /**
     * One report with its context, SPEC 15.3.
     *
     * <p>Three queries rather than one join, because the three tables answer three unrelated
     * questions and a join across them would be harder to read than the code that reads them.
     * This runs when an admin asks about a specific report, which is not a hot path.
     */
    public CompletableFuture<Detailed> detail(ReportRow report, long now) {
        long window = configs.get(ConfigFile.CONFIG)
                .getLong("moderation.context-days", 30) * 86_400_000L;
        int size = configs.get(ConfigFile.CONFIG).getInt("moderation.context-entries", 50);

        return daos.ledger().findByPlayer(report.targetUuid(), now - window, size)
                .thenCompose(ledger -> killsOf(report.targetUuid(), size)
                        .thenApply(kills -> new Detailed(report, ledger, kills)));
    }

    /**
     * The reported player's recent war activity.
     *
     * <p>SPEC 15.3 says "war actions", and kills are the ones a report is usually about: a
     * complaint about somebody's conduct in a war is a complaint about who they killed and
     * when. Block-level war damage is in M17's log, which is scoped per war and far too large
     * to attach to a chat message.
     */
    private CompletableFuture<List<WarKillRow>> killsOf(UUID player, int limit) {
        return daos.warKills().findRecentByKiller(player, limit);
    }

    // ==================================================================================
    // Handling
    // ==================================================================================

    /**
     * Closes a report.
     *
     * <p>The state is part of the WHERE clause, so two moderators acting at once cannot both
     * close it and the second is told it was already handled. Without that they would each
     * believe they had dealt with it, which is how a report gets actioned twice.
     */
    public CompletableFuture<Result<Integer>> handle(long id, UUID moderator, boolean resolved,
                                                      String resolution, long now) {
        String state = resolved
                ? dev.civitas.storage.dao.ReportDao.RESOLVED
                : dev.civitas.storage.dao.ReportDao.DISMISSED;

        return daos.reports().handle(id, state, moderator, now, resolution)
                .thenApply(changed -> changed > 0
                        ? Result.success(changed)
                        : Result.failure("ALREADY_HANDLED", "report.already-handled"));
    }

    /** Everything ever filed about a player, for the pattern rather than the incident. */
    public CompletableFuture<List<ReportRow>> about(UUID target) {
        return daos.reports().findAbout(target, queueSize());
    }

    public int queueSize() {
        return configs.get(ConfigFile.CONFIG).getInt("moderation.queue-size", 25);
    }

    public long cooldownHours() {
        return configs.get(ConfigFile.CONFIG).getLong("moderation.report-window-hours", 24);
    }

    public int reportsPerWindow() {
        return configs.get(ConfigFile.CONFIG).getInt("moderation.reports-per-window", 5);
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /** Kept so the window is visible where the queue is rendered. */
    static long millis(long hours) {
        return TimeUnit.HOURS.toMillis(hours);
    }
}
