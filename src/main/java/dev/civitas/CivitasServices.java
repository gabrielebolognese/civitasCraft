package dev.civitas;

import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.city.CityService;
import dev.civitas.core.city.CityHall;
import dev.civitas.core.city.RankService;
import dev.civitas.core.city.SpawnService;
import dev.civitas.core.claim.BorderRenderer;
import dev.civitas.core.claim.ClaimMap;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.PlayerAccountService;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.core.economy.UpkeepCalculator;
import dev.civitas.core.economy.UpkeepTask;
import dev.civitas.core.income.ChallengeService;
import dev.civitas.core.income.QuestService;
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
        UpkeepCalculator upkeep,
        UpkeepTask upkeepTask,
        MarketService market,
        MarketItemFilter marketFilter,
        PlayerShopService shops,
        QuestService quests,
        ChallengeService challenges,
        MenuManager menus,
        LayoutLoader layouts,
        AmountInput amountInput,
        SpawnService spawns,
        CityHall cityHall,
        PlayerAccountService accounts,
        PlayerLookup lookup,
        Scheduler scheduler) {
}
