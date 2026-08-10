package dev.civitas.storage.dao;

import java.util.List;
import java.util.Objects;

import dev.civitas.storage.DatabaseManager;

/**
 * Every table DAO, constructed once and handed to the services that need them.
 *
 * <p>Services take the DAOs they use through their constructors rather than reaching into
 * this registry, so a service's dependencies stay visible in its signature. The registry
 * exists to own construction and to give tests a single place to build the whole set.
 */
public final class DaoRegistry {

    private final PlayerDao players;
    private final PlayerStatDao playerStats;
    private final PlayerLoginDao playerLogins;
    private final ServerEventDao serverEvents;
    private final WarChunkHashDao warChunkHashes;
    private final WarRollbackIssueDao warRollbackIssues;
    private final CityDao cities;
    private final CityRankDao cityRanks;
    private final CityMemberDao cityMembers;
    private final CityInviteDao cityInvites;
    private final CityBanDao cityBans;
    private final ClaimDao claims;
    private final OutpostDao outposts;
    private final LedgerDao ledger;
    private final EconomySnapshotDao economySnapshots;
    private final WarDao wars;
    private final WarBlockLogDao warBlockLog;
    private final WarContainerLogDao warContainerLog;
    private final WarParticipantDao warParticipants;
    private final WarKillDao warKills;
    private final WarEntitySnapshotDao warEntitySnapshots;
    private final SiegeCampDao siegeCamps;
    private final SiegeUnitDao siegeUnits;
    private final BountyDao bounties;
    private final PlayerNoticeDao playerNotices;
    private final ProtectedChunkDao protectedChunks;
    private final ReportDao reports;
    private final UpkeepMultiplierDao upkeepMultipliers;
    private final AllianceDao alliances;
    private final TruceDao truces;
    private final MarketStockDao marketStock;
    private final SellQuotaDao sellQuota;
    private final DailyActivityDao dailyActivity;
    private final PlayerToggleDao playerToggles;
    private final WarpDao warps;
    private final MiningClaimDao miningClaims;
    private final WaystationDao waystations;
    private final PlayerShopDao playerShops;
    private final PlayerQuestDao playerQuests;
    private final CityChallengeDao cityChallenges;
    private final ContestDao contests;
    private final ContestEntryDao contestEntries;
    private final ContestVoteDao contestVotes;
    private final CityUpgradeDao cityUpgrades;
    private final CityVaultDao cityVault;
    private final DefenseUnitDao defenseUnits;
    private final CityWardenDao cityWardens;
    private final AuditLogDao auditLog;

    public DaoRegistry(DatabaseManager db) {
        Objects.requireNonNull(db, "db");
        this.players = new PlayerDao(db);
        this.playerStats = new PlayerStatDao(db);
        this.playerLogins = new PlayerLoginDao(db);
        this.serverEvents = new ServerEventDao(db);
        this.warChunkHashes = new WarChunkHashDao(db);
        this.warRollbackIssues = new WarRollbackIssueDao(db);
        this.cities = new CityDao(db);
        this.cityRanks = new CityRankDao(db);
        this.cityMembers = new CityMemberDao(db);
        this.cityInvites = new CityInviteDao(db);
        this.cityBans = new CityBanDao(db);
        this.claims = new ClaimDao(db);
        this.outposts = new OutpostDao(db);
        this.ledger = new LedgerDao(db);
        this.economySnapshots = new EconomySnapshotDao(db);
        this.wars = new WarDao(db);
        this.warBlockLog = new WarBlockLogDao(db);
        this.warContainerLog = new WarContainerLogDao(db);
        this.warParticipants = new WarParticipantDao(db);
        this.warKills = new WarKillDao(db);
        this.warEntitySnapshots = new WarEntitySnapshotDao(db);
        this.siegeCamps = new SiegeCampDao(db);
        this.siegeUnits = new SiegeUnitDao(db);
        this.bounties = new BountyDao(db);
        this.playerNotices = new PlayerNoticeDao(db);
        this.protectedChunks = new ProtectedChunkDao(db);
        this.reports = new ReportDao(db);
        this.upkeepMultipliers = new UpkeepMultiplierDao(db);
        this.alliances = new AllianceDao(db);
        this.truces = new TruceDao(db);
        this.marketStock = new MarketStockDao(db);
        this.sellQuota = new SellQuotaDao(db);
        this.dailyActivity = new DailyActivityDao(db);
        this.playerToggles = new PlayerToggleDao(db);
        this.warps = new WarpDao(db);
        this.miningClaims = new MiningClaimDao(db);
        this.waystations = new WaystationDao(db);
        this.playerShops = new PlayerShopDao(db);
        this.playerQuests = new PlayerQuestDao(db);
        this.cityChallenges = new CityChallengeDao(db);
        this.contests = new ContestDao(db);
        this.contestEntries = new ContestEntryDao(db);
        this.contestVotes = new ContestVoteDao(db);
        this.cityUpgrades = new CityUpgradeDao(db);
        this.cityVault = new CityVaultDao(db);
        this.defenseUnits = new DefenseUnitDao(db);
        this.cityWardens = new CityWardenDao(db);
        this.auditLog = new AuditLogDao(db);
    }

    public PlayerDao players() {
        return players;
    }

    public PlayerStatDao playerStats() {
        return playerStats;
    }

    public PlayerLoginDao playerLogins() {
        return playerLogins;
    }

    public ServerEventDao serverEvents() {
        return serverEvents;
    }

    public WarChunkHashDao warChunkHashes() {
        return warChunkHashes;
    }

    public WarRollbackIssueDao warRollbackIssues() {
        return warRollbackIssues;
    }

    public CityDao cities() {
        return cities;
    }

    public CityRankDao cityRanks() {
        return cityRanks;
    }

    public CityMemberDao cityMembers() {
        return cityMembers;
    }

    public CityInviteDao cityInvites() {
        return cityInvites;
    }

    public CityBanDao cityBans() {
        return cityBans;
    }

    public ClaimDao claims() {
        return claims;
    }

    public OutpostDao outposts() {
        return outposts;
    }

    public LedgerDao ledger() {
        return ledger;
    }

    public EconomySnapshotDao economySnapshots() {
        return economySnapshots;
    }

    public WarDao wars() {
        return wars;
    }

    public WarBlockLogDao warBlockLog() {
        return warBlockLog;
    }

    public WarContainerLogDao warContainerLog() {
        return warContainerLog;
    }

    public WarParticipantDao warParticipants() {
        return warParticipants;
    }

    public WarKillDao warKills() {
        return warKills;
    }

    public WarEntitySnapshotDao warEntitySnapshots() {
        return warEntitySnapshots;
    }

    public SiegeCampDao siegeCamps() {
        return siegeCamps;
    }

    public SiegeUnitDao siegeUnits() {
        return siegeUnits;
    }

    public BountyDao bounties() {
        return bounties;
    }

    /** SPEC 17.1 case 1's deferred notices. */
    public PlayerNoticeDao playerNotices() {
        return playerNotices;
    }

    public ProtectedChunkDao protectedChunks() {
        return protectedChunks;
    }

    public ReportDao reports() {
        return reports;
    }

    public UpkeepMultiplierDao upkeepMultipliers() {
        return upkeepMultipliers;
    }

    public AllianceDao alliances() {
        return alliances;
    }

    public TruceDao truces() {
        return truces;
    }

    public MarketStockDao marketStock() {
        return marketStock;
    }

    /** {@code player_sell_quota}, the SPEC 21.5 daily sell quota. */
    public SellQuotaDao sellQuota() {
        return sellQuota;
    }

    /** {@code player_daily_activity}, SPEC 21.4 F12's active-playtime-today baseline. */
    public DailyActivityDao dailyActivity() {
        return dailyActivity;
    }

    /** {@code player_toggles}, SPEC 23.6's notification preferences. */
    public PlayerToggleDao playerToggles() {
        return playerToggles;
    }

    /** {@code warps}, SPEC 32.7's public warps. */
    public WarpDao warps() {
        return warps;
    }

    /** {@code mining_claims}, SPEC 32.6's personal claims in the resource worlds. */
    public WaystationDao waystations() {
        return waystations;
    }

    public MiningClaimDao miningClaims() {
        return miningClaims;
    }

    public PlayerShopDao playerShops() {
        return playerShops;
    }

    public PlayerQuestDao playerQuests() {
        return playerQuests;
    }

    public CityChallengeDao cityChallenges() {
        return cityChallenges;
    }

    public ContestDao contests() {
        return contests;
    }

    public ContestEntryDao contestEntries() {
        return contestEntries;
    }

    public ContestVoteDao contestVotes() {
        return contestVotes;
    }

    public CityUpgradeDao cityUpgrades() {
        return cityUpgrades;
    }

    public CityVaultDao cityVault() {
        return cityVault;
    }

    public DefenseUnitDao defenseUnits() {
        return defenseUnits;
    }

    /** SPEC 28's City Warden, one row per city that owns one. */
    public CityWardenDao cityWardens() {
        return cityWardens;
    }

    public AuditLogDao auditLog() {
        return auditLog;
    }

    /** Every DAO, in no particular order. Used by tests that assert across the whole set. */
    public List<Dao<?>> all() {
        return List.of(players, playerStats, playerLogins, serverEvents, warChunkHashes, warRollbackIssues, cities, cityRanks, cityMembers, cityInvites, cityBans, claims, outposts,
                ledger, economySnapshots, wars, warBlockLog, warContainerLog, warParticipants, warKills,
                warEntitySnapshots, siegeCamps, siegeUnits, bounties, protectedChunks, reports,
                upkeepMultipliers, alliances,
                truces, marketStock, sellQuota, dailyActivity, playerToggles, warps, miningClaims, waystations, playerShops, playerQuests, cityChallenges, contests, contestEntries, contestVotes,
                cityUpgrades, cityVault, defenseUnits, cityWardens, auditLog);
    }
}
