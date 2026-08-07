-- SPEC 9.4.2's upkeep multiplier, MySQL and MariaDB.
-- See the SQLite copy for why this is a table rather than a column on cities.

CREATE TABLE city_upkeep_multipliers (
    city_id    INT           NOT NULL PRIMARY KEY,
    multiplier DOUBLE        NOT NULL,
    set_by     CHAR(36)      NULL,
    set_at     BIGINT        NOT NULL,
    expires_at BIGINT        NULL,
    reason     TEXT          NULL,
    CONSTRAINT fk_city_upkeep_city FOREIGN KEY (city_id) REFERENCES cities (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
