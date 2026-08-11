package dev.civitas.storage.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.StaffNoteRow;

/**
 * {@code staff_notes}, SPEC 22.7.2.
 *
 * <p>No update and no delete, deliberately. A note a moderator could quietly revise is not a
 * record of what they thought at the time, which is the whole reason to keep one — the same
 * reasoning SPEC 3.6 applies to the ledger and M21 applied to the audit log.
 */
public final class StaffNoteDao extends Dao<StaffNoteRow> {

    public StaffNoteDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "staff_notes";
    }

    @Override
    protected StaffNoteRow map(ResultSet rs) throws SQLException {
        return new StaffNoteRow(rs.getLong("id"), uuid(rs, "target_uuid"),
                uuid(rs, "author_uuid"), rs.getString("note"), rs.getLong("created_at"));
    }

    /** Newest first, which is the order a moderator reads a history in. */
    public CompletableFuture<List<StaffNoteRow>> findAbout(UUID target, int limit) {
        return queryList("SELECT id, target_uuid, author_uuid, note, created_at "
                + "FROM staff_notes WHERE target_uuid = ? ORDER BY created_at DESC LIMIT ?",
                target, limit);
    }

    public CompletableFuture<Long> insert(StaffNoteRow row) {
        return db.call(connection -> insertSync(connection,
                "INSERT INTO staff_notes (target_uuid, author_uuid, note, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                row.targetUuid(), row.authorUuid(), row.note(), row.createdAt()));
    }

    public CompletableFuture<Integer> countAbout(UUID target) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT COUNT(*) AS total FROM staff_notes WHERE target_uuid = ?",
                rs -> rs.getInt("total"), target).orElse(0));
    }
}
