-- City waystations, SPEC 39.10, SQLite.
--
-- The resource-world counterpart to an outpost, and deliberately a different thing rather than
-- an outpost with a flag set. SPEC 39.10: "A Mining Claim (32.6) is personal, one chunk,
-- available to any player including those with no city. A Waystation is city-owned, up to two
-- chunks, and gives every member a teleport anchor. A player may hold both."
--
-- Its own table rather than rows in `claims`, for the reason M3a made structural: ClaimService
-- refuses to write a claim in a world that is not city-claimable, and the resource worlds never
-- are. Reusing `claims` would mean either weakening that refusal or carrying an exception
-- through every path that reads it. A waystation is protected by the same rule mining claims
-- are, which keeps one authority per world instead of two that can disagree.
--
-- One chunk per row, linked by waystation_id, which is the same shape the SPEC 39 rework found
-- already worked for multi-chunk outposts: several rows sharing an id IS a multi-chunk holding.
--
-- Money columns are INTEGER minor units here for the same reason as V1; see that file.

CREATE TABLE waystations (
    id           INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    city_id      INT      NOT NULL,
    world        VARCHAR(64) NOT NULL,
    created_at   BIGINT   NOT NULL,
    warp_x       DOUBLE   NOT NULL,
    warp_y       DOUBLE   NOT NULL,
    warp_z       DOUBLE   NOT NULL,
    warp_yaw     FLOAT    NOT NULL,
    warp_pitch   FLOAT    NOT NULL
);

-- SPEC 39.10's "1 per city per resource world". Enforced physically rather than only in the
-- service, so two members buying at the same tick cannot both succeed.
CREATE UNIQUE INDEX idx_waystations_city_world ON waystations (city_id, world);

CREATE TABLE waystation_chunks (
    id             INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    waystation_id  INT      NOT NULL,
    world          VARCHAR(64) NOT NULL,
    chunk_x        INT      NOT NULL,
    chunk_z        INT      NOT NULL,
    claimed_at     BIGINT   NOT NULL,
    cost_paid      INTEGER  NOT NULL   -- minor units
);

-- The same physical guarantee SPEC 3.4 gives city claims, extended across the two systems that
-- can own ground in a resource world: a chunk is a mining claim or a waystation chunk, never
-- both. The service checks the other table; this index stops two waystations colliding.
CREATE UNIQUE INDEX idx_waystation_chunks_chunk ON waystation_chunks (world, chunk_x, chunk_z);
CREATE INDEX idx_waystation_chunks_parent ON waystation_chunks (waystation_id);
