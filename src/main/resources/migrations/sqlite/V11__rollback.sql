-- The rollback engine's own bookkeeping, SQLite.
--
-- SPEC 11.8.4 hashes every war-zone chunk before a war and again after the rollback, and
-- flags any that disagree. SPEC 17.4 case 57 wants a failed verification sample surfaced in
-- /ca war rollbackstatus. Both outlive the process that produced them: an admin looks at this
-- after the war, possibly after a restart, so neither can live in memory.
--
-- SPEC 3 defines no table for either.

-- SPEC 11.8.4. One row per chunk of the war zone.
CREATE TABLE war_chunk_hashes (
    war_id       INTEGER      NOT NULL REFERENCES wars (id) ON DELETE CASCADE,
    world        VARCHAR(64)  NOT NULL,
    chunk_x      INTEGER      NOT NULL,
    chunk_z      INTEGER      NOT NULL,
    -- Taken at war start, before anyone can grief.
    hash_before  BIGINT       NOT NULL,
    -- Taken after the rollback finishes. Null until then.
    hash_after   BIGINT       NULL,
    PRIMARY KEY (war_id, world, chunk_x, chunk_z)
);

-- Everything that went wrong during a rollback, kept for the admin who has to explain it.
--
-- Deliberately append-only in the same spirit as the SPEC 3.6 ledger: these rows are the
-- evidence that a restore was imperfect, and evidence that can be edited is not evidence.
CREATE TABLE war_rollback_issues (
    id           INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    war_id       INTEGER      NOT NULL REFERENCES wars (id) ON DELETE CASCADE,
    -- VERIFY_MISMATCH, CHUNK_HASH_MISMATCH, APPLY_FAILED or LOG_UNREADABLE.
    kind         VARCHAR(32)  NOT NULL,
    world        VARCHAR(64)  NULL,
    x            INTEGER      NULL,
    y            INTEGER      NULL,
    z            INTEGER      NULL,
    detail       TEXT         NULL,
    detected_at  BIGINT       NOT NULL
);
CREATE INDEX idx_war_rollback_issues_war ON war_rollback_issues (war_id, kind);
