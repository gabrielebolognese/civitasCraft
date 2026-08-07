-- Admin tooling, MySQL and MariaDB.
-- See the SQLite copy for why protection is its own table rather than a column on claims, and
-- why a report's context is attached when it is read rather than copied in when it is written.

CREATE TABLE admin_protected_chunks (
    world        VARCHAR(64) NOT NULL,
    chunk_x      INT         NOT NULL,
    chunk_z      INT         NOT NULL,
    protected_by CHAR(36)    NULL,
    protected_at BIGINT      NOT NULL,
    reason       TEXT        NULL,
    PRIMARY KEY (world, chunk_x, chunk_z)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE reports (
    id            INT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    reporter_uuid CHAR(36)    NOT NULL,
    target_uuid   CHAR(36)    NOT NULL,
    reason        TEXT        NOT NULL,
    created_at    BIGINT      NOT NULL,
    state         VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    handled_by    CHAR(36)    NULL,
    handled_at    BIGINT      NULL,
    resolution    TEXT        NULL,
    INDEX idx_reports_state (state, created_at),
    INDEX idx_reports_target (target_uuid, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
