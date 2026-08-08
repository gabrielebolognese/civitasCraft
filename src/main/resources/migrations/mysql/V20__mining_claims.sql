-- Personal mining claims, SPEC 32.6, MySQL and MariaDB.
--
-- The only land ownership available to a player with no city, deliberately: SPEC 32.6 exists
-- because SPEC 32.5 blocks city claims in the resource worlds, which would otherwise leave
-- every mine base griefable.

CREATE TABLE mining_claims (
    id                BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid              CHAR(36)       NOT NULL,
    world             VARCHAR(64)    NOT NULL,
    chunk_x           INT            NOT NULL,
    chunk_z           INT            NOT NULL,
    claimed_at        BIGINT         NOT NULL,
    cost_paid         DECIMAL(20, 2) NOT NULL,
    delinquent_since  BIGINT,
    UNIQUE KEY idx_mining_claims_chunk (world, chunk_x, chunk_z),
    INDEX idx_mining_claims_owner (uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Keyed by owner rather than claim, because /mine trust takes no claim argument.
CREATE TABLE mining_claim_trust (
    owner_uuid    CHAR(36) NOT NULL,
    trusted_uuid  CHAR(36) NOT NULL,
    granted_at    BIGINT   NOT NULL,
    PRIMARY KEY (owner_uuid, trusted_uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
