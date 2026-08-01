# Changelog

All notable changes to CivitasCraft. One section per milestone from `PLAN.md`.

## [Unreleased]

### M6, Market and player shops

Added:
- `MarketPricing`: the SPEC 4.4 curve, pure and config-driven, with the clamp that keeps a
  flooded item from falling to nothing and a bought-out one from costing unbounded money.
  Batches walk the curve unit by unit rather than being priced once at the opening price.
- `MarketRegistry`: the catalogue from `economy.yml` and live stock in memory, written
  through to `market_stock` as SQL arithmetic so two sales in a tick cannot lose each other.
  A config reload keeps stock, because a reload must not hand players a price reset.
- `MarketService`: buy, sell and quote. Items are taken from the inventory before the sale
  and put back if it fails, never the other way round. The SPEC 4.3 sale tax is written to
  the ledger as its own row and credited to nobody, because it is deleted from circulation.
- `MarketItemFilter`: SPEC 17.3 cases 29 and 30, refusing damaged, enchanted, renamed and
  filled-container items, each behind its own config toggle.
- `StockDecayTask`: the SPEC 4.4 drift back toward target, so no item stays permanently dead.
- `ShopSign`, `ShopTerms`, `PlayerShop`, `PlayerShopService`: SPEC 4.5 chest shops, untaxed
  by design. Sign parsing refuses a shop that buys higher than it sells, which would
  otherwise be a money pump anyone could run against the owner.
- `ShopSignListener` and `ShopInteractListener`: creation on a sign attached to a container
  where the player may build, right-click to buy, shift-right-click to sell, and removal when
  either the sign or the chest is destroyed.
- V4 `player_shops`, which SPEC 3 lists no table for and M1 deferred to this milestone.
- Commands: `/shop`, `/shop buy`, `/sell hand`, `/sell all`, `/worth`.
- Config: `market.decay-interval-minutes`, `player-shops.max-quantity-per-transaction`.
- Tests: the SPEC 18.1 price formula at stock 0, at target, at 10x target and at both clamp
  boundaries, plus cases 28, 29, 30 and 75, the shop sign grammar, and the full trade both
  ways including what happens when either side cannot complete it.

Changed:
- `EconomyService.transfer` generalises `pay` over the ledger type, so a shop sale is
  searchable as `PLAYER_SHOP` rather than hidden among ordinary payments.

### M5, Economy core

Added:
- `EconomyService`: personal balances over a write-through cache, per-player locking, and
  `/pay` moving both halves in one transaction. Replaces the `StorageFunds` placeholder M2
  shipped behind the `Funds` interface.
- `Money`: one place for reading and rounding amounts. Plain decimals only, floored rather
  than rounded (SPEC 17.3 case 26), a configurable ceiling (case 27), and scientific
  notation and negatives refused (SPEC 17.5 case 68).
- `TreasuryService`: deposit, withdraw, and the SPEC 8.5 25%-per-day cap for non-mayors.
  The cap needs no counter: it is derived from the ledger, so the limit and the audit trail
  can never disagree.
- `UpkeepCalculator` and `UpkeepTask`: the SPEC 4.3 daily charge, idempotent per cycle
  (SPEC 17.3 case 33), capped catch-up after downtime (case 31), and delinquency with grace
  warnings then auto-unclaim of the outermost chunks, three a day, retrying the charge from
  the refunds (case 32). The core chunk is never sold.
- `InflationTracker` and the V3 `economy_snapshots` table: hourly circulation readings and
  the SPEC 4.8 week-over-week warning.
- Commands: `/money`, `/balance`, `/pay`, `/city deposit`, `/city withdraw`.
- Integrations, SPEC 20 decision 7: PlaceholderAPI placeholders under `%civitas_...%` and an
  optional Vault economy provider.
- Tests: the SPEC 18.1 upkeep figures for cities of 8, 50 and 200 claims; the SPEC 18.2
  treasury deposit, withdraw and cap flow; and cases 24 to 27, 31 to 34, 68 and 71.

Fixed:
- `DatabaseManager.transaction` now rolls back when the work returns a `Result.Failure`, not
  only when it throws. A transfer whose credit was refused was debiting the sender and
  destroying the money.
- SQLite connections open transactions as `IMMEDIATE`. The default deferred transaction
  takes its write lock lazily, so two concurrent read-then-write transactions raced to
  `SQLITE_BUSY_SNAPSHOT`, which `busy_timeout` cannot retry. Two players paying at the same
  moment was enough to hit it.
- The PlaceholderAPI and Vault hooks are now presence-checked by the caller. Both classes
  extend a type from the plugin they integrate with, so calling a static method on one was
  enough to load its superclass and throw `NoClassDefFoundError` on a server without it,
  which aborted the rest of the startup sequence.

### M4, Land protection

Added:
- `ProtectionService`: every rule in SPEC 5.5 as pure functions over the claim and city
  caches, taking no Bukkit types. Bypass, then wilderness, then dormancy, then war, then
  membership; each is a reason the answer is yes before membership is consulted.
- `ProtectionAction`: the mapping from SPEC 5.5's prose to SPEC 5.4's flags, in one enum.
- `BlockClassifier`: which blocks are containers and which are interactables, built from
  Bukkit's own tags so a new wood type is protected the day it ships, with config overrides
  in both directions.
- `ProtectionGuard`: the bridge from events to the service, resolving `civitas.bypass.claim`
  and throttling refusals so holding down left-click does not print twenty lines a second.
- Eight listeners covering block break and place, multi-block placement, containers, the
  read-only take distinction, interaction, item frames, armor stands, hanging entities,
  buckets, entity damage, PvP, explosions, fire, fluid flow, ignition and pistons.
- `civitas.bypass.claim` is enforced, having been declared since M0 and honoured nowhere.
- Config: `protection.deny-message-cooldown-ms`, `protection.extra-containers`,
  `protection.extra-interactables`, `protection.unprotected`.
- Tests: the full SPEC 5.5 decision matrix without a server, the block classification, and
  end-to-end listener checks under MockBukkit, including the SPEC 18.2 requirement that a
  rank's permissions are enforced on a block break.

Changed:
- `PlayerInteractEvent` refusals deny the block rather than cancelling the whole event, so a
  player can still eat an apple while looking at someone else's door.

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
