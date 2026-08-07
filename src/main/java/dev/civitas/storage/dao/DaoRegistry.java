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
    private final BountyDao bounties;
    private final AllianceDao alliances;
    private final TruceDao truces;
    private final MarketStockDao marketStock;
    private final PlayerShopDao playerShops;
    private final PlayerQuestDao playerQuests;
    private final CityChallengeDao cityChallenges;
    private final ContestDao contests;
    private final ContestEntryDao contestEntries;
    private final ContestVoteDao contestVotes;
    private final CityUpgradeDao cityUpgrades;
    private final CityVaultDao cityVault;
    private final DefenseUnitDao defenseUnits;
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
        this.bounties = new BountyDao(db);
        this.alliances = new AllianceDao(db);
        this.truces = new TruceDao(db);
        this.marketStock = new MarketStockDao(db);
        this.playerShops = new PlayerShopDao(db);
        this.playerQuests = new PlayerQuestDao(db);
        this.cityChallenges = new CityChallengeDao(db);
        this.contests = new ContestDao(db);
        this.contestEntries = new ContestEntryDao(db);
        this.contestVotes = new ContestVoteDao(db);
        this.cityUpgrades = new CityUpgradeDao(db);
        this.cityVault = new CityVaultDao(db);
        this.defenseUnits = new DefenseUnitDao(db);
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

    public BountyDao bounties() {
        return bounties;
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

    public AuditLogDao auditLog() {
        return auditLog;
    }

    /** Every DAO, in no particular order. Used by tests that assert across the whole set. */
    public List<Dao<?>> all() {
        return List.of(players, playerStats, playerLogins, serverEvents, warChunkHashes, warRollbackIssues, cities, cityRanks, cityMembers, cityInvites, cityBans, claims, outposts,
                ledger, economySnapshots, wars, warBlockLog, warContainerLog, warParticipants, warKills,
                warEntitySnapshots, bounties, alliances,
                truces, marketStock, playerShops, playerQuests, cityChallenges, contests, contestEntries, contestVotes,
                cityUpgrades, cityVault, defenseUnits, auditLog);
    }
}
