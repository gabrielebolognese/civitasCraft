-- Player chest shops, SQLite.
--
-- SPEC 4.5 defines chest shops and SPEC 10 gives them a per-player limit, but SPEC 3 lists
-- no table for them; M1 recorded that and deferred it to the milestone that implements the
-- feature, which is this one.
--
-- The sign and the chest are stored separately because either can be found first: a click
-- arrives on the sign, a break event arrives on either, and a chest may be under a sign that
-- was destroyed by an explosion.
--
-- Money columns are INTEGER minor units here for the same reason as V1; see that file.

CREATE TABLE player_shops (
    id           INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    owner_uuid   CHAR(36)     NOT NULL,
    world        VARCHAR(64)  NOT NULL,
    sign_x       INTEGER      NOT NULL,
    sign_y       INTEGER      NOT NULL,
    sign_z       INTEGER      NOT NULL,
    chest_x      INTEGER      NOT NULL,
    chest_y      INTEGER      NOT NULL,
    chest_z      INTEGER      NOT NULL,
    material     VARCHAR(64)  NOT NULL,
    quantity     INTEGER      NOT NULL,
    buy_price    INTEGER      NULL,      -- minor units; what a customer pays, null if none
    sell_price   INTEGER      NULL,      -- minor units; what a customer receives, null if none
    created_at   BIGINT       NOT NULL
);

-- One shop per sign: the physical guarantee that two rows cannot claim the same sign.
CREATE UNIQUE INDEX idx_player_shops_sign ON player_shops (world, sign_x, sign_y, sign_z);
CREATE INDEX idx_player_shops_chest ON player_shops (world, chest_x, chest_y, chest_z);
CREATE INDEX idx_player_shops_owner ON player_shops (owner_uuid);
