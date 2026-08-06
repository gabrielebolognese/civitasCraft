package dev.civitas.core.city;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.storage.row.CityRow;

/**
 * A city, held in memory for the lifetime of the server, SPEC 3.2 and SPEC 2.3.
 *
 * <p>This is the read path. Nothing that answers "who owns this", "may this player build" or
 * "what rank is this member" is allowed to touch the database, so a city carries its ranks
 * and members with it. {@link CityService} owns every mutation and is responsible for
 * persisting it; the setters here are package-private so nothing else can drift the cache
 * out of step with the database.
 */
public final class City {

    private final int id;
    private String name;
    private String displayName;
    private String tag;
    private UUID mayorUuid;
    private final long foundedAt;
    private BigDecimal treasury;
    private String coreWorld;
    private int coreChunkX;
    private int coreChunkZ;
    private double spawnX;
    private double spawnY;
    private double spawnZ;
    private float spawnYaw;
    private float spawnPitch;
    private boolean openJoin;
    private String motd;
    private long upkeepDue;
    private Long delinquentSince;
    private long warProtectionUntil;
    private boolean frozen;
    private Long deletedAt;

    private final Map<Integer, CityRank> ranks = new ConcurrentHashMap<>();
    private final Map<UUID, CityMember> members = new ConcurrentHashMap<>();
    private final Map<UUID, String> bans = new ConcurrentHashMap<>();

    private City(CityRow row) {
        this.id = row.id();
        this.name = row.name();
        this.displayName = row.displayName();
        this.tag = row.tag();
        this.mayorUuid = row.mayorUuid();
        this.foundedAt = row.foundedAt();
        this.treasury = row.treasury();
        this.coreWorld = row.coreWorld();
        this.coreChunkX = row.coreChunkX();
        this.coreChunkZ = row.coreChunkZ();
        this.spawnX = row.spawnX();
        this.spawnY = row.spawnY();
        this.spawnZ = row.spawnZ();
        this.spawnYaw = row.spawnYaw();
        this.spawnPitch = row.spawnPitch();
        this.openJoin = row.openJoin();
        this.motd = row.motd();
        this.upkeepDue = row.upkeepDue();
        this.delinquentSince = row.delinquentSince();
        this.warProtectionUntil = row.warProtectionUntil();
        this.frozen = row.frozen();
        this.deletedAt = row.deletedAt();
    }

    public static City fromRow(CityRow row) {
        return new City(row);
    }

    public CityRow toRow() {
        return new CityRow(id, name, displayName, tag, mayorUuid, foundedAt, treasury,
                coreWorld, coreChunkX, coreChunkZ, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch,
                openJoin, motd, upkeepDue, delinquentSince, warProtectionUntil, frozen, deletedAt);
    }

    // --- identity ---------------------------------------------------------------------

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String displayName() {
        return displayName;
    }

    void setDisplayName(String displayName) {
        this.displayName = Objects.requireNonNull(displayName, "displayName");
    }

    public String tag() {
        return tag;
    }

    void setTag(String tag) {
        this.tag = tag;
    }

    public UUID mayorUuid() {
        return mayorUuid;
    }

    void setMayorUuid(UUID mayorUuid) {
        this.mayorUuid = Objects.requireNonNull(mayorUuid, "mayorUuid");
    }

    public long foundedAt() {
        return foundedAt;
    }

    public long ageMillis(long now) {
        return now - foundedAt;
    }

    // --- money and state --------------------------------------------------------------

    public BigDecimal treasury() {
        return treasury;
    }

    /**
     * Kept in step with the database by whichever service just wrote it. Public because the
     * claim service charges the treasury too; nothing outside a service may call it, or the
     * cache and the database will drift.
     */
    public void setTreasury(BigDecimal treasury) {
        this.treasury = Objects.requireNonNull(treasury, "treasury");
    }

    public boolean isFrozen() {
        return frozen;
    }

    /** See {@link #setTreasury}: service-only, and public so admin tooling can freeze a city. */
    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public boolean isDelinquent() {
        return delinquentSince != null;
    }

    public Long delinquentSince() {
        return delinquentSince;
    }

    /** See {@link #setTreasury}: service-only, and public so the upkeep task can mark a debt. */
    public void setDelinquentSince(Long delinquentSince) {
        this.delinquentSince = delinquentSince;
    }

    public long upkeepDue() {
        return upkeepDue;
    }

    /** See {@link #setTreasury}: service-only, and public so the upkeep sweep can advance it. */
    public void setUpkeepDue(long upkeepDue) {
        this.upkeepDue = upkeepDue;
    }

    public long warProtectionUntil() {
        return warProtectionUntil;
    }

    /**
     * SPEC 11.9's post-war immunity.
     *
     * <p>Public since M19: the war system sets it when a war ends, and SPEC 11.9 is explicit
     * that this "is protection, not punishment" for the city that just lost.
     */
    public void setWarProtectionUntil(long warProtectionUntil) {
        this.warProtectionUntil = warProtectionUntil;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Long deletedAt() {
        return deletedAt;
    }

    void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }

    // --- location and settings --------------------------------------------------------

    public String coreWorld() {
        return coreWorld;
    }

    public int coreChunkX() {
        return coreChunkX;
    }

    public int coreChunkZ() {
        return coreChunkZ;
    }

    /** See {@link #setTreasury}: service-only, and public so the claim service can promote a core. */
    public void setCore(String world, int chunkX, int chunkZ) {
        this.coreWorld = Objects.requireNonNull(world, "world");
        this.coreChunkX = chunkX;
        this.coreChunkZ = chunkZ;
    }

    public double spawnX() {
        return spawnX;
    }

    public double spawnY() {
        return spawnY;
    }

    public double spawnZ() {
        return spawnZ;
    }

    public float spawnYaw() {
        return spawnYaw;
    }

    public float spawnPitch() {
        return spawnPitch;
    }

    /** See {@link #setTreasury}: service-only, and public so unclaiming can reset a stranded spawn. */
    public void setSpawn(double x, double y, double z, float yaw, float pitch) {
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
        this.spawnYaw = yaw;
        this.spawnPitch = pitch;
    }

    public boolean isOpenJoin() {
        return openJoin;
    }

    void setOpenJoin(boolean openJoin) {
        this.openJoin = openJoin;
    }

    public String motd() {
        return motd;
    }

    void setMotd(String motd) {
        this.motd = Objects.requireNonNull(motd, "motd");
    }

    // --- ranks ------------------------------------------------------------------------

    public Collection<CityRank> ranks() {
        return Collections.unmodifiableCollection(ranks.values());
    }

    public Optional<CityRank> rank(int rankId) {
        return Optional.ofNullable(ranks.get(rankId));
    }

    /** Case-insensitive, because players type rank names as they remember them. */
    public Optional<CityRank> rankByName(String rankName) {
        return ranks.values().stream()
                .filter(rank -> rank.name().equalsIgnoreCase(rankName))
                .findFirst();
    }

    /** The rank new joiners receive. */
    public Optional<CityRank> defaultRank() {
        return ranks.values().stream().filter(CityRank::isDefault).findFirst();
    }

    /** The highest-weight rank, which is the mayor's. */
    public Optional<CityRank> mayorRank() {
        return ranks.values().stream().max((a, b) -> Integer.compare(a.weight(), b.weight()));
    }

    void putRank(CityRank rank) {
        ranks.put(rank.id(), rank);
    }

    void removeRank(int rankId) {
        ranks.remove(rankId);
    }

    /** How many members currently hold a rank, used before deleting it. */
    public long membersWithRank(int rankId) {
        return members.values().stream().filter(member -> member.rankId() == rankId).count();
    }

    // --- members ----------------------------------------------------------------------

    public Collection<CityMember> members() {
        return Collections.unmodifiableCollection(members.values());
    }

    public int memberCount() {
        return members.size();
    }

    public Optional<CityMember> member(UUID uuid) {
        return Optional.ofNullable(members.get(uuid));
    }

    public boolean isMember(UUID uuid) {
        return members.containsKey(uuid);
    }

    public boolean isMayor(UUID uuid) {
        return mayorUuid.equals(uuid);
    }

    void putMember(CityMember member) {
        members.put(member.uuid(), member);
    }

    void removeMember(UUID uuid) {
        members.remove(uuid);
    }

    /**
     * The rank a member holds, or empty if they are not a member.
     *
     * <p>Returns empty rather than a default rank when the rank id does not resolve, so a
     * corrupt row fails closed instead of silently granting whatever the default allows.
     */
    public Optional<CityRank> rankOf(UUID uuid) {
        return member(uuid).flatMap(member -> rank(member.rankId()));
    }

    /**
     * Whether a player holds a permission in this city.
     *
     * <p>The mayor always holds every permission regardless of their rank's bitmask, so a
     * mayor cannot lock themselves out by editing their own rank.
     */
    public boolean hasPermission(UUID uuid, CityPermission permission) {
        if (isMayor(uuid)) {
            return true;
        }
        return rankOf(uuid).map(rank -> rank.has(permission)).orElse(false);
    }

    /** The effective permission set for a player, all flags for the mayor. */
    public PermissionSet permissionsOf(UUID uuid) {
        if (isMayor(uuid)) {
            return PermissionSet.ALL;
        }
        return rankOf(uuid).map(CityRank::permissions).orElse(PermissionSet.NONE);
    }

    /** The effective weight for a player, {@link CityRank#MAYOR_WEIGHT} for the mayor. */
    public int weightOf(UUID uuid) {
        if (isMayor(uuid)) {
            return CityRank.MAYOR_WEIGHT;
        }
        return rankOf(uuid).map(CityRank::weight).orElse(Integer.MIN_VALUE);
    }

    // --- bans -------------------------------------------------------------------------

    public boolean isBanned(UUID uuid) {
        return bans.containsKey(uuid);
    }

    /** @return the ban reason, which may be empty even when the player is banned */
    public Optional<String> banReason(UUID uuid) {
        return Optional.ofNullable(bans.get(uuid));
    }

    public Collection<UUID> bannedPlayers() {
        return Collections.unmodifiableCollection(bans.keySet());
    }

    void putBan(UUID uuid, String reason) {
        bans.put(uuid, reason == null ? "" : reason);
    }

    void removeBan(UUID uuid) {
        bans.remove(uuid);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof City city && city.id == id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    @Override
    public String toString() {
        return "City[" + id + " " + name + "]";
    }
}
