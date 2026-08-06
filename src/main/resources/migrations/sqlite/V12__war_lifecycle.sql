-- War gameplay, SQLite.
--
-- Two tables SPEC 3 does not define but SPEC 4.7 and SPEC 11.8.3 both need.

-- SPEC 4.7. A bounty is escrowed the moment it is placed, so the money must be recorded
-- somewhere that survives a restart or it would be lost with no way to refund it.
--
-- SPEC 4.7 makes a bounty claimable only during an active war, deliberately, "so bounties
-- cannot be used to fund random murder outside of the sanctioned combat window".
CREATE TABLE bounties (
    id           INTEGER   NOT NULL PRIMARY KEY AUTOINCREMENT,
    placer_uuid  CHAR(36)  NOT NULL,
    target_uuid  CHAR(36)  NOT NULL,
    amount       INTEGER   NOT NULL,             -- minor units
    placed_at    BIGINT    NOT NULL,
    expires_at   BIGINT    NOT NULL,
    -- OPEN, CLAIMED or REFUNDED. Rows are kept in every case: SPEC 1.5 makes money movement
    -- auditable, and a deleted bounty is money the ledger says moved to nowhere.
    state        VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    claimed_by   CHAR(36)  NULL,
    claimed_at   BIGINT    NULL
);
CREATE INDEX idx_bounties_target ON bounties (target_uuid, state);
CREATE INDEX idx_bounties_expiry ON bounties (state, expires_at);

-- SPEC 11.8.3: "Villagers, animals in the war zone: Snapshotted at war start (type, position,
-- NBT, name, profession, trades) and respawned if killed."
--
-- Taken at war start rather than on death, because a mob killed in a war is gone before
-- anything can ask it what it was. The payload is the same YAML form the tile codec uses, for
-- the same reason: ItemStack is the one thing Bukkit guarantees to serialize across versions.
CREATE TABLE war_entity_snapshots (
    id           INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    war_id       INTEGER      NOT NULL REFERENCES wars (id) ON DELETE CASCADE,
    entity_uuid  CHAR(36)     NOT NULL,
    entity_type  VARCHAR(48)  NOT NULL,
    world        VARCHAR(64)  NOT NULL,
    x            DOUBLE       NOT NULL,
    y            DOUBLE       NOT NULL,
    z            DOUBLE       NOT NULL,
    payload      BLOB         NULL,
    -- Set when the entity dies during the war, so the rollback knows which ones to bring back
    -- rather than duplicating the ones that survived.
    died_at      BIGINT       NULL,
    snapshot_at  BIGINT       NOT NULL
);
CREATE UNIQUE INDEX uq_war_entity_snapshots ON war_entity_snapshots (war_id, entity_uuid);
CREATE INDEX idx_war_entity_snapshots_dead ON war_entity_snapshots (war_id, died_at);
