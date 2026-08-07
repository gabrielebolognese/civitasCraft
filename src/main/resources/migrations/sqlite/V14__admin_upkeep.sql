-- SPEC 9.4.2's upkeep multiplier, SQLite.
--
-- "/ca city setupkeep <city> <mult> — Temporary upkeep multiplier, e.g. for a returning-player
-- grace period."
--
-- Its own table rather than a column on cities, for two reasons. SPEC calls it temporary, and
-- an expiry is a thing a row can carry and a column would need a second column for; and a city
-- with no override is the overwhelming majority, so an absent row says that more cheaply than
-- a column of 1.0 on every city ever founded.
CREATE TABLE city_upkeep_multipliers (
    city_id    INTEGER NOT NULL PRIMARY KEY REFERENCES cities (id) ON DELETE CASCADE,
    multiplier REAL    NOT NULL,
    set_by     CHAR(36) NULL,
    set_at     BIGINT  NOT NULL,
    -- Null means until an admin removes it.
    expires_at BIGINT  NULL,
    reason     TEXT    NULL
);
