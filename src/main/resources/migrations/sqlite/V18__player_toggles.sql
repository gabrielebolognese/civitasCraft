-- Notification preferences, SPEC 23.6, SQLite.
--
-- SPEC 22.1 lists /toggle as a High severity omission from Part I: "Section 23 adds many
-- messages. Without a toggle, chat becomes unusable."
--
-- One row per player per category they have CHANGED, not per category. A player who has
-- never touched their preferences has no rows at all and reads every default, so the table
-- stays proportional to the players who care rather than to players times eighteen.
--
-- The four categories SPEC 23.6 locks on are absent from here by construction: the service
-- refuses to write them, so a row saying war messages are off cannot exist. That is why the
-- lock lives in code rather than in a default written into this table.

CREATE TABLE player_toggles (
    uuid      CHAR(36) NOT NULL,
    category  VARCHAR(32) NOT NULL,
    enabled   BOOLEAN NOT NULL,
    PRIMARY KEY (uuid, category)
);
