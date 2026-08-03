-- The shared city vault, MySQL and MariaDB.
--
-- SPEC 5.7's Vault upgrade grants "+1 shared vault page (27 slots)" per level, SPEC 9.2 gives
-- it /city vault, and SPEC 11.7 makes it the one store war looting cannot reach. SPEC 3 lists
-- no table for it.
--
-- A page is stored as one blob rather than as 27 rows; see the SQLite copy for why.

CREATE TABLE city_vault (
    city_id     INT        NOT NULL,
    page        INT        NOT NULL,
    contents    MEDIUMBLOB NULL,
    updated_at  BIGINT     NOT NULL,
    PRIMARY KEY (city_id, page),
    CONSTRAINT fk_city_vault_city FOREIGN KEY (city_id) REFERENCES cities (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
