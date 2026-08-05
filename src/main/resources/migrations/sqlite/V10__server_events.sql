-- Scheduled server-wide events, SQLite.
--
-- SPEC 13.5 defines eight events with durations from 4 hours to 7 days, but SPEC 3 lists no
-- table for one. Without persistence a restart silently cancels whatever was running: prices
-- move back mid-Market-Boom, a Founders' Week ends on day two, and nobody can explain why.
--
-- Finished rows are kept rather than deleted. SPEC 13.5 events move real money (Invasion pays
-- treasuries, Double Upkeep takes more) and SPEC 1.5 makes that kind of thing auditable; an
-- admin asking "was Double Upkeep running last Tuesday" should have somewhere to look.

CREATE TABLE server_events (
    id          INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    event_key   VARCHAR(32)  NOT NULL,
    starts_at   BIGINT       NOT NULL,
    ends_at     BIGINT       NOT NULL,
    -- Null until the event actually finishes. What distinguishes "still running" from
    -- "ran and ended", which matters on startup: an event whose window has passed but which
    -- was never closed is one the server was down for.
    ended_at    BIGINT       NULL,
    announced   BOOLEAN      NOT NULL DEFAULT 0
);

-- The startup question is "is anything unfinished", asked once per boot.
CREATE INDEX idx_server_events_open ON server_events (ended_at, starts_at);
CREATE INDEX idx_server_events_key ON server_events (event_key, starts_at);
