-- Daily quests and weekly challenges, MySQL and MariaDB.
--
-- SPEC 3.9 gives player_quests a progress column but no target and no reward. SPEC 13.1
-- scales both with the player's playtime at the moment the quest is handed out, so neither
-- can be recomputed later without the target moving under a player who is halfway through
-- one. They are stored with the assignment instead.
--
-- SPEC 13.2's weekly challenges have no table in SPEC 3 at all. They are city-wide with
-- progress pooled across every member, so they are keyed by city and week rather than by
-- player.

ALTER TABLE player_quests ADD COLUMN target INT NOT NULL DEFAULT 1;
ALTER TABLE player_quests ADD COLUMN reward DECIMAL(20, 2) NOT NULL DEFAULT 0;

CREATE TABLE city_challenges (
    id            BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    city_id       INT            NOT NULL,
    challenge_id  VARCHAR(64)    NOT NULL,
    progress      BIGINT         NOT NULL DEFAULT 0,
    target        BIGINT         NOT NULL,
    reward        DECIMAL(20, 2) NOT NULL,
    week_start    BIGINT         NOT NULL,
    completed_at  BIGINT         NULL,
    UNIQUE KEY idx_city_challenges_week (city_id, challenge_id, week_start),
    INDEX idx_city_challenges_city (city_id, week_start),
    CONSTRAINT fk_city_challenges_city FOREIGN KEY (city_id) REFERENCES cities (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
