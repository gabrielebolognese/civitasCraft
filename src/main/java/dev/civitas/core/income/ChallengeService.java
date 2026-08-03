package dev.civitas.core.income;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityMember;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.CityChallengeDao;
import dev.civitas.storage.row.CityChallengeRow;
import dev.civitas.util.Result;

/**
 * Weekly challenges, SPEC 13.2.
 *
 * <p>Two a week per city, progress pooled across every member, reset Monday 00:00, and the
 * reward paid to the <em>treasury</em> rather than to whoever happened to finish it. That last
 * detail is the whole design: SPEC 13.2 exists to reward a city for doing something together,
 * and paying the individual would turn it into a race between members instead.
 *
 * <p>Assignment is keyed on city, challenge and week, and the database enforces that with a
 * unique index, so a challenge cannot be handed out or paid out twice in one week however
 * many members trigger the assignment at once.
 */
public final class ChallengeService {

    private final DatabaseManager db;
    private final CityChallengeDao challenges;
    private final CityRegistry cities;
    private final TreasuryService treasury;
    private final QuestPool pool;
    private final ConfigManager configs;
    private final StipendTask.Notifier notifier;
    private final ZoneId zone;

    /** This week's rows per city, so a block break costs no database read. */
    private final Map<Integer, List<CityChallengeRow>> active = new ConcurrentHashMap<>();

    public ChallengeService(DatabaseManager db, CityChallengeDao challenges, CityRegistry cities,
                            TreasuryService treasury, QuestPool pool, ConfigManager configs,
                            StipendTask.Notifier notifier, ZoneId zone) {
        this.db = Objects.requireNonNull(db, "db");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.pool = Objects.requireNonNull(pool, "pool");
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

    /** This week's challenges for a city, assigning them if the week is new. */
    public CompletableFuture<List<CityChallengeRow>> thisWeek(int cityId, long now) {
        if (pool.isEmpty() || !enabled()) {
            return CompletableFuture.completedFuture(List.of());
        }
        long weekStart = startOfWeek(now);

        return db.transaction(connection -> {
            List<CityChallengeRow> existing =
                    challenges.findForWeek(connection, cityId, weekStart);
            if (!existing.isEmpty()) {
                return existing;
            }

            List<QuestDefinition> drawn = pool.draw(perWeek(), weekStart * 31L + cityId);
            List<CityChallengeRow> assigned = new ArrayList<>(drawn.size());

            for (QuestDefinition definition : drawn) {
                BigDecimal reward = rewardFor(definition, weekStart + definition.id().hashCode());
                CityChallengeRow row = new CityChallengeRow(0, cityId, definition.id(), 0,
                        definition.baseTarget(), reward, weekStart, null);
                long id = challenges.insert(connection, row);
                assigned.add(new CityChallengeRow(id, cityId, definition.id(), 0,
                        definition.baseTarget(), reward, weekStart, null));
            }
            return assigned;
        }).thenApply(rows -> {
            active.put(cityId, List.copyOf(rows));
            return rows;
        });
    }

    public List<CityChallengeRow> cached(int cityId) {
        return active.getOrDefault(cityId, List.of());
    }

    public void forget(int cityId) {
        active.remove(cityId);
    }

    // ==================================================================================
    // Progress
    // ==================================================================================

    /**
     * Reports something a member did, on behalf of their city.
     *
     * <p>A player with no city reports nothing, which is why this takes a player rather than
     * a city: the caller is a listener that knows who acted and should not have to work out
     * whether it counts.
     */
    public void report(UUID player, QuestMetric metric, long amount) {
        if (amount <= 0) {
            return;
        }
        City city = cities.cityOf(player).orElse(null);
        if (city == null) {
            return;
        }
        List<CityChallengeRow> rows = active.get(city.id());
        if (rows == null || rows.isEmpty()) {
            return;
        }

        for (CityChallengeRow row : rows) {
            if (row.isComplete()) {
                continue;
            }
            QuestDefinition definition = pool.byId(row.challengeId()).orElse(null);
            if (definition == null || definition.metric() != metric) {
                continue;
            }
            advance(city, row, amount);
        }
    }

    private void advance(City city, CityChallengeRow row, long delta) {
        challenges.addProgress(row.id(), delta).thenCompose(updated -> {
            long progress = row.progress() + delta;
            if (progress < row.target()) {
                replaceCached(city.id(), row, progress);
                return CompletableFuture.completedFuture(null);
            }
            return complete(city, row);
        });
    }

    /** Pays the treasury, once, guarded by the conditional completion stamp. */
    private CompletableFuture<Void> complete(City city, CityChallengeRow row) {
        long now = System.currentTimeMillis();
        return challenges.markCompleted(row.id(), now).thenCompose(changed -> {
            if (changed == 0) {
                return CompletableFuture.completedFuture(null);
            }
            return db.transaction(connection -> treasury.adjust(connection, city, row.reward(),
                            TransactionType.CHALLENGE_REWARD, null,
                            "{\"challenge\":\"" + row.challengeId() + "\"}"))
                    .thenAccept(result -> {
                        markCachedComplete(city.id(), row);
                        if (result instanceof Result.Success<BigDecimal>) {
                            tellCity(city, "income.challenge.completed",
                                    LangManager.placeholder("challenge", row.challengeId()),
                                    LangManager.placeholder("amount",
                                            row.reward().toPlainString()));
                        }
                    });
        });
    }

    private void tellCity(City city, String key,
                          net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
        for (CityMember member : city.members()) {
            notifier.tell(member.uuid(), key, extra);
        }
    }

    private void replaceCached(int cityId, CityChallengeRow row, long progress) {
        active.computeIfPresent(cityId, (key, rows) -> rows.stream()
                .map(existing -> existing.id() == row.id()
                        ? new CityChallengeRow(existing.id(), existing.cityId(),
                                existing.challengeId(), progress, existing.target(),
                                existing.reward(), existing.weekStart(), existing.completedAt())
                        : existing)
                .toList());
    }

    private void markCachedComplete(int cityId, CityChallengeRow row) {
        long now = System.currentTimeMillis();
        active.computeIfPresent(cityId, (key, rows) -> rows.stream()
                .map(existing -> existing.id() == row.id()
                        ? new CityChallengeRow(existing.id(), existing.cityId(),
                                existing.challengeId(), existing.target(), existing.target(),
                                existing.reward(), existing.weekStart(), now)
                        : existing)
                .toList());
    }

    // ==================================================================================
    // Time
    // ==================================================================================

    /** Monday 00:00 of the week containing {@code now}, SPEC 13.2. */
    public long startOfWeek(long now) {
        LocalDate date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private BigDecimal rewardFor(QuestDefinition definition, long seed) {
        BigDecimal span = definition.rewardMax().subtract(definition.rewardMin());
        if (span.signum() <= 0) {
            return definition.rewardMin();
        }
        return dev.civitas.core.economy.Money.floor(definition.rewardMin()
                .add(span.multiply(BigDecimal.valueOf(new Random(seed).nextDouble()))));
    }

    public int perWeek() {
        return configs.get(ConfigFile.ECONOMY).getInt("income.challenges.per-week", 2);
    }

    public boolean enabled() {
        return configs.get(ConfigFile.ECONOMY).getBoolean("income.challenges.enabled", true);
    }
}
