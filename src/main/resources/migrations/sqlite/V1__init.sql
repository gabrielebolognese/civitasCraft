-- CivitasCraft initial schema, SQLite.
-- Mirrors SPEC.md Section 3. Keep this file and the MySQL V1 in lock step.
--
-- MONEY COLUMNS: SPEC 3 specifies DECIMAL(20,2). SQLite has no decimal type; a
-- column declared DECIMAL takes NUMERIC affinity and stores non-integral values as
-- an 8-byte float, which silently loses cents. Every monetary column below is
-- therefore INTEGER holding MINOR UNITS (hundredths). SqlDialect converts to and
-- from BigDecimal, so callers never see the difference, and SUM and ORDER BY still
-- work in SQL. On MySQL the same columns are real DECIMAL(20,2).

-- The schema_version bookkeeping table is created and owned by MigrationRunner, not
-- by any migration, so that a migration can never depend on its own ledger existing.

-- SPEC 3.1
CREATE TABLE players (
    uuid                CHAR(36)     NOT NULL PRIMARY KEY,
    last_known_name     VARCHAR(16)  NOT NULL,
    balance             INTEGER      NOT NULL DEFAULT 0,   -- minor units
    city_id             INTEGER      NULL,
    rank_id             INTEGER      NULL,
    first_join          BIGINT       NOT NULL,
    last_seen           BIGINT       NOT NULL,
    total_playtime_ms   BIGINT       NOT NULL DEFAULT 0,
    active_playtime_ms  BIGINT       NOT NULL DEFAULT 0,
    daily_streak        INTEGER      NOT NULL DEFAULT 0,
    last_daily_claim    BIGINT       NOT NULL DEFAULT 0,
    newcomer_until      BIGINT       NOT NULL DEFAULT 0,
    frozen              BOOLEAN      NOT NULL DEFAULT 0
);
CREATE INDEX idx_players_city ON players (city_id);
CREATE INDEX idx_players_name ON players (last_known_name);

-- SPEC 3.2. name is COLLATE NOCASE so the unique index is case-insensitive,
-- which is what SPEC 5.1 precondition 5 requires.
CREATE TABLE cities (
    id                    INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    name                  VARCHAR(24)  NOT NULL COLLATE NOCASE,
    display_name          VARCHAR(48)  NOT NULL,
    tag                   VARCHAR(5)   NULL COLLATE NOCASE,
    mayor_uuid            CHAR(36)     NOT NULL,
    founded_at            BIGINT       NOT NULL,
    treasury              INTEGER      NOT NULL DEFAULT 0,   -- minor units
    core_world            VARCHAR(64)  NOT NULL,
    core_chunk_x          INTEGER      NOT NULL,
    core_chunk_z          INTEGER      NOT NULL,
    spawn_x               DOUBLE       NOT NULL,
    spawn_y               DOUBLE       NOT NULL,
    spawn_z               DOUBLE       NOT NULL,
    spawn_yaw             FLOAT        NOT NULL DEFAULT 0,
    spawn_pitch           FLOAT        NOT NULL DEFAULT 0,
    open_join             BOOLEAN      NOT NULL DEFAULT 0,
    motd                  VARCHAR(128) NOT NULL DEFAULT '',
    upkeep_due            BIGINT       NOT NULL DEFAULT 0,
    delinquent_since      BIGINT       NULL,
    war_protection_until  BIGINT       NOT NULL DEFAULT 0,
    frozen                BOOLEAN      NOT NULL DEFAULT 0,
    deleted_at            BIGINT       NULL
);
CREATE UNIQUE INDEX uq_cities_name ON cities (name);
CREATE UNIQUE INDEX uq_cities_tag ON cities (tag);

-- SPEC 3.3
CREATE TABLE city_ranks (
    id           INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    city_id      INTEGER      NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    name         VARCHAR(16)  NOT NULL,
    weight       INTEGER      NOT NULL,
    permissions  BIGINT       NOT NULL DEFAULT 0,
    is_default   BOOLEAN      NOT NULL DEFAULT 0
);
CREATE INDEX idx_city_ranks_city ON city_ranks (city_id);
CREATE UNIQUE INDEX uq_city_ranks_city_name ON city_ranks (city_id, name);

-- SPEC 3.5. Declared before claims because claims.outpost_id references it.
CREATE TABLE outposts (
    id          INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    city_id     INTEGER      NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    name        VARCHAR(24)  NOT NULL,
    tp_x        DOUBLE       NOT NULL,
    tp_y        DOUBLE       NOT NULL,
    tp_z        DOUBLE       NOT NULL,
    tp_yaw      FLOAT        NOT NULL DEFAULT 0,
    tp_pitch    FLOAT        NOT NULL DEFAULT 0,
    created_at  BIGINT       NOT NULL
);
CREATE INDEX idx_outposts_city ON outposts (city_id);
CREATE UNIQUE INDEX uq_outposts_city_name ON outposts (city_id, name);

-- SPEC 3.4. The unique index is the physical guarantee that two cities can never
-- own the same chunk (SPEC 17.2 case 15).
CREATE TABLE claims (
    id          INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    city_id     INTEGER      NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    world       VARCHAR(64)  NOT NULL,
    chunk_x     INTEGER      NOT NULL,
    chunk_z     INTEGER      NOT NULL,
    claimed_at  BIGINT       NOT NULL,
    claimed_by  CHAR(36)     NOT NULL,
    cost_paid   INTEGER      NOT NULL DEFAULT 0,   -- minor units
    type        VARCHAR(8)   NOT NULL,             -- CORE, NORMAL, OUTPOST
    outpost_id  INTEGER      NULL REFERENCES outposts (id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX uq_claims_chunk ON claims (world, chunk_x, chunk_z);
CREATE INDEX idx_claims_city ON claims (city_id);
CREATE INDEX idx_claims_outpost ON claims (outpost_id);

-- SPEC 3.6. Append-only: never UPDATE, never DELETE.
CREATE TABLE ledger (
    id             INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    timestamp      BIGINT       NOT NULL,
    type           VARCHAR(32)  NOT NULL,
    actor_uuid     CHAR(36)     NULL,
    target_uuid    CHAR(36)     NULL,
    city_id        INTEGER      NULL,
    amount         INTEGER      NOT NULL,          -- minor units, signed
    balance_after  INTEGER      NOT NULL,          -- minor units
    metadata       TEXT         NULL
);
CREATE INDEX idx_ledger_actor_time ON ledger (actor_uuid, timestamp);
CREATE INDEX idx_ledger_target_time ON ledger (target_uuid, timestamp);
CREATE INDEX idx_ledger_city_time ON ledger (city_id, timestamp);
CREATE INDEX idx_ledger_type_time ON ledger (type, timestamp);

-- SPEC 3.7. state is VARCHAR rather than an enum because SPEC 11.2 and 11.8.5 name
-- states (DECLARED, ROLLBACK_FAILED) that SPEC 3.7's list omits; the war service
-- owns the valid set so M19 can settle it without a schema change.
-- rollback_checkpoint_sequence is required by SPEC 11.8.5, which says the last
-- restored sequence is checkpointed to this table.
CREATE TABLE wars (
    id                            INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    attacker_city_id              INTEGER      NOT NULL,
    defender_city_id              INTEGER      NOT NULL,
    declared_at                   BIGINT       NOT NULL,
    prep_ends_at                  BIGINT       NOT NULL,
    war_ends_at                   BIGINT       NOT NULL,
    state                         VARCHAR(16)  NOT NULL,
    attacker_score                INTEGER      NOT NULL DEFAULT 0,
    defender_score                INTEGER      NOT NULL DEFAULT 0,
    winner_city_id                INTEGER      NULL,
    wager                         INTEGER      NOT NULL DEFAULT 0,   -- minor units
    rollback_completed_at         BIGINT       NULL,
    rollback_checkpoint_sequence  BIGINT       NULL
);
CREATE INDEX idx_wars_state ON wars (state);
CREATE INDEX idx_wars_attacker ON wars (attacker_city_id);
CREATE INDEX idx_wars_defender ON wars (defender_city_id);

-- SPEC 3.8. Highest write volume in the plugin. The composite index exists because
-- rollback replays in reverse sequence order.
CREATE TABLE war_block_log (
    id              INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    war_id          INTEGER      NOT NULL,
    sequence        BIGINT       NOT NULL,
    world           VARCHAR(64)  NOT NULL,
    x               INTEGER      NOT NULL,
    y               INTEGER      NOT NULL,
    z               INTEGER      NOT NULL,
    old_block_data  TEXT         NOT NULL,
    new_block_data  TEXT         NULL,
    old_nbt         BLOB         NULL,
    actor_uuid      CHAR(36)     NULL,
    timestamp       BIGINT       NOT NULL
);
CREATE INDEX idx_war_block_log_replay ON war_block_log (war_id, sequence DESC);

-- SPEC 11.7. Not listed in SPEC 3 but named there with its columns. world is added
-- because SPEC 20 decision 4 allows a city to hold territory in several worlds, so
-- x/y/z alone would not identify a container.
CREATE TABLE war_container_log (
    id          INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    war_id      INTEGER      NOT NULL,
    world       VARCHAR(64)  NOT NULL,
    x           INTEGER      NOT NULL,
    y           INTEGER      NOT NULL,
    z           INTEGER      NOT NULL,
    actor_uuid  CHAR(36)     NOT NULL,
    item        VARCHAR(64)  NOT NULL,
    quantity    INTEGER      NOT NULL,
    timestamp   BIGINT       NOT NULL
);
CREATE INDEX idx_war_container_log_war ON war_container_log (war_id, timestamp);

-- SPEC 3.9
CREATE TABLE city_members (
    uuid               CHAR(36)  NOT NULL PRIMARY KEY,
    city_id            INTEGER   NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    rank_id            INTEGER   NOT NULL REFERENCES city_ranks (id),
    joined_at          BIGINT    NOT NULL,
    contributed_total  INTEGER   NOT NULL DEFAULT 0   -- minor units
);
CREATE INDEX idx_city_members_city ON city_members (city_id);
CREATE INDEX idx_city_members_rank ON city_members (rank_id);

CREATE TABLE city_invites (
    city_id       INTEGER   NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    invitee_uuid  CHAR(36)  NOT NULL,
    inviter_uuid  CHAR(36)  NOT NULL,
    expires_at    BIGINT    NOT NULL,
    PRIMARY KEY (city_id, invitee_uuid)
);
CREATE INDEX idx_city_invites_invitee ON city_invites (invitee_uuid);
CREATE INDEX idx_city_invites_expiry ON city_invites (expires_at);

CREATE TABLE alliances (
    city_a_id  INTEGER      NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    city_b_id  INTEGER      NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    state      VARCHAR(16)  NOT NULL,
    formed_at  BIGINT       NOT NULL,
    PRIMARY KEY (city_a_id, city_b_id)
);
CREATE INDEX idx_alliances_b ON alliances (city_b_id);

CREATE TABLE truces (
    city_a_id   INTEGER  NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    city_b_id   INTEGER  NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    expires_at  BIGINT   NOT NULL,
    PRIMARY KEY (city_a_id, city_b_id)
);
CREATE INDEX idx_truces_b ON truces (city_b_id);
CREATE INDEX idx_truces_expiry ON truces (expires_at);

CREATE TABLE war_participants (
    war_id   INTEGER      NOT NULL REFERENCES wars (id) ON DELETE CASCADE,
    city_id  INTEGER      NOT NULL,
    side     VARCHAR(16)  NOT NULL,
    is_ally  BOOLEAN      NOT NULL DEFAULT 0,
    PRIMARY KEY (war_id, city_id)
);
CREATE INDEX idx_war_participants_city ON war_participants (city_id);

CREATE TABLE war_kills (
    id            INTEGER       NOT NULL PRIMARY KEY AUTOINCREMENT,
    war_id        INTEGER       NOT NULL REFERENCES wars (id) ON DELETE CASCADE,
    killer_uuid   CHAR(36)      NOT NULL,
    victim_uuid   CHAR(36)      NOT NULL,
    timestamp     BIGINT        NOT NULL,
    location      VARCHAR(128)  NOT NULL
);
CREATE INDEX idx_war_kills_war ON war_kills (war_id, timestamp);

CREATE TABLE market_stock (
    material       VARCHAR(64)  NOT NULL PRIMARY KEY,
    current_stock  INTEGER      NOT NULL DEFAULT 0,   -- may go negative, SPEC 17.3 case 28
    target_stock   INTEGER      NOT NULL,
    base_price     INTEGER      NOT NULL              -- minor units
);

CREATE TABLE player_quests (
    id            INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    uuid          CHAR(36)     NOT NULL,
    quest_id      VARCHAR(64)  NOT NULL,
    progress      INTEGER      NOT NULL DEFAULT 0,
    assigned_at   BIGINT       NOT NULL,
    completed_at  BIGINT       NULL
);
CREATE INDEX idx_player_quests_player ON player_quests (uuid, assigned_at);

CREATE TABLE contests (
    id         INTEGER       NOT NULL PRIMARY KEY AUTOINCREMENT,
    theme      VARCHAR(128)  NOT NULL,
    starts_at  BIGINT        NOT NULL,
    ends_at    BIGINT        NOT NULL,
    state      VARCHAR(16)   NOT NULL
);
CREATE INDEX idx_contests_state ON contests (state);

CREATE TABLE contest_entries (
    id            INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    contest_id    INTEGER  NOT NULL REFERENCES contests (id) ON DELETE CASCADE,
    city_id       INTEGER  NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    plot_region   TEXT     NOT NULL,
    submitted_at  BIGINT   NULL,
    score         DOUBLE   NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_contest_entries_city ON contest_entries (contest_id, city_id);

CREATE TABLE contest_votes (
    id          INTEGER   NOT NULL PRIMARY KEY AUTOINCREMENT,
    contest_id  INTEGER   NOT NULL REFERENCES contests (id) ON DELETE CASCADE,
    voter_uuid  CHAR(36)  NOT NULL,
    entry_id    INTEGER   NOT NULL REFERENCES contest_entries (id) ON DELETE CASCADE,
    score       DOUBLE    NOT NULL
);
CREATE UNIQUE INDEX uq_contest_votes_voter ON contest_votes (contest_id, voter_uuid, entry_id);

CREATE TABLE city_upgrades (
    city_id      INTEGER      NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    upgrade_key  VARCHAR(32)  NOT NULL,
    level        INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (city_id, upgrade_key)
);

-- world is added for the same reason as war_container_log: spawn coordinates alone
-- do not identify a location on a multi-world server.
CREATE TABLE defense_units (
    id       INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    city_id  INTEGER      NOT NULL REFERENCES cities (id) ON DELETE CASCADE,
    type     VARCHAR(32)  NOT NULL,
    world    VARCHAR(64)  NOT NULL,
    spawn_x  DOUBLE       NOT NULL,
    spawn_y  DOUBLE       NOT NULL,
    spawn_z  DOUBLE       NOT NULL,
    upkeep   INTEGER      NOT NULL DEFAULT 0,   -- minor units
    active   BOOLEAN      NOT NULL DEFAULT 1
);
CREATE INDEX idx_defense_units_city ON defense_units (city_id, active);

-- SPEC 3.9 and 17.6 case 80: admin actions only, separate from the ledger, and not
-- clearable in game.
CREATE TABLE audit_log (
    id          INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    timestamp   BIGINT       NOT NULL,
    actor_uuid  CHAR(36)     NULL,
    action      VARCHAR(64)  NOT NULL,
    target      VARCHAR(64)  NULL,
    reason      TEXT         NULL,
    metadata    TEXT         NULL
);
CREATE INDEX idx_audit_log_time ON audit_log (timestamp);
CREATE INDEX idx_audit_log_actor ON audit_log (actor_uuid, timestamp);
