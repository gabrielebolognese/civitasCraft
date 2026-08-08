-- Public warps, SPEC 32.7, MySQL and MariaDB.
--
-- expires_at is null for a permanent warp; SPEC 40.1's contest visit warps are temporary and
-- write rows here rather than building a second warp system.
--
-- The name is the primary key and the collation is case-insensitive, so "Hub" and "hub" are
-- one warp. utf8mb4_general_ci gives that; SQLite gets the same from COLLATE NOCASE.

CREATE TABLE warps (
    name        VARCHAR(32)    NOT NULL PRIMARY KEY COLLATE utf8mb4_general_ci,
    world       VARCHAR(64)    NOT NULL,
    x           DOUBLE         NOT NULL,
    y           DOUBLE         NOT NULL,
    z           DOUBLE         NOT NULL,
    yaw         FLOAT          NOT NULL,
    pitch       FLOAT          NOT NULL,
    created_by  CHAR(36),
    created_at  BIGINT         NOT NULL,
    expires_at  BIGINT,
    INDEX idx_warps_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
