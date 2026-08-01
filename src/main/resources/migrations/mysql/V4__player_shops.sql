-- Player chest shops, MySQL and MariaDB.
--
-- SPEC 4.5 defines chest shops and SPEC 10 gives them a per-player limit, but SPEC 3 lists
-- no table for them; M1 recorded that and deferred it to the milestone that implements the
-- feature, which is this one.
--
-- The sign and the chest are stored separately because either can be found first: a click
-- arrives on the sign, a break event arrives on either, and a chest may be under a sign that
-- was destroyed by an explosion.

CREATE TABLE player_shops (
    id           BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_uuid   CHAR(36)       NOT NULL,
    world        VARCHAR(64)    NOT NULL,
    sign_x       INT            NOT NULL,
    sign_y       INT            NOT NULL,
    sign_z       INT            NOT NULL,
    chest_x      INT            NOT NULL,
    chest_y      INT            NOT NULL,
    chest_z      INT            NOT NULL,
    material     VARCHAR(64)    NOT NULL,
    quantity     INT            NOT NULL,
    buy_price    DECIMAL(20, 2) NULL,
    sell_price   DECIMAL(20, 2) NULL,
    created_at   BIGINT         NOT NULL,
    UNIQUE KEY idx_player_shops_sign (world, sign_x, sign_y, sign_z),
    INDEX idx_player_shops_chest (world, chest_x, chest_y, chest_z),
    INDEX idx_player_shops_owner (owner_uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
