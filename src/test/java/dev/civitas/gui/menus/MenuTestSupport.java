package dev.civitas.gui.menus;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.CivitasServices;
import dev.civitas.config.PluginResources;
import dev.civitas.core.city.CityHall;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.city.SpawnService;
import dev.civitas.core.economy.UpkeepTask;
import dev.civitas.core.income.ChallengeService;
import dev.civitas.core.income.IncomeMultipliers;
import dev.civitas.core.income.QuestPool;
import dev.civitas.core.income.QuestService;
import dev.civitas.core.outpost.OutpostRegistry;
import dev.civitas.core.outpost.OutpostService;
import dev.civitas.core.outpost.OutpostTeleport;
import dev.civitas.core.defense.DefenseBehaviour;
import dev.civitas.core.defense.DefenseCatalogue;
import dev.civitas.core.defense.DefenseRegistry;
import dev.civitas.core.defense.DefenseService;
import dev.civitas.core.defense.DefenseSpawner;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.vault.VaultService;
import dev.civitas.core.vault.VaultView;
import dev.civitas.gui.framework.AmountInput;
import dev.civitas.gui.framework.LayoutLoader;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.lang.LangManager;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Scheduler;
import org.bukkit.plugin.Plugin;

/**
 * A whole services record over the real city stack, so the SPEC Section 8 screens can be
 * opened and clicked in tests.
 *
 * <p>Built from {@link CityTestSupport} rather than duplicating it, so the screens are driven
 * by the same services the server runs, over the same SQLite database and the same shipped
 * configuration.
 */
final class MenuTestSupport implements AutoCloseable {

    final CityTestSupport cities;
    final MenuManager menus;
    final AmountInput input;
    final CivitasServices services;
    final LangManager lang;

    private MenuTestSupport(Path directory, Plugin plugin) {
        this.cities = CityTestSupport.open(directory);

        File dataFolder = directory.resolve("plugin").toFile();
        PluginResources resources = PluginResources.ofClasspath(dataFolder, quiet());
        this.lang = new LangManager(resources, cities.configs);
        lang.load();

        this.menus = new MenuManager(cities.configs, lang);
        this.input = new AmountInput(menus, lang, Scheduler.direct());

        LayoutLoader layouts = new LayoutLoader(resources);
        UpkeepTask upkeep = new UpkeepTask(cities.db, cities.daos, cities.registry, cities.claims,
                cities.treasury, cities.upkeep, (member, key, extra) -> { }, Scheduler.direct(),
                quiet(), java.time.ZoneId.of("UTC"));
        IncomeMultipliers multipliers = new IncomeMultipliers(cities.configs);
        QuestPool questPool = new QuestPool(cities.configs, quiet());
        questPool.load("income.quests.pool");
        QuestPool challengePool = new QuestPool(cities.configs, quiet());
        challengePool.load("income.challenges.pool");
        QuestService quests = new QuestService(cities.db, cities.daos.playerQuests(),
                cities.daos.players(), cities.economy, questPool, multipliers, cities.configs,
                (player, key, extra) -> { }, java.time.ZoneId.of("UTC"));
        ChallengeService challenges = new ChallengeService(cities.db,
                cities.daos.cityChallenges(), cities.registry, cities.treasury, challengePool,
                cities.configs, (player, key, extra) -> { }, java.time.ZoneId.of("UTC"));

        UpgradeService upgradeService = new UpgradeService(cities.db,
                cities.daos.cityUpgrades(), cities.treasury, cities.configs, Scheduler.direct());
        VaultService vaultService = new VaultService(cities.daos.cityVault(), upgradeService,
                cities.configs);
        VaultView vaultView = new VaultView(plugin, vaultService, lang, quiet());
        cities.cities.useUpgrades(upgradeService);
        cities.market.useUpgrades(cities.registry, upgradeService);

        OutpostRegistry outpostRegistry = new OutpostRegistry(cities.daos.outposts());
        OutpostService outposts = new OutpostService(cities.db, cities.daos, cities.registry,
                cities.claimRegistry, cities.claims, outpostRegistry, cities.treasury,
                cities.configs, Scheduler.direct());
        outposts.useUpgrades(upgradeService);
        OutpostTeleport outpostTeleport = new OutpostTeleport(plugin, outposts, cities.economy,
                cities.configs, lang);

        DefenseCatalogue defenseCatalogue = new DefenseCatalogue(cities.configs, quiet());
        defenseCatalogue.load();
        DefenseRegistry defenseRegistry = new DefenseRegistry(cities.daos.defenseUnits());
        DefenseService defenseService = new DefenseService(plugin, cities.db,
                cities.daos.defenseUnits(), defenseRegistry, defenseCatalogue,
                new DefenseSpawner(plugin, defenseCatalogue, lang), cities.registry,
                cities.claimRegistry, cities.treasury, upgradeService, lang, Scheduler.direct());

        SpawnService spawns = new SpawnService(plugin, cities.registry, cities.configs, lang);
        CityHall halls = new CityHall(plugin, cities.configs, lang);

        this.services = new CivitasServices(cities.registry, cities.cities, cities.ranks,
                cities.claimRegistry, cities.claims, null, null, cities.protection, null, null,
                cities.economy, cities.treasury, cities.upkeep, upkeep, cities.market,
                cities.marketFilter, cities.shops, quests, challenges, outposts,
                outpostTeleport, upgradeService, defenseService, vaultService,
                vaultView, menus, layouts,
                input, spawns, halls,
                cities.accounts, new PlayerLookup(cities.daos.players()), Scheduler.direct());
    }

    static MenuTestSupport open(Path directory, Plugin plugin) {
        return new MenuTestSupport(directory, plugin);
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("civitas-menu-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    @Override
    public void close() {
        cities.close();
    }
}
