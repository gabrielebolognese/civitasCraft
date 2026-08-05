-- Lifetime player counters, SQLite.
--
-- SPEC 13.3's Builder and Farmer leaderboards rank "blocks placed" and "crops harvested"
-- over a player's whole time on the server. Nothing persisted either before now: M9 reports
-- both metrics, but only into player_quests.progress, which is reassigned every day. A
-- leaderboard built on that would rank whoever happened to log in this morning.
--
-- SPEC 3 lists no table for this, so it follows the M1 pattern: the milestone that needs a
-- table adds it, which also exercises the migration runner on a real upgrade.
--
-- One row per (player, stat) rather than one column per stat on players. A new counter is
-- then a new enum constant and no migration, and the players row does not grow a column
-- every time a leaderboard is added. The cost is a join-free GROUP BY on read, which is
-- what the leaderboard cache is for.

CREATE TABLE player_stats (
    uuid        CHAR(36)     NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    stat        VARCHAR(32)  NOT NULL,
    value       BIGINT       NOT NULL DEFAULT 0,
    updated_at  BIGINT       NOT NULL,
    PRIMARY KEY (uuid, stat)
);

-- The leaderboard reads "top N for one stat", so the index leads with the stat and orders
-- by value within it. Without this every refresh is a full scan of every counter of every
-- player.
CREATE INDEX idx_player_stats_rank ON player_stats (stat, value DESC);
