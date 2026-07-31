package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.LedgerDao;
import dev.civitas.storage.dao.PlayerDao;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.PlayerRow;

/**
 * Creates and maintains the {@code players} row behind every online player.
 *
 * <p>Nothing else in the plugin works without this row: a city cannot charge a founder who
 * has no balance, and a member cannot be looked up by a name that was never recorded. It is
 * created on first join along with the SPEC 4.2 starting balance and its ledger entry.
 *
 * <p>Playtime is accumulated here from the session clock. SPEC 4.2.1 defines an anti-AFK
 * filter for {@code active_playtime_ms}, but that filter is M9's; until then this credits
 * unfiltered session time to both counters, which is more generous than SPEC intends and
 * never less, so no player is wrongly blocked from founding a city.
 */
public final class PlayerAccountService {

    private final DatabaseManager db;
    private final PlayerDao players;
    private final LedgerDao ledger;
    private final ConfigManager configs;

    /** Session start times, so playtime can be credited when the player leaves. */
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();

    public PlayerAccountService(DatabaseManager db, PlayerDao players, LedgerDao ledger,
                                ConfigManager configs) {
        this.db = Objects.requireNonNull(db, "db");
        this.players = Objects.requireNonNull(players, "players");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * Ensures a row exists, creating it with the starting balance on first join.
     *
     * @return the player's row, freshly created or updated with the current name
     */
    public CompletableFuture<PlayerRow> onJoin(UUID uuid, String name, long now) {
        sessionStart.put(uuid, now);

        return db.transaction(connection -> {
            Optional<PlayerRow> existing = players.findByUuid(connection, uuid);
            if (existing.isPresent()) {
                PlayerRow row = existing.get();
                PlayerRow refreshed = new PlayerRow(row.uuid(), name, row.balance(), row.cityId(),
                        row.rankId(), row.firstJoin(), now, row.totalPlaytimeMs(),
                        row.activePlaytimeMs(), row.dailyStreak(), row.lastDailyClaim(),
                        row.newcomerUntil(), row.frozen(), row.lastCityLeave(), row.lastCityDisband());
                players.update(connection, refreshed);
                return refreshed;
            }

            BigDecimal starting = startingBalance();
            long newcomerUntil = now + newcomerDays() * 86_400_000L;
            PlayerRow created = new PlayerRow(uuid, name, starting, null, null,
                    now, now, 0L, 0L, 0, 0L, newcomerUntil, false, 0L, 0L);
            players.insert(connection, created);

            if (starting.signum() > 0) {
                ledger.insert(connection, new LedgerRow(0, now,
                        TransactionType.STARTING_BALANCE.name(), uuid, null, null,
                        starting, starting, null));
            }
            return created;
        });
    }

    /** Credits the finished session's playtime and stamps {@code last_seen}. */
    public CompletableFuture<Void> onQuit(UUID uuid, long now) {
        Long started = sessionStart.remove(uuid);
        long sessionMillis = started == null ? 0L : Math.max(0L, now - started);

        return db.run(connection -> {
            Optional<PlayerRow> existing = players.findByUuid(connection, uuid);
            if (existing.isEmpty()) {
                return 0;
            }
            PlayerRow row = existing.get();
            return players.update(connection, new PlayerRow(row.uuid(), row.lastKnownName(),
                    row.balance(), row.cityId(), row.rankId(), row.firstJoin(), now,
                    row.totalPlaytimeMs() + sessionMillis,
                    row.activePlaytimeMs() + sessionMillis,
                    row.dailyStreak(), row.lastDailyClaim(), row.newcomerUntil(),
                    row.frozen(), row.lastCityLeave(), row.lastCityDisband()));
        });
    }

    /**
     * Active playtime including the session in progress.
     *
     * <p>Without the live session, a player who joins for the first time and plays for three
     * hours straight still reads as zero until they log out, which would make the SPEC 5.1
     * playtime precondition impossible to satisfy in one sitting.
     */
    public long effectiveActivePlaytime(PlayerRow row, long now) {
        Long started = sessionStart.get(row.uuid());
        long live = started == null ? 0L : Math.max(0L, now - started);
        return row.activePlaytimeMs() + live;
    }

    /** Forgets a session without crediting it, for use when the plugin disables. */
    public void clearSessions() {
        sessionStart.clear();
    }

    /** Exposed so a caller can seed a session for a player who was already online on reload. */
    public void beginSession(UUID uuid, long now) {
        sessionStart.putIfAbsent(uuid, now);
    }

    /** Read as text, not as a double: money must never take a trip through binary floating point. */
    private BigDecimal startingBalance() {
        return new BigDecimal(
                configs.get(ConfigFile.ECONOMY).getString("income.starting-balance", "2000"));
    }

    private long newcomerDays() {
        return configs.get(ConfigFile.ECONOMY).getInt("income.newcomer.days", 14);
    }
}
