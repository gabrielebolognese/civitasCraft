-- The attacker's siege units, SPEC 29.4, SQLite.
--
-- SPEC 3 defines no table and SPEC 29 asks for none, but two rules make one unavoidable.
--
-- The budget. SPEC 29.2 caps what an attacker may field, and the cap is only meaningful if the
-- server remembers what has already been fielded. Held in memory alone, a restart would hand the
-- attacker a fresh 70 points while their existing units were still standing in the world -- their
-- entities survive a restart because SPEC 12.5's persistence flags are set on them.
--
-- The despawn. SPEC 29.4 despawns every unit at war end and SPEC 29.5 despawns a city's units when
-- its camp falls. Both need a list of what to remove, and scanning the world for tagged mobs only
-- finds the ones in loaded chunks.
--
-- Deliberately NOT modelled on defense_units: a siege unit has no upkeep, no materialisation, no
-- city cap and no life beyond its war. Sharing that table would mean an exception in every reader.
CREATE TABLE siege_units (
    id       INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    war_id   INT      NOT NULL,
    city_id  INT      NOT NULL,
    type     VARCHAR(32) NOT NULL,
    points   INT      NOT NULL,   -- copied from the roster at purchase, so a retune cannot
                                  -- retroactively bankrupt or enrich a war already under way
    world    VARCHAR(64) NOT NULL,
    x        DOUBLE   NOT NULL,
    y        DOUBLE   NOT NULL,
    z        DOUBLE   NOT NULL,
    alive    BOOLEAN  NOT NULL DEFAULT 1,
    bought_at BIGINT  NOT NULL
);

-- The tally is read on every purchase, so it gets the index rather than the despawn sweep.
CREATE INDEX idx_siege_units_war_city ON siege_units (war_id, city_id, alive);
