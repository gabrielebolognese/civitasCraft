-- CivitasCraft initial schema, MySQL and MariaDB.
-- Mirrors SPEC.md Section 3. Keep this file and the SQLite V1 in lock step.
--
-- MONEY COLUMNS: real DECIMAL(20,2), exactly as SPEC 3 specifies. The SQLite copy
-- of this migration stores the same columns as INTEGER minor units because SQLite
-- has no decimal type; SqlDialect hides that difference from callers.

-- The schema_version bookkeeping table is created and owned by MigrationRunner, not
-- by any migration, so that a migration can never depend on its own ledger existing.

-- SPEC 3.1
CREATE TABLE players (
    uuid                CHAR(36)       NOT NULL PRIMARY KEY,
    last_known_name     VARCHAR(16)    NOT NULL,
    balance             DECIMAL(20, 2) NOT NULL DEFAULT 0,
    city_id             INT            NULL,
    rank_id             INT            NULL,
    first_join          BIGINT         NOT NULL,
    last_seen           BIGINT         NOT NULL,
    total_playtime_ms   BIGINT         NOT NULL DEFAULT 0,
    active_playtime_ms  BIGINT         NOT NULL DEFAULT 0,
    daily_streak        INT            NOT NULL DEFAULT 0,
    last_daily_claim    BIGINT         NOT NULL DEFAULT 0,
    newcomer_until      BIGINT         NOT NULL DEFAULT 0,
    frozen              BOOLEAN        NOT NULL DEFAULT 0,
    INDEX idx_players_city (city_id),
    INDEX idx_players_name (last_known_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- SPEC 3.2. name and tag use a case-insensitive collation so their unique indexes
-- are case-insensitive, which SPEC 5.1 precondition 5 requires.
CREATE TABLE cities (
    id                    INT            NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name                  VARCHAR(24)    NOT NULL COLLATE utf8mb4_general_ci,
    display_name          VARCHAR(48)    NOT NULL,
    tag                   VARCHAR(5)     NULL COLLATE utf8mb4_general_ci,
    mayor_uuid            CHAR(36)       NOT NULL,
    founded_at            BIGINT         NOT NULL,
    treasury              DECIMAL(20, 2) NOT NULL DEFAULT 0,
    core_world            VARCHAR(64)    NOT NULL,
    core_chunk_x          INT            NOT NULL,
    core_chunk_z          INT            NOT NULL,
    spawn_x               DOUBLE         NOT NULL,
    spawn_y               DOUBLE         NOT NULL,
    spawn_z               DOUBLE         NOT NULL,
    spawn_yaw             FLOAT          NOT NULL DEFAULT 0,
    spawn_pitch           FLOAT          NOT NULL DEFAULT 0,
    open_join             BOOLEAN        NOT NULL DEFAULT 0,
    motd                  VARCHAR(128)   NOT NULL DEFAULT '',
    upkeep_due            BIGINT         NOT NULL DEFAULT 0,
    delinquent_since      BIGINT         NULL,
    war_protection_until  BIGINT         NOT NULL DEFAULT 0,
    frozen                BOOLEAN        NOT NULL DEFAULT 0,
    deleted_at            BIGINT         NULL,
    UNIQUE KEY uq_cities_name (name),
    UNIQUE KEY uq_cities_tag (tag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- SPEC 3.3
CREATE TABLE city_ranks (
    id           INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    city_id      INT          NOT NULL,
    name         VARCHAR(16)  NOT NULL,
    weight       INT          NOT NULL,
    permissions  BIGINT       NOT NULL DEFAULT 0,
    is_default   BOOLEAN      NOT NULL DEFAULT 0,
    INDEX idx_city_ranks_city (city_id),
    UNIQUE KEY uq_city_ranks_city_name (city_id, name),
    CONSTRAINT fk_city_ranks_city FOREIGN KEY (city_id) REFERENCES cities (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- SPEC 3.5. Declared before claims because claims.outpost_id references it.
CREATE TABLE outposts (
    id          INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    city_id     INT          NOT NULL,
    name        VARCHAR(24)  NOT NULL,
    tp_x        DOUBLE       NOT NULL,
    tp_y        DOUBLE       NOT NULL,
    tp_z        DOUBLE       NOT NULL,
    tp_yaw      FLOAT        NOT NULL DEFAULT 0,
    tp_pitch    FLOAT        NOT NULL DEFAULT 0,
    created_at  BIGINT       NOT NULL,
    INDEX idx_outposts_city (city_id),
    UNIQUE KEY uq_outposts_city_name (city_id, name),
    CONSTRAINT fk_outposts_city FOREIGN KEY (city_id) REFERENCES cities (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- SPEC 3.4. The unique index is the physical guarantee that two cities can never
-- own the same chunk (SPEC 17.2 case 15).
CREATE TABLE claims (
    id          BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    city_id     INT            NOT NULL,
    world       VARCHAR(64)    NOT NULL,
    chunk_x     INT            NOT NULL,
    chunk_z     INT            NOT NULL,
    claimed_at  BIGINT         NOT NULL,
    claimed_by  CHAR(36)       NOT NULL,
    cost_paid   DECIMAL(20, 2) NOT NULL DEFAULT 0,
    type        VARCHAR(8)     NOT NULL,
    outpost_id  INT            NULL,
    UNIQUE KEY uq_claims_chunk (world, chunk_x, chunk_z),
    INDEX idx_claims_city (city_id),
    INDEX idx_claims_outpost (outpost_id),
    CONSTRAINT fk_claims_city FOREIGN KEY (city_id) REFERENCES cities (id) ON DELETE CASCADE,
    CONSTRAINT fk_claims_outpost FOREIGN KEY (outpost_id) REFERENCES outposts (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- SPEC 3.6. Append-only: never UPDATE, never DELETE.
CREATE TABLE ledger (
    id             BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    timestamp      BIGINT         NOT NULL,
    type           VARCHAR(32)    NOT NULL,
    actor_uuid     CHAR(36)       NULL,
    target_uuid    CHAR(36)       NULL,
    city_id        INT            NULL,
    amount         DECIMAL(20, 2) NOT NULL,
    balance_after  DECIMAL(20, 2) NOT NULL,
    metadata       TEXT           NULL,
    INDEX idx_ledger_actor_time (actor_uuid, timestamp),
    INDEX idx_ledger_target_time (target_uuid, timestamp),
    INDEX idx_ledger_city_time (city_id, timestamp),
    INDEX idx_ledger_type_time (type, timestamp)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- SPEC 3.7. state is VARCHAR rather than an enum because SPEC 11.2 and 11.8.5 name
-- states (DECLARED, ROLLBACK_FAILED) that SPEC 3.7's list omits; the war service
-- owns the valid set so M19 can settle it without a schema change.
-- rollback_checkpoint_sequence is required by SPEC 11.8.5.
CREATE TABLE wars (
    id                            INT            NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attacker_city_id              INT            NOT NULL,
    defender_city_id              INT            NOT NULL,
    declared_at                   BIGINT         NOT NULL,
    prep_ends_at                  BIGINT         NOT NULL,
    war_ends_at                   BIGINT         NOT NULL,
    state                         VARCHAR(16)    NOT NULL,
    attacker_score                INT            NOT NULL DEFAULT 0,
    defender_score                INT            NOT NULL DEFAULT 0,
    winner_city_id                INT            NULL,
    wager                         DECIMAL(20, 2) NOT NULL DEFAULT 0,
    rollback_completed_at         BIGINT         NULL,
    rollback_checkpoint_sequence  BIGINT         NULL,
    INDEX idx_wars_state (state),
    INDEX idx_wars_attacker (attacker_city_id),
    INDEX idx_wars_defender (defender_city_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- SPEC 3.8. Highest write volume in the plugin. The composite index exists because
-- rollback replays in reverse sequence order.
CREATE TABLE war_block_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    war_id          INT          NOT NULL,
    sequence        BIGINT       NOT NULL,
    world           VARCHAR(64)  NOT NULL,
    x               INT          NOT NULL,
    y               INT          NOT NULL,
    z               INT          NOT NULL,
    old_block_data  TEXT         NOT NULL,
    new_block_data  TEXT         NULL,
    old_nbt         LONGBLOB     NULL,
    actor_uuid      CHAR(36)     NULL,
    timestamp       BIGINT       NOT NULL,
    INDEX idx_war_block_log_replay (war_id, sequence DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- SPEC 11.7. Not listed in SPEC 3 but named there with its columns. world is added
-- because SPEC 20 decision 4 allows a city to hold territory in several worlds, so
-- x/y/z alone would not identify a container.
CREATE TABLE war_container_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    war_id      INT          NOT NULL,
    world       VARCHAR(64)  NOT NULL,
    x           INT          NOT NULL,
    y           INT          NOT NULL,
    z           INT          NOT NULL,
    actor_uuid  CHAR(36)     NOT NULL,
    item        VARCHAR(64)  NOT NULL,
    quantity    INT          NOT NULL,
    timestamp   BIGINT       NOT NULL,
    INDEX idx_war_container_log_war (war_id, timestamp)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- SPEC 3.9
CREATE TABLE city_members (
    uuid               CHAR(36)       NOT NULL PRIMARY KEY,
    city_id            INT            NOT NULL,
    rank_id            INT            NOT NULL,
    joined_at          BIGINT         NOT NULL,
    contributed_total  DECIMAL(20, 2) NOT NULL DEFAULT 0,
    INDEX idx_city_members_city (city_id),
    INDEX idx_city_members_rank (rank_id),
    CONSTRAINT fk_city_members_city FOREIGN KEY (city_id) REFERENCES cities (id) ON DELETE CASCADE,
    CONSTRAINT fk_city_members_rank FOREIGN KEY (rank_id) REFERENCES city_ranks (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE city_invites (
    city_id       INT       NOT NULL,
    invitee_uuid  CHAR(36)  NOT NULL,
    inviter_uuid  CHAR(36)  NOT NULL,
    expires_at    BIGINT    NOT NULL,
    PRIMARY KEY (city_id, invitee_uuid),
    INDEX idx_city_invites_invitee (invitee_uuid),
    INDEX idx_city_invites_expiry (expires_at),
    CONSTRAINT fk_city_invites_city FOREIGN KEY (city_id) REFERENCES cities (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE alliances (
    city_a_id  INT          NOT NULL,
    city_b_id  INT          NOT NULL,
    state      VARCHAR(16)  NOT NULL,
    formed_at  BIGINT       NOT NULL,
    PRIMARY KEY (city_a_id, city_b_id),
    INDEX idx_alliances_b (city_b_id),
    CONSTRAINT fk_alliances_a FOREIGN KEY (city_a_id) REFERENCES cities (id) ON DELETE CASCADE,
    CONSTRAINT fk_alliances_b FOREIGN KEY (city_b_id) REFERENCES cities (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE truces (
    city_a_id   INT     NOT NULL,
    city_b_id   INT     NOT NULL,
    expires_at  BIGINT  NOT NULL,
    PRIMARY KEY (city_a_id, city_b_id),
    INDEX idx_truces_b (city_b_id),
    INDEX idx_truces_expiry (expires_at),
    CONSTRAINT fk_truces_a FOREIGN KEY (city_a_id) REFERENCES cities (id) ON DELETE CASCADE,
    CONSTRAINT fk_truces_b FOREIGN KEY (city_b_id) REFERENCES cities (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE war_participants (
    war_id   INT          NOT NULL,
    city_id  INT          NOT NULL,
    side     VARCHAR(16)  NOT NULL,
    is_ally  BOOLEAN      NOT NULL DEFAULT 0,
    PRIMARY KEY (war_id, city_id),
    INDEX idx_war_participants_city (city_id),
    CONSTRAINT fk_war_participants_war FOREIGN KEY (war_id) REFERENCES wars (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE war_kills (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    war_id       INT           NOT NULL,
    killer_uuid  CHAR(36)      NOT NULL,
    victim_uuid  CHAR(36)      NOT NULL,
    timestamp    BIGINT        NOT NULL,
    location     VARCHAR(128)  NOT NULL,
    INDEX idx_war_kills_war (war_id, timestamp),
    CONSTRAINT fk_war_kills_war FOREIGN KEY (war_id) REFERENCES wars (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE market_stock (
    material       VARCHAR(64)    NOT NULL PRIMARY KEY,
    current_stock  INT            NOT NULL DEFAULT 0,
    target_stock   INT            NOT NULL,
    base_price     DECIMAL(20, 2) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE player_quests (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid          CHAR(36)     NOT NULL,
    quest_id      VARCHAR(64)  NOT NULL,
    progress      INT          NOT NULL DEFAULT 0,
    assigned_at   BIGINT       NOT NULL,
    completed_at  BIGINT       NULL,
    INDEX idx_player_quests_player (uuid, assigned_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE contests (
    id         INT           NOT NULL AUTO_INCREMENT PRIMARY KEY,
    theme      VARCHAR(128)  NOT NULL,
    starts_at  BIGINT        NOT NULL,
    ends_at    BIGINT        NOT NULL,
    state      VARCHAR(16)   NOT NULL,
    INDEX idx_contests_state (state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE contest_entries (
    id            INT     NOT NULL AUTO_INCREMENT PRIMARY KEY,
    contest_id    INT     NOT NULL,
    city_id       INT     NOT NULL,
    plot_region   TEXT    NOT NULL,
    submitted_at  BIGINT  NULL,
    score         DOUBLE  NOT NULL DEFAULT 0,
    UNIQUE KEY uq_contest_entries_city (contest_id, city_id),
    CONSTRAINT fk_contest_entries_contest FOREIGN KEY (contest_id) REFERENCES contests (id) ON DELETE CASCADE,
    CONSTRAINT fk_contest_entries_city FOREIGN KEY (city_id) REFERENCES cities (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE contest_votes (
    id          INT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    contest_id  INT       NOT NULL,
    voter_uuid  CHAR(36)  NOT NULL,
    entry_id    INT       NOT NULL,
    score       DOUBLE    NOT NULL,
    UNIQUE KEY uq_contest_votes_voter (contest_id, voter_uuid, entry_id),
    CONSTRAINT fk_contest_votes_contest FOREIGN KEY (contest_id) REFERENCES contests (id) ON DELETE CASCADE,
    CONSTRAINT fk_contest_votes_entry FOREIGN KEY (entry_id) REFERENCES contest_entries (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE city_upgrades (
    city_id      INT          NOT NULL,
    upgrade_key  VARCHAR(32)  NOT NULL,
    level        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (city_id, upgrade_key),
    CONSTRAINT fk_city_upgrades_city FOREIGN KEY (city_id) REFERENCES cities (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- world is added for the same reason as war_container_log: spawn coordinates alone
-- do not identify a location on a multi-world server.
CREATE TABLE defense_units (
    id       INT            NOT NULL AUTO_INCREMENT PRIMARY KEY,
    city_id  INT            NOT NULL,
    type     VARCHAR(32)    NOT NULL,
    world    VARCHAR(64)    NOT NULL,
    spawn_x  DOUBLE         NOT NULL,
    spawn_y  DOUBLE         NOT NULL,
    spawn_z  DOUBLE         NOT NULL,
    upkeep   DECIMAL(20, 2) NOT NULL DEFAULT 0,
    active   BOOLEAN        NOT NULL DEFAULT 1,
    INDEX idx_defense_units_city (city_id, active),
    CONSTRAINT fk_defense_units_city FOREIGN KEY (city_id) REFERENCES cities (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- SPEC 3.9 and 17.6 case 80: admin actions only, separate from the ledger, and not
-- clearable in game.
CREATE TABLE audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    timestamp   BIGINT       NOT NULL,
    actor_uuid  CHAR(36)     NULL,
    action      VARCHAR(64)  NOT NULL,
    target      VARCHAR(64)  NULL,
    reason      TEXT         NULL,
    metadata    TEXT         NULL,
    INDEX idx_audit_log_time (timestamp),
    INDEX idx_audit_log_actor (actor_uuid, timestamp)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
