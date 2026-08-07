package dev.civitas.core.war;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.WarBlockLogRow;
import dev.civitas.storage.row.WarChunkHashRow;
import dev.civitas.storage.row.WarRollbackIssueRow;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Puts a war's damage back, SPEC 11.8.2.
 *
 * <h2>Why this code matters more than the rest</h2>
 * SPEC 11.8 calls it "the most important code in the plugin" and SPEC 11.1 explains the stake:
 * the whole design rests on players being willing to fight wars, and they will only do that
 * while they believe their builds come back. A rollback that is merely usually correct is
 * worse than no war system at all, because it breaks the promise the feature was sold on.
 *
 * <h2>Shape</h2>
 * Two halves that must not mix. Reading the log is database work and runs off the server
 * thread; applying blocks touches the world and must run on it. So the engine alternates:
 * {@link #fetchNextPage} pulls a page asynchronously, {@link #applySlice} writes at most
 * {@code blocks-per-tick} of it on the server thread. A driver calls them in turn; the tests
 * call them in a loop, which is what makes the whole engine testable with no war in existence,
 * as SPEC 19 requires.
 *
 * <h2>Order</h2>
 * Descending sequence. SPEC 17.4 case 42 is the reason it works: a block placed, broken, then
 * placed again produces three entries, and applying them newest-first means the oldest entry
 * lands last and wins. The state a position ends in is the state it had before the war,
 * without the engine needing to know anything about the history at that position.
 */
public final class RollbackEngine {

    private final DaoRegistry daos;
    private final ConfigManager configs;

    /** SPEC 16.3's rollback switches. Built from the same config, so never null. */
    private final RollbackPolicy policy;
    private final TilePayloadCodec codec;
    private final ChunkHasher hasher;
    private final Logger logger;
    private final Random random;

    /** Jobs by war id, so a status query and the driver see the same object. */
    private final Map<Integer, RollbackJob> jobs = new java.util.concurrent.ConcurrentHashMap<>();

    /** The appliers, one per job, holding its loaded chunks and restored positions. */
    private final Map<Integer, BlockApplier> appliers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Issues found but not yet written, flushed with the rest at the end. */
    private final Map<Integer, List<WarRollbackIssueRow>> issues =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RollbackEngine(DaoRegistry daos, ConfigManager configs, TilePayloadCodec codec,
                          ChunkHasher hasher, Logger logger) {
        this(daos, configs, codec, hasher, logger, new Random());
    }

    /** @param random the sampling source; fixed in tests so a 2% sample is reproducible */
    public RollbackEngine(DaoRegistry daos, ConfigManager configs, TilePayloadCodec codec,
                          ChunkHasher hasher, Logger logger, Random random) {
        this.daos = Objects.requireNonNull(daos, "daos");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.policy = new RollbackPolicy(configs);
        this.codec = Objects.requireNonNull(codec, "codec");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.random = Objects.requireNonNull(random, "random");
        this.hangings = new HangingRestorer(logger);
    }

    /** SPEC 11.8.3's item frames, paintings and armor stands. */
    private final HangingRestorer hangings;

    private WarEntitySnapshots entities;

    /** SPEC 11.8.3's animals and villagers, wired by M19. */
    public void useEntitySnapshots(WarEntitySnapshots snapshots) {
        this.entities = snapshots;
    }

    public Optional<RollbackJob> jobFor(int warId) {
        return Optional.ofNullable(jobs.get(warId));
    }

    /** Every war this engine is currently rolling back. */
    public java.util.Set<Integer> activeWars() {
        return java.util.Set.copyOf(jobs.keySet());
    }

    /**
     * Waits for every pending checkpoint, for {@code onDisable}.
     *
     * <p>A checkpoint that never reached disk is work the next boot repeats. Waiting costs a
     * moment on shutdown; not waiting costs a rollback that resumes further back than it
     * needed to, every time.
     */
    public void awaitAllCheckpoints() {
        for (int warId : activeWars()) {
            awaitCheckpoints(warId);
        }
    }

    public boolean isRunning(int warId) {
        RollbackJob job = jobs.get(warId);
        return job != null && job.status() == RollbackStatus.RUNNING;
    }

    // ==================================================================================
    // Starting and resuming
    // ==================================================================================

    /**
     * Begins a rollback, or picks one up where a crash left it.
     *
     * <p>SPEC 11.8.5: a war found in {@code ROLLING_BACK} at startup "resumes rollback from
     * the last completed sequence number". The checkpoint is an exclusive upper bound, so
     * resuming from it re-reads nothing that was already applied and skips nothing that was
     * not.
     */
    public CompletableFuture<RollbackJob> begin(int warId) {
        return daos.wars().findById(warId).thenApply(row -> {
            long cursor = row.map(war -> war.rollbackCheckpointSequence() == null
                            ? Long.MAX_VALUE
                            : war.rollbackCheckpointSequence())
                    .orElse(Long.MAX_VALUE);

            RollbackJob job = new RollbackJob(warId, cursor);
            jobs.put(warId, job);
            appliers.put(warId, new BlockApplier(logger));
            issues.put(warId, new ArrayList<>());

            if (cursor != Long.MAX_VALUE) {
                logger.info("Resuming rollback of war " + warId + " from sequence " + cursor
                        + "; entries already applied are not repeated.");
            }
            return job;
        });
    }

    /** Every war left mid-rollback by a crash, SPEC 11.8.5. */
    public CompletableFuture<List<Integer>> findInterrupted() {
        return daos.wars().findByStates(List.of("ROLLING_BACK"))
                .thenApply(rows -> rows.stream().map(dev.civitas.storage.row.WarRow::id).toList());
    }

    // ==================================================================================
    // Reading, SPEC 11.8.2 step 3
    // ==================================================================================

    /**
     * Loads the next page of the log, newest first.
     *
     * <p>Runs off the server thread. Paged at {@code read-page-size} because SPEC 17.7 case 83
     * allows a two-million-row war and reading that at once would be several hundred megabytes
     * of rows in memory at the exact moment the server can least afford it.
     *
     * @return how many rows were read; zero means the log is exhausted
     */
    public CompletableFuture<Integer> fetchNextPage(RollbackJob job) {
        if (job.status() != RollbackStatus.RUNNING || job.hasPending()) {
            return CompletableFuture.completedFuture(0);
        }

        try {
            return daos.warBlockLog().findForReplay(job.warId(), job.cursor(), readPageSize())
                    .thenApply(rows -> {
                        job.page().addAll(rows);
                        if (!rows.isEmpty()) {
                            job.cursor(rows.get(rows.size() - 1).sequence());
                        }
                        return rows.size();
                    })
                    .exceptionally(error -> {
                        failLog(job, error);
                        return 0;
                    });
        } catch (RuntimeException e) {
            // Not every failure arrives as a failed future: a closed pool throws from the call
            // itself, before there is a future to fail. Without this the war would never reach
            // ROLLBACK_FAILED and SPEC 11.8.5's guarantee would be lost at the exact moment it
            // matters. The same shape has bitten StatsService and WarBlockLogger.
            failLog(job, e);
            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * SPEC 11.8.5: an unreadable log is not something to work around. The war fails, the zone
     * stays closed, and an admin is told. It must never silently give up and reopen a griefed
     * city.
     */
    private void failLog(RollbackJob job, Throwable error) {
        failJob(job, "LOG_UNREADABLE", String.valueOf(error.getMessage()));
        logger.log(Level.SEVERE, "Could not read the block log for war " + job.warId()
                + ". The rollback has FAILED and the war zone stays closed until an "
                + "administrator resolves it.", error);
    }

    // ==================================================================================
    // Applying, SPEC 11.8.2 steps 4 to 6
    // ==================================================================================

    /**
     * Writes at most {@code blocks-per-tick} entries.
     *
     * <p>Runs on the server thread. SPEC 11.8.2 step 6 sets the throttle so the server never
     * freezes: 400 a tick clears a 300,000-block war in about twelve and a half seconds of
     * server time, which is a pause nobody notices, where doing it in one tick is an outage.
     *
     * @return how many entries were applied
     */
    public int applySlice(RollbackJob job) {
        if (job.status() != RollbackStatus.RUNNING) {
            return 0;
        }
        BlockApplier applier = appliers.get(job.warId());
        if (applier == null) {
            return 0;
        }

        int budget = blocksPerTick();
        int done = 0;
        double sampleRate = verifySamplePercent() / 100.0;

        while (done < budget && job.hasPending()) {
            WarBlockLogRow row = job.page().pollFirst();
            if (row == null) {
                break;
            }

            World world = Bukkit.getWorld(row.world());
            if (world == null) {
                job.countSkipped();
                done++;
                continue;
            }

            if (WarBlockRecorder.HANGING_MARKER.equals(row.oldBlockData())) {
                // An entity, not a block. M17 records these at the block they occupy and M19
                // rebuilds them: spawning an entity is a different operation from writing a
                // block, so it does not go through the applier or the sampling below.
                if (policy.restoreEntities()
                        && hangings.restore(world, row.x(), row.y(), row.z(), row.oldNbt())) {
                    job.countApplied();
                } else {
                    job.countSkipped();
                }
                done++;
                continue;
            }

            boolean applied = applier.apply(world, row.x(), row.y(), row.z(), row.oldBlockData());
            if (applied) {
                // SPEC 11.8.2 step 5: the tile payload goes back after the block exists,
                // unless SPEC 16.3's rollback.restore-container-nbt says not to.
                if (row.oldNbt() != null && policy.restoreContainerNbt()) {
                    codec.restore(world.getBlockAt(row.x(), row.y(), row.z()), row.oldNbt());
                }
                job.countApplied();

                // Chosen once and then kept up to date: see RollbackJob.sampled for why the
                // last value written at a position is the correct expectation.
                RollbackJob.Sample sample = new RollbackJob.Sample(row.world(),
                        row.x(), row.y(), row.z(), row.oldBlockData());
                if (job.sampled().containsKey(sample.key()) || random.nextDouble() < sampleRate) {
                    job.sampled().put(sample.key(), sample);
                }
            } else {
                job.countSkipped();
                recordIssue(job, "APPLY_FAILED", row.world(), row.x(), row.y(), row.z(),
                        "could not restore " + row.oldBlockData());
            }
            done++;
        }

        checkpointIfDue(job);
        return done;
    }

    /**
     * SPEC 11.8.5: the last restored sequence is written every
     * {@code checkpoint-every-blocks} so a crash resumes rather than restarting.
     */
    private void checkpointIfDue(RollbackJob job) {
        long since = job.applied() + job.skipped() - job.lastCheckpoint();
        if (since < checkpointEvery()) {
            return;
        }
        job.checkpointed(job.applied() + job.skipped());

        // The cursor descends, so progress means a *lower* number. Writes go out
        // asynchronously and can land out of order, so a checkpoint that would move the
        // stored cursor backwards is dropped rather than written. Out-of-order writes are
        // safe either way (a cursor too high only replays work that was already done) but
        // they make a resume non-deterministic, and this engine is one where "it recovered
        // differently that time" is not an acceptable thing to say.
        long cursor = job.cursor();
        if (job.lastWrittenCursor() != null && cursor >= job.lastWrittenCursor()) {
            return;
        }
        job.wroteCursor(cursor);

        // Chained rather than fired independently, so two checkpoints cannot land in the
        // wrong order and leave a resume starting from somewhere neither of them chose.
        checkpointChains.compute(job.warId(), (warId, previous) -> {
            CompletableFuture<?> after = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous;
            return after.handle((ignored, error) -> null)
                    .thenCompose(ignored -> {
                        try {
                            return daos.wars().updateRollbackCheckpoint(warId, cursor);
                        } catch (RuntimeException e) {
                            // A missed checkpoint costs replayed work after a crash, not
                            // correctness: reapplying an entry writes the same data twice.
                            logger.log(Level.WARNING, "Could not checkpoint the rollback of "
                                    + "war " + warId, e);
                            return CompletableFuture.completedFuture(0);
                        }
                    })
                    .exceptionally(error -> {
                        logger.log(Level.WARNING, "Could not checkpoint the rollback of war "
                                + warId, error);
                        return 0;
                    });
        });
    }

    /** Checkpoint writes still in flight, chained so they land in order. */
    private final Map<Integer, CompletableFuture<?>> checkpointChains =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Waits for a war's checkpoint writes to land.
     *
     * <p>For shutdown, and for tests that need to read the checkpoint back. Blocking is
     * correct in both: on disable there is no later opportunity, and a checkpoint that never
     * reached disk is work a restart will repeat.
     */
    public void awaitCheckpoints(int warId) {
        CompletableFuture<?> chain = checkpointChains.get(warId);
        if (chain == null) {
            return;
        }
        try {
            chain.join();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "A checkpoint write for war " + warId + " did not "
                    + "complete; the rollback will resume from an earlier point.", e);
        }
    }

    // ==================================================================================
    // Finishing, SPEC 11.8.2 steps 7 to 9
    // ==================================================================================

    /**
     * Runs the boundary physics pass, the verification sample and the chunk-hash comparison,
     * then marks the war resolved.
     *
     * <p>Runs on the server thread. Called once the log is exhausted.
     *
     * @return the issues found, which is empty on a clean rollback
     */
    public CompletableFuture<List<WarRollbackIssueRow>> finish(RollbackJob job) {
        if (job.status() == RollbackStatus.FAILED) {
            return flushIssues(job);
        }

        BlockApplier applier = appliers.get(job.warId());
        if (applier != null) {
            // Step 7, then the two SPEC 17.4 case 50 courtesies, then release what we loaded.
            for (World world : Bukkit.getWorlds()) {
                applier.applyBoundaryPhysics(world);
                applier.freeTrappedPlayers(world);
                applier.releaseChunks(world);
            }
        }

        // SPEC 11.8.3's animals and villagers, after the blocks and not before: one respawned
        // first would be standing inside whatever the replay was about to put where it stood.
        // SPEC 16.3's rollback.restore-entities, shipped from M0 and read by nothing:
        // villagers and animals were always put back whatever the operator set.
        if (entities != null && policy.restoreEntities()) {
            entities.restoreDead(job.warId()).thenAccept(restored -> {
                if (restored > 0) {
                    logger.info("Restored " + restored + " animal(s) and villager(s) killed in "
                            + "war " + job.warId() + ".");
                }
                entities.forget(job.warId());
            });
        }

        verifySample(job);

        return compareChunkHashes(job)
                .thenCompose(ignored -> flushIssues(job))
                .thenCompose(found -> {
                    job.complete();
                    long now = System.currentTimeMillis();
                    return daos.wars().findById(job.warId())
                            .thenCompose(row -> row.map(war -> daos.wars().update(
                                            new dev.civitas.storage.row.WarRow(
                                                    war.id(), war.attackerCityId(),
                                                    war.defenderCityId(), war.declaredAt(),
                                                    war.prepEndsAt(), war.warEndsAt(),
                                                    "RESOLVED", war.attackerScore(),
                                                    war.defenderScore(), war.winnerCityId(),
                                                    war.wager(), now, war.rollbackCheckpointSequence()))
                                            .thenApply(updated -> found))
                                    .orElseGet(() -> CompletableFuture.completedFuture(found)));
                });
    }

    /**
     * SPEC 11.8.2 step 8: re-read a sample and confirm it matches.
     *
     * <p>SPEC 17.4 case 57 settles what a mismatch means: "Log ERROR with coordinates,
     * continue the rollback, then surface the mismatch list in {@code /ca war rollbackstatus}.
     * Do not abort." A rollback that stopped on its first disagreement would leave more of the
     * city broken than one that finished and reported.
     */
    void verifySample(RollbackJob job) {
        for (RollbackJob.Sample sample : job.sampled().values()) {
            World world = Bukkit.getWorld(sample.world());
            if (world == null) {
                continue;
            }
            String actual = world.getBlockAt(sample.x(), sample.y(), sample.z())
                    .getBlockData().getAsString();
            if (!actual.equals(sample.expected())) {
                logger.severe("Rollback verification mismatch for war " + job.warId() + " at "
                        + sample.x() + "," + sample.y() + "," + sample.z() + ": expected "
                        + sample.expected() + " but found " + actual);
                recordIssue(job, "VERIFY_MISMATCH", sample.world(),
                        sample.x(), sample.y(), sample.z(),
                        "expected " + sample.expected() + " but found " + actual);
            }
        }
    }

    /** SPEC 11.8.4: re-hash every zone chunk and flag the ones that disagree. */
    CompletableFuture<Integer> compareChunkHashes(RollbackJob job) {
        if (!hasher.isEnabled()) {
            return CompletableFuture.completedFuture(0);
        }
        return daos.warChunkHashes().findByWar(job.warId()).thenApply(rows -> {
            int mismatched = 0;
            for (WarChunkHashRow row : rows) {
                World world = Bukkit.getWorld(row.world());
                if (world == null) {
                    continue;
                }
                long after = hasher.hash(world, row.chunkX(), row.chunkZ());
                daos.warChunkHashes().recordAfter(row.warId(), row.world(), row.chunkX(),
                        row.chunkZ(), after);
                if (after != row.hashBefore()) {
                    mismatched++;
                    logger.warning("Chunk " + row.chunkX() + "," + row.chunkZ() + " in "
                            + row.world() + " does not match its pre-war hash after the "
                            + "rollback of war " + job.warId() + ". Something changed it that "
                            + "no listener saw; see /ca war rollbackstatus.");
                    recordIssue(job, "CHUNK_HASH_MISMATCH", row.world(),
                            row.chunkX(), null, row.chunkZ(),
                            "chunk hash " + after + " does not match pre-war " + row.hashBefore());
                }
            }
            return mismatched;
        });
    }

    /**
     * SPEC 9.4.5's {@code /ca war verify}: "Dry-run integrity check of the block log, reports
     * any entries that would fail to restore and why."
     *
     * <p>A dry run in the strict sense — it reads every entry, asks whether it could be
     * applied, and writes nothing. The value is in running it <em>before</em> a rollback rather
     * than finding the answer during one, which is why SPEC 18.3 step 9 makes it the last check
     * before a server may launch.
     *
     * <p>Three things can make an entry unrestorable, and each is reported separately because
     * each has a different fix: a world that no longer exists (restore the world, or accept the
     * loss), block data this build cannot parse (a version change), and a position outside the
     * world's height range (a height change since the war).
     */
    public CompletableFuture<VerifyReport> verifyLog(int warId) {
        return verifyPage(warId, Long.MAX_VALUE, new VerifyAccumulator());
    }

    /** What a dry run found. Clean means every entry could be applied today. */
    public record VerifyReport(int warId, long entries, long unreadable, long missingWorlds,
                               List<String> problems) {

        public boolean isClean() {
            return unreadable == 0 && missingWorlds == 0;
        }
    }

    /** Running totals for the paged walk, kept out of the report so the report is immutable. */
    private static final class VerifyAccumulator {
        private long entries;
        private long unreadable;
        private long missingWorlds;
        private final List<String> problems = new ArrayList<>();

        void problem(String detail) {
            // Bounded: a log with a million broken rows would otherwise produce a million
            // lines of chat, and the first twenty say the same thing as the millionth.
            if (problems.size() < 20) {
                problems.add(detail);
            }
        }
    }

    private CompletableFuture<VerifyReport> verifyPage(int warId, long before,
                                                       VerifyAccumulator totals) {
        return daos.warBlockLog().findForReplay(warId, before, readPageSize())
                .thenCompose(rows -> {
                    if (rows.isEmpty()) {
                        return CompletableFuture.completedFuture(new VerifyReport(warId,
                                totals.entries, totals.unreadable, totals.missingWorlds,
                                List.copyOf(totals.problems)));
                    }
                    long lowest = before;
                    for (WarBlockLogRow row : rows) {
                        totals.entries++;
                        lowest = Math.min(lowest, row.sequence());
                        verifyRow(row, totals);
                    }
                    return verifyPage(warId, lowest, totals);
                });
    }

    private void verifyRow(WarBlockLogRow row, VerifyAccumulator totals) {
        World world = Bukkit.getWorld(row.world());
        if (world == null) {
            totals.missingWorlds++;
            totals.problem("world '" + row.world() + "' is not loaded, so "
                    + row.x() + "," + row.y() + "," + row.z() + " cannot be restored");
            return;
        }
        if (WarBlockRecorder.HANGING_MARKER.equals(row.oldBlockData())) {
            // An entity rather than a block; its payload is checked when it is restored, and
            // an unreadable one costs a decoration rather than a wall.
            return;
        }
        if (row.y() < world.getMinHeight() || row.y() >= world.getMaxHeight()) {
            totals.unreadable++;
            totals.problem("y=" + row.y() + " is outside " + row.world() + "'s height range");
            return;
        }
        try {
            Bukkit.createBlockData(row.oldBlockData());
        } catch (IllegalArgumentException e) {
            totals.unreadable++;
            totals.problem("unreadable block data '" + row.oldBlockData() + "' at "
                    + row.x() + "," + row.y() + "," + row.z());
        }
    }

    /** Takes the pre-war hashes SPEC 11.8.4 compares against. Called at war start by M19. */
    public CompletableFuture<Integer> recordPreWarHashes(int warId, World world,
                                                         List<long[]> chunks) {
        if (!hasher.isEnabled() || chunks.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        List<WarChunkHashRow> rows = new ArrayList<>(chunks.size());
        for (long[] chunk : chunks) {
            int chunkX = (int) chunk[0];
            int chunkZ = (int) chunk[1];
            rows.add(new WarChunkHashRow(warId, world.getName(), chunkX, chunkZ,
                    hasher.hash(world, chunkX, chunkZ), null));
        }
        return daos.warChunkHashes().insertAll(rows);
    }

    // ==================================================================================
    // Failure and issues
    // ==================================================================================

    /**
     * Fails a rollback, in memory first and in storage if it can.
     *
     * <p>The in-memory state is set before anything is written, because the most likely reason
     * to be here is that the database is unreachable: if persisting the failure were what made
     * the job fail, a dead database would leave the job merrily reporting RUNNING. SPEC 11.8.5
     * cares that the zone stays closed, and the zone is closed on the strength of this flag.
     */
    private void failJob(RollbackJob job, String kind, String detail) {
        job.fail(kind);
        recordIssue(job, kind, null, null, null, null, detail);
        try {
            daos.wars().updateState(job.warId(), "ROLLBACK_FAILED")
                    .exceptionally(error -> {
                        logger.log(Level.SEVERE, "War " + job.warId() + " could not be marked "
                                + "ROLLBACK_FAILED in storage. It is failed in memory and its "
                                + "zone stays closed; an administrator must set the state by "
                                + "hand if the server restarts before storage recovers.", error);
                        return 0;
                    });
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "War " + job.warId() + " could not be marked "
                    + "ROLLBACK_FAILED in storage; it is failed in memory.", e);
        }
    }

    private void recordIssue(RollbackJob job, String kind, String world,
                             Integer x, Integer y, Integer z, String detail) {
        issues.computeIfAbsent(job.warId(), ignored -> new ArrayList<>())
                .add(new WarRollbackIssueRow(0, job.warId(), kind, world, x, y, z, detail,
                        System.currentTimeMillis()));
    }

    private CompletableFuture<List<WarRollbackIssueRow>> flushIssues(RollbackJob job) {
        List<WarRollbackIssueRow> found = issues.getOrDefault(job.warId(), List.of());
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return daos.warRollbackIssues().insertAll(found).thenApply(ignored -> List.copyOf(found));
    }

    /** Releases a finished war's in-memory state. */
    public void forget(int warId) {
        jobs.remove(warId);
        appliers.remove(warId);
        issues.remove(warId);
    }

    // ==================================================================================
    // Configuration, SPEC 16.3
    // ==================================================================================

    public boolean isEnabled() {
        return configs.get(ConfigFile.WAR).getBoolean("rollback.enabled", true);
    }

    public int blocksPerTick() {
        return Math.max(1, configs.get(ConfigFile.WAR).getInt("rollback.blocks-per-tick", 400));
    }

    public int readPageSize() {
        return Math.max(1, configs.get(ConfigFile.WAR).getInt("rollback.read-page-size", 5000));
    }

    public long checkpointEvery() {
        return Math.max(1L, configs.get(ConfigFile.WAR)
                .getLong("rollback.checkpoint-every-blocks", 5000));
    }

    public double verifySamplePercent() {
        return configs.get(ConfigFile.WAR).getDouble("rollback.verify-sample-percent", 2.0);
    }

}
