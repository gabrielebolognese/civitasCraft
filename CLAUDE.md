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
./deploy.sh                # build + copy jar into ../testserver/plugins/  (Linux/macOS)
.\deploy.ps1               # same on Windows
```

The test server lives at `../testserver/`. It runs Paper with `online-mode=false`
so TLauncher offline accounts can connect. Connect to `localhost:25565`.

## Architecture

Service layer pattern. Commands and GUIs NEVER touch DAOs directly, they call services.
Services own all business logic and validation.

Claims are cached in a `Long2ObjectMap<Claim>` keyed by a packed `(worldId, chunkX, chunkZ)`
long, because claim lookup runs on every block event and must be O(1).

## Things that will bite you

- Paper API version strings change. If the dependency fails to resolve, check
  https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/ for the
  current 1.21.x version and update `build.gradle.kts`.
- `InventoryClickEvent` with `ClickType.NUMBER_KEY` is a common GUI exploit vector.
  Cancel it explicitly. See SPEC.md case 63.
- Applying blocks during rollback must use `setBlockData(data, false)` to suppress physics.
  Physics during rollback cascades and corrupts the restore. See SPEC.md 11.8.2.
