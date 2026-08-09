-- The City Warden, SPEC 28, MySQL.
--
-- Its own table rather than a column on defense_units, for two reasons that are both about
-- ownership rather than about tidiness.
--
-- First, SPEC 28.2's "One per city" is a fact about the CITY, not about the unit, and a primary
-- key on city_id is the physical guarantee -- the same shape SPEC 3.4 uses for claims, where the
-- unique index is what actually stops two cities owning one chunk. Two members buying at the same
-- tick cannot both succeed, whatever the service happens to check.
--
-- Second, SPEC 28.6's peacetime recovery needs somewhere to keep a deadline, and the obvious
-- candidate on defense_units is `active` -- which the upkeep sweep already owns. Two systems
-- writing one boolean from opposite directions is how a city ends up with an army it is not
-- paying for, which CapacityReconciler documents at length and declines to do.
--
-- A DEADLINE and not a scheduled task, because SPEC 30.2 case 98 forbids recovery being
-- accelerated -- "Recovery continues. The city fights that war without it." -- and a task that
-- died with the server would either restart at six hours or never fire at all.

CREATE TABLE city_wardens (
    -- SPEC 28.2's limit, enforced physically. Never two at once.
    city_id          INT      NOT NULL PRIMARY KEY,
    -- The defense_units row it stands as. The Warden is an ordinary unit in every respect the
    -- materialisation, leash, upkeep and death paths care about; this table carries only what
    -- makes it the flagship.
    unit_id          INT      NOT NULL,
    purchased_at     BIGINT   NOT NULL,
    -- SPEC 28.6: null while it is present, a timestamp while it is burrowed. This column is the
    -- whole of SPEC 28.7's fifth state; RECOVERING is deliberately not a UnitState constant,
    -- because SPEC 30.1's targeting table has no row for one and already cancels on DORMANT.
    recovering_until BIGINT   NULL
);

CREATE INDEX idx_city_wardens_unit ON city_wardens (unit_id);
