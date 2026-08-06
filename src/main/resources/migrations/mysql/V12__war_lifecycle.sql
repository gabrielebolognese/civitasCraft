-- War gameplay, MySQL and MariaDB.
-- See the SQLite copy for why a bounty row is kept after it is paid and why entity snapshots
-- are taken at war start rather than on death.

CREATE TABLE bounties (
    id           INT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    placer_uuid  CHAR(36)    NOT NULL,
    target_uuid  CHAR(36)    NOT NULL,
    amount       DECIMAL(20, 2) NOT NULL,
    placed_at    BIGINT      NOT NULL,
    expires_at   BIGINT      NOT NULL,
    state        VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    claimed_by   CHAR(36)    NULL,
    claimed_at   BIGINT      NULL,
    INDEX idx_bounties_target (target_uuid, state),
    INDEX idx_bounties_expiry (state, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE war_entity_snapshots (
    id           INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    war_id       INT          NOT NULL,
    entity_uuid  CHAR(36)     NOT NULL,
    entity_type  VARCHAR(48)  NOT NULL,
    world        VARCHAR(64)  NOT NULL,
    x            DOUBLE       NOT NULL,
    y            DOUBLE       NOT NULL,
    z            DOUBLE       NOT NULL,
    payload      MEDIUMBLOB   NULL,
    died_at      BIGINT       NULL,
    snapshot_at  BIGINT       NOT NULL,
    UNIQUE KEY uq_war_entity_snapshots (war_id, entity_uuid),
    INDEX idx_war_entity_snapshots_dead (war_id, died_at),
    CONSTRAINT fk_war_entity_snapshots_war FOREIGN KEY (war_id) REFERENCES wars (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
