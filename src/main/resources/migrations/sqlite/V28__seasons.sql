-- SPEC 35's seasons, SQLite.
--
-- SPEC 35.1 states the problem: "Six months in, the founding cities hold every top slot and a
-- player who joins in month seven cannot ever appear on any of them. The multi-axis leaderboard
-- design in Part I 13.3 existed specifically to give newcomers a path to visible status. Permanent
-- accumulation removes that path and quietly breaks pillar 1.3."
--
-- A season is a SCOREBOARD RESET AND NOTHING ELSE. SPEC 35.2: "Nothing a player built or owns is
-- ever taken away. This is not a wipe." Cities, claims, treasuries, balances, builds, upgrades and
-- the world all survive untouched, and there is no code here that could take any of them.
CREATE TABLE seasons (
    id         INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    name       VARCHAR(48) NOT NULL,
    theme      VARCHAR(64) NOT NULL,
    starts_at  BIGINT   NOT NULL,
    ends_at    BIGINT   NOT NULL,
    state      VARCHAR(16) NOT NULL,   -- RUNNING or FINISHED
    ended_at   BIGINT                  -- when it was actually scored, null while running
);

CREATE INDEX idx_seasons_state ON seasons (state);

-- SPEC 35.2's Hall of Fame: "a permanent record of every season's winners in each category."
-- Permanent is the point -- it is what a player who topped a board in season two still has in
-- season nine, and it is the only thing in this system that never resets.
CREATE TABLE season_results (
    id          INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    season_id   INT      NOT NULL,
    board       VARCHAR(32) NOT NULL,
    position    INT      NOT NULL,
    holder_uuid CHAR(36),               -- null for a city board
    holder_name VARCHAR(48) NOT NULL,
    value       VARCHAR(32) NOT NULL    -- rendered at scoring time; the underlying number moves
);

CREATE INDEX idx_season_results_season ON season_results (season_id, board, position);

-- What a counter read when the season opened, so the board can be shown SINCE it opened.
--
-- This is what makes "reset the rankings without resetting anything else" possible at all. The
-- flow boards -- Builder, Farmer, Contribution -- are lifetime counters, and a season value is
-- lifetime minus this baseline. Resetting the counter itself would destroy the lifetime figure,
-- which is a thing a player owns.
CREATE TABLE season_baselines (
    season_id INT         NOT NULL,
    board     VARCHAR(32) NOT NULL,
    subject   VARCHAR(48) NOT NULL,   -- a player uuid or a city name, per the board
    value     BIGINT      NOT NULL,
    PRIMARY KEY (season_id, board, subject)
);
