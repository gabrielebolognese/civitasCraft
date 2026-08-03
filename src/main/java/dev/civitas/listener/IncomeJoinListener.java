package dev.civitas.listener;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.income.ChallengeService;
import dev.civitas.core.income.DailyLoginService;
import dev.civitas.core.income.QuestService;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * What happens to a player's income the moment they log in.
 *
 * <p>Three things: today's quests are assigned if they have none, this week's challenges are
 * assigned to their city if it has none, and the SPEC 4.2 daily login is claimed
 * automatically. That last is deliberate. A daily reward a player has to remember to claim is
 * a reward that rewards remembering, and SPEC 4.2 lists it as an income source rather than as
 * a minigame.
 *
 * <p>All three run after the account row exists, which is why this listener sits behind
 * {@code PlayerAccountListener} at a later priority and does its work asynchronously.
 */
public final class IncomeJoinListener implements Listener {

    private final QuestService quests;
    private final ChallengeService challenges;
    private final DailyLoginService dailyLogin;
    private final CityRegistry cities;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public IncomeJoinListener(QuestService quests, ChallengeService challenges,
                              DailyLoginService dailyLogin, CityRegistry cities,
                              LangManager lang, Scheduler scheduler, Logger logger) {
        this.quests = Objects.requireNonNull(quests, "quests");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.dailyLogin = Objects.requireNonNull(dailyLogin, "dailyLogin");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();

        // The account row is created by PlayerAccountListener on the same event, and both
        // are async, so the daily claim waits for the quests call which reads the same row.
        quests.todaysQuests(player.getUniqueId(), now)
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not assign quests for "
                            + player.getName(), error);
                    return null;
                })
                .thenRun(() -> claimDaily(player, now));

        cities.cityOf(player.getUniqueId()).ifPresent(city ->
                challenges.thisWeek(city.id(), now).exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not assign challenges for "
                            + city.name(), error);
                    return null;
                }));
    }

    private void claimDaily(Player player, long now) {
        dailyLogin.claim(player.getUniqueId(), now)
                .whenComplete((result, error) -> scheduler.runOnMain(() -> {
                    if (error != null || !player.isOnline()) {
                        return;
                    }
                    if (result instanceof Result.Success<DailyLoginService.Claim> success
                            && success.value() != null) {
                        DailyLoginService.Claim claim = success.value();
                        lang.send(player, "income.daily.claimed",
                                LangManager.placeholder("amount",
                                        claim.amount().toPlainString()),
                                LangManager.placeholder("streak",
                                        String.valueOf(claim.streak())));
                    }
                    // A refusal is silent. "You already claimed today" on every relog would
                    // be noise, and "you are too new to earn" is not something to greet a
                    // brand-new player with.
                }));
    }
}
