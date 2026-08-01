-- Hourly circulation snapshots, MySQL and MariaDB.
--
-- SPEC 4.8 requires the plugin to track total circulating currency, log it hourly, and warn
-- when circulation grows more than 15% week over week. A week-over-week comparison needs
-- history, and SPEC 3 defines no table to keep it in.

CREATE TABLE economy_snapshots (
    id              BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    timestamp       BIGINT         NOT NULL,
    player_total    DECIMAL(20, 2) NOT NULL,
    treasury_total  DECIMAL(20, 2) NOT NULL,
    INDEX idx_economy_snapshots_time (timestamp)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
