-- Notification preferences, SPEC 23.6, MySQL and MariaDB.
--
-- One row per player per category they have CHANGED, not per category, so the table stays
-- proportional to the players who care rather than to players times eighteen.
--
-- The four categories SPEC 23.6 locks on cannot appear here: the service refuses to write
-- them, which is why the lock lives in code rather than as a default row.

CREATE TABLE player_toggles (
    uuid      CHAR(36)    NOT NULL,
    category  VARCHAR(32) NOT NULL,
    enabled   BOOLEAN     NOT NULL,
    PRIMARY KEY (uuid, category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
