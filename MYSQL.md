# The MySQL path

SPEC 2.1 offers SQLite (default) and MySQL/MariaDB (optional). `OPEN_QUESTIONS.md` recorded at
M1 that "MySQL is not covered locally", and the gap was wider than that sounded: the fourteen
files under `migrations/mysql/` had **never been executed by anything**. They were written
alongside their SQLite counterparts, reviewed by eye, and checked by `MigrationIndexTest` only
for appearing in `index.txt`. A syntax error in any of them would have surfaced the first time
an operator set `storage.type: MYSQL` — at which point the plugin fails to start.

They have now been run. Result below.

## Running the dialect tests

They are **skipped by default**, because a developer without a server must still be able to
build and a suite that goes red where nothing is wrong teaches people to ignore it.

```bash
# All 13 dialect tests: migrations, schema parity, money, indexes, transactions
./gradlew test --tests "dev.civitas.storage.MySqlDialectTest" \
  -Dcivitas.test.mysql.url="jdbc:mysql://127.0.0.1:3306/civitas_test" \
  -Dcivitas.test.mysql.user=root

# All 40 DAO round-trip tests, against MySQL instead of SQLite
./gradlew test --tests "dev.civitas.storage.dao.DaoRoundTripTest" \
  -Dcivitas.test.dialect=MYSQL \
  -Dcivitas.test.mysql.url="jdbc:mysql://127.0.0.1:3306/civitas_test" \
  -Dcivitas.test.mysql.user=root
```

> **The named schema is dropped and recreated before every test.** Point it at a throwaway.
> Gradle does not forward the invoking JVM's system properties to the test JVM, so
> `build.gradle.kts` passes these four through explicitly — without that the tests would skip
> silently even when a server was named, and the run would still go green.

## What was found

**Nothing broken.** All fourteen migrations applied to an empty schema on the first attempt,
the resulting schema matched `SchemaTest.expectedSchema()` — SPEC Section 3 — table for table
and column for column, and all 40 DAO round-trip tests passed unchanged.

| Checked | Result |
|---|---|
| All 14 migrations apply from empty | Pass |
| Re-running the migrator applies nothing | Pass |
| Tables and columns match SPEC Section 3 | Pass |
| Money columns are real `DECIMAL(_,2)`, not a float | Pass |
| Money round-trips with cents, negatives, and 999999999999.99 | Pass |
| Unique index on `(world, chunk_x, chunk_z)`, SPEC 3.4 | Pass |
| Transaction rolls back on `Result.Failure` and on a throw | Pass |
| Main-thread guard refuses a query from the server thread | Pass |
| All 40 DAO round trips, including foreign keys and cascades | Pass |

The one defect the exercise produced was in the test, not the product: it looked for a
bookkeeping table called `schema_migrations` when the runner's is `schema_version`. It now
reads `MigrationRunner.VERSION_TABLE` so the two cannot drift.

## What this does not prove

- **The server tested was MariaDB 10.4.32, not MySQL 8.** SPEC 2.1 names both, and the driver
  is MySQL Connector/J either way, but they are not the same product. The class of bug this
  cannot find is a word MySQL 8.0 reserves and MariaDB 10.4 does not — `rank`, `groups`,
  `system`, `row`, `window` and the other window-function keywords. That was checked
  separately and statically: **no identifier in the MySQL DDL is one of them**, and none needs
  backticking. It is the reason the schema is portable, not an accident.
- **The service layer was not re-run against MySQL.** A `CityService` rule behaves the same
  whichever database is underneath, and re-running 1,600 tests to prove that would take a long
  time to learn nothing. What differs by dialect is the DDL, the money representation, the
  indexes and transaction behaviour — which is exactly what is covered above.
- **No concurrency or pool testing under load.** The pool is Hikari on both dialects and
  `/ca perf` reports its status, but nothing here drives it with many simultaneous callers.
- **Backups still do nothing on MySQL.** This is deliberate and predates the pass: M1 recorded
  that a correct MySQL backup means `mysqldump` or a storage-level snapshot, and a plugin
  shelling out to a binary it cannot verify would give operators false confidence. The service
  writes nothing and says so once at startup, so the operator learns before they need a backup
  rather than after. **An operator running MySQL must arrange their own backups.**

## Money, and why it differs by dialect

SPEC Section 3 specifies `DECIMAL(20,2)` for every monetary column. SQLite has no decimal type
— a column declared `DECIMAL` takes NUMERIC affinity and stores non-integral values as an
8-byte float, so cents drift, and drifting balances cannot be audited (SPEC 1.5). M1's answer
was to store minor units in an `INTEGER` on SQLite and a real `DECIMAL(20,2)` on MySQL, hidden
behind `SqlDialect.setMoney` and `getMoney`.

On MySQL that indirection has nothing to do — which is precisely why it is worth a test. The
round-trip case above asserts that `0.01`, `-4321.09` and `999999999999.99` all come back
identical, and the schema case asserts the columns really are `DECIMAL` with two digits rather
than a `DOUBLE` that would look right until the ledger stopped reconciling.
