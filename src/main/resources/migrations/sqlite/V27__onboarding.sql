-- SPEC 34.3's starter quest chain, SQLite.
--
-- Deliberately NOT player_quests. SPEC 34.3: "Five steps, once per account, distinct from the
-- daily quests in Part I 13.1." The daily table is reassigned every midnight and its rows carry a
-- scaled target and reward; these five are fixed, permanent and each paid exactly once. Sharing
-- the table would mean an exception in every reader of it.
--
-- One row per COMPLETED step rather than a progress column per player. A step is done or it is
-- not, the set is five long, and an absent row is the same answer as a zero without needing a
-- backfill for every account that existed before this milestone.
CREATE TABLE player_onboarding (
    uuid         CHAR(36)    NOT NULL,
    step         VARCHAR(32) NOT NULL,
    completed_at BIGINT      NOT NULL,
    PRIMARY KEY (uuid, step)
);

-- The primary key is the idempotency: a step cannot be paid twice, whatever fires the trigger
-- twice. That matters because several of these are driven by events a player can repeat freely.
CREATE INDEX idx_player_onboarding_uuid ON player_onboarding (uuid);
