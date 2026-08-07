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

- **[M8]** SPEC 8.3's hub names thirteen screens, and seven of them (Defense, Wars, Diplomacy,
  Upgrades, Vault, Outposts, Contests) belong to milestones PLAN.md orders *after* M8. A Wars
  menu with no war system would mean inventing behaviour. *Implemented default:* those seven
  keep their SPEC slots and icons and render through the framework's refusal path, with lore
  saying the system is not available on this server yet. M10 to M19 each replace one line in
  `MainMenu` rather than rearranging the hub. **SPEC 8.8 (Wars) and 8.9 (Defense) therefore
  have no screen at all after M8**, which is recorded in PLAN.md's note rather than hidden.
  *Date:* 2026-08-03

- **[M8]** SPEC 8.4's Claims menu puts a live 3x3 minimap on the bottom row, and SPEC 8.2 fixes
  Back on slot 45 and Close on 49 - both inside that row. *Implemented default:* the minimap
  owns slots 45 to 53, and Back and Close move to 53 and 44. The alternative, overlapping
  them, would put a Close button in the middle of the map. This is the one screen that does
  not use the framework's automatic navigation. *Date:* 2026-08-03

- **[M8]** SPEC 8.5 shows a member their withdrawal allowance, but the SPEC 8.5 cap is derived
  from the ledger, which is a database read and may not happen on the server thread while a
  menu is being drawn. *Implemented default:* the screen draws the figure it was last told
  and asks for a fresh one; being a live screen, the new number appears a tick later. The cap
  itself is enforced by the service, so a label one tick old cannot let anyone past it.
  *Date:* 2026-08-03

- **[M8]** SPEC 8.1 says the City Hall "cannot be broken by anyone below Co-Mayor", but ranks
  are fully editable (SPEC 5.4) and a city may well have renamed or deleted the rank called
  Co-Mayor. *Implemented default:* the rule is a rank *weight* from `cities.yml`
  (`city-hall.min-break-weight`, default 80, which is Co-Mayor's default weight). A city that
  restructures its ranks keeps a working rule. *Date:* 2026-08-03

- **[M8]** SPEC 8.1 gives the mayor one free City Hall replacement "if somehow destroyed", but
  nothing can prove it was destroyed. *Implemented default:* `/city hall` gives the mayor the
  item whenever they ask. The block opens a menu that `/city` opens anyway, so a spare one
  grants nothing; policing a limit would cost more than the thing being policed.
  *Date:* 2026-08-03

- **[M8]** SPEC 5.4 forbids editing a rank whose weight is at or above your own, which means
  the Mayor rank is not editable by the mayor either. *Implemented default:* left exactly as
  SPEC says. The Mayor rank holds ALL permissions by SPEC 5.4, so there is nothing to edit,
  and the alternative (a special case letting the mayor edit their own rank) would be the one
  hole in the rule. The permission editor shows that rank as read-only with the reason.
  *Date:* 2026-08-03

- **[M8]** SPEC 8.10 asks for a "double confirmation, type-name-to-confirm" on disband.
  *Implemented default:* both, in that order: the framework's confirmation dialog, then the
  city's name typed in chat and compared case-insensitively. The dialog catches a misclick;
  the typed name catches the player who clicks through dialogs without reading them.
  *Date:* 2026-08-03

- **[M9]** SPEC 13.1 says quest difficulty scales with playtime "so veterans do not trivially
  clear beginner quests, but rewards scale with it too, so the effort-to-reward ratio stays
  flat", and gives no formula for either. *Implemented default:* one factor multiplies both
  the target and the reward, so the ratio is flat by construction rather than by tuning and
  no later change to the curve can quietly make veteran quests a better or worse deal per
  unit of work. The curve itself is config: a linear ramp from 1x at no playtime to
  `income.quests.max-scale` (2.5) at `income.quests.scale-hours` (100), flat after that so a
  veteran's quests stop growing rather than becoming a second job. *Date:* 2026-08-03

- **[M9]** SPEC 3.9 gives `player_quests` a progress column but no target and no reward, and
  SPEC 13.2's weekly challenges have no table in SPEC 3 at all. *Implemented default:* V5 adds
  `target` and `reward` to `player_quests` and creates `city_challenges`. The two columns are
  stored with the assignment rather than recomputed, because SPEC 13.1 scales them with a
  playtime that keeps rising: recomputing would move the goalposts under a player who is
  halfway through a quest. *Date:* 2026-08-03

- **[M9]** SPEC 4.2 lists the daily login as an income source but does not say whether it is
  claimed or paid. *Implemented default:* paid automatically on join. A daily reward a player
  has to remember to collect rewards remembering, and SPEC 4.2 lists this beside the playtime
  stipend rather than beside the quests. A refusal (already claimed, or too new) is silent,
  because "you already claimed today" on every relog is noise. *Date:* 2026-08-03

- **[M9]** SPEC 4.2.1 defines the anti-AFK check per interval but does not say what happens to
  `active_playtime_ms` in an interval that fails it. *Implemented default:* it is not
  credited either. Active playtime is what the SPEC 5.1 founding gate and the SPEC 6.2 member
  divisor are measured in, so an AFK machine accumulating it would defeat SPEC 17.6 case 69
  even while earning no money. This replaces the deliberately over-permissive placeholder M2
  shipped, and tightens both of those gates retroactively. *Date:* 2026-08-03

- **[M9]** SPEC 13.2 pools challenge progress across a city but SPEC 13.1's playtime scale has
  no meaning for a city, which has no single playtime. *Implemented default:* challenge
  targets are not scaled at all; they are city-sized in config instead. Scaling by, say, the
  mayor's playtime would make a challenge harder because one member played more.
  *Date:* 2026-08-03

- **[M10]** SPEC 7.2 checks the 8-chunk minimum from another city only when an outpost is
  founded, so a city may later expand to within one chunk of somebody else's outpost. SPEC
  says nothing about what should happen then. *Implemented default:* nothing happens. The
  outpost stays where it is, and the growing city is not blocked. Inventing either rule (a
  claim refused because of a foreign outpost, or an outpost pushed out by a neighbour's
  growth) would be a land-grab mechanic SPEC never asked for. *Date:* 2026-08-03

- **[M10]** SPEC 7.2 measures an outpost's 32-chunk minimum "from own city", which is
  ambiguous once a city already has outposts: are they part of the city for this purpose?
  *Implemented default:* no. The distance is measured from non-outpost claims only, so two
  outposts may sit beside each other. The rule exists to stop outposts being used to step
  past SPEC 6.1 adjacency, and a cluster of outposts 32 chunks from home does not do that,
  since none of them is contiguous with anything. *Date:* 2026-08-03

- **[M10]** SPEC 7.4's auto-conversion says the outpost "converts to a normal claim" but not
  what happens to its `cost_paid`, which is what a later unclaim refunds half of.
  *Implemented default:* kept as it was, so a converted chunk refunds half of the outpost
  premium rather than half of an ordinary chunk. The city paid that money; SPEC 7.4 declines
  to give it back at conversion, and quietly shrinking the refund would take it away twice.
  *Date:* 2026-08-03

- **[M10]** SPEC 4.6 lists `OUTPOST_CREATE` as a ledger type, but SPEC 7.2 prices an outpost
  as a chunk purchase, which the claim engine already records as `CHUNK_CLAIM`. Writing both
  as charges would double the money in an audit. *Implemented default:* the treasury is
  charged once, under `CHUNK_CLAIM`, and a second `OUTPOST_CREATE` row records the same
  amount with metadata naming where it was accounted. An admin searching either type finds
  the event; summing both would double-count, which the metadata says out loud.
  *Date:* 2026-08-03

- **[M10]** SPEC 7.2 gives outpost teleport a cost, a warmup and a cooldown but does not say
  when the 100 C is taken. *Implemented default:* on arrival. A player knocked out of the
  warmup by damage has not travelled and should not have paid, and charging up front would
  make interrupting somebody a way to take their money. *Date:* 2026-08-03

- **[M11]** SPEC 5.7 and SPEC 12.4 contradict each other on Fortification. SPEC 5.7's table
  says "+5% defense unit health, **+1 max unit**"; SPEC 12.4 says "Maximum active units:
  5 + (**2** per Fortification level), so 5 to 15", and 5 + 2x5 = 15 makes SPEC 12.4
  internally consistent while SPEC 5.7's "+1" is not. *Implemented default:* M11 only stores
  the level; nothing reads it until M12. `cities.yml` ships
  `upgrades.fortification.units-per-level: 1`, following SPEC 5.7's table, and M12 will read
  that key. **This needs a developer decision before M12**: it is the difference between a
  maxed city fielding 10 units or 15. *Date:* 2026-08-03

- **[M11]** SPEC 5.7's Outpost Range grants "+1 max outpost" over five levels from a base of
  2, which reaches 7, but SPEC 7.2 says "2 base, up to 6". *Implemented default:* the ceiling
  wins. `upgrades.outpost-range.max-total` caps the total at 6, so the fifth level of the
  track buys nothing. That is the conservative reading, because SPEC 7.2's number is the one
  a player is promised; the alternative is a seventh outpost SPEC says does not exist.
  *Date:* 2026-08-03

- **[M11]** SPEC 5.7 grants the vault "+1 shared vault page (27 slots)" per level and states
  no base. *Implemented default:* a city that has bought nothing has no vault at all, so the
  first level of the track is what unlocks the feature. Granting a free page would make the
  30,000 C first level buy a second page rather than the vault, and SPEC 11.7 leans on the
  vault being something a city chose to have. *Date:* 2026-08-03

- **[M11]** SPEC 5.7 says nothing about buying levels out of order or in bulk. *Implemented
  default:* one level at a time, in order, at that level's listed price. The total cost of a
  track is then the sum of its column in SPEC 5.7, which is how the numbers read.
  *Date:* 2026-08-03

- **[M11]** The vault inverts the GUI framework's central rule. M7's listener cancels every
  click in a plugin inventory (SPEC 17.5 cases 61 to 63), and a vault is a container where
  items must move. *Implemented default:* the vault is not a framework menu at all. It uses
  its own `VaultHolder`, so M7's listener never sees it, and the hardening on every other
  screen is untouched. A page is one shared inventory rather than one per viewer, because two
  members each holding a copy would duplicate whatever the second one closed over.
  *Date:* 2026-08-03

- **[M12]** SPEC 5.7 and SPEC 12.4 contradict each other on how many extra units a
  Fortification level allows: SPEC 5.7's upgrade table says "+1 max unit", SPEC 12.4 says
  "5 + (2 per Fortification level), so 5 to 15". *Resolved in favour of SPEC 12.4*, on the
  developer's instruction: 12.4's stated range is only arithmetic at +2, which makes 5.7's
  "+1" the typo of the two. A maxed city fields 15 units. The number now lives in exactly one
  place, `defense.yml`'s `placement.units-per-fortification-level`; the duplicate in
  `cities.yml` was removed, because shipping the contradiction in two config files was worse
  than having it in the specification. *Date:* 2026-08-03

- **[M12]** SPEC 12.4 says a purchase gives a spawn egg to be placed by hand, but does not say
  what happens if the buyer never places it. *Implemented default:* the money is gone and the
  egg is an ordinary item they keep. Refunding an unplaced egg would make the SPEC 12.4
  wartime double-price meaningless, since a city could buy at peacetime rates and hold the
  eggs. The egg carries the city id, so it cannot be given to another city and placed there.
  *Date:* 2026-08-03

- **[M12]** SPEC 12.3 says a unit is "teleported back if it wanders more than 8 blocks past
  the claim border", which needs a live tick to enforce. *Implemented default:* the rule and
  its distance are in `DefenseBehaviour.shouldReturn` and tested, but nothing calls it yet:
  units are given no wander goal and the Sentry has zero movement speed, so in practice they
  stay put. The leash matters when a unit is chasing something, which only happens in war, so
  the tick belongs with M19 where there is something to chase. *Date:* 2026-08-03

- **[M12]** SPEC 12.5 says units "must not count toward the mob cap", with the qualifier
  "where possible". *Implemented default:* not attempted. Paper offers no supported way to
  exclude a specific entity from spawn calculations without NMS, and SPEC 2.1 forbids NMS
  unless unavoidable. `setPersistent(true)` already stops them despawning, which is the part
  that would actually lose a city its money. *Date:* 2026-08-03

- **[M13]** SPEC 14.1 lists five relation states and SPEC 3.9 gives `alliances` a `state`
  column, but SPEC names no states for that column, and SPEC 14.2's 24-hour notice period
  needs one: during it the alliance "still holds", so it is neither active-and-settled nor
  ended. *Implemented default:* `PENDING`, `ACTIVE`, `BREAKING`, `BROKEN`, with `BREAKING`
  counting as allied everywhere. `BROKEN` rows are kept rather than deleted, because the
  seven-day re-ally cooldown is timed from when the break completed and deleting the row
  would lose the only record of that. *Date:* 2026-08-04

- **[M13]** SPEC 3.9 gives `alliances` four columns, none of which can carry the notice
  timer, the reciprocal build grant, or which city proposed. *Implemented default:* V7 adds
  `state_changed_at`, `trusted` and `proposed_by`. Without `proposed_by` either city could
  accept its own proposal, which is not an agreement. *Date:* 2026-08-04

- **[M13]** SPEC 14.3 gives `/truce offer` and `/truce accept`, but describes no way to
  refuse and no consequence of ignoring an offer, and a truce restricts both parties
  identically. *Implemented default:* offering agrees it. `/truce accept` is therefore not
  registered. The conservative reading is that a pact nobody can be harmed by needs no
  defence against being given one. *Date:* 2026-08-04

- **[M13]** SPEC 14.1 says a relation is exactly one state per pair, but a pair can be allied
  and under truce at once, since SPEC 14.3 imposes a truce automatically after a war and
  nothing forbids allying during one. *Implemented default:* precedence is war, then truce,
  then alliance, then the post-war enemy marker. A truce outranks an alliance because it is
  the one with an end date and the one that blocks a declaration. *Date:* 2026-08-04

- **[M13]** SPEC 14.2 says allies "may join each other's wars" and SPEC 14.1 lists `AT_WAR`
  and `ENEMY`, both of which need the war system in M19. *Implemented default:*
  `DiplomacyService.isAtWar` and `isRecentEnemy` are named methods that answer no, so M19
  fills in two methods rather than auditing the relation table. *Date:* 2026-08-04

- **[M13]** SPEC 19 gives M13 "alliance chat" but SPEC 9.2's `/cc` city chat was never
  assigned to a milestone and is still the M0 stub. *Implemented default:* `/ac` is
  implemented and `/cc` is left alone, since M13's deliverable names only the alliance
  channel. City chat is still unowned and needs a home. *Date:* 2026-08-04

- **[M13]** Nothing cleaned up after a disbanded city beyond its claims: `UpgradeService`
  and `DefenseService` both had a removal method that no caller reached, so a city id reused
  by a later founding would inherit bought upgrade levels and a garrison. *Implemented
  default:* `CityService.onCityDisbanded` is a hook that runs after the transaction commits,
  and diplomacy, upgrades and defense are all registered on it. A `CityDisbandEvent` listener
  could not be used: that event fires before the mutation so it can be cancelled.
  *Date:* 2026-08-04

- **[M14]** SPEC 13.3 says "There are seven, all shown with equal prominence", and SPEC 19's
  M14 row repeats "All seven leaderboards", but the table in SPEC 13.3 lists **nine** rows
  (Wealth, Cities by Treasury, Cities by Size, Cities by Population, Contest Champions, War
  Record, Contribution, Builder, Farmer) and no section anywhere enumerates a set of seven.
  *Implemented default:* all nine. The count appears twice and the list once, but only the
  list says which, and dropping two would mean choosing which two on no authority at all.
  Implementing every board SPEC names invents nothing; picking a subset would.
  *Date:* 2026-08-05

- **[M14]** SPEC 13.3 defines Contribution as "Lifetime treasury deposits (personal)", and
  `city_members.contributed_total` already holds a per-member deposit total that SPEC 8.5's
  in-city contribution list uses. It is not the same number: the membership row is deleted
  when a player leaves a city, so that column measures what someone has given the city they
  are in now. *Implemented default:* the board sums `TREASURY_DEPOSIT` rows from the ledger,
  grouped by actor. SPEC 3.6 never deletes from the ledger, so it is the only lifetime record
  there is, and SPEC 1.5 already makes it the authority. Only positive amounts are counted,
  because a treasury movement writes both sides under the same type and actor and an
  unfiltered `SUM` nets to zero. *Date:* 2026-08-05

- **[M14]** SPEC 13.3's Builder and Farmer boards rank blocks placed and crops harvested, but
  nothing persisted either: M9 reports both metrics into `player_quests.progress`, which SPEC
  13.1 reassigns every day. *Implemented default:* V8 adds a `player_stats` table of lifetime
  counters, one row per (player, counter), following the M1 pattern of adding a table in the
  milestone that needs it. One row per counter rather than a column per counter on `players`,
  so a future board is an enum constant and no migration. *Date:* 2026-08-05

- **[M14]** SPEC 13.3 names the boards and what each ranks, but gives no board size, no page
  size and no refresh rate, and SPEC 19 asks for "caching" without saying how fresh.
  *Implemented default:* `leaderboards.size` (25), `page-size` (10),
  `refresh-interval-minutes` (5) and `stats-flush-seconds` (30) in `events.yml`, which already
  owns the other SPEC 13 progression features that are not income. These four numbers are this
  implementation's, not the specification's. The refresh interval is also the staleness a
  player can see: someone who deposits does not move up Contribution until the next sweep.
  *Date:* 2026-08-05

- **[M14]** SPEC 13.3's Contest Champions and War Record rank data that M15 and M19 produce,
  so at M14 there is nothing to rank, and SPEC does not say what a board with no data source
  should do. *Implemented default:* both are listed and both report themselves *unavailable*,
  which is deliberately distinct from empty. "Nobody has any contest points" and "contests do
  not exist on this server yet" are different statements, and showing the first when the
  second is true is how a player concludes the feature is broken. The War Record ordering SPEC
  13.3 specifies (wins, then fewest losses) is settled and tested now, while it is being read
  out of the specification, rather than reconstructed in M19. *Date:* 2026-08-05

- **[M14]** SPEC 13.3 defines the Builder metric as "blocks placed (excluding war zones)", but
  war zones are M19. *Implemented default:* `StatsService.isInWarZone` answers false, in the
  same shape as the `ProtectionService` war seams, so the exclusion already has its branch and
  M19 changes one method rather than remembering that a leaderboard depended on it.
  *Date:* 2026-08-05

- **[M15]** SPEC 13.4 step 4 has voters score an entry "1 to 10 across three axes: Creativity,
  Technical Skill, Theme Fit", but SPEC 3.9's `contest_votes` carries a single `score` column.
  *Implemented default:* V9 adds a column per axis and keeps `score` as the combined figure
  the tally uses, the unweighted mean of the three. Equal weight per axis, because SPEC 13.4
  lists them without ranking them. *Date:* 2026-08-05

- **[M15]** SPEC 13.4 requires that "entries must be built during the contest window (verified
  against block placement logs)", but no block placement log exists: the one SPEC 11.8.1
  specifies is scoped to a war zone and belongs to M17, and M14's lifetime counters are
  per-player totals with no region or time in them. *Implemented default:*
  `ContestService.canVerifyBuildWindow` answers false, `events.yml` keeps its
  `verify-built-during-window` key, and the server logs a warning at startup when the operator
  has asked for a check this build cannot perform. Quietly passing every entry would let an
  operator believe the check was running, which is worse than not having it.
  *Date:* 2026-08-05

- **[M15]** SPEC 13.4 words two anti-abuse rules differently: "Players cannot vote for their
  own city" against "Votes from accounts sharing an IP with a member of the entered city are
  discarded". *Implemented default:* the difference is kept. A self-city vote is refused and
  the player told why. A shared-connection vote is accepted, stored, and weighed at zero.
  Telling the voter their vote was discarded for sharing a connection would report on another
  account's connection to somebody who did not ask and has no business knowing; storing at
  zero achieves what SPEC 17.6 case 72 wants, which is only that the vote does not count.
  *Date:* 2026-08-05

- **[M15]** SPEC 13.4's IP rule needs to know how players connect, and nothing in SPEC 3
  stores anything about a connection. *Implemented default:* V9 adds `player_logins` holding
  `SHA-256(salt || address)` and never the address. The rule only ever asks whether two
  accounts connect from the same place, which a hash answers. The salt is generated once and
  kept in a file beside the database, so a stolen copy of the table cannot be reversed by
  hashing candidate addresses, and losing the salt fails the rule *open* (nothing is
  discarded) rather than discarding everyone's votes. A separate table rather than a column on
  `players` so it can be purged on its own. *Date:* 2026-08-05

- **[M15]** SPEC 13.3 ranks Contest Champions by "cumulative contest points" and defines
  contest points nowhere. *Implemented default:* the sum of a city's finished, qualifying
  entry scores. It rewards entering often and doing well, and cannot be gamed by a city that
  entered once. *Date:* 2026-08-05

- **[M15]** SPEC 13.4 does not say how to break a tie between two entries on the same score,
  nor what an entry that nobody voted for should win. *Implemented default:* the earlier
  submission wins the tie, which is the one tiebreak that cannot be arranged after the votes
  are in; and an entry with a score of zero is placed but paid nothing, so entering unopposed
  is not a way to farm the treasury. *Date:* 2026-08-05

- **[M15]** SPEC 13.4 step 4 says a visitor is teleported "to a viewing platform above the
  build, spectator-ish, no build permission". *Implemented default:* teleported above the
  region, with no platform built and no game mode changed. An entry sits inside the entrant's
  own claims, so M4's protection already stops a visitor touching anything; placing blocks in
  somebody's city to make a viewing stand, or moving a player into spectator, are both larger
  interventions than SPEC describes. *Date:* 2026-08-05

- **[M15]** SPEC 13.4 gives no length for the contest state list and SPEC 3.9's `contests`
  table carries `state` without naming its values. *Implemented default:* BUILDING, VOTING,
  SCORING, FINISHED. SCORING is a state of its own rather than a moment inside the tally
  because the tally moves money into treasuries, and a server that dies halfway through one
  must come back knowing it was mid-tally rather than replaying the payouts.
  *Date:* 2026-08-05

- **[M15]** SPEC 9.4.6 gives `/ca contest start|end|disqualify`, but PLAN.md assigns every
  SPEC 9.4 command to M21. *Implemented default:* M15 builds and tests the service methods,
  including disqualification with its mandatory reason, and registers no admin command. A
  disqualified entry is marked rather than deleted, because its votes reference it and an
  admin action that erased its own evidence would be the one thing in this plugin that cannot
  be audited. *Date:* 2026-08-05

- **[M16]** SPEC 13.5 calls server events "automatic, scheduled, config-driven" and lists what
  each one does, but never says how often an event fires, how the next is chosen, or whether
  two may overlap. *Implemented default:* one at a time; a fixed interval after the last one
  ends (`events.interval-hours`, 12); a weighted draw (`weight` per event); and a repeat
  cooldown (`events.default-cooldown-hours`, 72, overridable per event) so a weighted draw
  cannot hand out the same rare event twice in a fortnight. **None of these four numbers is
  from SPEC.** One at a time because Market Boom and Tax Holiday together compound into a
  multiplier nobody designed, and SPEC 4.1 is explicit that the economy's properties are
  deliberate. *Date:* 2026-08-05

- **[M16]** SPEC 13.5's Gold Rush is "ore generation bonus via a temporary loot modifier".
  Generation cannot be what changes: the chunks a player mines were generated long before the
  event began, and Paper exposes no supported hook into world generation or loot tables
  without NMS, which SPEC 2.1 forbids unless unavoidable. *Implemented default:* the event
  multiplies what an ore block *drops*, on `BlockDropItemEvent`, which is the effect a player
  would describe as a gold rush and the one that can actually be delivered. On the drop event
  rather than the break so a silk-touch pick is included and a block broken by anything other
  than a player is not. *Date:* 2026-08-05

- **[M16]** SPEC 3 lists no table for a running event, but SPEC 13.5's Founders' Week lasts
  seven days, so an event outliving a restart is the normal case. *Implemented default:* V10
  adds `server_events`, one row per run, closed when the event ends. An open row whose window
  has passed is what an outage looks like, and it is closed on startup rather than resumed;
  one still inside its window resumes with the right time remaining. Finished rows are kept,
  because SPEC 13.5 events move real money and SPEC 1.5 makes that auditable.
  *Date:* 2026-08-05

- **[M16]** SPEC 13.5's Tax Holiday sets "market tax 0%" and Double Upkeep sets "upkeep
  doubled", but neither says how it combines with the SPEC 5.7 upgrade that changes the same
  number. *Implemented default:* the tax holiday **overrides** the rate, so a city's bought
  Market Access discount is untouched and is still there when the holiday ends; Double Upkeep
  **multiplies** the already-discounted figure, so a city that paid for cheaper upkeep keeps
  its discount and pays double the discounted amount. Multiplying the tax by zero would have
  silently discarded a purchase; multiplying upkeep before the discount would have cancelled
  one. *Date:* 2026-08-05

- **[M16]** SPEC 13.5's Invasion pays a city "proportional to mobs killed inside their claims",
  which taken literally would pay a city for any hostile mob, including the ones from its own
  dark rooms and the cave under it. *Implemented default:* only mobs this event spawned pay
  out, stamped in their persistent data with the invasion's id. They are also spawned just
  *outside* a city's border rather than inside it: SPEC 13.5 says "near city borders", and
  dropping twenty hostiles into somebody's town square would damage the build that SPEC 1.2's
  rollback promise exists to protect. *Date:* 2026-08-05

- **[M16]** SPEC 13.5's Harvest Festival doubles "crop growth rate", which Minecraft exposes
  no per-block-type control over. *Implemented default:* when a crop grows naturally, the
  extra stages the multiplier buys are applied on top, so a multiplier of 2 advances the crop
  twice per natural growth. Applied with physics suppressed, for the reason SPEC 11.8.2 gives
  about rollback: a growth that cascades into neighbours is not what anyone asked for.
  *Date:* 2026-08-05

- **[M17]** SPEC 11.8.1 says that for tile entities the logger should "additionally serialize
  the full NBT to `old_nbt` using Paper's `BlockState` snapshot serialization". **That API does
  not exist.** The paper-api 1.21.11 jar contains no class matching `nbt`; `TileState` exposes
  only `isSnapshot` and the plugin-owned `PersistentDataContainer`, and `BlockState` exposes
  only `copy`. Vanilla NBT is reachable from a plugin only through NMS, which SPEC 2.1 forbids
  unless unavoidable. *Implemented default:* a `TilePayloadCodec` interface with a per-type
  Bukkit implementation, capturing what the API does expose: container and furnace inventories,
  furnace burn and cook timings, sign text with colour and glow, banner base colour and
  patterns, and spawner type and delays. Every element of SPEC 18.3's step 2 list round-trips
  **except the bees inside a beehive**, which `EntityBlockStorage` counts but will not hand
  over; a hive rolls back empty. The limitation is printed at startup and returned by
  `knownLimitations()` rather than left to be discovered during the SPEC 18.3 protocol.
  **This needs a developer decision before M20 signs off**: SPEC 18.3 step 8 as literally
  written ("every … matches the pre-war screenshots exactly") cannot pass for a hive without
  an NMS-backed codec. *Date:* 2026-08-06

- **[M17]** SPEC 11.8.1 lists item frames, paintings and armor stands among the sources to log,
  but SPEC 3.8's `war_block_log` is block-shaped: it has x, y, z and `old_block_data` and no
  notion of an entity. *Implemented default:* a hanging entity is recorded at the block it
  occupies, with the sentinel `civitas:hanging` in `old_block_data` and its detail in the
  payload column. M18's rollback distinguishes them by that marker. The alternative, a second
  table, would have been cleaner but SPEC 3 does not define one and the sequence numbering
  that orders a replay is per war, not per table: two tables would need their ordering merged.
  *Date:* 2026-08-06

- **[M17]** SPEC 11.8.1 does not say what to do when a change cannot be logged, and SPEC 17.4
  case 58 and SPEC 17.7 case 85 answer it only for their own cases. *Implemented default:* one
  rule everywhere. `WarBlockLogger.isAcceptingChanges` answers false when the shared buffer is
  full or a war has reached its row ceiling, and every listener cancels its event when it does.
  SPEC 17.4 case 58's own words settle the trade: "Correctness over gameplay." The buffer cap
  is global because it bounds memory; the row ceiling is per war because SPEC 17.4 case 58
  states it per war. *Date:* 2026-08-06

- **[M17]** SPEC 11.8.1 gives no event priority, and the obvious choice is wrong. *Implemented
  default:* `NORMAL` with `ignoreCancelled`, never `MONITOR`. The log records the state a block
  is changing *from*, so it must read the block before the change lands; at `MONITOR` it would
  read the new state and store it as the old one, and the rollback would then faithfully
  restore the rubble. This is the single most damaging mistake available in this subsystem and
  it would look correct in every log. *Date:* 2026-08-06

- **[M17]** SPEC 3.8 gives `war_block_log` a monotonic `sequence` per war but does not say how
  it survives a restart. *Implemented default:* `WarBlockLogger.resume` seeds the counter from
  `MAX(sequence)` on disk when a war is loaded. Without it a restarted server would hand out
  sequence numbers that already exist, and a replay ordered by sequence would apply two
  different changes in an order that is not the order they happened. *Date:* 2026-08-06

- **[M18]** A recurring hazard, now hit three times, worth stating once as a rule. Several DAO
  calls throw **synchronously** rather than returning a failed future: `DatabaseManager.call`
  checks `requireOpen()` before it ever reaches the executor, so a closed pool throws out of
  the call itself and any `.exceptionally(...)` attached to the result never runs. It cost
  `StatsService` a dropped batch at M14, `WarBlockLogger` the same at M17, and at M18 it meant
  an unreadable block log never reached `ROLLBACK_FAILED`, which is precisely the guarantee
  SPEC 11.8.5 exists to give. *Implemented default:* every async DAO call on a failure path is
  wrapped in `try/catch (RuntimeException)` **as well as** `.exceptionally`, and the two share
  one handler. Anything added later that must not lose work on a dead database needs both.
  *Date:* 2026-08-06

- **[M18]** SPEC 11.8.2 step 8 says to "re-read a random 2% sample of logged positions and
  confirm they match `old_block_data`", which taken literally is wrong for any position that
  changed more than once: a random entry's `old_block_data` is the state before *that* change,
  not before the war, and SPEC 17.4 case 42 guarantees such positions exist. *Implemented
  default:* a sampled position keeps the value most recently written to it during the replay.
  Because the replay runs newest to oldest, that is the oldest entry's value, which is the
  state the block had before the war. *Date:* 2026-08-06

- **[M18]** SPEC 11.8.4 requires a chunk hash "at war start and again after rollback" but SPEC
  3 defines no table, and SPEC 17.4 case 57 requires verification mismatches to be surfaced in
  `/ca war rollbackstatus`, which is M21 and therefore a different process. *Implemented
  default:* V11 adds `war_chunk_hashes` and `war_rollback_issues`. The issues table is
  append-only in the spirit of the SPEC 3.6 ledger: these rows are the evidence that a restore
  was imperfect, and evidence that can be edited is not evidence. *Date:* 2026-08-06

- **[M18]** SPEC 11.8.4 asks for "a rolling checksum over block state IDs" per chunk, which on
  a modern world height is about 98,000 block reads per chunk, twice per war. *Implemented
  default:* a full hash by default, with `rollback.chunk-hash-stride` to sample every Nth block
  instead. A stride still catches a wall that vanished and can miss a single block; that is a
  trade an operator makes knowingly rather than one made for them. The whole failsafe is behind
  `rollback.chunk-hash-failsafe`, which SPEC 16.3 already ships as true. *Date:* 2026-08-06

- **[M18]** SPEC 11.8.2 step 7 says to apply physics "only at the boundary of restored
  regions", without defining boundary. *Implemented default:* a restored position with at least
  one of its six neighbours not restored. Inside a restored region every block is already
  consistent with its neighbours, and updating it would start the cascade step 4 spent the
  whole first pass avoiding. *Date:* 2026-08-06

- **[M18]** SPEC 11.8.2 step 1 (evacuate the zone), SPEC 11.8.3's villager and animal
  restoration, and SPEC 11.8.3's no-drops rule all belong to a rollback but none can be built
  yet: evacuation needs war zones and players, the animal snapshot is taken *at war start*, and
  the no-drops rule is a wartime gameplay listener. *Implemented default:* all three are M19's.
  M18 restores blocks and their tile payloads, which is what a synthetic log can exercise and
  what SPEC 19 assigns to this milestone. Hanging entities are logged by M17 and skipped by the
  replay for now, so M19 adds their restore rather than their capture. *Date:* 2026-08-06

- **[M19]** SPEC 11.9 does not add up. Its table says the winner "receives their own wager back
  plus 80% of the loser's wager" and that the loser "receives 20% of their own wager back"; the
  paragraph immediately after says "The remaining 20% of the loser's wager is **deleted from
  circulation**, acting as an economic sink". Those describe the same 20% twice, once as
  refunded and once as burned, and `war.yml` ships all three keys
  (`winner-wager-share-percent: 80`, `loser-refund-percent: 20`, `burn-percent: 20`), which
  together claim 120% of a 100% stake. *Implemented default:* resolved in favour of the later
  statement, so the burn is taken first and the loser's refund receives whatever survives it.
  With the shipped numbers that means 80% to the winner, 20% destroyed, nothing to the loser,
  which is the reading that makes SPEC's own economic-sink sentence true. Both keys stay
  meaningful: an operator who sets `burn-percent: 0` gets SPEC's table row instead. The
  arithmetic is closed in every configuration, so a war can neither mint coins nor lose them.
  **This needs a developer decision**: it is the difference between a war destroying 20% of the
  stake and returning it. *Date:* 2026-08-06

- **[M19]** SPEC 11.6 awards 0.1 points per block broken, up to a cap of 500. Accumulated as a
  running `double` this is wrong: 0.1 added ten times in binary floating point is
  0.9999999999999999, so a side would be awarded nine points for every ten it earned, and over
  the 5,000 blocks it takes to reach the cap the drift is points somebody fought for.
  *Implemented default:* the counter is a `long` of thousandths of a point, converted from the
  config value once per award. Exact at every scale the cap allows, and it keeps the config key
  a plain decimal. *Date:* 2026-08-06

- **[M19]** The SPEC 11.9 payout contradiction recorded above is **resolved on the developer's
  instruction in favour of the burn**: 80% of the loser's wager to the winner, 20% destroyed,
  nothing refunded to the loser. That is the reading which makes SPEC's own sentence about "an
  economic sink proportional to war activity" true, and it is the later of the two
  contradictory statements. `rewards.loser-refund-percent` stays in `war.yml` and still works:
  a server that sets `rewards.burn-percent: 0` gets SPEC's table row instead. *Date:* 2026-08-06

- **[M19]** SPEC 11.9 gives the winner "a 7-day +10% market sell price bonus" but does not say
  when the seven days start, and a war has two candidate moments: when the fighting ends and
  when the rollback finishes restoring the land. *Implemented default:* from the end of the
  fighting. A restore can take minutes and SPEC 11.8.5 allows it to take much longer after a
  crash; measuring from its completion would quietly shorten a reward the city earned by
  winning, by an amount that depends on how badly the server was behaving. The same moment is
  used for the loser's immunity, so the two consequences of one war always run together.
  *Date:* 2026-08-06

- **[M19]** SPEC 3.7's `rollback_completed_at` is set by SPEC 11.8.2 step 9, at the end of the
  restore, but M19's resolution runs *before* the rollback starts and was initially writing it.
  *Implemented default:* resolution leaves the column null and M18's engine owns it. The column
  means what SPEC says it means, and the market bonus is keyed on `war_ends_at` instead. Caught
  by a test asserting the bonus survives a restart, which it could not while the column was
  being written at the wrong time. *Date:* 2026-08-06

- **[M19]** SPEC 11.6 places three capture points "at the geometric extremes of the defender's
  claim set (north-most, south-most, and the chunk furthest from the core)", but a city with
  one or two chunks has fewer than three distinct extremes and SPEC does not say what happens.
  *Implemented default:* the extremes are deduplicated, then topped up from the city's other
  claims, and a city smaller than the requested count simply gets fewer points. Naming the same
  chunk three times would make one place worth 75 points a minute, which is the opposite of
  what putting them at the extremes is for. *Date:* 2026-08-06

- **[M19]** SPEC 11.6 awards the City Hall bonus for reaching "the enemy City Hall chunk", but
  SPEC 8.1 lets the City Hall block be placed by hand if the founding spot was obstructed, so
  its position is not guaranteed to be recorded anywhere. *Implemented default:* the city's
  **core chunk** is the objective. SPEC 5.1 step 7 places the block where the founder stood,
  which is the chunk they claimed, so the two coincide in every ordinary case; and the core
  chunk is the one thing about a city's geography that SPEC 3.2 guarantees exists and SPEC 6.4
  forbids unclaiming. *Date:* 2026-08-06

- **[M19]** SPEC 11.6 awards capture points "for 60 continuous seconds" without saying whether
  a side that keeps holding a point keeps earning. *Implemented default:* yes, the timer
  restarts and the point pays again every sixty seconds. Reading it as once-only would make a
  point worth taking and then abandoning, where SPEC 11.6's stated intent is that a war is
  decided by fighting and capturing rather than by demolition. *Date:* 2026-08-06

- **[M19]** SPEC 8.8 offers "Sue for Peace … forfeits 25% of your wager" but does not say
  whether a peace agreed mid-war still rolls the world back. *Implemented default:* it must.
  Damage done during ACTIVE has already been logged and SPEC 1.2 promises it is never
  permanent, so a peace during ACTIVE goes to `ROLLING_BACK` exactly as a war that ran its
  course, with no winner recorded. A peace during PREP has nothing to restore and simply
  cancels. *Date:* 2026-08-06

- **[M19]** SPEC 11.10 says both that "a city may not join a war against its own ally" and that
  "if two allies end up on opposite sides, the alliance is automatically broken". The first
  makes the second unreachable through joining. *Implemented default:* both are kept, because
  they cover different routes to the same state. The join is refused outright, and
  `breakCrossSideAlliances` handles the case SPEC's second sentence really describes: two
  cities allying *after* they are already on opposite sides, which nothing forbids.
  *Date:* 2026-08-06

- **[M19]** SPEC 8.8 describes three different Wars screens but gives them one slot on the
  SPEC 8.3 hub, and does not say what happens between them: a war moves from PREP to ACTIVE
  while somebody is looking at it. *Implemented default:* one live menu that picks its face
  from the war's state on every draw, so the screen changes under the viewer at the moment
  the phase does. The alternative, three menus chosen when the window opens, would leave a
  mayor staring at a countdown that had already finished. *Date:* 2026-08-06

- **[M19]** SPEC 8.8 puts a Declare War button on the peacetime screen and says it "opens city
  selector, then wager selector, then a confirmation screen showing full terms". *Implemented
  default:* the button closes the window and points at `/war declare`. A wager selector needs
  a number the player chooses between 50,000 C and a quarter of the smaller treasury, which
  the framework's chat prompt already asks for elsewhere, and SPEC 11.3's terms run to twelve
  preconditions that read better as text than as lore on a confirm button. This is the one
  place M19 delivers less interface than SPEC 8.8 asks for, and it is recorded rather than
  quietly dropped. *Date:* 2026-08-06

- **[M19]** SPEC 8.8's ACTIVE screen shows "Capture Point Status" and SPEC 9.3 gives
  `/war scoreboard` a sidebar, neither with a refresh rate. *Implemented default:* both ride
  the one-second objective tick that SPEC 11.6's capture holds already need, so a score
  appears a second after the kill that earned it. A faster tick would count heads twenty
  times a second to answer a question measured in minutes. *Date:* 2026-08-06

- **[Bug, all GUI milestones]** Bukkit reads `.` as a path separator when it loads YAML, so a
  language file holding both `treasury: "Treasury"` and `treasury.lore: "..."` ends up with a
  *section* at `treasury`, and `getString` on a section returns its `toString`. Fifty labels
  across every GUI screen were rendering as
  `MemorySection[path='gui.main.treasury', root='YamlConfiguration']` to players. Nothing
  caught it: the key existed, the lookup returned a non-empty string, and every test passed.
  *Fixed:* 90 child keys renamed from `parent.child` to `parent-child` in both languages and
  at every call site, plus `LangKeyUsageTest.noRequestedKeyIsASection` so a key the code asks
  for can never again resolve to a section. Related: a bare `on:` or `off:` key is a YAML 1.1
  boolean and loads as `true`/`false`, so those are quoted. *Date:* 2026-08-06

- **[M19]** SPEC 11.7 requires a container log but does not say how "items removed" is
  measured, and a click-by-click count would have to handle shift-click, number-key swaps,
  drags, the double-click sweep and the cursor stack separately. Getting any one wrong
  under-reports a theft silently. *Implemented default:* the contents are snapshotted when the
  container is opened and diffed when it is closed, so the log cannot miss a route because it
  does not know which route was taken. Only net removals are recorded, which means a player who
  rummages and puts everything back has stolen nothing, and a swap logs the item that left
  rather than the one that arrived. *Date:* 2026-08-07

- **[M19]** SPEC 11.8.3 requires villagers and animals to be "snapshotted at war start (type,
  position, NBT, name, profession, trades)", and the NBT half is unavailable for the reason
  recorded at M17: paper-api exposes no vanilla NBT. *Implemented default:* the same per-type
  Bukkit capture the tile codec uses, covering name, health, age, tamed owner, villager
  profession, type, level, experience and the full trade list. Each field is captured and
  re-applied independently, so an accessor a build does not support costs that one field rather
  than the whole animal. What is covered is declared by `describedFields()` rather than left to
  be discovered. *Date:* 2026-08-07

- **[M19]** SPEC 11.8.3 says animals are "respawned if killed" but not when, and the rollback
  has two candidate moments. *Implemented default:* after the blocks are back, not before. An
  animal respawned first would be standing inside whatever the replay was about to put where it
  stood, which is SPEC 17.4 case 50's problem imported into the one part of the restore that
  could have avoided it. The read and the respawn are separate methods on separate threads:
  reading is storage work, spawning is world work, and a single method would have hidden which.
  *Date:* 2026-08-07

- **[M19]** SPEC 12.4 doubles the price of a unit "placed during ACTIVE war", and SPEC 11.5
  gives PREP for preparation. *Implemented default:* ACTIVE only. A city that used its 48 hours
  pays the ordinary price and one that left its defences until the fighting started pays twice,
  which is what makes SPEC 12.4's "defense must be planned in PREP" true. Charging double
  through PREP as well would punish the planning the rule exists to reward. *Date:* 2026-08-07

- **[M19]** SPEC 12.3's leash says a unit is "teleported back if it wanders more than 8 blocks
  past the claim border", and a border is a polygon rather than a point. *Implemented default:*
  Chebyshev distance to the nearest chunk the city owns, plus how far into its current chunk the
  unit stands, which is the same measure SPEC 6.2 uses for claim distance. Against a leash of
  eight blocks the approximation decides only whether a guard turns at the fence or a few blocks
  past it, and it costs one pass over the city's claims instead of a geometric edge test.
  *Date:* 2026-08-07

- **[M19]** SPEC 11.5 forbids members leaving a city at war but says nothing about a mayor
  kicking them. *Implemented default:* a kick is blocked too. The rule exists so a city cannot
  change what it is while a war is fought over it, and a kick empties the same seat as a
  departure; leaving the hole open would have been the same exploit from the other side.
  *Date:* 2026-08-07

- **[M19]** SPEC 11.6 blocks "`/city spawn` for the *attacking* city into the *defending*
  city", which cannot happen as written: `/city spawn` only ever goes to your own city's spawn.
  *Implemented default:* the readable rule from SPEC 5.6 is enforced instead — a teleport home
  during a war takes 15 seconds rather than 5 — plus a refusal to teleport into any zone that
  is closed for a restore, which SPEC 11.8.2 step 1 requires and SPEC 5.6 does not mention.
  *Date:* 2026-08-07
