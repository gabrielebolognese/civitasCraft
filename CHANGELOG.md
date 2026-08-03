# Changelog

All notable changes to CivitasCraft. One section per milestone from `PLAN.md`.

## [Unreleased]

### M12, Custom mobs

Added:
- `DefenseCatalogue`: the eight SPEC 12.2 units, read from `defense.yml` by full path so a
  server whose config predates a new unit still loads it, which is the bug M9 shipped once.
- `DefenseSpawner`: vanilla mobs with attribute modifiers, equipment at zero drop chance, a
  city-tagged name and the SPEC 12.5 persistent-data stamp. No NMS, as SPEC 12.5 requires.
- `DefenseService`: purchase, the spawn egg SPEC 12.4 asks for, the placement rules, death,
  dismissal, and the SPEC 12.3 deactivation that despawns a delinquent city's units while
  keeping their rows.
- `DefenseBehaviour`: the SPEC 12.3 table as pure decisions. The first row is the one that
  matters: a visitor in peacetime is ignored completely, at any distance, which is what keeps
  a defended city somewhere people can walk through.
- `DefenseListener`: placement from an egg, drops cleared on death, targeting filtered through
  the table, and SPEC 12.5's respawn-on-chunk-load for a unit lost to `/kill` or corruption.
- `DefenseMenu` (SPEC 8.9) and `/city defense`, with the hub button now opening rather than
  refusing.
- Tests: the SPEC 12.2 stat table, the SPEC 12.4 placement rules, SPEC 17.4 case 56, and one
  test per row of SPEC 12.3.

Changed:
- `UpkeepTask.defenseUpkeep` is no longer a seam, and a city that falls into delinquency now
  loses its garrison until it pays, per SPEC 12.3.
- The Fortification contradiction between SPEC 5.7 and SPEC 12.4 is resolved in favour of
  12.4: two units per level, so 5 to 15. The number lives only in `defense.yml` now.

### M11, City upgrades

Added:
- `UpgradeService` and `UpgradeType`: the six SPEC 5.7 tracks, five levels each, bought one
  at a time in order and paid from the treasury. Levels are cached because four hot paths
  read them, and re-checked inside the purchase transaction so two officers clicking at once
  cannot both buy level 3.
- The four seams other milestones left are now live: Population raises the member cap the
  join check uses, Treasury Interest lowers the bill the upkeep sweep produces, Outpost Range
  raises the cap M10 enforces, and Market Access lowers the tax a sale actually pays. Each is
  tested by asking the consuming system, not the upgrade service, because forgetting to read
  a stored level is a silent failure.
- The City Vault, SPEC 5.7 and 9.2: `VaultService`, `VaultView`, `VaultListener`, V6
  `city_vault`, and `/city vault [page]`. A page is 27 slots, one per Vault level, stored
  through Paper's own item serialisation.
- `UpgradesMenu`, and the Upgrades and Vault buttons on the hub now open rather than refuse.
- Tests: the thirty SPEC 5.7 prices, the five-level ceiling, the four effects asked of their
  own systems, and the vault's failure modes, including an unreadable page opening empty
  rather than taking the other pages down with it.

Note on the vault and the GUI framework: the vault is deliberately not a framework menu. M7's
listener cancels every click in anything it owns, which is right for a menu and wrong for a
container, so the vault carries its own holder and the framework never sees it. Nothing in
the SPEC 17.5 hardening was weakened to make this work.

### M10, Outposts

Added:
- `OutpostService`: SPEC 7 in full. The 1-chunk restriction, the 32-chunk minimum from your
  own land and 8 from anyone else's, the 25,000 C flat fee plus three times the next normal
  chunk, the slot cap, and the 50% refund on delete.
- The SPEC 7.4 conversion: when a city grows until its own outpost borders the city body,
  the outpost becomes an ordinary claim, the slot is freed, and nothing is refunded. Fired
  from a listener on the claim service, so it happens the moment the claim lands.
- `OutpostTeleport`: 100 C, an 8-second warmup cancelled by movement or damage, a 3-minute
  cooldown, and SPEC 7.4's safe landing, which drops a player at the highest safe Y in the
  chunk rather than into a wall somebody built over the warp point. The fee is charged on
  arrival, so an interrupted teleport costs nothing.
- `OutpostRegistry`: outposts in memory, because the upkeep sweep counts them for every city
  and the Claims menu shows the count on every redraw.
- `OutpostsMenu` and the six `/city outpost` subcommands from SPEC 7.3.
- Tests: one per rule in the SPEC 7.2 table and per case in SPEC 7.4, including the diagonal
  that is not adjacency, the foreign neighbour that converts nothing, and the outpost in
  another world that growth at home cannot reach.

Changed:
- `UpkeepTask.outpostCount` is no longer a seam: SPEC 7.2's 2,000 C a day per outpost is now
  part of what a city owes.
- The Outposts buttons on the Main and Claims menus open the screen instead of refusing.
- `ClaimService` gained a claim listener, so a milestone that needs to react to land being
  taken does not have to be wired into the claim path itself.

### M9, Income systems

Added:
- `ActivityTracker`: the SPEC 4.2.1 anti-AFK check. Three *distinct* kinds of action per
  interval, not a count of events, which is what makes it undefeatable by leaving a machine
  running for longer. Movement is a cumulative distance rather than an event, so a boat in
  flowing water covers nothing.
- `StipendTask`: the SPEC 4.2 stipend, and the accrual of `active_playtime_ms` behind it.
  An interval that fails the check pays nothing *and* credits no playtime. The daily cap is
  derived from the ledger, so a restart cannot reset it.
- `DailyLoginService`: 250 C plus 125 a day to a 1,000 C ceiling, the streak breaking after
  48 hours away rather than at midnight, and claimed automatically on join.
- `QuestPool`, `QuestService`: SPEC 13.1's three daily quests, drawn from a weighted pool
  with a seed made of the player and the date, so a relog cannot reroll a quest somebody
  dislikes. Paid the moment the target is met.
- `ChallengeService`: SPEC 13.2's two weekly challenges, pooled across the city, reset
  Monday 00:00, paid to the treasury.
- `IncomeMultipliers`: the SPEC 15.1 newcomer x1.5 and the SPEC 17.6 case 70 floor, in one
  place so every income source applies them identically.
- `ActivityListener` and `IncomeJoinListener`, `QuestsMenu` and `ChallengesMenu`,
  `/quests` and `/challenges`.
- V5: `target` and `reward` on `player_quests`, and the `city_challenges` table.
- Config: the whole `income.quests.pool` and `income.challenges.pool`, plus
  `income.quests.scale-hours` and `max-scale`.
- Tests: SPEC 4.2.1's own five calibration sentences, one test each, including the water
  clock, the jump-clicker and the single-key macro; SPEC 17.6 cases 69 and 70; the daily cap;
  the streak window; and the SPEC 13.1 rule that the effort-to-reward ratio stays flat.

Fixed:
- `ConfigManager` now writes packaged keys the operator's file has never had. Bukkit's
  `copyDefaults` makes a nested key read as *set* while still resolving its value against
  the on-disk tree, so a value added inside a section that already existed arrived empty.
  The first boot of this milestone on a server with an older `economy.yml` produced a quest
  pool with no metrics and refused every quest. This affected every future config addition,
  not only quests.
- `DailyLoginService` writes only the two columns it changes. A whole-row update after a
  deposit in the same transaction carried the balance read before it and undid the payment.
- `PlayerAccountService` no longer credits unfiltered session time to `active_playtime_ms`,
  the placeholder M2 shipped and documented.

### M8, GUI screens

Added:
- `MainMenu`: the SPEC 8.3 hub, on the slots the specification gives it. Seven of the
  thirteen buttons open systems later milestones build; those render, say so, and refuse the
  click rather than being invented.
- `OverviewMenu`, `ClaimsMenu`, `TreasuryMenu`, `TransactionHistoryMenu`, `ContributionMenu`,
  `MembersMenu`, `MemberActionsMenu`, `RankPickerMenu`, `BanListMenu`, `RanksMenu`,
  `PermissionEditorMenu`, `SettingsMenu`: SPEC 8.4 to 8.10.
- `CityMenu`: the base every screen sits on. The city is re-read on every draw, and a viewer
  who has been kicked, or whose city has gone, has the window taken away rather than left as
  a stale door into it (SPEC 17.1 case 11, SPEC 17.5 case 60).
- The SPEC 8.4 cost breakdown: base, distance multiplier, member divisor and total, so the
  SPEC 6.2 formula is visible rather than experienced as an arbitrary price.
- The SPEC 8.4 live 3x3 minimap on the bottom row, in the SPEC 6.5 map's colours.
- `CityHall`: the SPEC 8.1 Lodestone carrying `civitas:city_hall`, with the break rule as a
  configurable rank weight, plus `CityHallListener` and `/city hall`.
- `SpawnService` and `TeleportWarmupListener`: SPEC 5.6's five-second warmup cancelled by
  movement or damage and thirty-second cooldown, which M3 parked here. `/city spawn` and
  `/city setspawn` are no longer stubs.
- `CityService.setSpawn`, gated on `SET_SPAWN` and refusing a position outside the city's
  own claims.
- `AmountInput.askText`, the same chat prompt without the money parsing, for the places SPEC
  8 asks for a name or a sentence.
- `TreasuryService.history`, so the SPEC 8.5 history screen reads the ledger through a
  service rather than a DAO.
- Layouts: `gui/main.yml`, `claims.yml`, `treasury.yml`, `members.yml`, `ranks.yml`,
  `settings.yml`, all copied out on first boot rather than on first use.
- Config: `city-hall.material`, `city-hall.min-break-weight`, `spawn.warmup-seconds`,
  `spawn.cooldown-seconds`, `spawn.war-warmup-seconds`,
  `spawn.warmup-move-tolerance-blocks`, `gui.members.*`, `gui.history.entries`.
- Tests: the SPEC 18.2 revoked-permission requirement on the real treasury screen, SPEC 17.1
  case 11, SPEC 17.5 cases 59, 60 and 65, both SPEC 5.4 rules in the permission editor, the
  City Hall stamp and break rule, and the spawn warmup and cooldown. Plus a guard that every
  new message key resolves through `getString` rather than only appearing in `getKeys`.

Changed:
- `/city` with no arguments opens the Main Menu, per SPEC 8.3, instead of printing city info.

### M7, GUI framework

Added:
- `Menu`: the base screen. Owns the inventory, the SPEC 8.2 furniture, and the rule that a
  click is dispatched only after the button's own permission test has been asked again.
- `Button`: an icon, a label, and a permission that is a function rather than a flag, so
  SPEC 17.5 case 59 (revoked while the menu is open) is answered by construction. A button
  the viewer may not use renders as a barrier carrying the reason, SPEC 8.2.
- `MenuListener`: cancels every click and drag in a menu before reading anything about it,
  then dispatches. Shift-clicks, number-key swaps, offhand swaps, double-click sweeps and
  drops from the player's own inventory are refused; ordinary clicks on their own items are
  not. Cases 61 to 63.
- `PaginatedMenu`: previous on 48, next on 50, 28 entries a page, and the page clamped on
  every draw so a list that shrinks under an open menu still lands somewhere real (case 64).
- `ConfirmationMenu`: Confirm on 29, Cancel on 33, decided exactly once, and closing the
  window is a cancel (case 66).
- `MenuManager`: open sessions, the SPEC 8.2 20-tick refresh for menus showing live data, and
  force-closing by predicate for SPEC 17.1 case 11 and SPEC 17.5 case 60.
- `LayoutLoader` and `MenuLayout`: the YAML layouts SPEC 8 requires. The file owns appearance
  and position, Java owns behaviour. A bad entry costs that button and is logged with the
  file and key that caused it; it never costs the screen.
- `AmountInput`: the SPEC 8.5 custom-amount prompt, parsing strictly through `Money`, so
  letters, negatives and scientific notation are refused and the prompt repeats (cases 67
  and 68).
- `Icons`: one place that builds an item from a component, with the italics Minecraft adds to
  custom names turned off.
- `gui/common.yml`: the SPEC 8.2 constants in one file, copied out on first run.
- Tests: one per SPEC 17.5 case 59 to 68, plus the SPEC 18.2 click-validation-with-a-revoked-
  permission requirement, the layout loader against malformed files, and pagination against
  a list that changes size while open.

Note: M7 ships no screens. Nothing player-visible changes until M8 builds the menus in SPEC
Section 8 on top of this.

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
