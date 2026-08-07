package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.Result;

/**
 * SPEC 9.4.4's {@code /ca eco rollback}: "Reverses a single transaction and everything
 * downstream of it, writing compensating entries. Never deletes ledger rows."
 *
 * <h2>Two constraints that pull against each other</h2>
 * It has to undo a transaction, and it must not remove the record that the transaction
 * happened. SPEC 3.6 makes the ledger append-only and SPEC 1.5 makes it the authority in a
 * dispute, so a rollback that erased its own evidence would destroy the one thing the ledger
 * exists for. The reversal is therefore a <em>new</em> row of type {@code ADMIN_ROLLBACK}
 * naming the row it reverses, and reading the pair tells the whole story.
 *
 * <h2>The money may already be gone</h2>
 * SPEC 17.3 case 35 says exactly what to do: "may take the player negative. Balance floors at
 * 0 and the remainder is recorded as {@code debt} in metadata for admin follow-up." So a
 * player who received 10,000 C and spent 8,000 loses the 2,000 they still have and carries a
 * debt of 8,000 that an admin can see and act on. A balance that could go negative would
 * break every {@code compareTo} in the economy; a rollback that refused because the money was
 * spent would make the command useless in the case it exists for.
 *
 * <h2>Downstream is flagged, not cascaded</h2>
 * SPEC says "everything downstream of it" and gives no rule for how far to follow. Cascading
 * automatically would unwind trades with third parties who did nothing wrong: if the player
 * spent the money in somebody's shop, reversing that takes goods from a seller who was paid
 * in good faith. So one transaction is reversed and what followed it is reported, leaving the
 * judgement with the admin. Recorded in OPEN_QUESTIONS.md.
 */
public final class LedgerRollback {

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final EconomyService economy;
    private final CityRegistry cities;

    public LedgerRollback(DatabaseManager db, DaoRegistry daos, EconomyService economy,
                          CityRegistry cities) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.cities = Objects.requireNonNull(cities, "cities");
    }

    /**
     * What a reversal did.
     *
     * @param recovered how much was actually taken back
     * @param debt      what could not be recovered, per SPEC 17.3 case 35
     */
    public record Reversal(long originalId, UUID subject, BigDecimal original,
                           BigDecimal recovered, BigDecimal debt, int downstream) {

        public boolean isComplete() {
            return debt.signum() == 0;
        }
    }

    /**
     * Reverses one ledger row.
     *
     * @param admin who is doing it, recorded on the compensating entry
     * @param id    the row to reverse
     */
    public CompletableFuture<Result<Reversal>> reverse(UUID admin, long id, String reason) {
        return daos.ledger().findById(id).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(Result.failure("NO_SUCH_TRANSACTION",
                        "admin.eco.no-such-transaction"));
            }
            LedgerRow original = found.get();

            if (TransactionType.ADMIN_ROLLBACK.name().equals(original.type())) {
                // Reversing a reversal would be a way to launder money back into existence
                // by alternating, and an admin who wants the original state can reverse the
                // original row again.
                return completed(Result.failure("ALREADY_A_ROLLBACK",
                        "admin.eco.already-a-rollback"));
            }
            if (original.amount().signum() == 0) {
                return completed(Result.failure("NOTHING_TO_REVERSE",
                        "admin.eco.nothing-to-reverse"));
            }

            return alreadyReversed(id).thenCompose(done -> done
                    ? completed(Result.<Reversal>failure("ALREADY_REVERSED",
                            "admin.eco.already-reversed"))
                    : apply(admin, original, reason));
        });
    }

    /**
     * Whether this row has been reversed before.
     *
     * <p>Read from the ledger rather than from a flag on the row, because SPEC 3.6 forbids
     * updating a ledger row and a flag would need one. The compensating entry names what it
     * reversed, so the ledger answers the question itself.
     */
    private CompletableFuture<Boolean> alreadyReversed(long id) {
        return daos.ledger().findByType(TransactionType.ADMIN_ROLLBACK.name(), 0L, 10_000)
                .thenApply(rows -> rows.stream()
                        .anyMatch(row -> row.metadata() != null
                                && row.metadata().contains("\"reverses\":" + id + "")));
    }

    private CompletableFuture<Result<Reversal>> apply(UUID admin, LedgerRow original,
                                                       String reason) {
        return original.cityId() != null && original.actorUuid() == null
                ? reverseTreasury(admin, original, reason)
                : reversePlayer(admin, original, reason);
    }

    /**
     * Reverses a movement in a player's wallet.
     *
     * <p>Deliberately not through {@link EconomyService#withdraw}, which refuses when the
     * balance is short. That refusal is right for a player spending money and wrong here:
     * SPEC 17.3 case 35 requires the rollback to proceed and record the shortfall.
     */
    private CompletableFuture<Result<Reversal>> reversePlayer(UUID admin, LedgerRow original,
                                                              String reason) {
        UUID subject = original.actorUuid() != null ? original.actorUuid()
                : original.targetUuid();
        if (subject == null) {
            return completed(Result.failure("NO_SUBJECT", "admin.eco.no-subject"));
        }
        BigDecimal delta = original.amount().negate();
        // Captured from inside the transaction so the cache is refreshed with the balance
        // that was actually written rather than one recomputed outside it.
        java.util.concurrent.atomic.AtomicReference<BigDecimal> closing =
                new java.util.concurrent.atomic.AtomicReference<>();

        return db.transaction(connection -> {
            Optional<PlayerRow> row = daos.players().findByUuid(connection, subject);
            if (row.isEmpty()) {
                return Result.<Reversal>failure("NO_PLAYER_RECORD", "economy.no-account");
            }

            BigDecimal balance = row.get().balance();
            BigDecimal wanted = balance.add(delta);
            BigDecimal debt = BigDecimal.ZERO;
            BigDecimal after = wanted;

            if (wanted.signum() < 0) {
                // SPEC 17.3 case 35. The floor is not a rounding choice: a negative balance
                // would break every comparison in the economy, and the debt is what makes the
                // shortfall visible instead of silently forgiven.
                debt = wanted.negate();
                after = BigDecimal.ZERO;
            }

            BigDecimal moved = after.subtract(balance);
            closing.set(after);
            daos.players().updateBalance(connection, subject, after);
            daos.ledger().insert(connection, new LedgerRow(0, System.currentTimeMillis(),
                    TransactionType.ADMIN_ROLLBACK.name(), subject, admin, original.cityId(),
                    moved, after, metadata(original, reason, debt)));

            return Result.success(new Reversal(original.id(), subject,
                    original.amount().abs(), moved.abs(), debt, 0));
        }).thenCompose(result -> {
            if (result instanceof Result.Success<Reversal>(Reversal reversal)) {
                economy.remember(subject, closing.get());
                return countDownstream(subject, original)
                        .thenApply(count -> Result.success(new Reversal(reversal.originalId(),
                                reversal.subject(), reversal.original(), reversal.recovered(),
                                reversal.debt(), count)));
            }
            return completed(result);
        });
    }

    /** Reverses a movement in a city treasury, which has no ceiling and no owner to freeze. */
    private CompletableFuture<Result<Reversal>> reverseTreasury(UUID admin, LedgerRow original,
                                                                 String reason) {
        City city = cities.city(original.cityId()).orElse(null);
        if (city == null) {
            return completed(Result.failure("CITY_GONE", "city.unknown"));
        }
        BigDecimal delta = original.amount().negate();

        return db.transaction(connection -> {
            var row = daos.cities().findById(connection, city.id());
            if (row.isEmpty()) {
                return Result.<Reversal>failure("CITY_GONE", "city.unknown");
            }
            BigDecimal balance = row.get().treasury();
            BigDecimal wanted = balance.add(delta);
            BigDecimal debt = BigDecimal.ZERO;
            BigDecimal after = wanted;
            if (wanted.signum() < 0) {
                debt = wanted.negate();
                after = BigDecimal.ZERO;
            }

            BigDecimal moved = after.subtract(balance);
            daos.cities().updateTreasury(connection, city.id(), after);
            daos.ledger().insert(connection, new LedgerRow(0, System.currentTimeMillis(),
                    TransactionType.ADMIN_ROLLBACK.name(), null, admin, city.id(),
                    moved, after, metadata(original, reason, debt)));

            city.setTreasury(after);
            return Result.success(new Reversal(original.id(), null, original.amount().abs(),
                    moved.abs(), debt, 0));
        });
    }

    /**
     * How many of the subject's transactions followed the one being reversed.
     *
     * <p>Reported rather than reversed, for the reason in the class note. The number is what
     * tells an admin whether they have finished: reversing a grant that the player has since
     * spent in twelve places is not the end of the investigation.
     */
    private CompletableFuture<Integer> countDownstream(UUID subject, LedgerRow original) {
        // Ordered by row id rather than by timestamp. Ledger timestamps are milliseconds and
        // several transactions can share one, so "after this" measured by clock is ambiguous
        // exactly when a player is acting fast — which is the case an admin is investigating.
        // Ids are monotonic, so they answer it exactly.
        return daos.ledger().findByPlayer(subject, original.timestamp(), 1000)
                .thenApply(rows -> (int) rows.stream()
                        .filter(row -> row.id() > original.id())
                        // The compensating entry was written a moment ago and is itself after
                        // the original. Counting it would tell every admin that one
                        // transaction followed, whatever actually happened.
                        .filter(row -> !TransactionType.ADMIN_ROLLBACK.name().equals(row.type()))
                        .count());
    }

    private static String metadata(LedgerRow original, String reason, BigDecimal debt) {
        StringBuilder json = new StringBuilder("{\"reverses\":").append(original.id())
                .append(",\"original_type\":\"").append(original.type()).append('"');
        if (debt.signum() > 0) {
            // SPEC 17.3 case 35 names this key. An admin searching for unrecovered money
            // needs one thing to grep for.
            json.append(",\"debt\":\"").append(debt.toPlainString()).append('"');
        }
        if (reason != null && !reason.isBlank()) {
            json.append(",\"reason\":\"").append(reason.replace("\"", "'")).append('"');
        }
        return json.append('}').toString();
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
