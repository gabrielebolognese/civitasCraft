-- Personal mining claims, SPEC 32.6, SQLite.
--
-- The only land ownership available to a player with no city, and SPEC 32.6 says that is
-- deliberate: "This is the only form of land ownership available to a player with no city, and
-- it is deliberately available to them."
--
-- SPEC 32.5 blocks city claims in the resource worlds entirely, which leaves every mine base
-- griefable and means nobody builds there. One chunk each is the answer: enough for a mine
-- entrance, storage and a base, and not enough to be territory.
--
-- Money columns are INTEGER minor units here for the same reason as V1; see that file.

CREATE TABLE mining_claims (
    id                INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    uuid              CHAR(36) NOT NULL,
    world             VARCHAR(64) NOT NULL,
    chunk_x           INT      NOT NULL,
    chunk_z           INT      NOT NULL,
    claimed_at        BIGINT   NOT NULL,
    cost_paid         INTEGER  NOT NULL,   -- minor units
    delinquent_since  BIGINT              -- null while upkeep is current
);

-- The same physical guarantee SPEC 3.4 gives city claims: two players can never own one chunk.
CREATE UNIQUE INDEX idx_mining_claims_chunk ON mining_claims (world, chunk_x, chunk_z);
CREATE INDEX idx_mining_claims_owner ON mining_claims (uuid);

-- SPEC 32.6's "/mine trust <player>, max 4". Keyed by the OWNER rather than by the claim,
-- because the command takes no claim argument: trusting somebody trusts them on everything
-- you own. Recorded in OPEN_QUESTIONS.md.
CREATE TABLE mining_claim_trust (
    owner_uuid    CHAR(36) NOT NULL,
    trusted_uuid  CHAR(36) NOT NULL,
    granted_at    BIGINT   NOT NULL,
    PRIMARY KEY (owner_uuid, trusted_uuid)
);
