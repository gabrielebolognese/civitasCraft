-- SPEC 17.1 case 1's deferred notice, SQLite.
--
-- "Mayorship auto-transfers to the highest-weight member with the most recent login. Old mayor
-- is demoted to Co-Mayor, notified on next login."
--
-- The notice needs storage because the person it is for is, by definition, absent: the whole
-- reason the transfer happened is that they have not logged in for thirty days. There is
-- nowhere to send a message to.
--
-- A row per undelivered notice rather than a flag on players, because a player can accumulate
-- more than one (demoted in one city, whose city was then deleted under case 3) and because a
-- delivered notice should stop existing rather than sit as a permanent true.
CREATE TABLE player_notices (
    id         INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    uuid       CHAR(36) NOT NULL,
    -- A lang key, never rendered text: SPEC 2.1 keeps player-facing strings in lang/, and a
    -- notice written in English into the database would be unreadable to an Italian player
    -- and unfixable after the fact.
    message_key VARCHAR(64) NOT NULL,
    -- JSON of placeholder name to value, inserted unparsed exactly as Result.Failure does.
    placeholders TEXT     NULL,
    created_at BIGINT   NOT NULL
);

CREATE INDEX idx_player_notices_uuid ON player_notices (uuid, created_at);
