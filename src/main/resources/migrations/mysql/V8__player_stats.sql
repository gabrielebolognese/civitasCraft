-- Lifetime player counters, MySQL and MariaDB.
--
-- SPEC 13.3's Builder and Farmer leaderboards need a counter that outlives the daily quest
-- reset; see the SQLite copy for the full reasoning and for why this is one row per
-- (player, stat) rather than a column per stat.

CREATE TABLE player_stats (
    uuid        CHAR(36)    NOT NULL,
    stat        VARCHAR(32) NOT NULL,
    value       BIGINT      NOT NULL DEFAULT 0,
    updated_at  BIGINT      NOT NULL,
    PRIMARY KEY (uuid, stat),
    INDEX idx_player_stats_rank (stat, value DESC),
    CONSTRAINT fk_player_stats_player FOREIGN KEY (uuid) REFERENCES players (uuid)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
