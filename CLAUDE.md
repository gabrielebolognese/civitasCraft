# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# CivitasCraft

Minecraft Paper 1.21.x server plugin, Java 21. City-building server with claims,
economy, wars with full world rollback, custom mobs, and contests.

## READ THIS FIRST, EVERY SESSION

Before doing anything else, read these three files in full:

@SPEC.md
@PLAN.md
@OPEN_QUESTIONS.md

`SPEC.md` is the complete specification and the single source of truth.
`PLAN.md` is the milestone tracker with the session protocol.
`OPEN_QUESTIONS.md` is where ambiguities get recorded.

## Session protocol

1. Read the three files above.
2. Find the first milestone in PLAN.md with status `TODO` whose dependencies are all `DONE`.
3. State which milestone you are doing and list the files you will create or modify.
4. Work ONLY on that milestone. Do not skip ahead. Do not start the next one.
5. Write the tests specified in SPEC.md Section 18 for that milestone.
6. Run `./gradlew build`. Fix every error and every warning.
7. Update PLAN.md: set the milestone status to `DONE` with a one-line note.
8. Commit: `git add -A && git commit -m "M<n>: <milestone name>"`
9. Stop. Tell me the milestone is done and what the next one is.

`.claude/commands/` has `/next-milestone`, `/finish-milestone` and `/spec-check` for these steps.

## End every reply with a recap

Every message you send me ends with a recap block, without exception. Not only at the end of
a milestone, not only when something was completed: every message, including short answers,
questions back to me, and refusals.

```
**Recap**
- **Milestone:** M<n>, <name> — <TODO | in progress | DONE, uncommitted | DONE>
- **Just did:** one line, what changed in this message
- **Next action:** the single next concrete step, specific enough to act on without rereading
- **Waiting on me:** what you need from me, or "nothing"
```

Four lines, no more. "Next action" is one step, not a plan: `commit M13` or
`write ClaimCostEngineTest for the chunk-15 reference value`, never `continue with M14`.
If you are mid-task and blocked, "Next action" is what you would do once unblocked.

## Hard rules

- No database access on the main thread. Ever. All I/O is async.
- No hardcoded numbers. Every value in SPEC.md is a config key in a yml file.
- No hardcoded player-facing strings. Everything goes in `src/main/resources/lang/`.
- Never change SPEC.md Section 3 (data model) without writing a migration in `storage/migration/`.
- Never invent a feature that is not in SPEC.md. If SPEC.md is ambiguous, append the
  question to OPEN_QUESTIONS.md, implement the most conservative option, and keep going.
- `rollback.enabled` must never default to false.
- All player-facing text uses MiniMessage, never legacy color codes.
- Every service mutation returns a `Result<T>` sealed type. No exceptions for expected failures.

## Build and test

```
./gradlew build            # compile + tests, jar lands in build/libs/
./gradlew test             # tests only
./gradlew test --tests "dev.civitas.core.claim.ClaimCostEngineTest"        # one class
./gradlew test --tests "dev.civitas.core.claim.*"                          # one package
./deploy.sh                # build + copy jar into ../testserver/plugins/  (Linux/macOS)
.\deploy.ps1               # same on Windows
```

On Windows use `.\gradlew.bat`. Compilation runs `-Xlint:all -Werror`, so **any warning fails
the build** — an unused import or a raw type is a build break, not a nit. `build` runs
`shadowJar`, which relocates Hikari, fastutil and Configurate (but deliberately not the JDBC
drivers, which resolve by hardcoded package name).

The test server lives at `../testserver/`. It runs Paper with `online-mode=false`
so TLauncher offline accounts can connect. Connect to `localhost:25565`.

## Architecture

Service layer pattern. Commands and GUIs NEVER touch DAOs directly, they call services.
Services own all business logic and validation.

### Startup and the null window

`CivitasPlugin.onEnable` loads config and lang, registers commands, and then opens the
database **asynchronously** (migrations can take seconds and must not block the server
thread). Everything that needs storage is built in `onStorageReady` and published as one
immutable `CivitasServices` record into an `AtomicReference`.

Commands and listeners are therefore constructed with a `Supplier<CivitasServices>`
(`services::get`), not with the services themselves. **That supplier returns null until the
database is open** — a command that finds null must say so politely, never throw. Wiring a
new system means adding it to `onStorageReady`, adding its field to `CivitasServices`, and
handing dependent commands the same supplier.

### Storage

- `DatabaseManager.call` / `run` / `transaction` run work on a dedicated pool and return
  `CompletableFuture`. `callSync` **throws** if reached from the server thread — the
  main-thread rule is enforced, not trusted.
- `transaction` rolls back not only on exception but also when the work returns a
  `Result.Failure`. A service that writes half a change and then refuses the rest cannot
  commit the half it wrote.
- `Dao<T>` gives every mutation two shapes: a `...Sync(Connection, ...)` form that runs on
  the caller's thread so a service can compose several tables into one transaction, and a
  future-returning form for standalone calls.
- **Money must be bound and read through `SqlDialect.setMoney` / `getMoney`.** On SQLite,
  monetary columns are `INTEGER` minor units (SQLite has no real decimal type); on MySQL they
  are `DECIMAL(20,2)`. Binding a `BigDecimal` any other way loses cents. `Dao.bind` handles
  this automatically for parameters; raw SQL written outside a DAO must not bypass it.
- Migrations live in `resources/migrations/{sqlite,mysql}/` and must be added to **both**
  dialects plus each `index.txt` (a classpath directory cannot be listed from inside a jar;
  `MigrationIndexTest` fails the build if the index and the files disagree). An applied
  migration is never re-run, so edits to a released migration do nothing — always add a new file.

### Cache-first registries

The database is a persistence target, not a read path. `CityRegistry`, `ClaimRegistry`,
`DiplomacyRegistry`, `MarketRegistry`, `OutpostRegistry`, `DefenseRegistry` and
`UpgradeService` all load into memory at startup and are the authority at runtime.

Claims are cached in a `Long2ObjectMap<Claim>` keyed by a packed `(worldId, chunkX, chunkZ)`
long (`ChunkKey`: 12 bits world, 26 bits each coordinate, out-of-range throws rather than
wrapping), because claim lookup runs on every block event and must be O(1).

### Result, Scheduler, events

- `Result<T>` is `Success(T)` or `Failure(reason, messageKey, placeholders)`. The failure
  carries a **lang key**, never rendered text; placeholders are inserted unparsed so a
  player-supplied name can never inject MiniMessage.
- `Scheduler` is a one-method interface for hopping back to the server thread after async
  work. `Scheduler.direct()` runs inline, which is how services are tested without a server.
- Custom events (`CityCreateEvent`, etc.) fire **before** their mutation, because that is the
  only point at which cancelling can mean anything.

### Config and lang

- `ConfigManager` loads each `ConfigFile`, copies the packaged copy out on first run, and
  writes keys the operator's file has never heard of back into it. `ConfigDefaultsTest`
  asserts the SPEC numbers are present with their documented values.
- `Msg` holds only the M0 constants; later code passes string keys as literals.
  `LangKeyUsageTest` scans `lang.send/sendRaw/get(...)` and `Result.failure(...)` call sites
  and fails the build if a key is missing, and `LangKeysTest` requires `en.yml` and `it.yml`
  to agree. **A new message means editing both language files.**

### GUI

`gui/framework/` owns the machinery, `gui/menus/` one class per screen. Layout files under
`resources/gui/` own **appearance and position only** — nothing in them names an action, so
an operator cannot rewire the Disband button by editing yaml. What a menu *draws* is a
snapshot; what a click *does* re-reads live state and re-checks permission at execution time.
The city vault deliberately does not use the framework (it has its own `VaultHolder`) because
the framework cancels every click and a vault is a container where items must move.

### Seams for later milestones

Systems that SPEC references but that a later milestone implements are written as named
methods that answer conservatively — `DiplomacyService.isAtWar` returns false,
`ProtectionService` reports no war and no dormancy, the claim preconditions for war state and
admin regions always pass. The milestone that adds the system fills in one method instead of
auditing every call site. `CommandRegistry.COMMANDS` does the same for commands: each stub
records the milestone number that will implement it.

## Testing conventions

- Storage tests run against a **real SQLite file** in a JUnit temp dir, never a mock — the
  point is that the SQL is correct, and a mock cannot prove a unique index rejects a duplicate.
  MySQL is not covered locally.
- MockBukkit backs the GUI, listener and item-handling tests; pure logic (cost formulas,
  bitmasks, pricing, contiguity) is plain JUnit with no server.
- `Scheduler.direct()` turns an async service synchronous for the duration of a test.
- Shared fixtures live in `*TestSupport` classes beside the tests that use them.

## Things that will bite you

- Paper API version strings change. If the dependency fails to resolve, check
  https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/ for the
  current 1.21.x version and update `build.gradle.kts`.
- `InventoryClickEvent` with `ClickType.NUMBER_KEY` is a common GUI exploit vector.
  Cancel it explicitly. See SPEC.md case 63.
- Applying blocks during rollback must use `setBlockData(data, false)` to suppress physics.
  Physics during rollback cascades and corrupts the restore. See SPEC.md 11.8.2.
- `-Werror` means a stray import fails the build long before a test does.
- Adding a config key or a lang key without adding it to every packaged file fails
  `ConfigDefaultsTest` or `LangKeysTest`, not at runtime.
