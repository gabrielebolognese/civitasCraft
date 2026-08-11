-- SPEC 22.7.2's staff notes, SQLite.
--
-- Deliberately not the report queue and deliberately not audit_log. A report is somebody
-- complaining; an audit row is an action that happened; a note is a moderator's own memory of a
-- player across both. Folding notes into either would mean a moderator's private working notes
-- appearing in a player-facing report, or an append-only log gaining rows nobody performed.
--
-- Append-only in practice: there is no update and no delete. A note a moderator could quietly
-- revise is not a record of what they thought at the time, which is the whole reason to keep one.
CREATE TABLE staff_notes (
    id          INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    target_uuid CHAR(36) NOT NULL,
    author_uuid CHAR(36),            -- null for a note left by the console
    note        TEXT     NOT NULL,
    created_at  BIGINT   NOT NULL
);

CREATE INDEX idx_staff_notes_target ON staff_notes (target_uuid, created_at DESC);
