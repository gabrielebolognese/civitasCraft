package dev.civitas;

import dev.civitas.core.admin.AdminProtection;
import dev.civitas.core.admin.AuditService;
import dev.civitas.core.admin.FraudHeuristics;
import dev.civitas.core.admin.InspectMode;
import dev.civitas.core.admin.LedgerExport;
import dev.civitas.core.admin.UpkeepOverrides;
import dev.civitas.core.moderation.ReportService;
import dev.civitas.storage.BackupService;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.city.CityService;
import dev.civitas.core.city.CityHall;
import dev.civitas.core.city.RankService;
import dev.civitas.core.city.SpawnService;
import dev.civitas.core.claim.BorderRenderer;
import dev.civitas.core.claim.ClaimMap;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.core.economy.BountyService;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.LedgerRollback;
import dev.civitas.core.economy.PlayerAccountService;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.core.economy.UpkeepCalculator;
import dev.civitas.core.economy.UpkeepTask;
import dev.civitas.core.income.ChallengeService;
import dev.civitas.core.income.QuestService;
import dev.civitas.core.progression.LeaderboardService;
import dev.civitas.core.progression.StatsService;
import dev.civitas.core.outpost.OutpostService;
import dev.civitas.core.outpost.OutpostTravel;
import dev.civitas.core.defense.DefenseService;
import dev.civitas.core.contest.ContestService;
import dev.civitas.core.diplomacy.DiplomacyService;
import dev.civitas.core.events.EventService;
import dev.civitas.core.war.CapturePoints;
import dev.civitas.core.war.RollbackEngine;
import dev.civitas.core.war.War;
import dev.civitas.core.war.PeaceOffer;
import dev.civitas.core.war.WarAllies;
import dev.civitas.core.war.WarScoreboard;
import dev.civitas.core.war.WarService;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.vault.VaultService;
import dev.civitas.core.vault.VaultView;
import dev.civitas.core.market.MarketItemFilter;
import dev.civitas.core.market.MarketService;
import dev.civitas.core.protection.BlockClassifier;
import dev.civitas.core.protection.ProtectionGuard;
import dev.civitas.core.protection.ProtectionService;
import dev.civitas.core.shop.PlayerShopService;
import dev.civitas.gui.framework.AmountInput;
import dev.civitas.gui.framework.LayoutLoader;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Scheduler;

/**
 * The services that exist only once storage is open.
 *
 * <p>Commands are registered while the server is starting, but the database opens on an
 * async task, so a command can be typed before its service exists. Handing commands this
 * object through a supplier rather than the services directly makes that window explicit:
 * the supplier returns null until everything is ready, and a command that finds null says
 * so instead of throwing.
 */
public record CivitasServices(
        CityRegistry registry,
        CityService cities,
        RankService ranks,
        ClaimRegistry claimRegistry,
        ClaimService claims,
        ClaimMap map,
        BorderRenderer borders,
        ProtectionService protection,
        ProtectionGuard guard,
        BlockClassifier blockClassifier,
        EconomyService economy,
        TreasuryService treasury,
        BountyService bounties,
        LedgerRollback ledgerRollback,
        UpkeepCalculator upkeep,
        UpkeepTask upkeepTask,
        MarketService market,
        MarketItemFilter marketFilter,
        dev.civitas.msg.TogglePreferences toggles,
        dev.civitas.msg.Messenger messenger,
        dev.civitas.core.travel.TeleportService teleports,
        dev.civitas.core.travel.RandomTeleport randomTeleport,
        dev.civitas.core.travel.WarpService warps,
        dev.civitas.core.mining.MiningClaimService miningClaims,
        dev.civitas.core.waystation.WaystationService waystations,
        PlayerShopService shops,
        QuestService quests,
        ChallengeService challenges,
        LeaderboardService leaderboards,
        StatsService stats,
        ContestService contests,
        EventService events,
        WarService wars,
        WarAllies warAllies,
        PeaceOffer peaceOffers,
        CapturePoints capturePoints,
        RollbackEngine rollback,
        java.util.function.Consumer<War> rollbackTrigger,
        AuditService audit,
        AdminProtection adminProtection,
        FraudHeuristics fraud,
        InspectMode inspect,
        LedgerExport ledgerExport,
        UpkeepOverrides upkeepOverrides,
        ReportService reports,
        BackupService backups,
        int backupKeepCount,
        java.util.function.Supplier<java.util.List<String>> pendingMigrationSupplier,
        java.util.function.IntSupplier warBlockLogBufferedSupplier,
        java.util.function.Supplier<dev.civitas.core.admin.PerfReport> perfSupplier,
        dev.civitas.storage.dao.DaoRegistry daos,
        WarScoreboard warScoreboard,
        OutpostService outposts,
        OutpostTravel outpostTeleport,
        UpgradeService upgrades,
        DefenseService defense,
        dev.civitas.core.defense.WardenService warden,
        DiplomacyService diplomacy,
        VaultService vaults,
        VaultView vaultView,
        MenuManager menus,
        LayoutLoader layouts,
        AmountInput amountInput,
        SpawnService spawns,
        CityHall cityHall,
        PlayerAccountService accounts,
        PlayerLookup lookup,
        Scheduler scheduler) {

    /** Migrations discovered but not applied, for SPEC 9.4.6's {@code /ca migrate check}. */
    public java.util.List<String> pendingMigrations() {
        return pendingMigrationSupplier.get();
    }

    /** Rows waiting to be written by the war block logger, for {@code /ca perf}. */
    public int warBlockLogBuffered() {
        return warBlockLogBufferedSupplier.getAsInt();
    }

    /** A fresh reading of everything SPEC 9.4.6's {@code /ca perf} reports. */
    public dev.civitas.core.admin.PerfReport perf() {
        return perfSupplier.get();
    }
}
