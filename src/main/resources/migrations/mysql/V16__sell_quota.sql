-- The SPEC 21.5 daily sell quota, MySQL and MariaDB.
--
-- SPEC 21.5 calls this "the single most important mechanism in the revised economy, because
-- it is exploit-agnostic. It bounds money creation regardless of how clever the exploit is."
-- A per-player counter is the whole of it, and SPEC 3 defines no table for one.

CREATE TABLE player_sell_quota (
    uuid          CHAR(36)       NOT NULL PRIMARY KEY,
    period_start  BIGINT         NOT NULL,
    used          DECIMAL(20, 2) NOT NULL,
    INDEX idx_player_sell_quota_period (period_start)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
