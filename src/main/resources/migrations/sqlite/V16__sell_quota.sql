-- The SPEC 21.5 daily sell quota, SQLite.
--
-- SPEC 21.5 calls this "the single most important mechanism in the revised economy, because
-- it is exploit-agnostic. It bounds money creation regardless of how clever the exploit is."
-- A per-player counter is the whole of it, and SPEC 3 defines no table for one.
--
-- Money columns are INTEGER minor units here for the same reason as V1; see that file.

CREATE TABLE player_sell_quota (
    uuid          CHAR(36) NOT NULL PRIMARY KEY,
    period_start  BIGINT   NOT NULL,   -- 00:00 of the day this row is counting
    used          INTEGER  NOT NULL    -- minor units of value sold to the server market
);

-- Rows are pruned by period, so the sweep does not scan the whole table.
CREATE INDEX idx_player_sell_quota_period ON player_sell_quota (period_start);
