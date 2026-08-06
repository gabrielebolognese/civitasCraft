-- The rollback engine's own bookkeeping, MySQL and MariaDB.
-- See the SQLite copy for why both tables have to outlive the process that writes them.

CREATE TABLE war_chunk_hashes (
    war_id       INT          NOT NULL,
    world        VARCHAR(64)  NOT NULL,
    chunk_x      INT          NOT NULL,
    chunk_z      INT          NOT NULL,
    hash_before  BIGINT       NOT NULL,
    hash_after   BIGINT       NULL,
    PRIMARY KEY (war_id, world, chunk_x, chunk_z),
    CONSTRAINT fk_war_chunk_hashes_war FOREIGN KEY (war_id) REFERENCES wars (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE war_rollback_issues (
    id           INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    war_id       INT          NOT NULL,
    kind         VARCHAR(32)  NOT NULL,
    world        VARCHAR(64)  NULL,
    x            INT          NULL,
    y            INT          NULL,
    z            INT          NULL,
    detail       TEXT         NULL,
    detected_at  BIGINT       NOT NULL,
    INDEX idx_war_rollback_issues_war (war_id, kind),
    CONSTRAINT fk_war_rollback_issues_war FOREIGN KEY (war_id) REFERENCES wars (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
