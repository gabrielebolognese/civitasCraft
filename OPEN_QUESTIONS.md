# Open Questions

Append here whenever SPEC.md is ambiguous. Do not guess silently.

Format:
- **[M<milestone>]** Question. *Implemented default:* what you chose. *Date:* YYYY-MM-DD

---

- **[M0]** SPEC 2.3 defines the failure case as `Failure(reason, messageKey)`, but a message
  such as "you need 10,000 C" needs values substituted into it, and SPEC 2.1 forbids
  building that text in Java. *Implemented default:* `Failure(reason, messageKey, placeholders)`
  where `placeholders` is an immutable `Map<String, String>` defaulting to empty, and the
  two-argument factory is kept for the common case. Placeholder values are rendered
  unparsed so a player-supplied name can never inject MiniMessage. *Date:* 2026-07-31

- **[M0]** SPEC 10 lists Bukkit permission nodes for the player and admin commands, but not
  for `/war`, `/ally`, `/truce`, `/cc`, `/ac`, `/leaderboard` or `/report`. SPEC 9.2 and 9.3
  gate those on *city* permissions (`DECLARE_WAR`, `MANAGE_DIPLOMACY`, membership), which is
  a separate mechanism from Bukkit nodes. *Implemented default:* they gate on `civitas.use`
  at the Bukkit level, with the real city-permission check to be applied inside the command
  by the milestone that implements it. This is the conservative reading: it neither invents
  new nodes nor makes the commands op-only. *Date:* 2026-07-31

- **[M0]** SPEC 2.1 targets Paper 1.21.x, but the test server at `../testserver/` runs Paper
  26.2. *Implemented default:* compiled against `paper-api:1.21.11-R0.1-SNAPSHOT` as the
  specification says, and verified by hand that the jar loads and enables cleanly on the
  26.2 test server. Flagged for the developer to decide whether the specification or the
  test server should move. *Date:* 2026-07-31

- **[M0]** SPEC 19 assigns no tests to M0: everything in SPEC 18.1 tests formulas introduced
  in M3, M5, M6 and M19, and SPEC 18.2 needs the city model from M2. *Implemented default:*
  added infrastructure tests instead of shipping an untested milestone, asserting that every
  numeric default in SPEC is present in a yml file with its documented value, that every
  message key exists in both shipped languages, and that `Result` behaves as SPEC 2.3
  describes. *Date:* 2026-07-31

- **[M1]** SPEC 3 specifies `DECIMAL(20,2)` for every monetary column, but SQLite has no
  decimal type: a column declared `DECIMAL` takes NUMERIC affinity and stores non-integral
  values as an 8-byte float, so cents drift. Drifting balances cannot be audited, and SPEC
  1.5 makes auditability a core requirement. *Implemented default:* on SQLite every monetary
  column is `INTEGER` holding minor units (hundredths); on MySQL it is a real
  `DECIMAL(20,2)`. `SqlDialect.setMoney` and `getMoney` hide the difference, so callers only
  ever see `BigDecimal`, and `SUM`/`ORDER BY` still work in SQL. Any SQL written outside a
  DAO must bind and read money through the dialect. *Date:* 2026-07-31

- **[M1]** SPEC 11.7 names a `war_container_log` table with its columns, but SPEC 3 does not
  list it. *Implemented default:* created in V1 rather than deferred, so it needs no later
  migration. *Date:* 2026-07-31

- **[M1]** SPEC 11.8.5 says the last restored sequence is "checkpointed to the `wars` table",
  but SPEC 3.7 lists no such column. Without it, a crash mid-rollback restarts from the
  beginning. *Implemented default:* added `wars.rollback_checkpoint_sequence BIGINT NULL`.
  *Date:* 2026-07-31

- **[M1]** SPEC 3.7 gives the war state enum as PREP, ACTIVE, ROLLING_BACK, RESOLVED,
  CANCELLED, but SPEC 11.2 also names DECLARED and SPEC 11.8.5 names ROLLBACK_FAILED.
  *Implemented default:* `wars.state` is `VARCHAR(16)` on both backends and the war service
  owns the valid set, so M19 can settle it without a schema change. The same reasoning keeps
  `claims.type` and `ledger.type` as strings in the row records: the `ClaimType` and
  `TransactionType` enums belong to M3 and M5, and M1 does not reach into their packages.
  *Date:* 2026-07-31

- **[M1]** SPEC 3.9 lists `defense_units` with `spawn_x/y/z` and `war_container_log` with
  `x/y/z`, neither with a world, yet SPEC 20 decision 4 lets a city hold territory in several
  worlds, so coordinates alone do not identify a location. *Implemented default:* added a
  `world` column to both. *Date:* 2026-07-31

- **[M1]** Several SPEC 3.9 tables list no primary key and have no stable natural one:
  `player_quests` (daily quests reset, so the same player and quest recur), `war_kills`,
  `contest_entries`, `contest_votes`. *Implemented default:* each gets a surrogate
  auto-increment `id`. *Date:* 2026-07-31

- **[M1]** SPEC references a city ban list (5.2, 8.6), bounties (4.7), player shops (4.5) and
  the city vault (5.7), none of which have a table in SPEC 3. *Implemented default:* left out
  of V1. M1's deliverable is "all tables from Section 3", and each of these is added by the
  milestone that implements it as V2, V3 and so on, which also exercises the migration runner
  on a real upgrade rather than only ever on a fresh install. *Date:* 2026-07-31

- **[M1]** SPEC 16.1 defines `storage.mysql.pool-size` but nothing equivalent for SQLite, and
  the SQLite path needs a file name, a journal mode and a lock timeout. *Implemented
  default:* added `storage.sqlite.file`, `storage.sqlite.journal-mode` (WAL),
  `storage.sqlite.pool-size` (4) and `storage.sqlite.busy-timeout-ms` (5000), plus
  `storage.slow-query-warn-ms` (250) for diagnostics. WAL is the default because the war
  block logger writes in large batches and readers must not block behind it.
  *Date:* 2026-07-31

- **[M1]** SPEC 16.1 enables `storage.backup` for both backends, but a correct MySQL backup
  means `mysqldump` or a storage-level snapshot, and a plugin shelling out to an external
  binary it cannot verify would give operators false confidence. *Implemented default:*
  SQLite is backed up in-process with `VACUUM INTO`, which is consistent while the server
  runs. On MySQL the service writes nothing and says so once at startup, so the operator
  learns before they need a backup rather than after. *Date:* 2026-07-31

- **[M2]** SPEC 5.1 charges a 10,000 C creation fee, but the economy module is M5 and PLAN.md
  makes M5 depend on M2, not the reverse. *Implemented default:* a narrow `Funds` interface
  in M2 with a storage-backed implementation over `PlayerDao` and `LedgerDao`; M5's economy
  service will implement the same interface and replace it. Every method takes a
  `Connection`, so the fee lands in the same transaction as the city insert. Charging a
  founder and then failing to create their city is the failure mode this rules out.
  *Date:* 2026-07-31

- **[M2]** SPEC 2.3 says the custom events are "all cancellable", but SPEC 5.1 lists
  `CityCreateEvent` as step 9, after the city has been written. Cancelling at that point
  would mean undoing a committed transaction. *Implemented default:* every event fires
  *before* its mutation, which is the only point at which cancelling can mean anything.
  *Date:* 2026-07-31

- **[M2]** SPEC 5.1 precondition 2 requires two hours of *active* playtime, but
  `active_playtime_ms` is the anti-AFK-filtered counter defined in SPEC 4.2.1, which M9
  implements. Without something feeding it, no city could ever be founded. *Implemented
  default:* M2 credits unfiltered session time to both playtime counters and reads the live
  session so a player who joins and plays for three hours qualifies in one sitting. This is
  more permissive than SPEC 4.2.1 intends and never less, so nobody is wrongly blocked. M9
  replaces the accrual with the filtered version. *Date:* 2026-07-31

- **[M2]** SPEC 5.2 says rejoining the *same* city has no cooldown, but nothing records which
  city a player left. *Implemented default:* an invite exempts the joiner from the cooldown,
  an open-join walk-in does not. An invite is the city saying it wants that player back,
  which is the case the exemption exists for; a walk-in is exactly the war-day mercenary hop
  the cooldown exists to stop. *Date:* 2026-07-31

- **[M2]** SPEC 5.2 and 8.6 require a city ban list and SPEC 8.6 puts it in a GUI, but SPEC 9.2
  lists no `/city ban` command. *Implemented default:* the ban list, its table and the
  service methods exist in M2 because joining has to check them, but no command is added.
  The GUI in M8 will drive it. *Date:* 2026-07-31

- **[M2]** SPEC 5.1 preconditions 6 and 7 need claims, which are M3. *Implemented default:*
  both are plain queries rather than claim-engine work, "is this chunk claimed" and "is any
  claim within N chunks", so M2 enforces them through `ClaimDao` and writes the core claim
  row directly. The cost engine, adjacency, contiguity and the chunk cache remain M3's.
  *Date:* 2026-07-31

- **[M2]** SPEC 5.7 makes the member cap depend on the Population upgrade, which is M11.
  *Implemented default:* `memberCap` returns the base cap from `cities.yml` and ignores
  upgrade levels. The conservative direction: a city never gets capacity it has not paid for.
  *Date:* 2026-07-31

- **[M2]** SPEC 8.1's City Hall block is step 7 of SPEC 5.1, but the GUI it opens is M8.
  *Implemented default:* not implemented in M2. Founding writes everything else; the block is
  M8's, alongside the menu it exists to open. Likewise SPEC 17.1 cases 1 to 3, the inactivity
  sweeps, are deferred: cases 2 and 3 turn on claims becoming unprotected, which is land
  protection in M4. *Date:* 2026-07-31

- **[M3]** SPEC 2.3 asks for a single {@code long} packing `(worldId, chunkX, chunkZ)`, but
  two full `int` coordinates already fill all 64 bits. *Implemented default:* 12 bits for a
  session-scoped world index and 26 for each coordinate, giving 4,096 worlds and a reach of
  +/-33.5M chunks against a vanilla world-border cap of 1.87M. Out-of-range coordinates and
  a 4,097th world throw rather than wrapping: aliasing two chunks onto one key would hand a
  city another city's land. *Date:* 2026-07-31

- **[M3]** SPEC 18.1 asks for "all reference values in the Section 6.2 table, to within 1 C",
  but the table's *Cumulative* column cannot satisfy that. Seven rows are exact, and five
  (chunks 15, 30, 75, 150, 300) are rounded to a nice number and disagree with the formula by
  up to 18% (chunk 300 reads 79,000,000 against an actual 66,820,648). The per-chunk *Cost*
  column is exact everywhere, to within 0.4 C. *Implemented default:* tested every per-chunk
  cost, which is what the formula produces and what a player is charged, and treated the
  cumulative column as illustrative. *Date:* 2026-07-31

- **[M3]** SPEC 6.2 measures the distance multiplier from the core, but SPEC 20 decision 4
  lets a city hold land in several worlds, where there is no distance to the core at all.
  *Implemented default:* land outside the core's world is charged as if it sat inside the
  free radius. Treating it as infinitely far would make a second world unaffordable by
  accident rather than by design. Contiguity is likewise judged per-world, which decision 4
  states directly. *Date:* 2026-07-31

- **[M3]** SPEC 6.3 permits claiming into a world the city has no land in, but SPEC 6.1
  requires every normal claim to share an edge with existing land, and a city's first chunk
  in a new world can share an edge with nothing. *Implemented default:* refused, with a
  message pointing at outposts, which SPEC 7.1 defines as exactly the mechanism for reaching
  detached land. *Date:* 2026-07-31

- **[M3]** SPEC 6.3 preconditions 9 and 10 depend on the war system (M19) and admin-protected
  regions (M21), neither of which exists. *Implemented default:* both are written out as
  named methods that always pass, with their refusal messages already in `lang/`, so the
  milestone that adds them has one place to fill in rather than a check to remember.
  *Date:* 2026-07-31

- **[M3]** SPEC 6.5 specifies a 31x13 map with colours per category but no glyph.
  *Implemented default:* the glyph and its colour are both `lang/` keys
  (`claim.map.tile.*`), so a server can change either without a rebuild and the map is
  translatable. Ally and enemy tiles are wired but unreachable until M13 and M19.
  *Date:* 2026-07-31

- **[M3]** SPEC 9.2 lists `/city spawn` and `/city setspawn`, which M2 had stubbed as M3
  work, but neither is in M3's deliverable and SPEC puts both in the GUI (8.3 slot 40, 8.10
  slot 16). *Implemented default:* retargeted to M8. M3 still owns the claim-side half:
  unclaiming is refused on the spawn chunk, and SPEC 17.2 case 22 resets a stranded spawn to
  the core. *Date:* 2026-07-31

- **[M4]** SPEC 5.5 enumerates the protected containers and interactables by name, but a
  hand-written list of `Material` constants stops protecting new wood types the day they
  ship. *Implemented default:* classified through Bukkit's own tags (`Tag.DOORS`,
  `Tag.BUTTONS`, `Tag.PRESSURE_PLATES`, `Tag.BEDS`, `Tag.SHULKER_BOXES` and so on) plus the
  explicit SPEC list where no tag exists, with `protection.extra-containers`,
  `protection.extra-interactables` and `protection.unprotected` for operator overrides in
  either direction. Ender chests are deliberately unprotected: SPEC 5.5 does not list them
  and opening one shows the viewer their own inventory, so there is nothing to steal.
  *Date:* 2026-08-01

- **[M4]** SPEC 5.5 lists the protected actions but not which SPEC 5.4 flag each maps to.
  *Implemented default:* buckets, farmland trampling and entity damage are all gated on
  `BUILD`, because each destroys or changes what the city built; doors, plates and the rest
  on `INTERACT`; containers on `CONTAINER`, with `CONTAINER_READONLY` sufficient to open but
  not to take. The mapping lives in one enum so it can be read and changed in one place.
  *Date:* 2026-08-01

- **[M4]** SPEC 5.4 defines `CONTAINER_READONLY` as "open but not remove items", which cannot
  be enforced by allowing or denying the open alone. *Implemented default:* every
  `InventoryClickEvent` that would take from the container is checked against `CONTAINER`
  separately, covering pickups, shift-moves, hotbar swaps, drops and the double-click sweep.
  Depositing is left alone, since SPEC says "remove". *Date:* 2026-08-01

- **[M4]** SPEC 5.5 says an explosion is "fully disabled" inside claims, which could mean
  cancelling the event or removing the protected blocks from it. *Implemented default:*
  filtered the block list. An explosion straddling a border still flattens the wilderness
  half; cancelling outright would let a city's edge act as a shield for the land outside it.
  *Date:* 2026-08-01

- **[M4]** A claim whose city cannot be found is a state the game does not produce, since
  disbanding deletes the claims with the city. *Implemented default:* it reads as
  wilderness. Failing closed would freeze that land permanently with nobody able to release
  it; failing open lets an admin or a player clear it up. *Date:* 2026-08-01

- **[M4]** SPEC 5.5's four "except in war" clauses, and SPEC 17.1 case 2's dormant cities,
  both need systems that do not exist (M19 and the inactivity sweep). *Implemented default:*
  three named methods on `ProtectionService` that always answer "no war, not dormant", so
  the branches already exist and the milestone that adds either changes one method rather
  than auditing eight listeners. *Date:* 2026-08-01

- **[M4]** M2 deferred SPEC 17.1 cases 1 to 3, the inactivity sweeps, to M4 on the grounds
  that cases 2 and 3 turn on claims becoming unprotected. Delivering them needs a V3
  migration for a dormancy column and a scheduled maintenance task, neither of which is in
  M4's deliverable. *Implemented default:* M4 provides the protection-side half, a dormancy
  check in the decision path. The sweep that *sets* dormancy is still unowned and needs a
  home in a later milestone. *Date:* 2026-08-01

- **[M5]** SPEC 8.5 caps a non-mayor at "25% of the treasury per 24 hours" without saying 25%
  of *which* treasury: what it held when the window opened, or what it holds at each
  withdrawal. *Implemented default:* of the treasury at the moment of each withdrawal.
  Measuring against the opening balance would let a member take a quarter, wait for a large
  deposit, and take a quarter of the larger figure, which is exactly the drain SPEC 17.6 case
  71 exists to stop. A consequence worth knowing: taking the allowance in small bites gets
  strictly less than taking it in one, because the allowance shrinks with the treasury it
  measures. *Date:* 2026-08-01

- **[M5]** SPEC 4.8 requires circulation to be compared week over week, but SPEC 3 defines no
  table to keep the readings in, so there is nothing to compare against after a restart.
  *Implemented default:* V3 adds `economy_snapshots` (timestamp, wallets, treasuries), written
  hourly per `economy.inflation.log-interval-minutes` and pruned to
  `economy.inflation.keep-days`. *Date:* 2026-08-01

- **[M5]** SPEC 4.3's worked example, "roughly 22,700 C upkeep for a mature 100-chunk,
  10-member city", only holds if that city bought its land at solo prices. A real 10-member
  city pays the SPEC 6.2 member-divided price, so its land value, and therefore its 0.4%
  upkeep, is about 2.6x lower, around 8,650 C. *Implemented default:* the formula follows SPEC
  4.3 literally (0.4% of what the land actually cost) and both figures are asserted in
  `UpkeepCalculatorTest`, so whichever the developer intended is visible. The margin in SPEC
  4.3's design note is therefore wider than it reads. *Date:* 2026-08-01

- **[M5]** SPEC 17.3 case 32 orders the outermost chunks auto-unclaimed for debt, but SPEC 6.1
  forbids any unclaim that would split the city. The two can conflict: the furthest chunk may
  be the one holding the rest together. *Implemented default:* contiguity wins. The candidate
  list is filtered to chunks that may legally go, sorted furthest-first, and re-asked after
  every release, because removing the furthest chunk often makes the next-furthest safe. A
  city whose shape leaves nothing releasable simply stays in debt. *Date:* 2026-08-01

- **[M5]** SPEC 4.6 lists `UPKEEP_FAILED` as a ledger type, but a failed charge moves no money
  and SPEC 3.6 gives `ledger.amount` no nullable case. *Implemented default:* the row records
  the amount that *would* have been taken, negative, with the treasury unchanged in
  `balance_after` and `{"reason":"insufficient_treasury"}` in the metadata. An admin reading
  the ledger can see what was owed, not merely that something failed. *Date:* 2026-08-01

- **[M5]** SPEC 9.1 lists `/money` and `/balance` as aliases of one command, and SPEC 20
  decision 7 wants a Vault economy provider, but Vault's `Economy` interface is synchronous
  and SPEC 2.1 forbids database access on the server thread. *Implemented default:* Vault
  reads are served from the in-memory balance cache and Vault writes are dispatched async and
  reported as succeeding. Documented prominently on the class: a plugin that reads a balance
  back immediately after writing may see the old value for a tick. *Date:* 2026-08-01

- **[M6]** SPEC 9.1 defines `/shop` as "Open the server market GUI", but the GUI framework is
  M7 and every menu is M8, so at M6 there is nothing to open. *Implemented default:* `/shop`
  prints the same catalogue and prices in chat, with `/shop buy <item> [amount]` behind it,
  so the market is usable now. M8 replaces the presentation and keeps every service call
  underneath it unchanged. *Date:* 2026-08-01

- **[M6]** SPEC 4.4 gives one price per unit at the current stock, which leaves what a *batch*
  costs undefined: one price for the whole stack, or the curve walked unit by unit.
  *Implemented default:* walked. Pricing a batch at the opening price would let one player
  sell ten thousand pumpkins at the first pumpkin's price, which is the opposite of SPEC
  4.4's stated intent that "the first player to sell pumpkins gets rich and the hundredth
  does not". A consequence worth knowing: splitting a sale can never beat making it in one
  go, because both walk the same curve. *Date:* 2026-08-01

- **[M6]** SPEC 4.5 gives a shop sign four lines, of which two carry the quantity and the
  prices and one the owner, leaving nowhere to name the item being traded. *Implemented
  default:* the shop trades whatever plain item its chest already holds, read when the sign
  is written. Stocking the chest first is what a player does anyway, and it removes a line of
  spelling for every material name in the game. *Date:* 2026-08-01

- **[M6]** SPEC 4.5 gives the sign syntax `B <price>` and `S <price>` without saying whose
  point of view it is written from. *Implemented default:* the customer's, which is the
  convention every chest-shop plugin uses: `B 100` means "you may buy this for 100", so the
  shop pays out on `S` and takes in on `B`. A sign whose `S` price is above its `B` price is
  refused outright, because anyone could trade it in circles until the owner was bankrupt.
  *Date:* 2026-08-01

- **[M6]** SPEC 4.4 excludes items obtainable from fully automatic farms from the market but
  gives no mechanism, and SPEC 9.4.4's `/ca market setprice` implies items can be added at
  runtime. *Implemented default:* the market trades exactly what `economy.yml` lists, and the
  shipped list is the SPEC 4.4 table with nothing added. There is no code path that buys an
  unlisted material, so the exclusion is enforced by absence rather than by a blocklist that
  a later milestone could forget to check. *Date:* 2026-08-01

- **[M6]** SPEC 4.3 charges a 5% market tax and SPEC 5.7's Market Access upgrade reduces it,
  but city upgrades are M11, so there is no level to read. *Implemented default:* a named
  method returning 0, so everyone pays the full rate until the upgrade exists. Likewise the
  SPEC 11.9 winner's +10% sell bonus is a method returning 1 until M19. Nobody receives a
  discount they have not bought. *Date:* 2026-08-01

- **[M7]** SPEC 8.5 asks for "sign-input or anvil-input" when a player types their own amount,
  but both mean showing a second fake window: an anvil prompt is a real anvil inventory whose
  rename field is read, and a sign prompt is a sign the client is told to edit. Both can
  desynchronise, and neither is a thing SPEC describes the behaviour of. *Implemented
  default:* the prompt closes the menu and asks in chat, with a configurable cancel word and
  timeout. SPEC 17.5 cases 67 and 68 are questions about parsing rather than about the
  widget, and are answered identically either way. If the developer wants the anvil, it
  replaces one class and no caller. *Date:* 2026-08-01

- **[M7]** SPEC 8 requires every menu to be defined in YAML "so layouts can be changed without
  recompiling", but does not say how much of a menu lives in the file. Putting the click
  actions there would let an operator wire the Disband button to something else by editing a
  text file. *Implemented default:* the file owns appearance and position, Java owns
  behaviour. A layout entry names a slot, a material, a label key and lore keys; nothing in
  it names an action. Entries are looked up by key, so moving a button in the file does not
  move it in the code. *Date:* 2026-08-01

- **[M7]** SPEC 17.5 case 61 says every click and drag in a plugin GUI is "cancelled
  unconditionally", which read literally would also cancel a player rearranging their own
  hotbar while a menu happens to be open. *Implemented default:* unconditional inside the
  menu itself, and inside the player's own inventory only for the click types that can move
  an item into the menu (shift-click, number key, offhand swap, double-click, drop). A plain
  click on their own items is left alone, because their items are their own.
  *Date:* 2026-08-01

- **[M7]** SPEC 8.2 fixes Back on slot 45 and Close on 49, and SPEC 8.2 also puts the
  pagination arrows on 48 and 50, all of which sit on the bottom border row. *Implemented
  default:* the border is drawn first and buttons paint over it, so a page with no next page
  shows the border pane rather than a hole. A missing arrow is therefore invisible rather
  than greyed out, which SPEC does not specify but is the only option that leaves the row
  looking deliberate. *Date:* 2026-08-01

- **[M7]** SPEC 19 gives M7 no screens, so nothing in the framework would ever have been run
  before M8. *Implemented default:* two minimal menus in the *test* sources only, driving the
  listener tests against real inventories. No screen, no command and no layout file beyond
  the shared `gui/common.yml` ships in M7, because SPEC 8's screens are M8's deliverable.
  *Date:* 2026-08-01
