package dev.civitas.core.onboarding;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.dao.OnboardingDao;
import dev.civitas.util.Result;

/**
 * SPEC 34.3's starter chain: five steps, once per account.
 *
 * <h2>Paid at most once, whatever fires the trigger</h2>
 *
 * <p>Several of these steps hang off events a player can repeat freely — selling, teleporting,
 * walking into a city. The primary key on {@code (uuid, step)} is what makes that safe: a second
 * completion writes nothing, and {@link #complete} reports it as already done rather than paying
 * again. Guarding it in the service alone would lose the race between two events in one tick.
 *
 * <h2>Never a gate</h2>
 *
 * <p>SPEC 34.2: "No forced tutorial, ever. The player can walk away from all of it." Nothing here
 * blocks anything — the chain pays for doing things a player was going to do anyway, and a player
 * who ignores it loses 5,000 C and nothing else.
 */
public final class OnboardingService {

    private final OnboardingDao dao;
    private final EconomyService economy;
    private final ConfigManager configs;
    private final Logger logger;

    public OnboardingService(OnboardingDao dao, EconomyService economy, ConfigManager configs,
                             Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean enabled() {
        return configs.get(ConfigFile.ONBOARDING).getBoolean("onboarding.starter-quests-enabled", true);
    }

    /** What SPEC 34.3's table pays for a step, as a config key. */
    public BigDecimal rewardFor(StarterStep step) {
        String raw = configs.get(ConfigFile.ONBOARDING)
                .getString(step.rewardKey());
        if (raw == null || raw.isBlank()) {
            return step.defaultReward();
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            logger.warning(step.rewardKey() + " is not a number; using SPEC 34.3's default.");
            return step.defaultReward();
        }
    }

    public CompletableFuture<Set<StarterStep>> completed(UUID player) {
        return dao.findCompleted(player).thenApply(keys -> {
            Set<StarterStep> steps = EnumSet.noneOf(StarterStep.class);
            keys.forEach(key -> StarterStep.byKey(key).ifPresent(steps::add));
            return steps;
        });
    }

    /** The next step a player has not done, or empty when the chain is finished. */
    public CompletableFuture<Optional<StarterStep>> nextStep(UUID player) {
        return completed(player).thenApply(done -> {
            for (StarterStep step : StarterStep.values()) {
                if (!done.contains(step)) {
                    return Optional.of(step);
                }
            }
            return Optional.empty();
        });
    }

    /** What a completion produced, so the caller can say so without a second read. */
    public record Completion(StarterStep step, BigDecimal reward, boolean wasNew,
                             boolean chainFinished) {
    }

    /**
     * Marks a step done and pays for it.
     *
     * <p>The insert and the payment share a transaction. A step recorded and not paid is money the
     * player earned and will never see; a step paid and not recorded is a step they can farm.
     *
     * @return a completion with {@code wasNew} false when the step was already done, which is the
     *         common case for the triggers a player repeats
     */
    public CompletableFuture<Result<Completion>> complete(UUID player, StarterStep step,
                                                          long now) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(
                    Result.success(new Completion(step, BigDecimal.ZERO, false, false)));
        }

        BigDecimal reward = rewardFor(step);
        return dao.transaction(connection -> {
            if (dao.insertIfAbsent(connection, player, step.name(), now) == 0) {
                return Result.success(new Completion(step, BigDecimal.ZERO, false, false));
            }
            Result<BigDecimal> paid = economy.deposit(connection, player, reward,
                    TransactionType.QUEST_REWARD, null,
                    "{\"starter\":\"" + step.configKey() + "\"}");
            if (paid instanceof Result.Failure<BigDecimal> failure) {
                return Result.<Completion>propagate(failure);
            }
            boolean finished = dao.countCompleted(connection, player) >= StarterStep.values().length;
            return Result.success(new Completion(step, reward, true, finished));
        });
    }

    /** Fire-and-forget for the listeners, which have nothing useful to do with a failure. */
    public void completeQuietly(UUID player, StarterStep step, long now,
                                java.util.function.Consumer<Completion> then) {
        try {
            complete(player, step, now).whenComplete((result, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Starter step " + step + " failed for " + player,
                            error);
                    return;
                }
                if (result instanceof Result.Success<Completion>(Completion completion)
                        && completion.wasNew()) {
                    then.accept(completion);
                }
            });
        } catch (RuntimeException e) {
            // db.call throws synchronously on a closed pool, so exceptionally alone is not enough.
            logger.log(Level.WARNING, "Starter step " + step + " failed for " + player, e);
        }
    }
}
