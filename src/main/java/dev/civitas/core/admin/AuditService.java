package dev.civitas.core.admin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.dao.AuditLogDao;
import dev.civitas.storage.row.AuditLogRow;

/**
 * Every admin action, written down, SPEC 17.6 case 80.
 *
 * <h2>Why this is separate from the ledger</h2>
 * SPEC 3.9 calls the audit log "admin actions only, separate from the ledger", and the
 * separation is the point. The ledger answers "where did this money go"; the audit log answers
 * "who did this to whom, and what reason did they give". An admin who gives a player a million
 * coins writes to both, and the two are cross-checkable precisely because neither is derived
 * from the other.
 *
 * <p>SPEC 17.6 case 80 also says it "cannot be cleared in-game", which is not enforced by a
 * permission check: {@link AuditLogDao} simply offers no update and no delete, so there is no
 * code path that could clear it. A guard can be bypassed; a method that does not exist cannot.
 *
 * <h2>Recorded even when the action fails</h2>
 * An attempt is as interesting as a success, and often more so. An admin trying repeatedly to
 * give themselves money and being refused is exactly the pattern this log exists to make
 * visible, and a log that only recorded successes would hide it.
 */
public final class AuditService {

    private final AuditLogDao audit;
    private final Logger logger;

    public AuditService(AuditLogDao audit, Logger logger) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Records one admin action.
     *
     * <p>Fire and forget, and deliberately so: a command must not fail because the audit write
     * was slow. A write that cannot happen is logged to the console at {@code SEVERE}, because
     * an admin action nobody can reconstruct is the thing SPEC 1.5 says must not happen
     * quietly.
     *
     * @param actor  who did it, or null for a console or scheduled action
     * @param action a stable identifier such as {@code CITY_FREEZE}
     * @param target what it was done to, in whatever form reads best in a report
     * @param reason the admin's stated reason, which some commands require
     */
    public CompletableFuture<Long> record(UUID actor, String action, String target,
                                          String reason, Map<String, String> metadata) {
        AuditLogRow row = new AuditLogRow(0, System.currentTimeMillis(), actor,
                Objects.requireNonNull(action, "action"), target, reason, json(metadata));
        try {
            return audit.insert(row).exceptionally(error -> {
                logger.log(Level.SEVERE, "Could not record the admin action " + action
                        + " on " + target + ". It happened and there is now no record of it.",
                        error);
                return 0L;
            });
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not record the admin action " + action
                    + " on " + target, e);
            return CompletableFuture.completedFuture(0L);
        }
    }

    public CompletableFuture<Long> record(UUID actor, String action, String target,
                                          String reason) {
        return record(actor, action, target, reason, Map.of());
    }

    /** An action that was refused, which is worth as much as one that succeeded. */
    public CompletableFuture<Long> recordRefusal(UUID actor, String action, String target,
                                                 String why) {
        return record(actor, action, target, null, Map.of("refused", why));
    }

    /** The most recent entries, for {@code /ca audit}. */
    public CompletableFuture<List<AuditLogRow>> recent(long since, int limit) {
        return audit.findRecent(since, limit);
    }

    /**
     * A minimal JSON object.
     *
     * <p>Hand-built rather than pulled from a library: the metadata column is read by admins
     * and by whatever they pipe it into, the values are short, and adding a JSON dependency to
     * write six key-value pairs would be a poor trade. Quotes and backslashes are escaped
     * because a player name is not trusted input.
     */
    static String json(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        return out.append('}').toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
