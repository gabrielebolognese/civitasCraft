-- Scheduled server-wide events, MySQL and MariaDB.
-- See the SQLite copy for why an event has to survive a restart and why finished rows stay.

CREATE TABLE server_events (
    id          INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_key   VARCHAR(32)  NOT NULL,
    starts_at   BIGINT       NOT NULL,
    ends_at     BIGINT       NOT NULL,
    ended_at    BIGINT       NULL,
    announced   BOOLEAN      NOT NULL DEFAULT 0,
    INDEX idx_server_events_open (ended_at, starts_at),
    INDEX idx_server_events_key (event_key, starts_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
