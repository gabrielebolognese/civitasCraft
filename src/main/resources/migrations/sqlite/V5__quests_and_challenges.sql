-- Daily quests and weekly challenges, SQLite.
--
-- SPEC 3.9 gives player_quests a progress column but no target and no reward. SPEC 13.1
-- scales both with the player's playtime at the moment the quest is handed out, so neither
-- can be recomputed later without the target moving under a player who is halfway through
-- one. They are stored with the assignment instead.
--
-- SPEC 13.2's weekly challenges have no table in SPEC 3 at all. They are city-wide with
-- progress pooled across every member, so they are keyed by city and week rather than by
-- player.
--
-- Money columns are INTEGER minor units here for the same reason as V1; see that file.

ALTER TABLE player_quests ADD COLUMN target INTEGER NOT NULL DEFAULT 1;
ALTER TABLE player_quests ADD COLUMN reward INTEGER NOT NULL DEFAULT 0;

CREATE TABLE city_challenges (
    id            INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    city_id       INTEGER      NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    challenge_id  VARCHAR(64)  NOT NULL,
    progress      BIGINT       NOT NULL DEFAULT 0,
    target        BIGINT       NOT NULL,
    reward        INTEGER      NOT NULL,          -- minor units, paid to the treasury
    week_start    BIGINT       NOT NULL,          -- Monday 00:00 server time
    completed_at  BIGINT       NULL
);

-- One row per city per challenge per week: the physical guarantee that a challenge cannot
-- be assigned, or paid out, twice in the same week.
CREATE UNIQUE INDEX idx_city_challenges_week
    ON city_challenges (city_id, challenge_id, week_start);
CREATE INDEX idx_city_challenges_city ON city_challenges (city_id, week_start);
