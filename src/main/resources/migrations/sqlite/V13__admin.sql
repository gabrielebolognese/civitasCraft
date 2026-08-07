-- Admin tooling, SQLite.
--
-- Two tables SPEC 3 does not define and SPEC 9.4 and 15.3 both need.

-- SPEC 9.4.3: "/ca claim protect <on|off> — Marks the chunk as admin-protected:
-- unclaimable, unbuildable, war-immune."
--
-- A separate table rather than a column on claims, because the whole point is that it applies
-- to ground nobody owns: an admin protects a spawn area or a build the server itself made, and
-- those are wilderness. SPEC 11.6 also lists admin-protected chunks among the three things
-- that stay protected even during a war, so a protected chunk is not a claim in any sense.
CREATE TABLE admin_protected_chunks (
    world       VARCHAR(64) NOT NULL,
    chunk_x     INTEGER     NOT NULL,
    chunk_z     INTEGER     NOT NULL,
    protected_by CHAR(36)   NULL,
    protected_at BIGINT     NOT NULL,
    reason      TEXT        NULL,
    PRIMARY KEY (world, chunk_x, chunk_z)
);

-- SPEC 15.3: "/report <player> <reason> writes to a moderation queue visible via /ca reports,
-- with automatic attachment of the reported player's last 50 ledger entries and last 50 war
-- actions, so admins have context without asking."
--
-- The context is attached when the report is read rather than copied in when it is written:
-- copying fifty ledger rows into a text column would duplicate the record SPEC 1.5 makes
-- authoritative, and the two could then disagree.
CREATE TABLE reports (
    id            INTEGER     NOT NULL PRIMARY KEY AUTOINCREMENT,
    reporter_uuid CHAR(36)    NOT NULL,
    target_uuid   CHAR(36)    NOT NULL,
    reason        TEXT        NOT NULL,
    created_at    BIGINT      NOT NULL,
    -- OPEN, RESOLVED or DISMISSED. Rows are kept in every case: a moderation decision that
    -- erased its own evidence would be the one action in this plugin nobody could review.
    state         VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    handled_by    CHAR(36)    NULL,
    handled_at    BIGINT      NULL,
    resolution    TEXT        NULL
);
CREATE INDEX idx_reports_state ON reports (state, created_at);
CREATE INDEX idx_reports_target ON reports (target_uuid, created_at);
