-- City ban list and the membership cooldown timestamps, SQLite.
--
-- SPEC 5.2 makes "not on the city's ban list" a join precondition and SPEC 8.6 gives the
-- list a management screen, but SPEC 3 defines no table for it. SPEC 5.2 also imposes a
-- 24-hour cooldown before a player may join a *different* city, and SPEC 17.1 case 7 a
-- 24-hour cooldown after disbanding one; neither has a column in SPEC 3.1.

CREATE TABLE city_bans (
    city_id      INTEGER       NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    banned_uuid  CHAR(36)      NOT NULL,
    banned_by    CHAR(36)      NOT NULL,
    reason       VARCHAR(128)  NULL,
    banned_at    BIGINT        NOT NULL,
    PRIMARY KEY (city_id, banned_uuid)
);
CREATE INDEX idx_city_bans_player ON city_bans (banned_uuid);

-- 0 means "never", which is what every existing row should read as.
ALTER TABLE players ADD COLUMN last_city_leave BIGINT NOT NULL DEFAULT 0;
ALTER TABLE players ADD COLUMN last_city_disband BIGINT NOT NULL DEFAULT 0;
