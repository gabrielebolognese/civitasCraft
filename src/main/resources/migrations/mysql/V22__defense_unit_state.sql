-- Defense unit persistence, SPEC 25.4, MySQL.
--
-- SPEC 25.4 opens "This is an architectural requirement, not an optimisation": two hundred
-- cities times twelve units is 2,400 permanently loaded entities, which destroys tick rate
-- long before the plugin is finished. A unit is therefore a row that becomes an entity only
-- while a player is near it, and goes back to being a row when they leave.
--
-- Two columns are what that costs. Before this, a unit's health lived only in the entity, so
-- dematerialising it healed it to full -- and SPEC 25.4 is explicit that "a unit at 40% health
-- that dematerializes returns at 40% health".

-- Current health. Null means "never materialised", which reads as full: a unit nobody has ever
-- approached has taken no damage, and defaulting to zero would kill every unit on upgrade.
ALTER TABLE defense_units ADD COLUMN health DOUBLE NULL;

-- When it last dematerialised, so SPEC 25.4's dormant regeneration knows how long it has been
-- resting. Null while materialised or never placed.
ALTER TABLE defense_units ADD COLUMN dormant_since BIGINT NULL;
