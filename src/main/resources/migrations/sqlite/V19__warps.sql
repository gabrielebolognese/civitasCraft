-- Public warps, SPEC 32.7, SQLite.
--
-- SPEC 32.7 lists /warp as "admin-defined public warps" and defines no command anywhere that
-- creates one, so /ca warp set arrives with this table. Recorded in OPEN_QUESTIONS.md.
--
-- expires_at is null for a permanent warp. SPEC 40.1 needs temporary ones: submitting a
-- contest entry "generates a temporary public warp to the entry's viewing platform, available
-- for the duration of the voting window only". That milestone writes rows here rather than
-- building a second warp system beside this one.

CREATE TABLE warps (
    name        VARCHAR(32) NOT NULL PRIMARY KEY COLLATE NOCASE,
    world       VARCHAR(64) NOT NULL,
    x           DOUBLE      NOT NULL,
    y           DOUBLE      NOT NULL,
    z           DOUBLE      NOT NULL,
    yaw         REAL        NOT NULL,
    pitch       REAL        NOT NULL,
    created_by  CHAR(36),
    created_at  BIGINT      NOT NULL,
    expires_at  BIGINT
);

CREATE INDEX idx_warps_expiry ON warps (expires_at);
