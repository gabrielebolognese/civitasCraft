# Changelog

All notable changes to CivitasCraft. One section per milestone from `PLAN.md`.

## [Unreleased]

### M3, Claim system

Added:
- `ClaimCostEngine`: the SPEC 6.2 polynomial curve, with the flat starter band, distance
  multiplier, member divisor and young-city discount. Every per-chunk value in the SPEC 6.2
  reference table is matched to within 0.4 C.
- `ClaimRegistry`: the `Long2ObjectMap` cache from SPEC 2.3, on a packed chunk key, loaded
  once at startup. This is the lookup every block event in M4 will make.
- `Contiguity`: the SPEC 6.1 flood-fill, enforced on unclaim, refusing anything that would
  orphan part of a city and naming the stranded chunks.
- `ClaimService`: claim, unclaim, atomic `radius`, and auto-claim, with all ten SPEC 6.3
  preconditions and all five SPEC 6.4 blocks.
- `ClaimMap` and `BorderRenderer`: the SPEC 6.5 chunk map and particle outlines.
- `ClaimBoundaryListener`: the enter/leave action bar, behind a single integer comparison so
  `PlayerMoveEvent` costs nothing when the player has not changed chunk.
- `/city claim`, `claim auto`, `claim radius`, `unclaim`, `unclaim radius`, `map`, `here`,
  `border`.
- `ChunkClaimEvent` and `ChunkUnclaimEvent`.
- Tests: the SPEC 6.2 reference table, the member divisor at 1/5/10/25, the distance
  multiplier at 0/4/5/20, all five SPEC 18.1 contiguity shapes, claim/unclaim/contiguity
  rejection, and SPEC 17.2 cases 12, 13, 15, 17, 20, 22 and 23.

Fixed:
- **Disbanding now refunds 50% of each claim's `cost_paid` to the mayor, as SPEC 5.3
  requires.** It was missing since M2, harmless only because claims cost nothing until now.
- A `radius` claim was bought centre-outward, so a legal square whose near edge touched the
  city but whose centre did not was refused entirely. Squares are now grown from whatever is
  already adjacent.

### M2, Core city model

Added:
- `City`, `CityMember` and `CityRank`, held in a `CityRegistry` loaded once at startup.
  Membership and permission lookups run on the hot path of chat and commands, so they never
  touch the database.
- `CityPermission` and `PermissionSet`: the 22 SPEC 5.4 flags as a value type over the stored
  bitmask. A mask written by another version cannot grant a flag that does not exist here.
- `CityService`: create, disband, invite, accept, deny, open-join, leave, kick, transfer,
  rename, MOTD, open-join toggle, ban and unban. Every operation checks cheaply in memory,
  fires a cancellable event, then does all its writes in one transaction that re-checks
  whatever the database owns.
- `RankService`: create, delete, rename, reweight, per-flag toggling, assignment, promote and
  demote, enforcing "cannot grant what you lack" and "cannot edit equal or higher weight".
- Seven cancellable events under `dev.civitas.api.event`, all fired before their mutation.
- `Funds` and `StorageFunds`: the narrow money seam M5 will implement, so the SPEC 5.1 fee
  lands in the same transaction as the city it paid for.
- `PlayerAccountService` and its listener: the `players` row, the SPEC 4.2 starting balance
  and playtime accrual.
- The `/city` command tree for everything M2 implements; the rest remain named stubs.
- `CityChatListener`, the SPEC 20 decision 6 city-tag prefix, wrapping any renderer already
  set so a dedicated chat plugin still wins.
- Migration V2: `city_bans`, plus `players.last_city_leave` and `players.last_city_disband`
  for the SPEC 5.2 and 17.1 case 7 cooldowns.
- Tests: the SPEC 18.1 bitmask and rank rules, the SPEC 18.2 creation flow with all nine
  preconditions failing individually, membership, SPEC 17.1 cases 4, 6, 7, 9 and 10, and a
  guard that every message key the code asks for exists and no message is orphaned.

Changed:
- `ConfigManager` and `LangManager` now depend on a narrow `PluginResources` rather than on
  `Plugin`, so config and language loading can be exercised without a server.

Fixed:
- Cooldown messages round remaining time up rather than down. "Come back in 23 hours" when
  23 hours 59 minutes remain sends a player away and then refuses them again.

### M1, Storage layer

Added:
- `DatabaseManager`: HikariCP pool, a dedicated database thread pool, and a guard that
  throws if a query is issued on the server thread. SPEC 2.1's "zero database access on the
  main thread" is enforced rather than trusted.
- `MigrationRunner`: versioned `V<n>__<name>.sql` scripts, applied in order, each in its own
  transaction, recorded in `schema_version`, idempotent on restart. Discovery is driven by an
  `index.txt` beside the scripts, because a classpath folder cannot be listed once packaged
  in a jar; a test asserts the index and the files agree.
- `V1__init.sql` for SQLite and MySQL: every table in SPEC Section 3, plus the unique
  `(world, chunk_x, chunk_z)` index that makes it physically impossible for two cities to own
  the same chunk, and the `(war_id, sequence DESC)` index the rollback replay reads through.
- A DAO and a row record per table, 23 of each, with every mutation available both as a
  `CompletableFuture` and as a `Connection`-taking call so a service can compose several
  tables into one transaction.
- `SqlDialect`: the SQLite/MySQL differences in one place, including money. SQLite has no
  decimal type, so monetary columns are integer minor units there and real `DECIMAL(20,2)` on
  MySQL; callers only ever see `BigDecimal`.
- `BackupService`: scheduled `VACUUM INTO` backups with retention pruning on SQLite. On MySQL
  it writes nothing and says so, rather than shelling out to `mysqldump` and implying safety
  it cannot deliver.
- Config keys for the SQLite path (`storage.sqlite.*`) and a slow-query threshold.
- Tests: schema matches SPEC Section 3 table by table and column by column, migrations are
  idempotent, the claim index rejects duplicates, money survives a round trip without drift,
  transactions roll back cleanly, and every DAO round-trips.

Known limitation:
- The MySQL path is written and reviewed but not covered by tests. There is no MySQL server
  in this environment, and running it against a compatibility emulator would prove something
  about the emulator, not about MySQL.

### M0, Project skeleton

Added:
- Gradle (Kotlin DSL) build with the Gradle 9.6.1 wrapper, a Java 21 toolchain and a
  shaded jar. Warnings are errors (`-Werror -Xlint:all`).
- `CivitasPlugin` entry point. Enables and disables cleanly with no state to unwind yet.
- `ConfigManager` and the six configuration files from SPEC 16: `config.yml`, `cities.yml`,
  `economy.yml`, `war.yml`, `defense.yml`, `events.yml`. Every numeric value in SPEC.md is
  a key in one of them. In-jar defaults are installed as the defaults tree on every load, so
  a key added by an update still resolves against an operator's older file.
- `LangManager` with `lang/en.yml` and `lang/it.yml`, MiniMessage rendering, and lookup that
  falls back active language to English to a visible missing-key marker. Placeholder values
  are inserted unparsed so player-supplied text cannot inject formatting.
- `Result<T>`, the sealed `Success` / `Failure` type that every service mutation returns
  (SPEC 2.3).
- The root command tree, registered through Brigadier and Paper's Lifecycle API. Every
  command from SPEC 9 is registered and permission-gated; each replies that it is not
  implemented yet, naming the milestone that will fill it in.
- `plugin.yml` with every permission node from SPEC 10 at its documented default.
- Tests: SPEC config defaults, language completeness across both languages, permission-node
  declarations, and `Result` semantics.
