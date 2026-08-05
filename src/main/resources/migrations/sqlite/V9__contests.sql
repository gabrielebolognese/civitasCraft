-- Contest voting and its anti-abuse data, SQLite.
--
-- SPEC 13.4 step 4 has voters score an entry "1 to 10 across three axes: Creativity,
-- Technical Skill, Theme Fit", but SPEC 3.9's contest_votes carries a single score. The three
-- axes are added beside it; `score` keeps its meaning as the combined figure the tally uses,
-- so nothing that already reads it changes.

ALTER TABLE contest_votes ADD COLUMN creativity      INTEGER NOT NULL DEFAULT 0;
ALTER TABLE contest_votes ADD COLUMN technical_skill INTEGER NOT NULL DEFAULT 0;
ALTER TABLE contest_votes ADD COLUMN theme_fit       INTEGER NOT NULL DEFAULT 0;

-- The weight SPEC 13.4 applies to this vote: 1.0 normally, 0.25 for an account under the
-- playtime bar, 0 for a vote its anti-abuse rules discard. Stored rather than recomputed
-- because playtime keeps rising, and a vote cast by a new account should not silently gain
-- weight later just because they kept playing.
ALTER TABLE contest_votes ADD COLUMN weight DOUBLE NOT NULL DEFAULT 1.0;

-- SPEC 9.4.6 /ca contest disqualify. The entry is kept rather than deleted: the vote rows
-- reference it, and an admin decision that erased its own evidence would be the one thing in
-- this plugin that cannot be audited (SPEC 1.5).
ALTER TABLE contest_entries ADD COLUMN disqualified        BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE contest_entries ADD COLUMN disqualified_reason TEXT    NULL;

-- Final placement, 1 to 3 for the paid places and NULL for the rest. Written once when the
-- contest is scored, so a result cannot drift if scores are ever recomputed.
ALTER TABLE contest_entries ADD COLUMN placement INTEGER NULL;

-- SPEC 13.4: "Votes from accounts sharing an IP with a member of the entered city are
-- discarded", and SPEC 17.6 case 72 lists that among the fraud controls. Nothing in SPEC 3
-- stores anything about how a player connects, so this is new.
--
-- It holds a SALTED HASH of the address and never the address itself. The rule only ever
-- asks "are these two the same connection", which a hash answers, and the plugin has no
-- business keeping the answer to anything else. The salt is generated once per server and
-- kept outside the database, so a stolen copy of this table cannot be reversed by hashing
-- candidate addresses.
--
-- A separate table rather than a column on players so it can be dropped or purged on its own
-- without touching the row every other system reads.
CREATE TABLE player_logins (
    uuid        CHAR(36)     NOT NULL PRIMARY KEY REFERENCES players (uuid) ON DELETE CASCADE,
    login_hash  VARCHAR(64)  NOT NULL,
    updated_at  BIGINT       NOT NULL
);
CREATE INDEX idx_player_logins_hash ON player_logins (login_hash);
