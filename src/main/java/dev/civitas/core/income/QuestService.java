package dev.civitas.core.income;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.PlayerQuestDao;
import dev.civitas.storage.dao.PlayerDao;
import dev.civitas.storage.row.PlayerQuestRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.Result;

/**
 * Daily quests, SPEC 13.1.
 *
 * <p>Three a day, drawn once and remembered, progressed by whatever the player does, and paid
 * out the moment the target is met rather than at some collection screen: a reward a player
 * has to remember to claim is a reward half of them never see.
 *
 * <p>Assignment is idempotent. Two events in the same tick, a relog, and a second server all
 * ask for today's quests and all get the same three, because the draw is seeded and the write
 * is guarded by what is already stored for today.
 */
public final class QuestService {

    private final DatabaseManager db;
    private final PlayerQuestDao quests;
    private final PlayerDao players;
    private final EconomyService economy;
    private final QuestPool pool;
    private final IncomeMultipliers multipliers;
    private final ConfigManager configs;
    private final StipendTask.Notifier notifier;
    private final ZoneId zone;

    /** Today's quests per player, so a block break costs no database read. */
    private final Map<UUID, List<PlayerQuestRow>> active = new ConcurrentHashMap<>();

    public QuestService(DatabaseManager db, PlayerQuestDao quests, PlayerDao players,
                        EconomyService economy, QuestPool pool, IncomeMultipliers multipliers,
                        ConfigManager configs, StipendTask.Notifier notifier, ZoneId zone) {
        this.db = Objects.requireNonNull(db, "db");
        this.quests = Objects.requireNonNull(quests, "quests");
        this.players = Objects.requireNonNull(players, "players");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.multipliers = Objects.requireNonNull(multipliers, "multipliers");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public QuestPool pool() {
        return pool;
    }

    // ==================================================================================
    // Assignment
    // ==================================================================================

    /**
     * Today's quests for a player, assigning them if they have none yet.
     *
     * <p>Called on join and by {@code /quests}. Safe to call repeatedly.
     */
    public CompletableFuture<List<PlayerQuestRow>> todaysQuests(UUID player, long now) {
        if (pool.isEmpty() || !enabled()) {
            return CompletableFuture.completedFuture(List.of());
        }
        long dayStart = startOfDay(now);

        return db.transaction(connection -> {
            List<PlayerQuestRow> existing = quests.findForPlayer(connection, player, dayStart);
            if (!existing.isEmpty()) {
                return existing;
            }

            Optional<PlayerRow> found = players.findByUuid(connection, player);
            if (found.isEmpty()) {
                return List.<PlayerQuestRow>of();
            }
            long playtime = found.get().activePlaytimeMs();

            List<QuestDefinition> drawn = pool.draw(perDay(), seedFor(player, now));
            List<PlayerQuestRow> assigned = new ArrayList<>(drawn.size());

            for (QuestDefinition definition : drawn) {
                long target = pool.targetFor(definition, playtime);
                BigDecimal reward = pool.rewardFor(definition, playtime,
                        seedFor(player, now) + definition.id().hashCode());

                PlayerQuestRow row = new PlayerQuestRow(0, player, definition.id(), 0, now,
                        null, target, reward);
                long id = quests.insert(connection, row);
                assigned.add(new PlayerQuestRow(id, player, definition.id(), 0, now, null,
                        target, reward));
            }
            return assigned;
        }).thenApply(rows -> {
            active.put(player, List.copyOf(rows));
            return rows;
        });
    }

    /** What is cached for this player right now, without touching the database. */
    public List<PlayerQuestRow> cached(UUID player) {
        return active.getOrDefault(player, List.of());
    }

    public void forget(UUID player) {
        active.remove(player);
    }

    // ==================================================================================
    // Progress
    // ==================================================================================

    /**
     * Reports something a player did.
     *
     * <p>Cheap and safe to call from a block-break listener: it reads the cache, does nothing
     * at all if no live quest counts this metric, and only then touches the database.
     *
     * @param amount how much of the metric, in whole units or whole coins
     */
    public void report(UUID player, QuestMetric metric, long amount) {
        if (amount <= 0) {
            return;
        }
        List<PlayerQuestRow> mine = active.get(player);
        if (mine == null || mine.isEmpty()) {
            return;
        }

        for (PlayerQuestRow row : mine) {
            if (row.isClaimed()) {
                continue;
            }
            QuestDefinition definition = pool.byId(row.questId()).orElse(null);
            if (definition == null || definition.metric() != metric) {
                continue;
            }
            advance(player, row, (int) Math.min(Integer.MAX_VALUE, amount));
        }
    }

    private void advance(UUID player, PlayerQuestRow row, int delta) {
        quests.addProgress(row.id(), delta).thenCompose(updated -> {
            long progress = row.progress() + (long) delta;
            if (progress < row.target()) {
                replaceCached(player, row, progress);
                return CompletableFuture.completedFuture(null);
            }
            return complete(player, row);
        });
    }

    /**
     * Pays a finished quest.
     *
     * <p>The completion stamp is written first, conditionally on it being unset, and the
     * money only moves if that write actually changed a row. Two events finishing the same
     * quest in the same tick therefore pay once.
     */
    private dev.civitas.core.events.EventEffects events;

    /** SPEC 13.5 Harvest Festival and Gold Rush, which pay more for one quest category. */
    public void useEvents(dev.civitas.core.events.EventEffects effects) {
        this.events = effects;
    }

    /**
     * The event multiplier for the category this quest belongs to.
     *
     * <p>Looked up through the quest pool rather than stored on the assignment, because SPEC
     * 13.5's events start and end after quests are handed out: a farming quest assigned this
     * morning must pay double if a Harvest Festival begins this afternoon.
     */
    private BigDecimal eventMultiplierFor(String questId) {
        if (events == null) {
            return BigDecimal.ONE;
        }
        return pool.byId(questId)
                .map(definition -> events.questRewardMultiplier(definition.category()))
                .orElse(BigDecimal.ONE);
    }

    private CompletableFuture<Void> complete(UUID player, PlayerQuestRow row) {
        long now = System.currentTimeMillis();
        return quests.markCompleted(row.id(), now).thenCompose(changed -> {
            if (changed == 0) {
                return CompletableFuture.completedFuture(null);
            }
            return payout(player, row, now);
        });
    }

    private CompletableFuture<Void> payout(UUID player, PlayerQuestRow row, long now) {
        return db.transaction(connection -> {
            Optional<PlayerRow> found = players.findByUuid(connection, player);
            if (found.isEmpty()) {
                return Result.<BigDecimal>failure("NO_PLAYER_RECORD", "economy.no-account");
            }
            PlayerRow account = found.get();

            // SPEC 13.5's Harvest Festival and Gold Rush double the reward of one quest
            // category. Applied before the SPEC 4.2 newcomer multiplier, so the two compound
            // in the player's favour rather than one replacing the other.
            BigDecimal base = row.reward().multiply(eventMultiplierFor(row.questId()));
            BigDecimal amount = multipliers.apply(base, account,
                    account.activePlaytimeMs(), now);
            if (amount.signum() <= 0) {
                // SPEC 17.6 case 70: a quest completed by an account too new to earn pays
                // nothing. It still counts as done, so the alt cannot retry it.
                return Result.<BigDecimal>failure("TOO_NEW", "income.too-new",
                        Map.of("minutes",
                                String.valueOf(multipliers.minimumPlaytimeMillis() / 60_000L)));
            }
            return economy.deposit(connection, player, amount, TransactionType.QUEST_REWARD,
                    null, "{\"quest\":\"" + row.questId() + "\"}");
        }).thenAccept(result -> {
            markCachedComplete(player, row);
            if (result instanceof Result.Success<BigDecimal>) {
                notifier.tell(player, "income.quest.completed",
                        LangManager.placeholder("quest",
                                pool.byId(row.questId()).map(QuestDefinition::messageKey)
                                        .orElse(row.questId())),
                        LangManager.placeholder("amount", row.reward().toPlainString()));
            }
        });
    }

    private void replaceCached(UUID player, PlayerQuestRow row, long progress) {
        active.computeIfPresent(player, (key, rows) -> rows.stream()
                .map(existing -> existing.id() == row.id()
                        ? new PlayerQuestRow(existing.id(), existing.uuid(), existing.questId(),
                                (int) Math.min(Integer.MAX_VALUE, progress), existing.assignedAt(),
                                existing.completedAt(), existing.target(), existing.reward())
                        : existing)
                .toList());
    }

    private void markCachedComplete(UUID player, PlayerQuestRow row) {
        long now = System.currentTimeMillis();
        active.computeIfPresent(player, (key, rows) -> rows.stream()
                .map(existing -> existing.id() == row.id()
                        ? new PlayerQuestRow(existing.id(), existing.uuid(), existing.questId(),
                                (int) existing.target(), existing.assignedAt(), now,
                                existing.target(), existing.reward())
                        : existing)
                .toList());
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /**
     * The seed for a player's daily draw.
     *
     * <p>UUID and date, so the same player gets the same three quests all day however many
     * times they relog, and a different three tomorrow.
     */
    private long seedFor(UUID player, long now) {
        LocalDate date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return player.getMostSignificantBits() * 31L
                + player.getLeastSignificantBits()
                + date.toEpochDay() * 1_000_003L;
    }

    private long startOfDay(long now) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli();
    }

    public int perDay() {
        return configs.get(ConfigFile.ECONOMY).getInt("income.quests.per-day", 3);
    }

    public boolean enabled() {
        return configs.get(ConfigFile.ECONOMY).getBoolean("income.quests.enabled", true);
    }
}
