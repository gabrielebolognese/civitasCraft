-- Hourly circulation snapshots, SQLite.
--
-- SPEC 4.8 requires the plugin to track total circulating currency, log it hourly, and warn
-- when circulation grows more than 15% week over week. A week-over-week comparison needs
-- history, and SPEC 3 defines no table to keep it in.
--
-- Money columns are INTEGER minor units here for the same reason as V1; see that file.

CREATE TABLE economy_snapshots (
    id              INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    timestamp       BIGINT   NOT NULL,
    player_total    INTEGER  NOT NULL,   -- minor units, sum of every wallet
    treasury_total  INTEGER  NOT NULL    -- minor units, sum of every live city treasury
);
CREATE INDEX idx_economy_snapshots_time ON economy_snapshots (timestamp);
