-- Alliance state and trust, SQLite.
--
-- SPEC 14.2 gives breaking an alliance a 24-hour notice period and then a 7-day cooldown
-- before the two may re-ally. Both are measured from the moment the state last changed, and
-- SPEC 3.9's alliances table records only when the alliance was formed. A row that has been
-- through invite, accept and break has one timestamp for three events.
--
-- SPEC 14.2 also makes reciprocal build access an option a pair turns on, with no column
-- for it.

ALTER TABLE alliances ADD COLUMN state_changed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE alliances ADD COLUMN trusted BOOLEAN NOT NULL DEFAULT 0;

-- Which city asked, so an invite can be accepted only by the other one.
ALTER TABLE alliances ADD COLUMN proposed_by INTEGER NOT NULL DEFAULT 0;
