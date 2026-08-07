-- SPEC 17.1 case 1's deferred notice, MySQL and MariaDB.
-- See the SQLite copy for why an absent mayor needs their notice stored rather than sent.

CREATE TABLE player_notices (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid         CHAR(36)    NOT NULL,
    message_key  VARCHAR(64) NOT NULL,
    placeholders TEXT        NULL,
    created_at   BIGINT      NOT NULL,
    INDEX idx_player_notices_uuid (uuid, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
