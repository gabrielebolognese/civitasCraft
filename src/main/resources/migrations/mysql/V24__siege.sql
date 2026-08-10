-- Siege camps and the frozen siege budget, SPEC 29, MySQL.
--
-- SPEC 29.2: "Siege Capacity is computed once, at war declaration, and frozen." Frozen means
-- stored: derived from the defender's Fortification level at declaration, it would otherwise be
-- recomputed from the CURRENT level after any restart -- so a defender could hand their attacker
-- a larger army mid-war by buying an upgrade, or shrink the attack by selling one. Neither is a
-- decision a war should contain.
ALTER TABLE wars ADD COLUMN siege_capacity INT NOT NULL DEFAULT 0;

-- SPEC 29.5's camp. The spawn and rally point for an attacking city's siege units, and a real
-- secondary objective: destroying it despawns that city's units and scores for the defender.
--
-- Deliberately visible to BOTH sides on /city map. SPEC 29.5: "The defender should know where the
-- attack is staging, because a siege the defender cannot see is not a siege, it is an ambush."
CREATE TABLE siege_camps (
    id           INT      NOT NULL PRIMARY KEY AUTO_INCREMENT,
    war_id       INT      NOT NULL,
    city_id      INT      NOT NULL,
    world        VARCHAR(64) NOT NULL,
    x            INT      NOT NULL,
    y            INT      NOT NULL,
    z            INT      NOT NULL,
    health       DOUBLE   NOT NULL,
    placed_at    BIGINT   NOT NULL,
    destroyed_at BIGINT,             -- null while it stands
    rebuilt      BOOLEAN  NOT NULL DEFAULT FALSE   -- "rebuilt once per war at half cost"
);

-- SPEC 29.5's "one camp per attacking city". Enforced physically as well as in the service,
-- because two members placing in the same tick is a race no service check in front of it can win.
CREATE UNIQUE INDEX idx_siege_camps_war_city ON siege_camps (war_id, city_id);
CREATE INDEX idx_siege_camps_war ON siege_camps (war_id);
