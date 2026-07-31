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
    private final CityDao cities;
    private final CityRankDao cityRanks;
    private final CityMemberDao cityMembers;
    private final CityInviteDao cityInvites;
    private final ClaimDao claims;
    private final OutpostDao outposts;
    private final LedgerDao ledger;
    private final WarDao wars;
    private final WarBlockLogDao warBlockLog;
    private final WarContainerLogDao warContainerLog;
    private final WarParticipantDao warParticipants;
    private final WarKillDao warKills;
    private final AllianceDao alliances;
    private final TruceDao truces;
    private final MarketStockDao marketStock;
    private final PlayerQuestDao playerQuests;
    private final ContestDao contests;
    private final ContestEntryDao contestEntries;
    private final ContestVoteDao contestVotes;
    private final CityUpgradeDao cityUpgrades;
    private final DefenseUnitDao defenseUnits;
    private final AuditLogDao auditLog;

    public DaoRegistry(DatabaseManager db) {
        Objects.requireNonNull(db, "db");
        this.players = new PlayerDao(db);
        this.cities = new CityDao(db);
        this.cityRanks = new CityRankDao(db);
        this.cityMembers = new CityMemberDao(db);
        this.cityInvites = new CityInviteDao(db);
        this.claims = new ClaimDao(db);
        this.outposts = new OutpostDao(db);
        this.ledger = new LedgerDao(db);
        this.wars = new WarDao(db);
        this.warBlockLog = new WarBlockLogDao(db);
        this.warContainerLog = new WarContainerLogDao(db);
        this.warParticipants = new WarParticipantDao(db);
        this.warKills = new WarKillDao(db);
        this.alliances = new AllianceDao(db);
        this.truces = new TruceDao(db);
        this.marketStock = new MarketStockDao(db);
        this.playerQuests = new PlayerQuestDao(db);
        this.contests = new ContestDao(db);
        this.contestEntries = new ContestEntryDao(db);
        this.contestVotes = new ContestVoteDao(db);
        this.cityUpgrades = new CityUpgradeDao(db);
        this.defenseUnits = new DefenseUnitDao(db);
        this.auditLog = new AuditLogDao(db);
    }

    public PlayerDao players() {
        return players;
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

    public ClaimDao claims() {
        return claims;
    }

    public OutpostDao outposts() {
        return outposts;
    }

    public LedgerDao ledger() {
        return ledger;
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

    public AllianceDao alliances() {
        return alliances;
    }

    public TruceDao truces() {
        return truces;
    }

    public MarketStockDao marketStock() {
        return marketStock;
    }

    public PlayerQuestDao playerQuests() {
        return playerQuests;
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

    public DefenseUnitDao defenseUnits() {
        return defenseUnits;
    }

    public AuditLogDao auditLog() {
        return auditLog;
    }

    /** Every DAO, in no particular order. Used by tests that assert across the whole set. */
    public List<Dao<?>> all() {
        return List.of(players, cities, cityRanks, cityMembers, cityInvites, claims, outposts,
                ledger, wars, warBlockLog, warContainerLog, warParticipants, warKills, alliances,
                truces, marketStock, playerQuests, contests, contestEntries, contestVotes,
                cityUpgrades, defenseUnits, auditLog);
    }
}
