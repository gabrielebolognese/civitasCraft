-- Contest voting and its anti-abuse data, MySQL and MariaDB.
-- See the SQLite copy for why each column exists, and in particular for why the login table
-- holds a salted hash and never an address.

ALTER TABLE contest_votes ADD COLUMN creativity      INT    NOT NULL DEFAULT 0;
ALTER TABLE contest_votes ADD COLUMN technical_skill INT    NOT NULL DEFAULT 0;
ALTER TABLE contest_votes ADD COLUMN theme_fit       INT    NOT NULL DEFAULT 0;
ALTER TABLE contest_votes ADD COLUMN weight          DOUBLE NOT NULL DEFAULT 1.0;

ALTER TABLE contest_entries ADD COLUMN disqualified        BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE contest_entries ADD COLUMN disqualified_reason TEXT    NULL;
ALTER TABLE contest_entries ADD COLUMN placement           INT     NULL;

CREATE TABLE player_logins (
    uuid        CHAR(36)    NOT NULL PRIMARY KEY,
    login_hash  VARCHAR(64) NOT NULL,
    updated_at  BIGINT      NOT NULL,
    INDEX idx_player_logins_hash (login_hash),
    CONSTRAINT fk_player_logins_player FOREIGN KEY (uuid) REFERENCES players (uuid)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
