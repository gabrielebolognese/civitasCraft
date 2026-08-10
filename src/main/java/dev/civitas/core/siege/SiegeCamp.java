package dev.civitas.core.siege;

import java.util.Objects;

import dev.civitas.storage.row.SiegeCampRow;

/**
 * An attacking city's staging point, SPEC 29.5.
 *
 * <h2>Why the health lives here and not on the block</h2>
 *
 * <p>SPEC 29.5 gives the camp "200 HP as a block-entity". Bukkit has no such thing: a block is not
 * damageable and has no health, and the only entity that could carry one would be a mob standing
 * where the banner is. So the banner in the world is a <b>marker</b> — it says where the camp is
 * and nothing else — and the camp is this object, checkpointed to its row.
 *
 * <p>The consequence worth knowing: breaking the marker block is not how a camp dies. Mining it is
 * refused outright and hitting it is what does damage, because a 200 HP objective that a diamond
 * pickaxe removes in a second is not an objective.
 */
public final class SiegeCamp {

    private final int id;
    private final int warId;
    private final int cityId;
    private final String world;
    private final int x;
    private final int y;
    private final int z;

    private double health;
    private Long destroyedAt;
    private boolean rebuilt;

    public SiegeCamp(int id, int warId, int cityId, String world, int x, int y, int z,
                     double health, Long destroyedAt, boolean rebuilt) {
        this.id = id;
        this.warId = warId;
        this.cityId = cityId;
        this.world = Objects.requireNonNull(world, "world");
        this.x = x;
        this.y = y;
        this.z = z;
        this.health = health;
        this.destroyedAt = destroyedAt;
        this.rebuilt = rebuilt;
    }

    public static SiegeCamp fromRow(SiegeCampRow row) {
        return new SiegeCamp(row.id(), row.warId(), row.cityId(), row.world(), row.x(), row.y(),
                row.z(), row.health(), row.destroyedAt(), row.rebuilt());
    }

    public int id() {
        return id;
    }

    public int warId() {
        return warId;
    }

    public int cityId() {
        return cityId;
    }

    public String world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public double health() {
        return health;
    }

    public boolean stands() {
        return destroyedAt == null;
    }

    public Long destroyedAt() {
        return destroyedAt;
    }

    /** SPEC 29.5 allows exactly one rebuild per war, so this is spent, not reset. */
    public boolean rebuilt() {
        return rebuilt;
    }

    /**
     * Takes a hit.
     *
     * @return true when this blow is the one that destroyed the camp
     */
    public boolean damage(double amount, long now) {
        if (!stands()) {
            // Already down. Returning false rather than true is what stops two players landing
            // the killing blow in one tick from both scoring SPEC 29.5's 40 points.
            return false;
        }
        health = Math.max(0, health - Math.max(0, amount));
        if (health <= 0) {
            destroyedAt = now;
            return true;
        }
        return false;
    }

    void markRebuilt(double fullHealth) {
        this.health = fullHealth;
        this.destroyedAt = null;
        this.rebuilt = true;
    }
}
