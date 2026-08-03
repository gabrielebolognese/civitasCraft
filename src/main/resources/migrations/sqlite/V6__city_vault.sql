-- The shared city vault, SQLite.
--
-- SPEC 5.7's Vault upgrade grants "+1 shared vault page (27 slots)" per level, SPEC 9.2 gives
-- it /city vault, and SPEC 11.7 makes it the one store war looting cannot reach. SPEC 3 lists
-- no table for it.
--
-- A page is stored as one blob rather than as 27 rows. The whole page is read when it opens
-- and written when it closes, so per-slot rows would buy nothing and cost 27 statements a
-- click. The blob is Bukkit's own ItemStack serialisation, which survives item formats
-- changing under it in a way a hand-rolled column layout would not.

CREATE TABLE city_vault (
    city_id     INTEGER  NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    page        INTEGER  NOT NULL,
    contents    BLOB     NULL,
    updated_at  BIGINT   NOT NULL,
    PRIMARY KEY (city_id, page)
);
