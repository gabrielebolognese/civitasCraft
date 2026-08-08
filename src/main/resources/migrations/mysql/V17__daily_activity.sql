-- Active playtime within the current day, MySQL and MariaDB.
--
-- SPEC 21.4 F12: "daily login rewards require 30 minutes of active playtime that day before
-- paying out." SPEC 3.1's players.active_playtime_ms is a lifetime counter, so it cannot
-- answer "today" on its own.
--
-- One baseline row per player rather than an accrual: it stores the lifetime figure as it
-- stood when the day turned, and today's active playtime is the difference.

CREATE TABLE player_daily_activity (
    uuid          CHAR(36) NOT NULL PRIMARY KEY,
    day_start     BIGINT   NOT NULL,
    baseline_ms   BIGINT   NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
