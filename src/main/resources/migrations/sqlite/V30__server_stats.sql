-- SPEC 36.6's daily server statistics, SQLite.
--
-- SPEC 36.6 says what these are for, and it is not vanity: "so a server owner can see trends,
-- which is the only way to notice retention problems before they are terminal."
--
-- One row a day rather than a running counter, because the question is always about a trend --
-- "are we losing players" is a shape, not a number, and a counter cannot be differenced.
CREATE TABLE server_stats (
    day_start        BIGINT NOT NULL PRIMARY KEY,   -- midnight of the day this describes
    registered       INT    NOT NULL,
    active_7d        INT    NOT NULL,
    active_30d       INT    NOT NULL,
    cities           INT    NOT NULL,
    claims           INT    NOT NULL,
    average_city     DOUBLE NOT NULL,
    wars_started     INT    NOT NULL,
    contest_entries  INT    NOT NULL
);
