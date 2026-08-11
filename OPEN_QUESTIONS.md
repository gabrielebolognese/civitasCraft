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

- **[M20]** SPEC 17.4 case 51 says two overlapping wars are handled by rolling back "in war end
  time" order, and that ordering is not sufficient. Each war's log records, per position, the
  state before *that war* first touched it, so a war that started later has no record of damage
  the earlier one had already done — and its rollback restores the damage. Proved with a failing
  test before it was fixed: block broken during war A, replaced during A and B, A restores it,
  B puts the hole back. *Implemented default:* when a war becomes ACTIVE, `OverlapSeeder` copies
  the oldest-per-position entries of any older war it shares ground with into the new war's log,
  at negative sequence numbers so the replay reaches them last. The new war's log is then
  complete and the replay needs no knowledge that overlapping wars exist. Fixing it in the
  replay instead would mean a join across the highest-write-volume table during the one
  operation SPEC 11.1 says must be reliable above all else. *Date:* 2026-08-07

- **[M20]** SPEC 17.4 case 46 requires fluid flow across the war zone boundary to be "cancelled
  outright", and the ownership rule that predates it very nearly does that. A zone's perimeter
  (SPEC 11.4) is usually wilderness and wilderness counts as its own owner, so lava inside the
  perimeter was free to flow on into the wilderness beyond — outside every zone, so never logged
  by M17 and never restored by M18, which is permanent damage from a war. *Implemented default:*
  an explicit zone-boundary check in `ProtectionService.allowsSpreadBetween`, so the rule says
  what SPEC means rather than resting on a coincidence of the ownership rule. *Date:* 2026-08-07

- **[M20]** SPEC 18.3's structure cannot be fully verified by any automated test, and the reason
  is worth stating precisely. MockBukkit builds a block's state class when the *material* is
  set and does not rebuild it when `setBlockData` writes one — and the rollback must use
  `setBlockData(data, false)`, because SPEC 11.8.2 step 4 requires physics to be suppressed. So
  under test a restored chest is a chest with no chest state. **The contents of chests, the text
  on signs, banner patterns and spawner types are therefore verified by the manual protocol and
  by nothing else.** *Implemented default:* `Spec18ProtocolTest` covers what it can (plain
  blocks, fluids, the sand-on-a-torch physics case, repeated damage, a clean verification pass)
  and skips the tile-state assertions with that reason attached rather than passing them
  silently; `WAR_TEST_PROTOCOL.md` carries the rest. *Date:* 2026-08-07

- **[M20]** `Evacuation.moveOut`, added in M19 for SPEC 17.4 cases 41 and 48, sent a player to
  their own city spawn without checking whether that spawn was inside the zone they were being
  taken out of. For a defender it always is, so the one group the rule most needed to protect
  was moved out of the war and back into it in a single step. Invisible in the common case,
  because an attacker's spawn is elsewhere. *Fixed:* it now uses the same `destinationFor` rule
  the bulk evacuation uses. It also teleports synchronously rather than asynchronously, which is
  correct for a single player whose chunk is necessarily loaded and has the side benefit of
  being testable. *Date:* 2026-08-07

- **[M21]** SPEC 17.6 case 79's last heuristic flags "any player whose income rate exceeds the
  99th percentile by more than 3x", and taken at face value the rule can never fire. By
  nearest-rank, the 99th percentile of any realistic player count *is* the top earner, so a lone
  outlier is compared against itself and cannot exceed itself threefold. The rule would have
  looked implemented and detected nothing. *Implemented default:* the baseline is the 99th
  percentile of the field with the single highest earner removed, which is what "compared
  against the 99th percentile" has to mean for the comparison to say anything. The rule also
  declines to answer below `audit.income-percentile-minimum` (20) players, because a percentile
  over a handful of samples is not a percentile and would simply flag the richest of four
  people. *Date:* 2026-08-07

- **[M21]** SPEC 9.4.1 lists the heuristics under a command that "reports hits", without saying
  how a hit should be presented. *Implemented default:* as something to look at, never as a
  finding. Every one of the six rules has an innocent explanation — a founder emptying the
  treasury to buy an upgrade, a returning player receiving a gift, a good trading day — and the
  command prints a standing disclaimer to that effect after the list. A heuristic presented as
  proof gets somebody banned for playing well, which is the opposite of what SPEC 1.5 built the
  ledger for. *Date:* 2026-08-07

- **[M21]** SPEC 9.4.1's `/ca ledger export` takes a target without saying whether it is a
  player, a city or a transaction type, and all three are valid subjects elsewhere in the same
  command tree. *Implemented default:* it tries each in that order rather than adding an
  argument to disambiguate. An admin exporting "Roma" should not have to tell the plugin that
  Roma is a city. The export filename is stripped to `[A-Za-z0-9_-]` first: the label reaches a
  file path, and a target named `../../server.properties` would otherwise be a way to write a
  CSV over something that matters. *Date:* 2026-08-07

- **[M21]** SPEC 9.4.4's `/ca eco rollback` says it "reverses a single transaction and
  everything downstream of it", and gives no rule for how far downstream to follow or what to
  do when the money reached a third party. Cascading automatically would unwind trades with
  people who did nothing wrong: if the player spent the money in somebody's shop, reversing
  that takes goods from a seller who was paid in good faith and has no way to know why.
  *Implemented default:* one transaction is reversed and everything that followed it is
  **counted and reported**, leaving the judgement with the admin. The count is the number that
  tells them whether the job is finished. *Date:* 2026-08-07

- **[M21]** SPEC 9.4.4 pairs "never deletes ledger rows" with a command whose job is to undo a
  transaction, which cannot both be done to the same row. *Implemented default:* the reversal
  is a new `ADMIN_ROLLBACK` row whose metadata names the row it reverses, so the pair reads as
  a complete story and SPEC 3.6's append-only rule is untouched. "Already reversed" is answered
  by searching for that reference rather than by a flag on the original, because a flag would
  need an update the ledger does not allow. Reversing a reversal is refused outright: alternating
  would mint money back into existence one command at a time. *Date:* 2026-08-07

- **[M21]** SPEC 9.4.4's `/ca market setprice` changes a value that lives in `economy.yml`
  (SPEC 4.4's table). *Implemented default:* the change is in memory and lasts until the next
  reload. An operator tuning a price live wants to see its effect before committing it, and a
  chat command that silently rewrote their configuration file would be worse than one whose
  change they have to remember to persist. The message says so. *Date:* 2026-08-07

- **[M21]** SPEC 15.3 specifies `/report` with no rate limit, and a player-facing command that
  writes to a moderation queue needs one: without it a single player can bury the queue and make
  the feature useless for everybody, which defeats it rather than serving it. *Implemented
  default:* `moderation.reports-per-window` (5) in `moderation.report-window-hours` (24), with
  the reason trimmed to `moderation.max-reason-length` (200) rather than refused — somebody
  typing a long complaint has a complaint, and discarding it to protect a column would be the
  wrong trade. **None of these four numbers is from SPEC.** *Date:* 2026-08-07

- **[M21]** SPEC 15.3 asks that a report carry "the reported player's last 50 ledger entries and
  last 50 war actions". It does not say when to gather them. *Implemented default:* when the
  report is **read**, not when it is written. Copying fifty ledger rows into a text column would
  fork the record SPEC 1.5 makes authoritative, and the copy — the one a moderator would be
  looking at — could then disagree with the ledger. Reading on demand also means a report filed
  on Monday and read on Friday shows the week. "War actions" is read as kills: block-level war
  damage is M17's log, which is scoped per war and far too large to attach to a chat message.
  *Date:* 2026-08-07

- **[M21]** SPEC 9.4.6 gives `/ca reload` a module argument, implying the modules reload
  independently. They do not: the configuration files reference each other — a defense unit's
  cost is in `defense.yml` and its upkeep is charged by the economy — so reloading one of a pair
  leaves the plugin holding two halves of two different configurations. *Implemented default:*
  the argument is accepted and everything reloads, read as naming what an operator is interested
  in rather than promising isolation the files do not have. *Date:* 2026-08-07

- **[M21]** SPEC 9.4.6 asks `/ca perf` for "avg claim lookup, block-log write rate, GUI open
  time, DB pool status". Two of those are measured; **claim-lookup and GUI-open times are not
  instrumented anywhere in the plugin.** *Implemented default:* the command reports what is real
  and prints a line naming the two that are not measured. Printing a plausible figure would be
  worse than useless: an operator diagnosing a stall would chase a number nobody ever took.
  *Date:* 2026-08-07

- **[M22]** SPEC 15.2 lists "Seven leaderboards" as an anti-toxicity mechanism and SPEC 13.3's
  table lists nine, which M14 already recorded. M22 is where it stops mattering: the audit
  asserts what SPEC 15.2 is protecting — that there is more than one ladder, and that a player
  who cannot compete on wealth can be the top Builder or Farmer — rather than a count the
  specification gives twice and differently. A test on the number would have to pick one of
  SPEC's two answers and would fail if a later milestone added a tenth board, which would not
  be a regression. *Date:* 2026-08-07

- **[M22]** The audit found no unenforced mechanism, which is worth recording because a
  milestone that finds nothing looks the same as one that did not look. Three tests failed on
  the first run and all three were the test being wrong about the environment rather than the
  product: the shop-tax key is `player-shops.tax-percent` rather than `shops.tax-percent`, the
  member cap and the ten-claim war precondition both sit in front of the size-mismatch rule, and
  a `NOW` captured at class load makes a city founded in `@BeforeEach` younger than zero — the
  same trap recorded at M19. *Date:* 2026-08-07

- **[M22]** SPEC 15.2's "no passive income from land" is a property proved by absence, which no
  test can assert directly: there is no code to point at. *Implemented default:* the audit
  asserts the two things that would have to be true if it held — that holding land costs upkeep,
  and that a city's treasury does not move when nothing happens — and states in
  `ANTI_TOXICITY.md` that this is weaker evidence than the other fifteen rows. A future income
  source keyed on claim count would pass this test. *Date:* 2026-08-07

- **[M23]** SPEC 19 assigns M23 no tests: every unit test in SPEC 18.1 is a formula owned by
  M3, M5, M6 or M19, every integration test in SPEC 18.2 belongs to M2, M3, M5 or M7, and
  SPEC 18.3 is M20's manual protocol. The same situation as M0. *Implemented default:* wrote
  the tests this milestone's own deliverable implies rather than shipping it untested -- help
  coverage in both directions, the two rules SPEC requires the rules book to carry, the
  profiler's sampling, a tab-completion sweep that stays swept, and a localisation check that
  a copied language file does not satisfy. *Date:* 2026-08-07

- **[M23]** SPEC 9.1 asks for paginated help and says nothing about where the list of commands
  lives. Putting the whole thing in `lang/` is the obvious choice and the one that rots: a
  later milestone adds a command, nobody remembers the help text, and a stale help page is
  indistinguishable from a current one until a player follows it. *Implemented default:* the
  *set* of commands is declared in `HelpPages`, the *wording* of each line is a `lang/` key,
  and `HelpPagesTest` asserts both directions -- no entry names a command that does not exist,
  and no root command is undocumented. Help cannot go out of date without failing the build.
  *Date:* 2026-08-07

- **[M23]** A help line reads `/city info <name>`, and `<name>` is also how MiniMessage writes
  a placeholder, so the same three characters mean "show this text" in a usage line and
  "substitute a value here" everywhere else. *Implemented default:* `HelpPages.send` passes no
  resolvers at all, which is what leaves an unrecognised tag as literal text -- the behaviour
  `market.sell-usage` has relied on since M6. Recorded because it is a live trap: a future
  change that formats these lines with placeholders would silently delete every argument name
  from the help. *Date:* 2026-08-07

- **[M23]** The same ambiguity broke the first version of `LocalisationCompletenessTest`, which
  compared the placeholders in `en.yml` against those in `it.yml` and produced four findings,
  all of them wrong: `/sell all <material>` becomes `/sell all <materiale>`, and should,
  because that name is documentation and nothing substitutes it. *Implemented default:* the
  test asserts against **what the code passes**, read from the call sites by `LangCallSites`,
  rather than against the other language. Only the call site knows whether a name is a
  resolver or a piece of syntax being shown to the player. *Date:* 2026-08-07

- **[M23]** Rewritten that way it found six real defects, of which **two were visible to
  players and had been since M10**: `outpost.tp-warmup` and `outpost.tp-arrived` were passed a
  resolver called `outpost` while both messages show `<name>`, so a player teleporting read
  "Travelling to `<name>` in 8 seconds". Every other message in the outpost section uses
  `<name>`, so the two call sites were the outliers and were corrected. The other four passed
  a value the message never displayed -- a raw epoch, an internal failure code, a city name
  into a caption SPEC 8.1 fixes -- and were dropped, except `diplomacy.already-breaking`,
  where naming the city is worth showing. *Date:* 2026-08-07

- **[M23]** SPEC 13.4 scores a contest entry "1 to 10" and the range is a config key, but a
  Brigadier argument type is fixed when the tree is registered at startup. Bounding the
  argument would freeze that key: an operator who widened the range and ran `/ca reload` would
  get a parse error for a score the service accepts. *Implemented default:* the valid scores
  are **suggested**, read fresh on every keystroke, and the service stays the only authority.
  The same reasoning applies to every argument in the tree, since `/ca reload` does not
  re-register commands and nothing else depends on it doing so. *Date:* 2026-08-07

- **[M23]** M21's `/ca perf` printed a line naming average claim lookup and GUI open time as
  unmeasured. It understated the gap: SPEC 9.4.6 names four figures, and the block-log **write
  rate** and **DB pool status** were absent too -- what was printed in their place was a
  buffer depth and a claim count. All four are real now. *Implemented default:* claim lookup
  is sampled at one call in 64, because SPEC 17.7 case 81 puts it on every block event and
  `System.nanoTime` costs more than the map lookup it would measure; GUI opens are timed every
  time, because SPEC 17.7 case 86's worst case is 500 of them and a menu costs microseconds.
  `performance.timings-enabled` defaults **true**, since a profiler that ships off is one an
  operator turns on after the incident, and when it is off the clock is never read at all.
  *Date:* 2026-08-07

- **[M23]** SPEC 19 asks for "tab completion everywhere" without saying what everywhere
  excludes. Twenty-two string arguments offered nothing; ten of them should have, and twelve
  name a value the player invents -- an amount, a wager, the name of a city that does not
  exist yet. *Implemented default:* the ten are completed, and `TabCompletionTest` holds the
  line by requiring every remaining one to be listed as free-form, so re-opening the gap fails
  the build and exempting an argument is a decision somebody made. The sweep also found **five
  verbatim copies** of the online-player provider, two of which lowercased without a locale --
  on a Turkish server a player named Ian would not have matched "i". All five now share
  `Suggest.onlinePlayers()`. *Date:* 2026-08-07

- **[M23]** SPEC 9.1's rules book is where SPEC requires two rules to be written down: SPEC
  17.2 case 16 ("Builds do not confer ownership. Documented in the rules book") and SPEC 11.7
  with SPEC 17.4 case 44, the loot asymmetry that "must be communicated clearly to players".
  *Implemented default:* an Adventure book opened virtually rather than given as an item -- it
  cannot be lost, duplicated, or take up an inventory slot -- with a console fallback that
  prints the same pages as lines. `RulesBookTest` asserts both promises are present in both
  languages, including SPEC 17.4 case 44's own conclusion rather than only the mechanic, so
  neither can be trimmed out during a tidy-up. *Date:* 2026-08-07

- **[M23]** `CommandRegistry.COMMANDS`, the list of commands declared but not yet implemented,
  is **empty for the first time**: `/cc` was orphaned at M13 and `/civitas` was always M23's.
  *Implemented default:* the list, the stub builder and `CommandSpec` are kept rather than
  deleted -- they are how this tree was built one milestone at a time, and a future command
  wants the same treatment -- and `CommandRegistryTest` asserts the list is empty, so a stub
  reaching a release is a decision rather than a leftover. *Date:* 2026-08-07

- **[MySQL pass]** `OPEN_QUESTIONS.md` has said since M1 that "MySQL is not covered locally",
  and the gap was wider than that reads: the fourteen files under `migrations/mysql/` had
  **never been executed by anything**. `MigrationIndexTest` checked only that they appeared in
  `index.txt`. A syntax error in any one of them would have surfaced the first time an operator
  set `storage.type: MYSQL`, at which point the plugin fails to start. *Implemented default:*
  `MySqlDialectTest` (13 tests) plus a dialect switch on `DaoRoundTripTest` (40 tests), both
  gated on `civitas.test.mysql.url` so a developer without a server still builds. All fourteen
  migrations applied to an empty schema on the first attempt and every test passed; the one
  defect the exercise produced was in the test, which looked for `schema_migrations` where the
  runner's table is `schema_version`. It now reads `MigrationRunner.VERSION_TABLE`, so the two
  cannot drift. *Date:* 2026-08-07

- **[MySQL pass]** Gradle does not forward the invoking JVM's system properties to the test
  JVM, so the dialect tests would have skipped silently even when a server was named on the
  command line — and the run would still have gone green, which is the worst available outcome
  for a gated test. *Implemented default:* `build.gradle.kts` passes the four properties
  through explicitly, a negative control against a dead port confirms the tests fail when the
  server is unreachable rather than quietly falling back to SQLite, and `MySqlDialectTest`
  prints the URL it ran against so a build log distinguishes "MySQL passed" from "MySQL was
  skipped". *Date:* 2026-08-07

- **[MySQL pass]** The server available locally was **MariaDB 10.4.32, not MySQL 8**. SPEC 2.1
  names both and the driver is MySQL Connector/J either way, but they are not the same product,
  and the class of bug MariaDB structurally cannot reveal is an identifier MySQL 8.0 reserves
  and MariaDB 10.4 does not — `rank`, `groups`, `system`, `row`, `window` and the rest of the
  window-function keywords. *Implemented default:* checked statically instead. No identifier in
  the MySQL DDL is one of them and none needs backticking, which is why the schema is portable
  rather than lucky. Recorded rather than left implied: a future migration that adds a column
  called `rank` would pass every test here and fail on MySQL 8. *Date:* 2026-08-07

- **[MySQL pass]** The service layer was deliberately not re-run against MySQL. A `CityService`
  rule behaves the same whichever database is underneath, and re-running 1,600 tests would take
  a long time to learn nothing. What actually differs by dialect is narrow — the DDL, the money
  representation, the SPEC 3.4 unique index, and transaction rollback — and that is what the two
  test classes cover. Three things remain unproven and are named in `MYSQL.md` rather than left
  to be assumed: no load or concurrency testing of the pool, no MySQL 8 server, and **backups
  still do nothing on MySQL**, which is M1's deliberate decision and means an operator running
  MySQL must arrange their own. *Date:* 2026-08-07

- **[SPEC 17.1 cases 1-3]** These three were specified in M0's SPEC, deferred by M2 to M4,
  deferred again by M4 to "a later milestone", and then **never built**. What made that
  survivable for twenty-one milestones is also what made it invisible: `cities.yml` has shipped
  an `inactivity:` block carrying all four of SPEC's numbers, commented "SPEC 17.1 cases 1, 2
  and 3", since M2 — so the feature looked present to anyone reading the configuration, and
  **nothing read a single key**. M22's audit did not catch it because its scope was SPEC 15.
  `ProtectionService.isDormant` had been returning a hardcoded false since M4. *Implemented
  default:* `InactivityTask` and `DormancyCache`, with every rule asserted twice in the shape
  M22 established — that it happens, and that changing its config key changes the behaviour.
  *Date:* 2026-08-07

- **[SPEC 17.1 case 1]** SPEC says the successor is "the highest-weight member with the most
  recent login", which is two orderings and does not say which is primary. *Implemented
  default:* weight first, recency as the tiebreak, which is the order SPEC writes them in and
  the one a city would want — the point is to hand the city to whoever was most trusted with
  it, and among equals to whoever is most likely to be there tomorrow. The other reading hands
  a Recruit who logged in yesterday a city over a Co-Mayor who logged in last week. Candidates
  who are themselves past the threshold are skipped: promoting one would leave the city in the
  state the rule exists to fix, and the next sweep would do it all again. *Date:* 2026-08-07

- **[SPEC 17.1 case 1]** "Old mayor is demoted to Co-Mayor, **notified on next login**" needs
  storage, because the person it is for is absent by definition — that absence is why the
  transfer happened. *Implemented default:* V15 adds `player_notices`, holding a `lang/` key
  and a placeholder blob rather than rendered text: a notice stored in English would be
  unreadable to an Italian player and unfixable afterwards, and SPEC 2.1 keeps player-facing
  strings out of Java for the same reason. Delivered notices are deleted rather than flagged,
  and only *after* they have been shown — a notice that cleared itself before delivery would be
  lost for good on a disconnect at the wrong moment. *Date:* 2026-08-07

- **[SPEC 17.1 case 2]** Dormancy is asked about on **every block event**, which SPEC 17.7 case
  81 requires to stay O(1) and SPEC 2.1 forbids from touching the database — and the answer
  lives in `players.last_seen`, which `CityMember` does not carry. *Implemented default:* a
  `DormancyCache` the sweep publishes into and the hot path reads as a set lookup, rather than a
  column. Deriving it also gets SPEC's "restores instantly" honestly: a member logging in calls
  `wake`, which takes effect on the very next block event rather than at the next sweep an hour
  away. **An empty cache means nothing is dormant**, so a sweep that never runs, a database that
  will not answer, or a startup that has not finished all fail in the direction that keeps
  cities protected. *Date:* 2026-08-07

- **[SPEC 17.1 case 3]** SPEC says "soft-deleted, claims released, treasury burned. 14-day admin
  restore window", which cannot reuse `adminDelete`: M21 made `/ca city delete` deliberately
  **keep** the claims, on the grounds that "a restore that gave back a city with no land would
  be a restore in name only". Both are right for their own case and they are different
  operations sharing a column. *Implemented default:* `expireInactive` is its own path — it
  releases the land as SPEC 17.1 case 3 says, and the restore window still applies to the row,
  so an admin can bring the name and members back but the land will be gone. It is also not
  `disband`, which refunds half the land cost and splits the treasury: case 3 says burned, and
  paying out the treasury of a city dead for four months would reward abandoning it.
  *Date:* 2026-08-07

- **[SPEC 17.1 case 3]** SPEC 4.6 calls its transaction-type list exhaustive and none of the
  thirty-five types means "a dead city's treasury was destroyed". *Implemented default:*
  `UPKEEP_CHARGE` with `{"reason":"inactive_city_expired"}` in the metadata, because both are
  treasury money destroyed by the passage of time and inventing a type would break SPEC 4.6's
  stated exhaustiveness. Recorded because an admin summing `UPKEEP_CHARGE` will see these rows
  and the metadata is the only thing that distinguishes them. *Date:* 2026-08-07

- **[SPEC 17.1 case 3]** Automatic deletion is by a distance the most destructive thing this
  plugin does without a human asking: it fires on a timer against cities whose members may
  simply have been away for a season. *Implemented default:* its own switch,
  `inactivity.soft-delete-enabled`, separate from the master `inactivity.enabled`, so an
  operator can keep succession and dormancy without ever losing a city automatically. It
  **defaults true**, because that is what SPEC 17.1 case 3 says happens, and the deviation would
  be silently not doing what the specification describes. The first sweep is also delayed a full
  interval rather than firing at startup, so a server returning from a long outage does not
  delete a city in the same second the operator started it. *Date:* 2026-08-07

- **[SPEC 17.1]** All three rules measure `players.last_seen`, not the anti-AFK
  `active_playtime_ms` that SPEC 4.2.1 uses for income. *Implemented default:* deliberate. These
  rules are about abandonment, and somebody who logs in weekly to stand in their city has not
  abandoned it, whatever the stipend filter thinks of them. A city with no members at all falls
  back to its founding date, which is what stops a city founded by a long-dormant account being
  deleted before anybody could join it. *Date:* 2026-08-07

- **[SPEC 17.1]** V15 is the first migration written *after* the MySQL pass existed, so for the
  first time in this project a new migration's MySQL file was **executed before it shipped**
  rather than reviewed by eye. It applies cleanly and `player_notices` matches on both dialects.
  Every migration up to V14 was written blind. *Date:* 2026-08-07

- **[Config sweep]** SPEC 17.1 hid for twenty-one milestones behind a config key nothing read,
  which is a **class** of bug rather than an incident, so all 571 shipped keys were swept the
  same way. **Nineteen more were found.** *Implemented default:* `ConfigKeyUsageTest`, the
  counterpart to `LangKeyUsageTest` and written a milestone later for the same reason — a key
  with nothing behind it is indistinguishable from a working feature, and an operator who
  changes it and sees nothing cannot tell that from a typo. `CONFIG.md` records every finding
  and its resolution. *Date:* 2026-08-07

- **[Config sweep]** The worst three were not dead keys but **mismatched pairs**: the file
  shipped one name and the code read another, so both sides were inert and the value was stuck
  at its hardcoded default. `war.yml` shipped `scoring.city-hall-hold-seconds` while
  `WarScoring` read `scoring.city-hall-reach-seconds`; `peace.forfeit-percent` against
  `declaration.peace-forfeit-percent`; and `CityService.adminRestore` read
  `admin.restore-window-days`, which `cities.yml` did not contain at all — its only copy sat
  unread under `inactivity:`. SPEC 11.6's City Hall stand, SPEC 8.8's peace forfeit and SPEC
  9.4.2's restore window were therefore all permanently at their defaults. *Implemented
  default:* the code moved to the shipped name in the first two; the third gained an `admin:`
  section, because the window governs `/ca city delete` as much as SPEC 17.1's expiry. This
  failure is invisible from either side alone, which is why a second test, `noDeadTwins`, looks
  for one concept shipped under two names. *Date:* 2026-08-07

- **[Config sweep]** SPEC 16.3's five `rollback.*` flags shipped from M0 and were consulted
  nowhere. *Implemented default:* three now work — `suppress-block-drops`, `restore-entities`,
  `restore-container-nbt`. The other two are **declarations, not switches**:
  `loot-is-permanent: false` would mean returning items carried out of a chest during a war and
  `vault-immune: false` would mean letting the vault be looted, neither of which exists and
  neither of which SPEC describes the behaviour of. Inventing them to make a config key honest
  would be inventing a feature, so `RollbackPolicy` reads them and tells the operator at
  startup that the setting is not supported. `suppress-block-drops` is honoured *and* warned
  about, on the same footing as `rollback.enabled`: SPEC 11.8.3 calls the no-drops rule
  critical because without it an attacker keeps 50,000 blocks of materials and the rollback
  restores the blocks anyway, creating resources from nothing. *Date:* 2026-08-07

- **[Config sweep]** Six keys were removed rather than wired, because honouring them would be
  wrong or impossible: `economy.decimal-places` (two places is SPEC 3's `DECIMAL(20,2)` schema,
  not a preference, and changing it corrupts every balance), `player-shops.tax-percent` and
  `bounties.claimable-only-during-war` (**both are anti-toxicity mechanisms SPEC 15.2 and 4.7
  call deliberate — a switch that disables one is not something to ship because a file looked
  incomplete**), `contests.entries-per-city` and `contests.vote-axes` (structural: one entry row
  exists per contest and city, and the axes are a Java enum), and
  `scoring.capture-point-visible-range` (SPEC 11.6's particle column is **not implemented**, and
  shipping its range suggested it was). *Date:* 2026-08-07

- **[Config sweep]** Three SPEC 16.1 keys could not simply be removed, because SPEC mandates
  them, and `ConfigDefaultsTest` correctly failed when they were. *Implemented default:*
  `performance.claim-cache-size` is honoured as a **warning threshold rather than a cap** —
  evicting a claim means the chunk reads as wilderness on the next block event, which is a city
  losing protection to save a few kilobytes against SPEC 17.7 case 81's measured 2.5 MB for
  50,000 claims — and `ledger-batch-size` and `ledger-flush-seconds` are read by a startup
  notice that states they are not honoured, because SPEC 1.5 makes the ledger authoritative and
  a row waiting in a buffer is a row a crash loses. *Date:* 2026-08-07

- **[Config sweep]** The sweep **falsified a claim M22 made**. `ANTI_TOXICITY.md` recorded that
  the shop tax was "an explicit `0` rather than an absent key" so that "an operator who wants to
  tax shops can". That was untrue: `player-shops.tax-percent` was read by nothing, so the zero
  was decoration. *Implemented default:* the key is gone, and `Spec15AuditTest` now asserts the
  stronger and true guarantee — that no code path exists which could take a cut, so the rate
  cannot drift from zero by configuration or by accident. Worth recording because M22's whole
  premise was that a mechanism must be proved **enforced and configurable**, and this row passed
  that audit while being neither. *Date:* 2026-08-07

- **[M6a]** SPEC 21.10.2 defines the relation as "two items are in the same class if **either**
  is reachable from the other", which is not the same as connectivity in an undirected graph
  and not an equivalence relation at all. Raw iron and iron ore both smelt to an ingot; neither
  converts into the other, so there is no loop between them and both may safely be listed — but
  an undirected reading would refuse the pair, and a direct-edge reading would allow raw iron
  beside an iron block. *Implemented default:* a directed graph, with `related` as the **or** of
  reachability in the two directions. A consequence worth knowing: the relation is **not
  transitive**, so despite SPEC's name for it there is no partition into classes to compare, and
  the check is pairwise over the buy list. *Date:* 2026-08-07

- **[M6a]** SPEC 21.10.2 says the graph is built "by walking Bukkit's recipe iterator plus a
  hardcoded smelting and stonecutter table", and the milestone requires a test proving each of
  SPEC 21.3's twenty-two reversible pairs is detected. Those two cannot both be satisfied:
  **MockBukkit ships no vanilla recipes**, so a test cannot obtain the pairs from the iterator,
  and sourcing them only from the server would leave the single most important property in this
  milestone unverifiable in CI and true only by the grace of whatever recipe list the server
  hands over at boot. *Implemented default:* SPEC 21.3's pairs are hardcoded alongside the
  smelting and stonecutter tables as a **floor**, and the iterator adds to them. This is more
  than SPEC describes. The cost is that the table can go stale against a future Minecraft
  version, which is the safe direction — a stale entry over-reports and refuses to list an item,
  where a missing entry re-opens SPEC 21.3's infinite money loop. *Date:* 2026-08-07

- **[M6a]** A recipe taking several distinct materials is not a conversion in the sense SPEC 21.3
  cares about, and treating it as one would relate almost every material to almost every other:
  a piston takes planks, cobblestone, iron and redstone, and reading that as "redstone converts
  into pistons" would refuse a buy list of any size. *Implemented default:* only recipes whose
  inputs are a **single** material become edges. The arbitrage SPEC describes needs a player to
  convert a quantity of one traded item into a quantity of another and back, which a multi-input
  recipe cannot do, because it consumes something the market does not price. *Date:* 2026-08-07

- **[M6a]** SPEC 21.10.1 says the market "refuses to enable" on a failed assertion, which could
  mean throwing at startup. *Implemented default:* a latch rather than an exception. The market
  is one module of many, and a server whose buy list has a bad pair should still have its
  cities, claims, protection and wars — so the check records its failures and
  `MarketService.enabled` consults it, closing every buy and sell through the path that has
  existed for `market.enabled: false` since M6. Per SPEC 21.10.4 the latch sits **above**
  configuration and there is no key that says "run anyway"; an operator fixes the buy list.
  *Date:* 2026-08-07

- **[M6a]** `MarketSafetyCheck.passed()` answers **false before anything has been checked**,
  rather than true. A check that never ran because of a wiring mistake would otherwise open the
  market silently, which is the failure this milestone exists to prevent, arrived at from the
  other direction. *Date:* 2026-08-07

- **[M6a]** SPEC 21.10.1 lists four startup assertions and M6a's row says "startup validation
  from 21.10.1", but M6b's row explicitly claims the hard blacklist and the villager-disjointness
  check. *Implemented default:* M6a owns the third assertion — no two buy-list items in the same
  crafting equivalence class — and the refusal mechanism the other three plug into, via
  `MarketSafetyCheck.fail`. The fourth, the `# automatable: no|semi` comment, belongs with the
  revised buy list in M6b. *Date:* 2026-08-07

- **[M6a]** The buy list this plugin already ships **passes** the check, which was not a
  foregone conclusion: it lists iron and gold ingots, diamonds, emeralds, netherite scrap, a log
  and stone, and any of those paired with its block or plank form would have failed.
  `MarketSafetyCheckTest.shippedListIsSafe` runs the assertion against `economy.yml` itself, so
  a future edit that adds the other side of a recipe is a build failure rather than an economy
  failure. SPEC 21.3's flaw is invisible from reading a price table. *Date:* 2026-08-07

- **[M6b]** SPEC 21.9's buy list has fourteen rows and **one of them contradicts two other rules
  in the same Part.** Nautilus shell is priced at 200 with the note "Only via AFK fishing, so
  quota-bounded, review at launch" — but SPEC 21.8's hard blacklist, which SPEC 21.10.4 says
  config cannot override, forbids "all fishing loot and all fish", and SPEC 21.4's A11 forbids
  it again by name. *Implemented default:* not shipped. The blacklist is the rule SPEC states
  most forcefully and the one it makes unoverridable, so a row that a code-level check would
  reject at startup cannot be the intended reading. **The buy list is 13 entries, not 14**, and
  SPEC's own "review at launch" is the sentence that says this was undecided. *Date:* 2026-08-08

- **[M6b]** SPEC 21.11 requires every buy entry to carry an `# automatable: no|semi` comment and
  makes its absence a startup failure — SPEC 21.10.1: "Every buy-list entry has the required
  `# automatable: no|semi` comment parsed from config". **The placement SPEC shows does not
  work.** Its example puts the comment at the end of the line, after a flow-style `{ … }` map,
  and Bukkit's YAML reads neither trailing comments on flow mappings nor preserves them across
  `save()` — so `ConfigManager`, which rewrites the operator's file whenever it adds a key, would
  silently delete every one of them and close the market on the next restart for a reason nobody
  could trace. Probed both readers before deciding. *Implemented default:* the comment goes on
  the line **above** the entry, where `getComments` reads it and `save()` keeps it. Both
  positions are accepted on read, so an operator who copies SPEC's layout is not punished for it.
  *Date:* 2026-08-08

- **[M6b]** SPEC 21.6 describes the sell catalogue in prose — "all stone and deepslate variants,
  all wood types and processed wood… and every decorative block added in 1.20 and 1.21" — with
  no table and one pricing rule, "1.5x to 4x the value of their raw inputs". That is several
  hundred materials and no prices. *Implemented default:* `economy.yml` prices about thirty
  **groups**, and `SellGroups` resolves each to its materials from Bukkit's own tags where one
  exists and an explicit list where none does. The consequence worth knowing: **a group is
  priced as a whole**, so every stair costs the same whatever it is made of. For a sink that is
  the right trade — the price is what removing money from circulation is worth, not what the
  variant cost to make — and an operator wanting one material priced differently can still name
  it outright alongside the groups, which four entries already do. *Date:* 2026-08-08

- **[M6b]** SPEC 21.8's blacklist and SPEC 21.6's catalogue **overlap deliberately, and it reads
  like a bug.** Wool, carpet, cobblestone, stone and flowers are on the list of things the server
  may never buy *and* in the catalogue of things it sells. Both are correct: SPEC 21.6 says
  "every item the server buys is a potential money faucet. Every item the server *sells* is a
  money sink and carries no exploit risk at all." *Implemented default:* the blacklist is checked
  on the buy side only, `MarketItem.serverBuys` carries the distinction per item, and selling a
  sell-only item is refused with `NOT_BOUGHT` rather than `NOT_TRADED` — an item the server sells
  to builders and will never buy back is a different thing from one it has never heard of, and a
  player who cannot tell them apart concludes the market is broken. Asserted explicitly, because
  a future reader will otherwise "fix" the overlap. *Date:* 2026-08-08

- **[M6b]** SPEC 21.10.1 requires the market to refuse to enable on a failed assertion, which
  means the checks must run before a server is guaranteed — and `org.bukkit.Tag`, which the
  blacklist's category entries read, resolves through the running server. **A class whose static
  initialiser throws is poisoned for the life of the JVM**: one touch without a server makes
  every later tag lookup fail even after one exists, and `try/catch` does not undo it. That one
  fact was behind every cross-test failure in this milestone, including several in the defense
  suite that have nothing to do with the market. *Implemented default:* every path that would
  mention `Tag` checks `Bukkit.getServer()` first and returns empty, the tag source is an
  injectable interface so the blacklist can be tested with no server at all, and
  `HardBlacklist.tagsResolved` **fails closed** — if the categories could not be read the market
  does not open, because a list that looks complete and is missing "all mob drops" is worse than
  no list. *Date:* 2026-08-08

- **[M6b]** `MarketSafetyCheck` had to grow from one assertion to four while staying a latch
  rather than an exception, and the ordering matters: the villager-disjointness and equivalence
  checks both iterate the buy list, so a buy list that failed to parse would report a clean
  bill of health. *Implemented default:* each check records its own failures and the market
  consults the aggregate, so four independent problems are all reported in one startup rather
  than one per restart. `passed()` still answers **false before anything has been checked**, for
  the reason recorded at M6a: a check that never ran because of a wiring mistake must not open
  the market silently. *Date:* 2026-08-08

- **[M6b]** The sell catalogue took the market registry from 19 items to over 400, and
  `MarketRegistry.loadAll` was firing **one async transaction per item** — invisible at 19 and
  a few hundred round trips per market open at 400. It took the test suite from three minutes
  to eleven and was first misdiagnosed as Gradle caching. *Implemented default:*
  `MarketStockDao.upsertDefinitions` writes the whole catalogue in one transaction. Recorded
  because the defect was latent from M6 and only a change of scale made it visible.
  *Date:* 2026-08-08

- **[M6c]** SPEC 21.5 does not say what happens to a sale that **straddles** the quota boundary:
  a player with 24,000 of 25,000 spent who sells 5,000 worth. Reducing the whole sale is one
  reading; splitting it at the line and reducing only the overflow is the other. *Implemented
  default:* split. Under the other reading a player who divides a stack into four gets
  meaningfully more money than one who sells it whole, which turns the quota into a puzzle about
  batch sizes rather than a cap on value — and rewards exactly the fiddly behaviour SPEC 21.5's
  "soft caps generate shrugs" is trying to avoid. Splitting is the only reading under which
  selling in one go and selling in pieces pay the same, and there is a test that asserts that
  property directly rather than asserting the arithmetic. *Date:* 2026-08-08

- **[M6c]** The counter records **value sold at full rate**, not coins paid. Past the cap a sale
  consumes no further quota. The alternative — counting the gross of every sale — would have a
  player past the cap burning five coins of counter for every one they were paid, so `/quota`
  would show a number racing away from a limit it had already hit and meaning nothing. What is
  lost is that `/quota` cannot say "you have sold 60,000 today, 25,000 of it at full rate";
  it says 25,000 of 25,000, which is the number that governs what the next sale pays.
  *Date:* 2026-08-08

- **[M6c]** SPEC 21.5 says "the newcomer 1.5x multiplier applies **within** the quota, not to the
  quota itself", which presupposes the SPEC 4.2 newcomer bonus applies to market sales. **It does
  not.** `IncomeMultipliers` is wired to the playtime stipend, the daily login and quests, and
  has never reached `MarketService` — so the interaction SPEC describes cannot occur. *Implemented
  default:* recorded, not fixed. Adding an income multiplier to the market changes the money
  supply, which is a decision for a milestone that owns income rather than a side effect of
  building the cap that bounds it. The ordering in `MarketService.sell` is already the one SPEC's
  sentence asks for — every multiplier is inside the figure the quota counts — so whichever
  milestone adds it gets "within the quota, not to the quota" for free. **This needs a developer
  decision**: it is the difference between a newcomer's first fortnight of market income being
  1.0x or 1.5x. *Date:* 2026-08-08

- **[M6c]** SPEC 21.10.3 requires the counter to be "exact under concurrency… the same
  synchronised service method as balance mutation", and the test that asserts it **is weaker than
  it looks**. Verified by removing the per-player lock entirely: the test stayed green, because
  SQLite serialises writers and hands the read-modify-write its atomicity for free. The lock is
  load-bearing only on MySQL, whose default REPEATABLE READ lets two transactions read the same
  row before either writes, and MySQL is not run in the ordinary build. *Implemented default:*
  the lock is kept, the test asserts the end-to-end invariant, and its comment states plainly
  what it does not prove. Two further limits worth naming: the lock is a JVM lock, so two servers
  sharing one MySQL would not be protected — and `EconomyService` has had exactly the same
  property since M5, so this is the established pattern rather than a new weakness in it.
  *Date:* 2026-08-08

- **[M6c]** A quota of zero disables the mechanism rather than starving the market. Read
  literally, `daily-sell-quota: 0` means every sale on the server is instantly past the cap and
  pays a fifth, which an operator who typed a zero would experience as the market silently
  breaking. *Implemented default:* `enabled()` requires a positive quota, so zero reads as off.
  The deliberate way to switch it off is `market.quota-enabled: false`; zero is treated as a
  misconfiguration and given the harmless meaning. *Date:* 2026-08-08

- **[M6c]** SPEC 22.3's `/quota` and SPEC 23.5.1's quota messages both need a duration ("resets
  in 4h 12m"), and SPEC 23.7 requires exactly one central duration formatter — which it assigns
  to the message framework in M7a, queue position 6. *Implemented default:* a package-private
  method on `QuotaCommand`, used by the two call sites that need it. Building the shared
  formatter now would be building part of M7a early and would then be replaced by it, since
  every other message in the plugin has to use the same one. *Date:* 2026-08-08

- **[M9a]** Two of this milestone's seven mitigations **supersede a rule the codebase stated on
  purpose**, and both deserve recording because a later reader will otherwise "fix" them back.
  SPEC 21.4 F6 overrides SPEC 17.1 case 10's even disband split, which it identifies as a way to
  launder money past the 25% withdrawal cap using alts who never deposited. And SPEC 21.4 F7
  overrides what M19 recorded about SPEC 4.7's silence on self-claimed bounties — M19's reasoning
  was that refusing a self-claim "would only teach players to place bounties through a second
  account", and F7's answer is to block the second account too rather than allow the first. Both
  carry a test that names the older rule in its comment. *Date:* 2026-08-08

- **[M9a]** **`DatabaseManager.transaction` rolls back on a returned `Result.Failure`, and that
  bit twice in this milestone in the same shape**: a write performed inside a transaction whose
  common outcome is a refusal is silently discarded. `BountyService.claim` refunded a self-placed
  bounty and then returned `NO_BOUNTY`, undoing the refund and leaving the bounty open;
  `DailyLoginService` stamped SPEC 21.4 F12's daily baseline and then returned `NOT_ACTIVE_TODAY`,
  so the baseline never persisted and "active playtime today" read zero forever. *Implemented
  default:* the bounty path returns `Success(ZERO)` once a refund has happened, and the baseline
  is written in its own transaction before the claim. Both were found by tests rather than by
  review. The general rule, now hit four times across M14, M17, M18 and here: **if a method can
  write and then refuse, the write and the refusal cannot share a transaction.** *Date:* 2026-08-08

- **[M9a]** SPEC 21.11 lists `disband-treasury-split: BY_CONTRIBUTION | EVEN`. `EVEN` is the
  exploit F6 exists to close, so the key is **not shipped**. Shipping it would put the
  vulnerability behind a setting, which is the same call the config sweep made about
  `player-shops.tax-percent` and `bounties.claimable-only-during-war`. There is a test asserting
  the key is absent, so re-adding it is a decision somebody makes rather than a tidy-up.
  *Date:* 2026-08-08

- **[M9a]** SPEC 21.11 also names `stipend-required-distinct-action-types: 3`, which is the key
  `income.stipend.required-actions` has held since M9 with the same default and the same meaning.
  *Implemented default:* the older name is kept and the new one is not shipped, because two names
  for one concept is exactly the dead twin the config sweep found three of. Only the genuinely new
  half, `stipend-required-distinct-minutes`, is added. The value is a config key either way, which
  is what the hard rule requires. *Date:* 2026-08-08

- **[M9a]** SPEC 21.4 F12's "30 minutes of active playtime **that day**" has no data behind it:
  `players.active_playtime_ms` is a lifetime counter. *Implemented default:* V17's
  `player_daily_activity` stores a **baseline** — the lifetime figure as it stood when the day
  turned — so today is the difference. That keeps the SPEC 4.2.1 filter as the only thing that
  ever writes active playtime, where a second accrual could drift against the first. It also
  means the rule fails open when the table is unreadable, deliberately: a missing table must not
  stop legitimate players being paid, and the lifetime gate still stops the case F12 cares most
  about. *Date:* 2026-08-08

- **[M9a]** SPEC 21.4 F9 says "breaking a player-placed block does not count for mining quests",
  which taken literally would break farming: a wheat crop is a block a player placed, and
  harvesting it is the whole of SPEC 13.1's farming category. *Implemented default:* ripe crops
  are exempt from the placed-block rule. Planting and harvesting is farming, and the point of a
  farm is that somebody put the seed there. Without the exemption the "harvest 256 wheat" quest
  would have become uncompletable by anyone who grew the wheat. *Date:* 2026-08-08

- **[M9a]** SPEC 21.10.5 requires the placed-block cache to be "memory-bounded with LRU eviction"
  and names no bound, so `placed-block-cache-max-chunks` (4,096) is this implementation's number.
  What matters more than the number is the **direction eviction fails in**: a forgotten position
  counts, so the worst case is a player getting quest credit they marginally should not have,
  never a player robbed of credit they earned by a cache they cannot see. Asserted explicitly,
  because the opposite choice looks equally reasonable in code and is much worse in play.
  *Date:* 2026-08-08

- **[M9a]** SPEC 21.4 F7's IP-linked rule reuses M15's `player_logins`, which stores a salted hash
  and never an address. Two consequences carried over deliberately: it **fails open** when the
  hash cannot be read, for the reason M15 recorded about losing the salt, and the refusal is
  **silent** — telling a killer their bounty was voided would report on somebody else's connection
  to a player who has no business knowing it, the same reasoning SPEC 13.4 uses for discarded
  contest votes. The self-claim half does not depend on the table, so it still holds when the
  IP half cannot run; there is a test for exactly that. *Date:* 2026-08-08

- **[M9a]** SPEC 21.4 F4 filters the **leaderboard**, not the war. A walkover is still resolved,
  still paid out and still in `/war history`; it simply does not rank, which is what "recorded but
  not ranked" asks for. The threshold is compared with `CASE` rather than `MIN`/`LEAST`, because
  the two-argument scalar form is spelled differently on SQLite and MySQL and `WarDao` runs on
  both. *Date:* 2026-08-08

- **[M9a]** Three existing test classes asserted the pre-F11 and pre-F16 behaviour and now turn
  the new rule off in their own setup rather than being weakened. `ActivityTrackerTest` covers
  SPEC 4.2.1's distinct-**kinds** rule and records in one burst to do it; `TreasuryServiceTest`
  covers SPEC 8.5's 25% cap and every member in it joined moments ago. Each says why in a comment
  and points at the class that does cover the new rule — including one asserting the 25% cap still
  applies once F16's hold has passed, so the two rules are not traded for one another.
  *Date:* 2026-08-08

- **[M3a]** SPEC contradicts itself on the world border, and the contradiction is load-bearing.
  SPEC 37 ships a `border:` block — `dynamic: true`, a base radius, an expansion bracket, a
  maximum, a nether ratio. SPEC 32.3 rejects that design in full: "The vanilla Minecraft border
  stands, unchanged, at roughly 30 million blocks. The plugin does not impose, expand, or manage
  a border of any kind", and SPEC 41's M3a row repeats it in bold. *Implemented default:* the
  later section wins, so **no border management and no border keys**. Shipping SPEC 37's block
  anyway would be seven settings that change nothing, which is the failure the config sweep found
  nineteen of. Two tests hold it: one asserting the keys are absent from `world.yml`, and one
  asserting no non-comment line in `WorldRegistry` mentions a border. SPEC 32.3's own reasoning
  is kept in the file beside the absence, because an absence looks like an oversight: "Emptiness
  is the atmosphere, and the ability to disappear into it is a feature." *Date:* 2026-08-08

- **[M3a]** SPEC 37's `world.yml` lists `worlds.claimable: [world]`, which is a second name for
  SPEC 16.1's `worlds.city-enabled` in `config.yml`. *Implemented default:* not shipped. One
  concept, one name — the twin is exactly what the config sweep found three of, where the file
  offered one name, the code read another, and both sides were inert with the value stuck at its
  default. The split that is shipped has no overlap: `config.yml` owns **permission** (which
  worlds a city may claim in, which are forbidden), `world.yml` owns **identity** (which world is
  the main one, which are the resource worlds). *Date:* 2026-08-08

- **[M3a]** SPEC 37's `world.yml` also carries `resource-world:`, `mining-claims:`, `travel:` and
  `backup:` blocks, none of which this milestone reads — they belong to M3b, M3c and M19c.
  *Implemented default:* each milestone ships its own keys. Shipping them now would put four
  blocks of settings in front of an operator that do nothing, and the whole point of the config
  sweep was that a key with nothing behind it is indistinguishable from a working feature.
  *Date:* 2026-08-08

- **[M3a]** **The config-integrity sweep did not cover this milestone's new file, and the build
  went green for that reason.** `ConfigKeyUsageTest.SHIPPED` was a hardcoded list of six
  filenames, so `world.yml` shipped entirely outside the net that exists to catch exactly this.
  Caught by suspecting a first-run green rather than by any test. *Implemented default:* the list
  is now derived from `ConfigFile.values()`, so a file added to the enum is swept from the moment
  it exists and the list cannot go stale again — the same structural fix `MigrationIndexTest`
  applies to the migration index. Verified by adding a dead key to `world.yml` and confirming the
  sweep fails, because a second green proves nothing after a first one was wrong. *Date:* 2026-08-08

- **[M3a]** SPEC 41's M3a asks for "world whitelist enforcement in **every** protection listener",
  and on inspection that is already true and always was — for a reason worth recording rather
  than quietly agreeing with. Protection is driven by whether a chunk is **claimed**, not by
  which world it is in, and a claim cannot exist in a world `ClaimService` refuses. So the
  listeners need no world check, and adding one would create a second authority that could
  disagree with the first. What genuinely did not exist was SPEC 17.2 case 21's "warn on
  startup", which is now `WorldRegistry.auditClaimedWorlds`. Case 21's other two halves —
  existing claims persist and stay protected, new claims blocked — come free from the same
  property. *Date:* 2026-08-08

- **[M3a]** `WorldKind.BLACKLISTED` wins over `CLAIMABLE` when an operator lists one world as
  both, which SPEC does not address. *Implemented default:* refuse. Of the two readings, that is
  the one that cannot lose anyone their land, and it matches the order the pre-existing code
  already checked in. An unmentioned world is `PLAIN` for the same reason: an operator who adds a
  world and forgets to configure it gets one nobody can claim rather than one anybody can.
  *Date:* 2026-08-08

- **[M3a]** `WorldRegistry` is built per-caller rather than threaded through constructors. It
  holds no state — every method reads the live configuration — so `CityService` and `ClaimService`
  each construct their own from the `ConfigManager` they already hold, and only the plugin builds
  the logging variant for the startup audit. Threading it would have meant a tenth argument on two
  constructors that every test and the plugin already call. *Date:* 2026-08-08

- **[M7a]** SPEC 24 assigns M7a the message **machinery** and SPEC 23.5's catalogue to M23a, so
  this milestone builds what messages will be sent through and converts none of them. The one
  exception is `Money.format`, which had to change: SPEC 23.7 requires "two decimals with
  thousands separators" and it was a bare `toPlainString`, so every currency figure in the plugin
  read `12847.22` where SPEC 23.1's own worked example reads `12,847.22`. That moved output in
  three existing tests, each updated to state both properties rather than just the new one.
  *Date:* 2026-08-08

- **[M7a]** SPEC 23.6 lists five categories that look locked and SPEC's table locks **three**:
  `treasury_withdraw`, `upkeep_critical` and `war`. `actionbar` and `sounds` are presentation and
  stay mutable. *Implemented default:* exactly the three, asserted by a test that names them, so
  a later reader cannot quietly add or drop one. The lock is a property of the category rather
  than a default, and it is enforced **twice** — `set` refuses it and `wants` ignores any stored
  row to the contrary. Two guards because SPEC 23.5.6 calls the withdrawal broadcast "the primary
  anti-fraud mechanism in the plugin", and a mechanism with one guard is one bug from being off.
  There is a test that writes a mute row straight into the table and asserts it does nothing.
  *Date:* 2026-08-08

- **[M7a]** SPEC 24's row says "per-player toggle store" and puts `/toggle` under SPEC 22.6, which
  belongs to the command-completeness milestone. *Implemented default:* the command ships here.
  A preference store no player can drive is inert configuration wearing a different hat, which is
  the failure the config sweep found nineteen of and which SPEC 22.1 rates High severity in the
  first place: "Section 23 adds many messages. Without a toggle, chat becomes unusable." Recorded
  as beyond the row's letter. *Date:* 2026-08-08

- **[M7a]** SPEC 23.3's prefixes could not be nested under the existing `prefix` key. `lang/` has
  held a top-level `prefix:` **string** since M0, used by `LangManager.send`, and adding
  `prefix.economy` beside it would make `prefix` both a string and a section — Bukkit reads `.`
  as a path separator, and that is the bug that rendered fifty GUI labels as
  `MemorySection[path=...]` to players while every test passed. They live under `prefixes:`
  instead, and a test asserts no prefix key starts with `prefix.`. The same trap was caught
  mid-build in `ToggleCategory.messageKey`, which built `toggle.category.<name>` before being
  changed to `toggle.category-<name>`. *Date:* 2026-08-08

- **[M7a]** SPEC 23.7's abbreviation rule is about the **channel**, not the number: "abbreviate
  above 1,000,000 in action bars and boss bars only, never in chat, because a player reading a
  transaction wants the exact figure". So `1,250,000.00` and `1.25M` are the same balance and
  both are correct. `Channel` carries the flag rather than the formatter guessing, since only the
  caller knows where the text is going. *Date:* 2026-08-08

- **[M7a]** SPEC 23.4 caps titles at "4 per hour per player, hard-limited in code". Two decisions
  SPEC does not make. The window **slides** rather than resetting on the hour, because four at
  10:59 and four at 11:01 is eight in two minutes and that is what the rule exists to stop. And a
  title past the cap is **downgraded to chat** rather than dropped: the message still has
  something to say, and losing it entirely is a worse failure than showing it less prominently.
  *Date:* 2026-08-08

- **[M7a]** Number formatting uses `Locale.ROOT` rather than the active language. Italian writes
  1.234,50 where English writes 1,234.50, so a server whose players read both files would see the
  same balance two ways and each would look like a bug to the other. SPEC 23.7 asks for "a single
  central formatter" and does not say the grouping follows the translation. A currency figure is
  closer to an account number than to prose: it is formatted one way everywhere and only the
  words around it are translated. *Date:* 2026-08-08

- **[M7a]** **This milestone wrote mojibake into `it.yml` and nothing in the suite would have
  caught it.** A Python `unicode_escape` round-trip turned `à` into `Ã`+U+00A0 — valid UTF-8 that
  loads without complaint, passes key parity, passes placeholder validation, and renders as
  nonsense to an Italian player. Found by inspecting codepoints after the console printed
  question marks, which also proved a *false* alarm: the M3a lines the console mangled were
  correct all along. *Implemented default:* repaired, and `LangKeysTest.noMojibake` now fails the
  build on U+FFFD or U+00A0 in any language file. Those two characters are the signature — one is
  what a decoder writes when it gives up, the other is the second half of every Latin-1 mojibake
  pair and is never typed on purpose. *Date:* 2026-08-08

- **[M7a]** `DiplomacyServiceTest.disbandForgetsEverything` began failing in full runs while
  passing alone, and the cause is worth recording because the test was always wrong. It asserted
  that alliance rows were gone immediately after `disband` returned, but `CityService`'s
  disband hooks run **after** the transaction commits and nothing returns a future for them —
  M13 made them hooks rather than `CityDisbandEvent` listeners precisely because that event fires
  before the mutation so it can be cancelled. The assertion was passing on the cleanup usually
  winning a race, and it lost that race the moment this milestone put more work through the same
  executor. *Implemented default:* the row assertion polls; the registry assertions above it,
  which are what a player actually observes, stay immediate because the registry is updated
  synchronously. Nothing in the product changed. *Date:* 2026-08-08

- **[M4a]** **SPEC contradicts itself on whether peacetime PvP exists at all, and this is the
  largest open question in the project.** SPEC 33.1's table, SPEC 33.3's prose and SPEC 33.10's
  `combat.yml` all enable it — the wilderness is PvP with keepInventory on, "for skirmishing,
  bounty hunting, and the tension of travel". SPEC 37's `combat.yml` and SPEC 38's own M4a row
  disable it: "Peacetime PvP disabled globally." Both are in Part IV. *Implemented default:*
  **disabled**, per CLAUDE.md's instruction to take the most conservative option. Three reasons:
  Part I's pillar 1.4 says "outside of declared wars, the world is fully protected" and SPEC 1
  makes the pillars decide ambiguous calls; nobody is killed unexpectedly; and enabling it later
  adds something where disabling it later takes something away from players who have grown used
  to it. It is **one config key**, `pvp.peacetime`, with a test asserting the flip works, so the
  other reading costs an edit rather than a rewrite. **This needs a developer decision**: it is
  the difference between a wilderness that is dangerous and one that is not. *Date:* 2026-08-08

- **[M4a]** Evidence that SPEC 37 is the stale side of that contradiction, recorded because it is
  the same finding as M3a's and a third instance would make it a rule. SPEC 37 also ships the
  `border:` block that SPEC 32.3 rejects outright, which M3a already established. And its
  `combat-tag.seconds: 15` contradicts SPEC 33.8, which spends a paragraph arguing specifically
  for 30 and 120 — "120 seconds, not 300". Section 37 reads as a configuration appendix that fell
  out of sync with the design sections it serves. That is an argument, not a proof, which is why
  the decision above went to the conservative option rather than to the section I find more
  convincing. *Date:* 2026-08-08

- **[M4a]** SPEC 33.3 makes bounties claimable in peacetime, which Part I's SPEC 4.7 restricts to
  "during an active war". With peacetime PvP off, that question does not arise: there is no
  peacetime kill to claim on. Part I's rule stands unchanged. If the developer enables peacetime
  PvP, the bounty rule has to be revisited at the same time, and SPEC 21.4 F7's self-claim and
  IP-linked blocks from M9a apply either way. *Date:* 2026-08-08

- **[M4a]** Ordering inside the policy is a decision SPEC does not state: **zones are checked
  before wars**. SPEC 32.7 makes spawn peaceful "under all circumstances including active wars"
  and SPEC 33.5 says the same of the resource worlds, so a sanctuary a war could override would
  not be a sanctuary. Grace periods come before both, because they are about the players rather
  than the place. There is a test that injects an always-true war check and asserts an
  admin-protected chunk still refuses. *Date:* 2026-08-08

- **[M4a]** The war and mining-claim seams are **injected**, not overridable methods. The first
  draft made them protected and the test subclassed the policy, which does not work on a final
  class and would have been the wrong shape anyway: the war milestone needs to hand in a real
  check at wiring time, not define a subclass. Each is one setter — `useWarCheck`,
  `useMiningClaims` — and everything else in the class already behaves correctly once a war can
  say yes, because each other rule is evaluated before the war is asked. *Date:* 2026-08-08

- **[M4a]** `ProtectionService.checkPvp` is **superseded and now unreachable**. It only ever
  covered claims, with vanilla covering the rest of the map, and SPEC 33 replaces Part I 5.5 and
  11.6 "in full". `EntityProtectionListener` takes the policy as a **required** constructor
  argument rather than an optional one — it was optional for about ten minutes until a check
  found nothing constructed the listener without it, and an optional authority is two rules that
  will eventually disagree. The old method survives only because `ProtectionAction.PVP` is still
  in the action enum and removing an enum constant the guard's message routing knows about
  belongs with the war half. Marked in the javadoc so it is not mistaken for live code.
  *Date:* 2026-08-08

- **[M4a]** SPEC 37 lists `SPAWN` as an exclusion zone and nothing in the plugin knows where
  spawn is: SPEC 32.7 describes a built hub inside an admin-protected region, which is content
  rather than code. *Implemented default:* a configurable radius in chunks around the **main
  world's spawn point**, default 8, injected as a supplier so the policy stays free of Bukkit
  types. An operator who protects their hub with `/ca claim protect` gets the same result through
  `ADMIN_PROTECTED`, so the two overlap deliberately — the radius is the one that works on a
  server whose admin has not thought about it. *Date:* 2026-08-08

- **[M4a]** The config sweep covered `combat.yml` from the moment it existed, with no edit to any
  test. That is the M3a fix working: `ConfigKeyUsageTest` derives its file list from
  `ConfigFile.values()` rather than from a hardcoded literal, so the file that would previously
  have shipped outside the integrity net was inside it on the first build. Recorded because it is
  the first evidence that the structural fix was worth more than the milestone it came from.
  *Date:* 2026-08-08

- **[M3b]** SPEC 32.7 defines `/warp <name>` as "admin-defined public warps" and **no section
  anywhere defines a command that creates one** — not SPEC 9.4's admin tree, not SPEC 22.7's
  additions to it. *Implemented default:* `/ca warp set|delete|list`, under
  `civitas.admin.system` rather than a new permission node, because SPEC 10's node list is
  closed and inventing a permission is a larger liberty than inventing the subcommand. A warp
  system with no way to make a warp is inert, which is the same reasoning that shipped `/toggle`
  alongside its preference store in M7a. *Date:* 2026-08-08

- **[M3b]** The `warps` table carries an `expires_at` that nothing in this milestone sets. That is
  deliberate rather than speculative: SPEC 40.1 requires a contest submission to generate "a
  temporary public warp… available for the duration of the voting window only", and the
  alternative is that milestone building a second warp system beside this one. Expiry is judged on
  **read** rather than trusted to the sweep, so a contest warp stops working the moment voting
  closes rather than whenever housekeeping next runs. *Date:* 2026-08-08

- **[M3b]** **Warmup-and-cooldown was already written twice before this milestone**, in
  `SpawnService` for the city spawn and `OutpostTeleport` for outposts, and SPEC 32.7 tabulates
  six destinations that all share the rule. The three new ones go through one `TeleportService`.
  The two existing ones were **not** retrofitted: `OutpostTeleport` is superseded by the M10
  rebuild, and `SpawnService` works, is tested, and changing it would risk M8's behaviour for no
  new capability. That leaves three copies rather than one, which is duplication rather than the
  two-authorities contradiction M4a fixed — the failure mode is maintenance, not wrong behaviour.
  **The M10 rebuild should consolidate all three.** *Date:* 2026-08-08

- **[M3b]** SPEC 32.4 lists "any claim, any claim buffer, **any outpost**" as three separate
  things to avoid, and an outpost is a claim row with type `OUTPOST`, so the claim rule already
  covers it. *Implemented default:* one check, with a test that places an outpost claim and
  asserts it is refused. A separate outpost check would be a second rule that could drift from the
  first, which is the shape of defect this project has now found four times. *Date:* 2026-08-08

- **[M3b]** `/rtp`'s buffer distance is read from `claims.buffer-chunks`, the same key that
  governs claiming, rather than a `travel.rtp` figure of its own. If the two could differ, a
  player could be dropped somewhere and then told it is too close to a city to claim. There is a
  test asserting they are the same number. *Date:* 2026-08-08

- **[M3b]** The search runs **before** the warmup, not during it. SPEC 32.4 says only that a
  failure should "report honestly and refund". Searching first means a player is never told
  "travelling in five seconds" and then that there was nowhere to go — and the refund is a refund
  by construction, because `TeleportService` charges on arrival and there was never a payment to
  reverse. The affordability check happens up front, so a player is refused at once rather than
  made to wait five seconds to learn they are poor. *Date:* 2026-08-08

- **[M3b]** A fare that fails to collect **after** the player has arrived is logged and the
  journey stands. The alternative is teleporting somebody and then teleporting them back because
  their balance moved during the warmup, which is a worse outcome for the player than a fare the
  server occasionally misses. The affordability pre-check makes it a narrow window.
  *Date:* 2026-08-08

- **[M3b]** `RandomTeleport` and `TeleportService` accept a **null plugin**, and that is not
  laziness. The plugin is needed only for the scheduler — the async search and the warmup task —
  while the rules and the configuration readers are pure. Requiring it would mean either a stub
  server in every rule test or moving the pure half into a fourth class. Each async entry point
  checks for it explicitly and refuses with a clear reason rather than throwing a
  `NullPointerException` from inside Bukkit. *Date:* 2026-08-08

- **[M3b]** Two things this milestone genuinely cannot test and does not pretend to. SPEC 32.4's
  block-level safety check (`isSafe`) needs real terrain: MockBukkit generates none, so "solid
  ground, breathable, not in lava" is verified by the manual pass and by nothing else — the same
  limitation M20 recorded for tile states. And the warmup needs a running scheduler, so
  cancellation on movement and on damage is wired and reviewed but not asserted. Both belong on
  the launch checklist rather than in a test that mocks the thing it is meant to prove.
  *Date:* 2026-08-08

- **[M3b]** `/rtp`'s 500 C fare has no ledger type. SPEC 4.6 calls its list exhaustive and it
  predates Part IV's travel entirely, so there is no `TRAVEL_FEE`. *Implemented default:*
  `OUTPOST_TELEPORT_FEE`, with `{"travel":"rtp"}` in the metadata, which is the same call M9a made
  when a burned treasury had to go under `UPKEEP_CHARGE`: use the nearest existing type rather
  than break SPEC 4.6's stated exhaustiveness, and let the metadata carry the distinction.
  Recorded because an admin summing `OUTPOST_TELEPORT_FEE` will now see rows that are not outpost
  teleports, and the metadata is the only thing that separates them. *Date:* 2026-08-08

- **[M3b]** **`/quota` shipped at M6c and `/toggle` at M7a with no `/city help` entry, and
  `HelpPagesTest` did not catch it.** M23 built that test to assert both directions — no entry
  names a command that does not exist, and no root command is undocumented — but its list of root
  commands was a **hardcoded literal of nineteen names**, so a command the list had never heard of
  was invisible to both halves. Two player-facing commands were undiscoverable in the help for two
  milestones. *Implemented default:* the list moved into main code as `HelpPages.ROOT_COMMANDS`,
  the test reads it, and the five commands added since M23 are documented in both languages.
  Adding a command now means adding it to that list, at which point the test fails until it is
  documented. **This is the third instance of the same defect** — a test whose scope is a literal
  that goes stale, after `ConfigKeyUsageTest.SHIPPED` in M3a and `MigrationIndexTest`'s ancestor
  before it — so it is worth treating as a pattern: a coverage test must derive its universe from
  the thing it is checking, never restate it. *Date:* 2026-08-08

- **[M3b]** **The mojibake test I wrote at M7a was too narrow, and this milestone's Italian slipped
  past it.** `può` went into `it.yml` as `puÃ²`, and `noMojibake` did not fire — because I had made
  it look for U+FFFD and U+00A0, which are the two characters *M7a's* accident happened to produce.
  `à` mojibakes to `Ã` + NBSP; `ò` mojibakes to `Ã` + `²`. I had tested one instance of the defect
  and described it as the class. *Implemented default:* the test now looks for the actual
  signature — a character in U+00C2..U+00DF immediately followed by one in U+0080..U+00BF, which is
  a UTF-8 two-byte sequence read as Latin-1 — so it catches every accented character rather than
  the ones somebody has already tripped over. No false positives are possible in either shipped
  language: real prose never puts a capital A-with-diacritic immediately before a Latin-1
  punctuation character. Worth recording as a lesson about test design rather than about encoding:
  **a test written from one failure tends to encode that failure rather than its class.**
  *Date:* 2026-08-08

- **[M3c]** SPEC 32.6 gives `/mine trust <player>` a maximum of four and **no claim argument**, so
  trust cannot be per claim — there is nothing in the command to say which one. *Implemented
  default:* per **owner**. Trusting somebody trusts them on everything you hold, which is the only
  reading the command's shape supports and the simpler one for a player to hold in their head. The
  table is keyed `(owner_uuid, trusted_uuid)` accordingly, and there is a test asserting a grant
  covers both of a two-claim player's chunks. *Date:* 2026-08-08

- **[M3c]** `MiningClaimRegistry` is keyed by a `(world, chunkX, chunkZ)` **record** rather than by
  `ChunkKey`'s packed long, which is what city claims use. `ClaimRegistry`'s packing needs a
  world-index allocator that is private to it, and a second allocator is a second thing that could
  disagree about which world is index 3 — the two-authorities defect again, in a place where the
  consequence would be one player's protection applied to another's chunk. The record costs one
  small allocation per lookup, and that only matters on the hot path, which this is not:
  `ProtectionService` asks the **world kind first**, so a block broken in the main overworld never
  reaches the map at all. *Date:* 2026-08-08

- **[M3c]** SPEC 32.6 says an unpaid claim is "released. **Blocks are not removed**", and a unit
  test cannot assert the absence of an action. *Implemented default:* asserted structurally —
  `MiningClaimService` holds no reference to `org.bukkit.World` and calls no `setType`, so it has
  no means to remove anything. The same shape M22 used for the player-shop tax and M9a for the
  peer-trade exemption: prove no code path exists rather than that one path behaves.
  *Date:* 2026-08-08

- **[M3c]** The grace clock runs from the **first** missed payment, not the latest. SPEC 32.6 says
  "7-day grace" without saying which. Restarting it on each failed sweep would mean a player who
  can never pay never runs out of grace, because every sweep pushes the deadline back a day —
  which makes the upkeep optional. There is a test for exactly that. *Date:* 2026-08-08

- **[M3c]** Mining upkeep gets its **own timer** rather than a line inside the city upkeep sweep.
  The two charge different accounts — a city pays from its treasury, a mining claim from its
  owner's own wallet — and a failure in one must not stop the other. It also means an operator who
  switches mining claims off stops paying for the sweep entirely. *Date:* 2026-08-08

- **[M3c]** `/mine tp` reads its numbers from `mining-claims.teleport` rather than
  `travel.mine-tp`, which is where `TravelKind`'s other four destinations look. The override is one
  method on the enum constant. An operator turning mining claims off or retuning them should find
  every number for the feature in one block, rather than four of them in one place and the
  teleport's three somewhere else. *Date:* 2026-08-08

- **[M3c]** `/mine info` shows a trusted player's **id** when they are offline rather than their
  name. Resolving four names is four database reads for one line of output, and the id is still
  enough to run `/mine untrust` against. Online players are named. Recorded because it is visibly
  less polished than the rest of the command and the reason is not obvious from reading it.
  *Date:* 2026-08-08

- **[M3c]** SPEC 4.6's ledger types have nothing for a mining claim, so a purchase is
  `CHUNK_CLAIM`, a refund is `CHUNK_UNCLAIM_REFUND` and upkeep is `UPKEEP_CHARGE`, each with
  `{"mining_claim": …}` in the metadata. Third instance of the same call after M9a's burned
  treasury and M3b's travel fare: use the nearest existing type rather than break SPEC 4.6's
  stated exhaustiveness, and let the metadata carry the distinction. **Worth a developer decision
  at some point** — three features now share types with city land and city upkeep, and an admin
  summing `CHUNK_CLAIM` gets both. *Date:* 2026-08-08

- **[M3c]** This milestone filled the two seams earlier ones left rather than adding new checks:
  `ProtectionService.useMiningClaims` and `PvpPolicy.useMiningClaims`, one call each. Recorded
  because it is the first time the seam discipline has paid off in this project rather than merely
  been described — M4a wrote the PvP seam without knowing what would fill it, and filling it took
  one line and no changes to `PvpPolicy` at all. *Date:* 2026-08-08

- **[M3c]** **The upkeep sweep released a player's mining claim and told them nothing**, and the
  key-usage sweep is what found it: `mine.released` and `mine.upkeep-failed` were written into both
  language files and sent by nothing. SPEC 23.1's first principle is that "every action produces
  feedback", and a player losing a claim they paid 15,000 C for without a word is the exact silent
  failure that principle exists to prevent. *Implemented default:* a `Notifier` seam on the
  service, in the same shape `UpkeepTask.Notifier.online` already uses, telling the owner when a
  payment fails and how long they have, and again when the claim goes. Worth recording as evidence
  that the orphaned-key half of `LangKeyUsageTest` earns its keep: it does not catch dead text so
  much as **features that were never finished**. *Date:* 2026-08-08

- **[M3c]** `mine.wrong-world` was passed a `<world>` placeholder it never displayed — the defect
  M23's localisation check was built for, committed again one milestone after the check found six
  of them. *Implemented default:* the placeholder is dropped rather than the message changed. The
  refusal already tells the player where they *can* claim, which is more use than the name of the
  world they are standing in. *Date:* 2026-08-08

- **[M3c]** A pre-existing test was **flaky by construction** and failed this milestone's build for
  it. `IncomeSystemsTest`'s "a different day draws different quests" compared two **players** on
  one day, not two days, and asserted their draws differed — but SPEC 13.1 draws three quests from
  a pool of eight, so two players legitimately collide a few percent of the time. It passed three
  reruns immediately afterwards, which is exactly what makes this kind of test expensive: it will
  fail somebody else's build and look like a real regression. *Implemented default:* replaced with
  the two properties that are actually true and cannot collide — the draw is seeded **per player**,
  which shows up across thirteen players as more than one distinct draw; and asking twice on one
  day is **stable**, or a player could refresh until they liked their quests. The name and the body
  also disagreed, which is how the flakiness survived review. *Date:* 2026-08-08

- **[M10, in progress]** SPEC 39.3's `n` is genuinely ambiguous and the difference is money. It is
  "the city's TOTAL chunk count including all outpost chunks, exactly as Part I 6.2 computes it",
  and Part I 6.2 indexes the chunk **being** claimed — the ninth chunk has index 9. Those readings
  differ by one chunk and by about **6%**. *Implemented default:* the count **before** the
  purchase, settled against SPEC 39.4's published tables rather than by argument — a twenty-chunk
  city founding at 1,000 blocks pays 31,721 in that table, which is `400 × 20^1.25 × 1.25 × 1.5`,
  where the other reading gives 33,712. `OutpostCostEngineTest` asserts **every cell of both
  tables**, the D(d) table, and SPEC 39.5's upkeep and teleport table, because a formula that is
  nearly right passes any test written from the formula. *Date:* 2026-08-08

- **[M10, in progress]** **SPEC 39's multi-chunk outposts need no migration**, which was not
  obvious and is worth recording before somebody writes one. Part I's `outposts` table stores only
  the warp point; the chunk lives in `claims`, linked by `claims.outpost_id`. Several claims
  sharing one `outpost_id` *is* a multi-chunk outpost, so the Part I schema happens to support the
  design that replaces it — because the link was a foreign key rather than coordinates on the
  outpost row. *Date:* 2026-08-08

- **[M10, in progress]** **I committed the defect I have spent four milestones removing from other
  people's work.** Replacing `cities.yml`'s outpost block with SPEC 39.15's in full — before
  writing the service that reads placement distances, defence caps and the release order — shipped
  a dozen config keys nothing read, and broke `ConfigKeyUsageTest` and `ConfigDefaultsTest`
  together. It also would have silently repriced every outpost, because removing SPEC 16.2's keys
  left the Part I code that still reads them falling back to hardcoded defaults. *Implemented
  default:* the block now ships only the keys something reads, SPEC 16.2's keys stay while the code
  reading them does, and the engine reads the existing `delete-refund-percent` rather than
  shipping SPEC 39.15's `unclaim-refund-percent` beside it as a twin. The rest of SPEC 39.15 lands
  with the service that consumes it. **The lesson is ordering: config follows code, never leads
  it.** *Date:* 2026-08-08

- **[M10, in progress]** SPEC 39.7's merge has **three** outcomes and they are easy to conflate,
  so `OutpostGeometry.Merge` names them: absorbed into the city body (the outpost merges whole,
  slot frees, nothing refunded, and the four-chunk cap does **not** apply because the result is
  city land), two outposts merging, and the same bridge **blocked** because the result would exceed
  four chunks. SPEC is explicit that the third rejects the claim rather than merging and
  truncating. A fourth case, SPEC 39.14 case 132, comes from the other direction — a city growing
  into its own outpost — and is a separate check because the bridging claim is a city claim.
  *Date:* 2026-08-08

- **[M10, in progress]** SPEC 39.3's pricing is now what the service charges, and it changed a
  method signature in a way worth recording: `creationCost` takes a **position**. Part I 7.2's
  flat 25,000 C plus three chunk costs needed only a city, because the price was the same
  everywhere. Under SPEC 39.3 the same outpost costs 31,721 at a thousand blocks and 225,999 at a
  million, so a figure quoted without a place is not a price. Both callers — the command's help
  line and the outposts menu — already knew where the player was standing. *Date:* 2026-08-08

- **[M10, in progress]** Two Part I tests failed and **both were right to**. "An existing outpost
  does not push the next one 32 chunks away" asserted that outposts had no spacing rule, which
  Part I 7.2 intended; SPEC 39.6 adds 24 chunks because outposts grew to four, and six of them
  could otherwise be laid end to end into a continuous road — the SPEC 6.1 adjacency rule defeated
  by other means. "Two outposts touching each other stay outposts" asserted a state SPEC 39.6 now
  forbids outright. Both were replaced rather than deleted, each carrying a note of what it used to
  claim, because a test that vanishes leaves no record that the rule changed. *Date:* 2026-08-08

- **[M10, in progress]** Distance is measured from an outpost's **founding chunk**, not from its
  centre of mass, so expanding an outpost never moves its price or its upkeep. SPEC 39.3 says only
  "from the city core chunk centre to this chunk centre" and does not say which chunk of a
  multi-chunk outpost that is. Founding chunk is the stable answer: the alternative means a city's
  daily bill shifts when it adds a chunk on the far side, for reasons no player could predict.
  *Date:* 2026-08-08

- **[M10, in progress]** SPEC 39.7's outpost-to-outpost merge is **unreachable on a default
  server**, and that is a consequence of SPEC's own numbers rather than a bug. SPEC 39.6 requires
  24 chunks between a city's outposts at founding, and SPEC 39.6 caps each at four chunks — so two
  outposts can grow to at most eight chunks across a 24-chunk gap and can never touch. The merge
  becomes reachable only if an operator lowers `min-distance-from-own-outposts`. *Implemented
  default:* the rule is built and tested with the spacing turned down, rather than left untested
  on the grounds that it cannot fire — SPEC 39.7 describes it as a real case, an operator may well
  lower the spacing, and a rule nobody has exercised is a rule nobody knows works. Recorded because
  the first draft of that test quietly asserted nothing, having placed two outposts that could
  never reach each other. *Date:* 2026-08-08

- **[M10, in progress]** `Claim.convertTo` is package-private, so the merge **rebuilds** the cached
  claim rather than mutating it — which is what `convertAdjacent` already does two hundred lines
  above for the same reason. Worth recording only because the natural instinct is to widen the
  accessor, and the existing code had already chosen not to. *Date:* 2026-08-08

- **[M10, in progress]** I invented `outpost.unknown-name` for a refusal when `outpost.unknown`
  already existed — one concept, two names, caught by the lang sweep before it shipped. The path in
  question is only reachable if a claim points at an outpost row that is gone, which is corruption
  rather than a state the game produces, so it now reuses `outpost.no-chunk`. *Date:* 2026-08-08

- **[M10]** SPEC 39.5 changes the *shape* of upkeep, not only its number, and the signature had
  to change with it. Part I 7.2 charged every outpost a flat 2,000 a day, so `UpkeepCalculator`
  could take a count and multiply. SPEC 39.5 charges `1200 * D(d) * chunks`, so two outposts of
  one city rarely cost the same. *Implemented default:* the calculator takes a **figure**, summed
  by the outpost service, which is the only thing that can price them. Taking a count would have
  meant that class quietly averaging them, and the average of two outposts at a thousand and a
  million blocks is a number neither of them costs. SPEC 16.2's `outposts.upkeep-per-day` retires
  with it, under SPEC 39.15's own "replaces the `outposts` block in Part I 16.2" — the same
  linear-supersession rule the border and the PvP contradictions were settled by, and
  `ConfigDefaultsTest` now asserts the key is **absent** rather than merely not asserting it,
  because a key a superseded section mandated is exactly the kind that gets restored by accident.
  *Date:* 2026-08-08

- **[M10]** SPEC 39.5 says a delinquent city releases outposts "furthest first" but not whether
  a four-chunk outpost goes whole or a chunk at a time, and the day's release budget
  (`upkeep.delinquent-unclaim-per-day`, 3) is counted in chunks. *Implemented default:* a whole
  outpost, counting once against the budget. Releasing three of four chunks would leave the city
  paying a distance-scaled bill on a fragment nobody can use and no warp worth taking, which is
  the worst of both outcomes; and an outpost is a place rather than a pile of tiles, which is the
  premise of the whole SPEC 39 rework. *Date:* 2026-08-08

- **[M10]** **The teleport fold changed a deliberate behaviour, and it is a behaviour change
  rather than a refactor.** `OutpostTeleport` charged the fare *first* and travelled only if the
  charge succeeded ("Charged nothing, so travelled nowhere"); `TeleportService` travels first and
  charges after, logging a miss. Both were documented on purpose, and folding forced a choice.
  *Implemented default:* charge-after, for every destination. Its reasoning applies to outposts
  unchanged — "teleporting a player and then teleporting them back because their balance moved
  during the warmup is a worse outcome for the player than a fare the server occasionally fails
  to collect" — and the affordability check before the warmup keeps the window to eight seconds.
  `outpost.tp-unpaid` becomes unreachable and is removed. The cost is real and worth stating: a
  player whose balance drops mid-warmup now arrives without paying, where before they stayed put.
  *Date:* 2026-08-08

- **[M10]** The fold's first pass would have **undone a fix that took two milestones to notice**.
  M23 corrected `outpost.tp-warmup` and `outpost.tp-arrived`, which had shown players a literal
  `<name>` since M10; the generic `travel.warmup` and `travel.arrived` name no destination at all,
  so folding onto them would have replaced "Travelling to North" with "Travelling in 8 seconds".
  `LangKeyUsageTest`'s orphan half is what caught it — the three outpost messages went dead, and
  the reason they went dead was the regression. *Implemented default:* the generic messages gained
  a `<destination>` placeholder and `begin` gained an optional label, so an outpost passes its own
  name and every other destination passes its kind. Five destinations that previously said
  "Arrived." now say where. Worth recording as a lesson about what that test actually catches: it
  reads as a tidiness check and it is not — dead text is usually the *symptom* of a feature that
  lost a wire, twice now after M3c's silent mining-claim release. *Date:* 2026-08-08

- **[M10]** The fold turned up a live defect it was not looking for. The Outposts menu built its
  travel-cost lore from `outpostTeleport().teleportCost()`, a no-argument read of a flat config
  value — so every outpost button advertised 100 C while SPEC 39.5 charges `100 * D(d)`, up to
  891 at a million blocks. Invisible from reading either file alone: the menu was correct against
  Part I 7.2 and the engine was correct against SPEC 39.5. *Implemented default:* the button asks
  `fareFor(city, outpost)`, and the folded path cannot regress it, because it has to be handed a
  fare and there is no longer a no-argument cost to call. *Date:* 2026-08-08

- **[M10]** Three copies of warmup-and-cooldown become one, which is what M3b recorded as the
  M10 rebuild's job. `SpawnService` is deliberately **not** folded: SPEC 5.6 gives the city spawn
  its own numbers and its own war behaviour (a 15-second warmup during a war, SPEC 11.6), it
  works, it is tested, and it is not this milestone's subject. That leaves two mechanisms rather
  than one — duplication rather than the two-authorities contradiction M4a fixed, so the failure
  mode is maintenance and not wrong behaviour. **A milestone that touches the city spawn should
  finish the job.** *Date:* 2026-08-08

- **[M10a]** SPEC 39.10's table gives a waystation a cost, an upkeep and a teleport fee, and
  **no refund at all**. Deleting one therefore has no specified outcome. *Implemented default:*
  half of what each chunk cost, to the treasury, matching what SPEC 39.5 gives an outpost and
  SPEC 6.4 gives city land. A rule differing from both its neighbours would need a reason SPEC
  does not supply, and the alternative — refunding nothing — would make a waystation the one
  holding in the game that is pure sunk cost, which reads as an oversight rather than a design.
  Behind `waystations.refund-percent`, so an operator who disagrees sets it to zero.
  *Date:* 2026-08-09

- **[M10a]** SPEC 39.10 gives the teleport as "200 C" flat, where SPEC 39.5 scales the outpost
  fare by `D(d)`. Two systems built a week apart in the same Part, one scaling and one not, is
  the shape of an omission. *Implemented default:* flat, read as deliberate. SPEC 39.10 argues
  in its own text that "the resource worlds exist to be travelled deep into, and penalising that
  would defeat their purpose" — it makes that argument about the *distance constant*, and it
  applies with more force to a fee paid on every single trip than to a price paid once. A fare
  that rose with depth would tax exactly the mining the worlds exist for. *Date:* 2026-08-09

- **[M10a]** SPEC 39.10 measures distance "from that world's spawn, not from the city core",
  which is not a preference but a necessity: the core is in `world` and the waystation is in
  `resource`, so there is no distance between them to measure. Worth recording because the
  method reads like an inconsistency with `OutpostService.blocksFromCore` and is not one. It
  also falls back to `(0, 0)` when the world is not loaded, which keeps the pricing pure enough
  to test without a server and is correct for a default world spawn. *Date:* 2026-08-09

- **[M10a]** Waystation upkeep is **summed into the outpost figure** rather than given its own
  term in `UpkeepCalculator`. Both are the same thing to a treasury — remote holdings priced by
  how far out they are — and a fourth parameter would be a fourth thing every caller and every
  test has to pass. They stay visibly separate everywhere a player looks (`/city waystation
  list` prices each one, the outpost menu prices those) and merge only where the city simply
  owes money. The consequence to know: `dailyUpkeep`'s second argument is no longer "outposts",
  it is "remote holdings", and its javadoc says so. *Date:* 2026-08-09

- **[M10a]** Protection is checked **inside** the resource-world branch of
  `ProtectionService.check`, before its fallthrough, and the placement is the whole rule. That
  branch answers ALLOWED for every chunk no mining claim holds, because SPEC 32.5 leaves the
  resource worlds open on purpose — so a waystation check placed after it would never be
  reached, and the feature would have created rows, charged the treasury, appeared in its own
  list command and defended nothing. Nothing about that failure is visible from reading either
  class alone. The seam is optional and null-safe, so every test written before this milestone
  answers exactly as it did, and there is a test asserting that too. *Date:* 2026-08-09

- **[M10a]** **Three tests failed because this milestone added a table, which is never a
  defect** — the fourth, fifth and sixth instances of the same shape in this project after
  `ConfigKeyUsageTest`'s file list, `HelpPagesTest`'s command list and the migration index.
  `MigrationRunnerTest` asserted a hardcoded set of version numbers and `DaoRoundTripTest` a
  hardcoded DAO count; both now derive, and the DAO test asserts something better than a count
  in the process — that no two DAOs claim the same table, which a count could never catch.
  `SchemaTest` is **not** the same defect and was not "fixed": its curated table list is a
  deliberate conformance check, and a table added without an entry is exactly what it exists to
  catch, so it gained one. The distinction is worth stating because the reflex after two
  identical failures is to derive the third. *Date:* 2026-08-09

- **[M10b]** SPEC 39.12 asks the detail screen for "a chunk layout diagram showing which of the
  four chunks are owned" and specifies neither its size nor what it is anchored on. *Implemented
  default:* a 3x2 window anchored on the **founding chunk**, not centred on the player. Centring
  on the player would make the shape slide around while they walk through their own outpost,
  which defeats the purpose — the diagram exists because SPEC 39.6 refuses an expansion that does
  not border and an unclaim that would split, and both refusals are opaque without a still
  picture of what is owned. The cost: a straight line of four chunks does not fit the window, and
  the header's chunk count is what covers that case. *Date:* 2026-08-09

- **[M10b]** SPEC 39.12 gives the list entry "click to open detail" and travel its own slot,
  which retires the shift-click-to-delete gesture Part I's entry carried. *Implemented default:*
  deleting moved onto the detail screen, where it is a labelled button behind a confirmation
  rather than an undocumented modifier on a button that does something else. `confirmDelete` in
  the parent menu lost its only caller in the same edit and was removed rather than left as
  dead code the compiler does not warn about. *Date:* 2026-08-09

- **[M10b]** SPEC 39.11's `/city outpost claim` requires a name and SPEC 39.12's slot 20 is a
  button, which has nowhere to type one. *Implemented default:* the button **infers** the
  outpost, and cannot be ambiguous: SPEC 39.6 keeps a city's outposts 24 chunks apart and caps
  each at four, so no chunk can border two of them. When no outpost will take the chunk, the
  button re-asks against the nearest one so the lore names the rule that actually failed rather
  than a generic refusal — which is SPEC 39.12's "disabled with a reason" read literally.
  *Date:* 2026-08-09

- **[M10b]** SPEC 39.12's detail screen lists "view defense units", and SPEC 39.8 caps an outpost
  at four of them. The roster is M12a to M12f, which PLAN orders after this milestone.
  *Implemented default:* the entry renders through the framework's refusal path saying the system
  is not on this server yet — the same choice M8 made for seven screens whose systems did not
  exist, and for the same reason: an entry that silently showed zero units would be
  indistinguishable from an outpost that has none. *Date:* 2026-08-09

- **[M10b]** **Two of this milestone's twelve tests were mutation-checked before being trusted**,
  and the reason is M6c: that milestone shipped a concurrency test that stayed green with the
  lock removed, because SQLite serialises writers and handed it the property for free. A test
  that passes on the first run is not evidence until it has been seen to fail. The diagram
  assertion was broken deliberately (ownership forced false) and went red; the breakdown
  assertion likewise. Worth recording as a practice rather than an incident — twelve green tests
  on a first run is exactly the shape that hid M6c's defect. *Date:* 2026-08-09

- **[M12a]** **I reported three tests as passing that had never executed, twice.** MockBukkit
  raises `UnimplementedOperationException` for API it does not implement, and JUnit records that
  as a **skip**, not a failure. My verification was a grep for `FAILED|BUILD`, and a skipped test
  produces neither — so a suite in which the milestone's central assertion never ran printed
  `BUILD SUCCESSFUL`. It was caught only by reading the skip counts in the result XML while
  looking at something else. *The lesson is not "also grep for SKIPPED".* This project has spent
  three milestones mutation-checking tests to prove they **can fail**; that is a different
  question from whether they **ran**, and the second is both cheaper to get wrong and invisible
  in the output I had been trusting. Test counts are now read from the XML rather than inferred
  from the console. *Date:* 2026-08-09

- **[M12a]** The call MockBukkit lacks is `setRemoveWhenFarAway`, which is exactly what SPEC 31
  case 106 requires — "they must not [count toward the mob cap]. Exclude from spawn calculations
  and set `setRemoveWhenFarAway(false)`" — and which `DefenseSpawner` therefore calls on every
  spawn. So the one line SPEC mandates is the one that makes the spawner untestable.
  *Implemented default:* a `Spawn` seam on `UnitMaterializer`, defaulting to the real spawner,
  which the tests replace with the same mob minus that call. It exists for this reason rather
  than for tidiness: without it SPEC 25.4's "a unit at 40% health that dematerializes returns at
  40% health" — the whole promise of the milestone — could not be asserted at all. **Case 106
  itself remains implemented and untested, and `DefenseSpawner` has never run under any test.
  M12b to M12f all build on it.** *Date:* 2026-08-09

- **[M12a]** SPEC 31 case 113's published ceiling of 60 **cannot be met by a radius rule**, and
  the developer's instruction was that it is binding. Forty players each standing inside their
  own twelve-unit garrison are within 48 blocks of four hundred units, and no per-player rule
  makes forty players produce fewer than forty times what is in range of them; sixty implies
  under two units per player. *Implemented default:* the cap is a **global fleet budget** —
  which is what "server-wide" says — and units compete for seats. War-zone units are seated
  first and never displaced, because a defender arriving to find their garrison missing while
  forty strangers stood in other cities is the worst failure this system could produce; it has
  its own test. **The cost is real and is not a tuning detail:** on a busy server a player can
  walk up to their own garrison and find part of it absent because the seats are taken
  elsewhere. `defense.materialization.max-materialized`, and 0 means no ceiling.
  *Date:* 2026-08-09

- **[M12a]** The superseded M12 was **worse than a coarse trigger**. It respawned units on
  `ChunkLoadEvent` and had **no despawn path at all** — nothing anywhere took a unit down — so
  every unit any player had ever walked past stayed loaded until the next restart. That is
  precisely the 2,400 permanently loaded entities SPEC 25.4 opens by refusing, and it had been
  shipped and passing its tests since M12. Recorded because the milestone's framing ("replace
  the trigger") understated what was actually wrong. *Date:* 2026-08-09

- **[M12a]** Null health reads as **full**, not zero. V22 adds the column to a table with rows
  already in it, so every existing unit has `health = NULL`; read as zero, the migration would
  kill every defense unit on the server the moment an operator upgraded. Likewise
  `markAllDormant` **clears** dormancy at startup rather than trusting what is there: dormant
  regeneration measures from that timestamp, so a server offline for a week would otherwise heal
  every damaged unit to full on boot — the healing SPEC 25.4 disables during a war, arriving for
  free just after one. *Date:* 2026-08-09

- **[M12b]** SPEC 30.1's second clause — "no unit-specific targeting logic anywhere else" — is a
  prohibition, and honouring it meant **deleting** rather than adding. `DefenseBehaviour`'s
  `towardsPlayer` and `towardsHostile` were the superseded SPEC 12.3 table, and leaving them
  beside `TargetingRule` would have been two authorities deciding what a unit attacks: the
  defect this project has been bitten by repeatedly, in the one place where the consequence is a
  city's guards killing its own members. *Implemented default:* both deleted, with zero
  references remaining, so the prohibition is enforced by the compiler rather than by a comment.
  `isEnemyOf` and `isSameSide` were **kept and promoted**, because they carry a nuance the new
  code would otherwise have lost — SPEC 17.4 case 41, that a player with no city is a bystander
  rather than an enemy, so somebody who wandered in during a war is not shot at.
  *Date:* 2026-08-09

- **[M12b]** Two `defense.yml` toggles went with the old table and are deliberately not replaced.
  `behaviour.attack-players-in-peacetime` has **no equivalent anywhere in SPEC 30.1's decision
  table**, and switched on it makes units attack visitors — which breaks SPEC 13.4's contest
  voting outright, since that requires players to travel to other cities to view and score
  entries. SPEC 25.2 Rule 2 names this consequence directly: "peacetime is safe… a defense
  system that attacks visitors makes contest voting impossible and kills build tourism. On a
  building-focused server that is fatal." `attack-hostile-mobs-in-peacetime` went for the
  narrower reason that SPEC 30.1 allows a PASSIVE unit to attack a hostile mob unconditionally.
  Same call the config sweep made about `bounties.claimable-only-during-war`: a switch that
  disables a rule SPEC calls deliberate is not something to ship. *Date:* 2026-08-09

- **[M12b]** **I shipped a twin config key about an hour after writing about that exact defect.**
  `targeting.default-range-blocks` was a second name for `behaviour.war-target-range`, which
  already existed, was already shipped and was already tested. It was caught while removing the
  old code — not by `ConfigKeyUsageTest`, which cannot see it, because both keys were read by
  something and neither was dead. Worth recording because the sweep that found three of these
  has a blind spot: it detects a key nothing reads, not two keys meaning one thing. The
  `noDeadTwins` half only catches the case where one of the pair is inert. *Date:* 2026-08-09

- **[M12b]** A nested test class named `Ordering` claimed to verify SPEC 30.1's positional order
  and **did not**. Mutation testing showed that moving the ownership check to after the state
  checks fails nothing — it still cancels before any allow, so the decision is identical — and
  that only deleting the check outright fails anything, which it does, seven times.
  *Implemented default:* renamed to `NeverAttackedInAnyState`, which is what it actually
  guarantees: ownership and alliance are always consulted before a unit is allowed to attack.
  Third instance this session of a test claiming more than it delivers, after M6c's concurrency
  test and M12a's skipped materialisation tests, so it is a habit to watch rather than three
  accidents. *Date:* 2026-08-09

- **[M12c]** **SPEC asks for a "city colour" and defines one nowhere.** SPEC 26.2 has units
  "glow in the city colour" and SPEC 27 dresses the roster in "dyed leather in the city's
  colour", but SPEC 3.2's `cities` table has no such column, `City` has no such field, and no
  section of SPEC anywhere says what a city's colour is or how one is chosen. The nearest thing
  is SPEC 8.10 slot 22's "city banner", which is not implemented and is not a chat colour.
  *Implemented default:* **an invention, and recorded as one.** `CityColour` derives one of
  fifteen `NamedTextColor` values from the city id by a stride coprime with the wheel, so
  consecutive ids -- the cities most likely to border each other -- never share a colour, and
  the answer depends on the id and on nothing else. Black is dropped: the only use is a glowing
  outline, and a black outline at night is not a colour. The alternative, letting a city choose,
  is a feature SPEC never asked for and would cost a column, a command, a GUI and a migration.
  A city that later wants to pick one can be given a column that falls back to this.
  *Date:* 2026-08-09

- **[M12c]** SPEC 3.9 defines `audit_log` as "**Admin actions only**, separate from ledger", and
  `AuditService`'s own javadoc restates it. SPEC 26.2 then requires player violations to be
  written there: "Violations are logged to `audit_log`, so an admin investigating a grief report
  can see the pattern." *Implemented default:* the later section wins, so they are written --
  actor is the trespasser, action is `TRESPASS_VIOLATION`, target is the city, metadata carries
  the chunk and the strike count. **Worth knowing before reading `/ca audit`:** those rows have
  a player as their actor and no admin anywhere in them, which is the first time that has been
  true of that table. *Date:* 2026-08-09

- **[M12c]** SPEC 26.2 says violations are logged and gives the reason -- so an admin can see the
  pattern -- but every blocked click is a violation, and a held left-click fires a break event
  every tick. One row per raw violation is an unbounded database write rate driven by a player
  holding a mouse button. *Implemented default:* one row per **debounced** violation, which
  after `trespass.violation-cooldown-ms` is one per deliberate act. Every counted violation and
  not only the ones that cross the threshold, because two strikes and a walk away is a pattern
  and never produces a warning. *Date:* 2026-08-09

- **[M12c]** **Violations were over-counted by construction, in the half that was already
  committed.** `reportViolation` sits inside `ProtectionGuard.decide`, which `allowsSilently`
  also calls, so: stepping on a pressure plate was a violation (the PHYSICAL branch is silent
  *by design* -- "a player walking past a door should not be nagged for something they did with
  their feet"), one bucket pour was two, and one `BlockMultiPlaceEvent` was one per replaced
  block. Three steps down a garden path crossed SPEC 26.2's threshold, and a visitor who was
  never even shown a refusal got a title telling them the city's defenses were activating.
  *Implemented default:* two fixes, at two different levels. `allowsSilently` no longer counts
  at all -- a refusal the player was never shown is not something they kept doing after being
  told, which is the whole structure of SPEC 26.2. And a debounce in `TrespassService`, not in
  the guard: the guard would then be a protection concern reading defense configuration, and
  any later violation source gets the debounce for nothing where it is. **The debounce interval
  is not from SPEC.** *Date:* 2026-08-09

- **[M12c]** **Two of SPEC 26.2's six violation sources could never fire.** "Damaging a defense
  unit": `EntityProtectionListener` returns early for `victim instanceof Enemy`, and SPEC 27's
  Watchman, City Guard, Elite Guard, Archer and Sharpshooter are all Zombies or Skeletons -- so
  hitting the five most common units never reached the guard at all. "Damaging a city member":
  PvP is decided by `PvpPolicy` in a branch that returns before any guard call, so it never
  produced a `NOT_A_MEMBER` refusal. *Implemented default:* both report explicitly, through
  `ProtectionGuard.reportDirectViolation`, which runs the ordinary `ENTITY_DAMAGE` check and
  throws the answer away -- so "is this person a non-member here" is still answered by one piece
  of code and SPEC 26.2's trusted-ally exemption is not remembered twice. Hitting a unit is
  **reported and not blocked**: SPEC 25.2's Rule 3 requires every unit to have a stated
  counterplay, and a guard that cannot be hit has none. The member case is reported only when
  the person hit is a member of the city whose land they are standing on, because SPEC says
  "damaging a city member" and a scuffle between two outsiders in somebody's streets is not
  that. *Date:* 2026-08-09

- **[M12c]** **SPEC 26.2 step 3's ten-second de-escalation shipped as a config key nothing
  called.** `trespass.de-escalation-seconds` was in `defense.yml` with SPEC's default and
  `TrespassService.deEscalationSeconds()` read it -- which is why `ConfigKeyUsageTest` passed --
  and no caller existed. `leftClaims` calmed instantly, so a raider could step over the border
  and be safe on the spot. Exactly the CONFIG.md class of defect, committed in the half that was
  already marked done, and the reason a getter is not evidence that a key is honoured.
  *Implemented default:* the listener schedules the calm and cancels it on re-entry;
  mutation-checked by making it instant and confirming the two named tests go red.
  *Date:* 2026-08-09

- **[M12c]** SPEC 23.6's toggle categories are a closed list and **none of them is about
  defense**. SPEC 26.2's warning exists so that "no player is ever killed without being told",
  which makes it a safety message and therefore one that must not be mutable -- and the only
  locked categories are `TREASURY_WITHDRAW`, `UPKEEP_CRITICAL` and `WAR`. *Implemented default:*
  `WAR` for the two trespasser-facing messages, on the ground that it is the only locked
  category whose subject is "somebody is about to attack you", and `MEMBERSHIP` for the
  city-facing notice, which is informational. The name is wrong and the property is right;
  adding a category SPEC lists exhaustively would have been inventing one. *Date:* 2026-08-09

- **[M12c]** SPEC 26.2 puts the glow in step 1 and says nothing about it in step 2, so read
  literally it is a warning-phase effect. *Implemented default:* the literal reading, and it is
  also the safe one. The warning always ends, because ending it is a scheduled task that always
  runs, so a glow tied to the warning always clears. A glow tied to the **alert** would have to
  be cleared when the alert expires -- and nothing sweeps expiries, `UnitStates` resolves them on
  read, so the first time nobody asked a city's guards would glow until the next restart. The
  practical argument for the other reading (a unit about to attack you should be visible) is
  real and is recorded here rather than acted on. *Date:* 2026-08-09

- **[M12c]** The glow must **not** be produced by alerting units early. That is the obvious
  implementation and it would break the one guarantee the warning phase exists to give:
  `TargetingRule` permits a unit to attack a player only in ALERTED or HOSTILE, so the sole
  reason nothing attacks during SPEC 26.2's five seconds is that `UnitStates` is untouched until
  the warning ends. Reaching for an alert to get a visual effect would let guards kill somebody
  during the window that exists so they do not. Recorded because the shortcut looks harmless.
  *Date:* 2026-08-09

- **[M12c]** SPEC 30.4 spells its key `trespass.city_notice`, with an underscore, and
  `LangKeyUsageTest`'s key-shaped literal pattern is `[a-z][a-z0-9-]*(\.[a-z0-9-]+)+` -- which
  underscores do not match. Shipped as SPEC writes it, the Java literal would be invisible to
  the scanner and the `en.yml` key would be reported as orphaned. *Implemented default:*
  `trespass.city-notice`. The same trap waits for `warden.defeated_peacetime` in M12f.
  *Date:* 2026-08-09

- **[M12c]** **`<city>` is both a SPEC 23.2 palette colour and the placeholder every existing
  message uses for a city's name**, and the two collide the moment a message goes through
  `Messenger`: `render` puts the palette resolver first, and Adventure's sequential resolver
  returns the first match, so the colour wins and the name is never substituted. Latent until
  now, because M7a shipped the palette and no catalogue, and every existing `<city>` message is
  sent through `lang.send` with no palette. *Implemented default:* these five messages name the
  city with `<cityname>`, with a test asserting it in both languages. Not fixed globally --
  reversing the resolver order in `Messenger.render` is the right fix and it belongs to M23a,
  which is the milestone that converts every message and would otherwise hit this on the first
  one. **This needs a decision before M23a.** *Date:* 2026-08-09

- **[M12c]** `Messenger.send(Channel.TITLE)` renders one key and an empty subtitle, and SPEC
  30.4's trespass warning is a title *and* a subtitle. Calling `audience.showTitle` directly is
  the obvious fix and walks straight past SPEC 23.4's four-per-hour cap and the toggle, which
  are the only reasons the class exists. *Implemented default:* `Messenger.sendTitle`, which
  goes through the same cap. Unlike `send`, a capped title is **dropped rather than downgraded
  to chat**: every caller pairs it with a chat line carrying the same fact, so downgrading would
  print it twice. *Date:* 2026-08-09

- **[M12c]** SPEC 26.3 suspends the trespass response during an ACTIVE war and nothing
  implemented it. *Implemented default:* a `TrespassService.Wars` seam in the shape
  `UnitTargeting.useWars` already established, answering false until the plugin wires it from
  the war registry. Without it a besieged city would warn its attackers before its guards
  engaged them, which is the opposite of a siege. *Date:* 2026-08-09

- **[M12c]** SPEC 30.2 case 94 -- "a trespasser who logs out during ALERTED keeps the alert for
  its remaining duration, and logging back in inside the claims resumes it" -- is the most
  fragile thing in this milestone, and the reason is that the state lives in two places. The
  response survives a logout on its own because it is a clock. The per-unit half does not: case
  95 lets the units dematerialise while the trespasser is away, and `UnitStates.materialized`
  brings every one of them back PASSIVE. So without `TrespassService.reapply` the response would
  say ALERTED, every unit would say PASSIVE, and not one guard would move. Two consequences
  recorded rather than discovered later: the listener must **not** call `trespass.forget` on
  quit, which every other listener in the package does for its own state, and a logout is not
  "leaving the claims", so routing quit into `leftClaims` breaks the same case from the other
  side. Mutation-checked by adding the `forget` call and confirming two named tests go red.
  A re-applied alert also ends when the response does rather than at its full length, or a unit
  standing up would buy the trespasser another forty-five seconds. *Date:* 2026-08-09

- **[M12c]** SPEC 23.5's audience code CITY means every online member with no radius, so
  `trespass.city-notice` reaches a member on the far side of a two-hundred-chunk city.
  *Implemented default:* left as SPEC words it. Reusing `trespass.alert-radius-chunks` would be
  a second meaning for one key, which is the twin the config sweep found three of. It fires
  **once, at the warning**, rather than on every violation or again on the alert: the warning is
  the moment the city can still do something about it, and a line per blocked click is the flood
  SPEC 23.1 spends a section avoiding. *Date:* 2026-08-09

- **[M12c]** SPEC 30.4's own chat template hardcodes "within **5 seconds**", which contradicts
  CLAUDE.md's rule on hardcoded numbers and SPEC 26.2's own `trespass.warning-seconds`.
  *Implemented default:* the configured value is rendered. *Date:* 2026-08-09

- **[M12c]** `LangKeyUsageTest`'s "does it exist" half scans `lang.send`, `lang.sendRaw`,
  `lang.get` and `Result.failure`, and nothing else -- so a key reached only through the
  messenger is checked in one direction only: an orphan fails the build, but a **typo ships as
  "Missing message" to a player**. All five of this milestone's keys are messenger-only.
  *Implemented default:* a targeted test asserting each key exists and is non-blank in both
  language files. Widening the scanner to `messenger.send` is awkward -- the key is the fourth
  or fifth argument of a multi-line call -- and belongs with M23a, which will have a hundred
  such keys rather than five. *Date:* 2026-08-09

- **[M12d]** **SPEC 27 requires a city colour and SPEC defines one nowhere.** SPEC 27.3, 27.5 and
  27.6 all dress a unit in "dyed leather in the city's colour" and SPEC 27.4 gives the Warhound a
  dyed collar, but SPEC 3.2's `cities` table has a name, a tag, a display name and a motd, and
  nothing that is a colour. *Implemented default:* `CityColour`, which M12c already added for SPEC
  26.2's glow and which derives one from the city id, extended into leather and a collar dye. No
  column, no migration, no command. **A developer decision is needed** on whether a city should be
  able to *choose* its colour, which needs a SPEC 3.2 column; until then the derivation is the
  conservative option because it adds nothing a player has to learn. *Date:* 2026-08-09

- **[M12d]** SPEC 27.1 gives the Watchtower Keeper's health as **"n/a"** and SPEC 27.3 as
  "Invulnerable outside war, 40 during war", while SPEC 30.3 ships `war-health: 40` and no
  `health`. Two names for one number, one of which does not exist. *Implemented default:* one
  `health: 40` and a separate `invulnerable-outside-war: true` flag, which is the shape that does
  not need `DefenseUnitType`'s "health must be positive" precondition relaxed and does not ship a
  twin. The war half is a seam on `DefenseSpawner` in the shape the package already uses, and it
  **fails towards invulnerable**: a wiring mistake leaves a 9,000 C asset indestructible rather
  than leaving it destructible during peace. *Date:* 2026-08-09

- **[M12d]** **SPEC 30.2 cases 107 and 112 together destroy SPEC 27.2's counterplay if read
  literally.** Case 107 cancels a snow golem's "water and melting damage"; case 112 saves a unit
  from terrain; and SPEC 27.2's stated counterplay for the Frost Sentry is, in full, "Melts in lava
  or near fire". SPEC 25.2 Rule 3 makes counterplay a shipping gate, so the two are separated on
  SPEC's own two axes: **case 107 is about weather** (MELTING and DROWNING cancelled, fire and lava
  not), and **case 112 is about pathfinding** -- its premise is a unit that "pathfinds into lava or
  off a cliff", so the save is offered only to a unit that can walk. The Frost Sentry and the
  Keeper are both static at zero speed, so neither is ever saved. That is the only reading under
  which both SPEC sentences are true at once. Both halves are mutation-checked. *Date:* 2026-08-09

- **[M12d]** SPEC 27.6 gives the City Guard "Armor 8 points, plus 2 toughness" **and** full dyed
  leather, and worn armour contributes through attribute modifiers -- so a base of 8 plus a leather
  set is 15, and a 90 HP unit at 15 armour is most of the way to the unbeatable garrison SPEC 25.2
  Rule 1 forbids. *Implemented default:* SPEC 25.3 files dyed leather under **appearance**, beside
  custom names and team colours, so the leather is made cosmetic -- an explicit `ARMOR` modifier of
  zero replaces the item's own -- and the config number is the total. The same argument settles the
  Archer: SPEC 27.5 gives it "7 damage per arrow" and a "Power III bow", which cannot both be the
  final figure, so the arrow's damage is written to 7 at launch (vanilla bakes Power in at that
  moment) and Power III is the glint. *Date:* 2026-08-09

- **[M12d]** **Nothing in the plugin acquires a target, and until M12d nothing ever had.** SPEC
  30.1's handler only *vetoes* -- it answers "may this unit target that candidate" -- and a veto is
  meaningless until something proposes a candidate. A City Guard that becomes ALERTED stands still,
  because a zombie's own goals never picked that player out; a Warhound is worse, because SPEC 27.4
  makes it a wolf and `DefenseSpawner` tames it with no owner, and a tamed ownerless wolf initiates
  nothing at all. So the entire roster was inert while every test of the state machine passed.
  *Implemented default:* `DefenseTick.acquire`, a ten-tick pass that asks the one handler and then
  proposes. `UnitAcquisition` is the pure selection half and carries SPEC 27.4's lowest-health
  priority -- **selection, never permission**, which is the distinction that keeps SPEC 30.1's "no
  unit-specific targeting logic anywhere else" true. *Date:* 2026-08-09

- **[M12d]** SPEC 27.6's alert network fires "regardless of trespass state", which contradicts SPEC
  26.2's promise that "no player is ever killed without being told, in plain language, that they
  are about to be" -- 26.2 requires three strikes and a five-second warning first. *Implemented
  default:* both. The network turns the guards hostile immediately, as the later and more specific
  rule says, **and** the attacker is told at that moment, through the same sink and the same words
  the trespass response already uses. Skipping the telling would break the promise; skipping the
  network would break SPEC 27.6's stated behaviour and its stated weakness. *Date:* 2026-08-09

- **[M12d]** SPEC 27.5 calls the Archer's twenty blocks "hard capped" and a vanilla skeleton's
  follow range is **sixteen**, so the cap sits above the unit's natural reach, is never met, and a
  test asserting it proves nothing. *Implemented default:* every unit's `range` is written onto
  `Attribute.FOLLOW_RANGE` at spawn, so the cap binds and the Warhound's 24-block chase and the
  Keeper's 32-block detection are real rather than aspirational. Worth recording because the
  implementation looks complete without it and the test passes either way. *Date:* 2026-08-09

- **[M12d]** Several SPEC 27 numbers have no home in SPEC 30.3's config block and are shipped
  anyway, because the hard rule forbids hardcoding them: the Frost Sentry's 16-block range and the
  Keeper's 32 (SPEC 27.2 and 27.3 state them in prose), the Archer's five-block melee radius (SPEC
  27.5, prose), case 112's 20% and one hour (stated in the case itself), and the Colossus's slam
  knockback, for which **SPEC gives no magnitude anywhere** -- 1.2 is this implementation's.
  Conversely three SPEC 30.3 keys are deliberately **not** shipped: `points` (SPEC 25.5's budget is
  M12e and a key nothing reads is the defect the config sweep found nineteen of),
  `placement.leash-blocks` and `placement.war-purchase-cost-multiplier` (twins of the existing
  `behaviour.leash-distance-blocks` and `placement.wartime-purchase-multiplier`). SPEC 30.3 also
  writes unit keys in snake_case and potion strengths as **levels** where Bukkit takes an
  amplifier; this project is kebab-case throughout and the level-to-amplifier conversion happens
  once, in `UnitAbilities`. *Date:* 2026-08-09

- **[M12d]** **Existing rows of retired unit types have no stated fate.** A server that ran the
  superseded M12 has `defense_units` rows of type `watchman`, `elite-guard`, `sharpshooter`,
  `siege-golem` and `sentry`. After the catalogue change they load into the registry, never
  materialise (the catalogue has no entry, so `UnitMaterializer` returns false), still appear in
  `/city defense list` as a raw key, and **still charge upkeep** from the amount stored on the row.
  SPEC 30.2 case 100 covers a Fortification downgrade and says nothing about a retired type.
  *Implemented default:* left alone and recorded, because the three options -- remap, refund and
  remove, or leave and log -- are a policy decision about other people's money rather than a
  reading of SPEC. **This needs a developer decision; the test server has such rows.**
  *Date:* 2026-08-09

- **[M12d]** Two rules were added that SPEC does not list, both in the class case 105 belongs to. A
  Watchtower Keeper is an armour stand wearing dyed leather and holding a spyglass, and any player
  may take all five items by hand through `PlayerArmorStandManipulateEvent` -- which is case 105's
  loot pinata arriving from a direction case 105 does not mention, and drop chances do nothing
  about it; a snow golem can likewise be sheared out of existence with one click. Both are refused
  alongside case 110's name tag and case 111's lead, as one "hands off" rule. Separately, a
  garrison of Frost Sentries lays a snow layer wherever it stands, and inside a war zone every one
  of those is a block change M17 logs and M18 must replay, so `EntityBlockFormEvent` is cancelled
  for units. *Date:* 2026-08-09

- **[M12d]** SPEC 30.2 case 106 says units "must not count toward the vanilla mob cap". Only half
  is deliverable and that half was already true: `setRemoveWhenFarAway(false)` and
  `setPersistent(true)` are called on every spawn, and Paper exposes no supported way to exclude a
  specific entity from spawn calculations without NMS, which SPEC 2.1 forbids unless unavoidable.
  Unchanged from the superseded M12's finding, restated because case 106 is on this milestone's
  list and a reader will otherwise look for the other half. *Date:* 2026-08-09

- **[M12c to M12f]** These four were built by an orchestrated multi-agent run: four survey agents
  reading SPEC in parallel, four build agents in strict sequence (they share `DefenseCatalogue`,
  `DefenseSpawner` and `defense.yml`, and SPEC 31 insists the Warden be built on a targeting system
  "already proven by six simpler units"), then three adversarial reviewers. **The review found a
  critical defect none of the four builders did, and it is the kind this project keeps producing:
  a rule assigned to a milestone that had already shipped.** Nothing anywhere called
  `UnitStates.hostile()`. During an ACTIVE war every unit sat at PASSIVE, `TargetingRule` cancelled
  every enemy with `STATE_PASSIVE`, and `TrespassService` refuses violations during a war — so a
  city's whole garrison was **inert in the one situation SPEC 27 built it for**. `UnitStates`' own
  javadoc said "M19" writes HOSTILE, and M19 closed before this defense system existed.
  `UnitMaterializer.useWars` had never been wired either, so SPEC 25.4's war materialisation
  trigger was dead alongside it. *Implemented default:* HOSTILE on materialising into a running
  war, plus `reconcileWarStates` on every sweep so a war starting around a standing garrison arms
  it and SPEC 30.2 case 96 disarms it — ALERTED left alone, or every sweep would drop a trespass
  alert. Five tests; reverting the fix fails all five. **ACTIVE only, never PREP**: SPEC 26.3 says
  "During PREP, units remain PASSIVE. Prep is a building phase, not a fighting phase", and
  `isEngaged()` covers both, so it would have armed a garrison two days before anyone was allowed
  to fight it. *Date:* 2026-08-09

- **[M12c to M12f]** What is built and what is **verified** differ here more than in any previous
  milestone, and the difference is worth stating rather than leaving to be discovered:
  **`DefenseSpawner` has still never executed under any test**, and M12d added SCALE, four
  attributes, dyed-leather colouring, wolf collar colours and five behaviour flags to it. The cause
  is unchanged from M12a: MockBukkit does not implement `setRemoveWhenFarAway`, which SPEC 31 case
  106 requires and the spawner calls on every spawn, and an unimplemented call **skips** rather
  than fails. `UnitShaping` was written as a pure record so the intended shaping is asserted
  somewhere — that is a mitigation, not a fix, because nothing asserts it is *applied*.
  `DefenseAbilityListener` and `DefenseTick` have no tests at all. The glow and the roar are
  review-only for the same reason. **All of this needs the SPEC 18.3-style live-server pass, and
  M20a's balance pass cannot substitute for it.** *Date:* 2026-08-09

- **[M12f]** SPEC 28.8 says to "cancel `EntityDamageEvent` with `DamageCause.SONIC_BOOM`
  unconditionally", and read literally that is server-wide. *Implemented default:* scoped to this
  plugin's Wardens. A wild Warden in an ancient city has nothing to do with a city's defenses, and
  a player who beat one and took no damage would be looking at a bug rather than a feature. The
  same applies to `WardenAngerChangeEvent` and to the Darkness aura; each has a test asserting a
  wild Warden is left alone. Recorded because the literal reading is defensible and this is a
  narrowing. *Date:* 2026-08-09

- **[M12c]** SPEC 26.2 says units "glow in the city colour" and **no city colour exists anywhere
  in SPEC or in this codebase**. *Implemented default:* derived stably from the city id and applied
  through a scoreboard team, which is the mechanism SPEC 25.3 lists. Known limitation, documented
  in `UnitGlow`: a team lives on one scoreboard, and `WarScoreboard` hands a player their own board
  on `/war scoreboard`. The team is registered on the main board and on every board reachable
  through an online player, which covers the case only if the viewer is online when the glow
  starts; otherwise they see a white outline. *Date:* 2026-08-09

- **[M12c]** An agent caught an error of mine worth recording: I briefed the run that
  `TrespassService`, its test and the `ProtectionGuard` seam were committed. They were not — commit
  `acc37cf` contains only `TrespassTracker` and `TrespassResponse`, because the service was written
  after it. The agent checked `git show` rather than believing the brief, and said so. Worth noting
  as evidence for what adversarial verification is actually for: not only reviewing code, but
  disbelieving the premises it was handed. *Date:* 2026-08-09

- **[M19b]** SPEC 33.8's tag **refreshes, it does not stack**, and stacking is the natural
  implementation. SPEC argues the case itself: twenty arrows from one engagement would be a
  ten-minute lockout, and "a harasser who lands one hit every four minutes can keep a target
  tagged indefinitely". Both parties are tagged rather than only the victim, or an archer could
  fire and teleport out — which is the escape the tag exists to close. A war tag never shortens
  into a peacetime one while running: SPEC 33.9 case 115 extends the other direction, and the
  reverse would be a way to cut a two-minute lockout to thirty seconds by having an ally poke you.
  *Date:* 2026-08-10

- **[M19b]** SPEC 33.4's war check has a fourth condition that is a **deliberate narrowing rather
  than a reading**: a neutral city's claims are never a battleground, so two enemies meeting
  inside an uninvolved city cannot fight there. SPEC 33.4 states the reason — "any city adjacent
  to a war becomes collateral, and the anti-toxicity pillar does not survive that." A player with
  no city is a bystander either way, per SPEC 17.4 case 41. *Date:* 2026-08-10

- **[M19b]** `DeathPolicy` asserts a property about the whole plugin rather than about itself.
  Items are lost to another player **only inside a war**; read together with SPEC 11.7's rule that
  hand-looted container items are never restored by the rollback, that makes war the **only**
  mechanism in the entire plugin by which a player permanently loses possessions to another
  player. Everything else the plugin takes is money, ranking or reputation, and that is what makes
  SPEC 1.2's "destruction is never permanent" true everywhere else. SPEC 33.9 case 127 has its own
  test because it is the rule a city will dislike: a defense unit is a mob, not a war participant,
  so killing a raider with a 55,000-coin Colossus does not hand the city their inventory.
  *Date:* 2026-08-10

- **[M19b]** The action-bar countdown uses `ToggleCategory.WAR`, which SPEC 23.6 locks on, rather
  than `ACTIONBAR`, which a player may mute. SPEC 33.8 requires the countdown for a stated reason:
  "A player must never be surprised that a teleport was refused." A mutable countdown turns a
  working refusal into an unexplained failure, which is what players report as a bug.
  *Date:* 2026-08-10

- **[M19b]** **I shipped a twin lang key and the orphan sweep caught it**, not me:
  `combat.tagged-teleport` when `TeleportService` already refuses with `travel.combat-tagged`.
  Second twin in two days, both within hours of writing about the pattern — the config one at
  M12b, this one in lang. Worth recording that `LangKeyUsageTest`'s orphan half catches the lang
  case automatically (the unused one is orphaned), where `ConfigKeyUsageTest` **cannot** catch the
  config case, because both keys are read and neither is dead. *Date:* 2026-08-10

- **[M19b]** **Unverified surface, stated rather than left to be found.** `CombatTagListener` has
  no tests: damage tagging, the countdown timer and the combat-logout kill are reviewed and
  compiled only. The pure halves — `CombatTag` and `DeathPolicy`, 40 tests — are mutation-checked,
  and the two blocks that had seams (teleports, vault) are wired through code that is tested. What
  is missing is the event plumbing, which needs either MockBukkit coverage of
  `EntityDamageByEntityEvent` or the live pass. **Peacetime PvP also remains disabled**, so SPEC
  33.6's peacetime keepInventory row is built, configured and unreachable until a developer flips
  `pvp.peacetime`. *Date:* 2026-08-10

- **[M19b]** **I closed this milestone having built three of the four parts I had listed**, and
  the fourth turned out to be unbuildable, which is not the same as it being done. Recording both
  halves because only the second is SPEC's fault.
  SPEC 38's M19b row asks for an "unopposed score multiplier and walkover", and
  `war.unopposed-score-multiplier: 0.3` with `war.walkover-absence-percent: 70` appear in exactly
  one place in all 41 sections — the config block at SPEC 37. **No prose anywhere describes what
  either does**: not what an unopposed score is, not what counts as an absence, not what the 70%
  is a percentage of. Implementing them means inventing the behaviour, which CLAUDE.md forbids.
  *Implemented default:* not built, and the keys are **not shipped** — a key with no code behind
  it is the defect the config sweep found nineteen of. The one walkover rule SPEC does define is
  SPEC 21.4 F4, "a war only counts toward the leaderboard if the losing side scored at least 25%
  of the winner's score", and **M9a already built it** as
  `anti-abuse.war-leaderboard-min-loser-score-percent`, filtered in `WarDao.findRecords`. So the
  fourth part is either already delivered under another milestone or undefined.
  **This needs a developer decision** if the two keys were meant to do something else.
  *Date:* 2026-08-10

- **[M19b]** SPEC 38's M19b row cites section numbers that are **stale by roughly two throughout**:
  it gives "(33.2)" for global war PvP where 33.2 is Friendly fire, "(33.3)" for scoped
  keepInventory where 33.3 is Peacetime, "(33.4)" for combat tagging where 33.4 is War, and
  "(33.5)" for the unopposed multiplier where 33.5 is Resource worlds. The real sections are 33.4,
  33.6 and 33.8, which is what this milestone was built against. Worth recording because the row
  reads as authoritative and following its references leads to the wrong text every time — the
  same hazard as SPEC 37 shipping a `border:` block that SPEC 32.3 rejects, which M3a found.
  *Date:* 2026-08-10

- **[M19a]** SPEC 29.2's formula and SPEC 29.2's own table disagree. The formula is
  `round(defender_defense_capacity * 0.70)`; at Fortification 5 that is `round(225 * 0.70)`, and
  225 x 0.70 is exactly 157.5, which rounds to **158** under any half-up convention. **The
  published table says 157.** *Implemented default:* truncation, settled against the published
  figures rather than by argument, the same way SPEC 39.3's ambiguous `n` was settled against SPEC
  39.4's tables. Truncation matches all three rows SPEC prints (70, 105, 157); half-up matches two
  of three. It is also the conservative direction, since an attacker never gets more siege than the
  exact share. `SiegeCapacityTest` asserts the three published rows rather than the formula, because
  a ratio that is nearly right gives a curve of the right shape and the wrong numbers.
  *Date:* 2026-08-10

- **[M19a]** SPEC 29.5 says a siege camp "can be rebuilt once per war at half cost" and **never
  says what the whole cost is**. SPEC 30.3 ships `camp-rebuild-cost-percent: 50` and no camp price
  anywhere. A percentage needs a figure to be a percentage of. *Implemented default:*
  `siege.camp-cost`, **this implementation's number** (20,000 C), stated as such in `defense.yml`.
  The alternative was a free camp, under which SPEC's own `camp-rebuild-cost-percent` would be
  inert -- half of nothing is nothing -- and that is precisely the class of dead key the config
  sweep found nineteen of. 20,000 sits between the Siege Archer's 15,000 and the Siege Beast's
  40,000, so a camp is a real commitment without being most of a Fortification-0 attacker's
  70-point budget. *Date:* 2026-08-10

- **[M19a]** SPEC 29.5 gives the camp "200 HP as a block-entity", and **Bukkit has no such thing**:
  a block is not damageable and carries no health, and the only entity that could hold one would be
  a mob standing where the banner is. *Implemented default:* the banner in the world is a
  **marker** and the camp is its row. Two consequences worth knowing, both deliberate. Mining the
  marker is refused outright, because a 200 HP objective a diamond pickaxe removes in one second is
  not an objective -- SPEC 29.5 calls it "a real secondary objective". And hitting it is what does
  damage, at `siege.camp-damage-per-hit` (5) over `siege.camp-hit-cooldown-ms` (400), **neither of
  which is a SPEC figure**; together they make roughly twenty seconds of committed hitting. Only the
  defending side's hits count: an attacker striking their own camp, or a bystander wandering past,
  changes nothing. *Date:* 2026-08-10

- **[M19a]** SPEC 29 describes camps and units in full and **defines no command and no GUI for
  either**. SPEC 9.3's `/war` tree has no siege entry, SPEC 8.8's Wars menu has no siege slot, and
  SPEC 8.9's Defense menu is the defender's. *Implemented default:* `/war siege` with `camp`, `buy`,
  `list` and a bare status -- the minimum that makes SPEC 29 reachable. Same reasoning as
  `/ca warp set` at M3b (SPEC defines `/warp` and no way to create one) and `/toggle` at M7a: a
  system with no way to drive it is inert configuration wearing a different hat. Recorded as beyond
  SPEC rather than slipped in. *Date:* 2026-08-10

- **[M19a]** SPEC 30.1 forbids "unit-specific targeting logic anywhere else", and SPEC 29.4 needs
  exactly one exception to SPEC 26.4's "units never fight units" -- the Breacher. Those pull against
  each other, and the obvious implementation is the wrong one: writing the exception beside the code
  that spawns Breachers would be a second place deciding what may be attacked, free to drift.
  *Implemented default:* `TargetingRule.breacherException`, so the prohibition and its one hole are
  a single readable statement, plus one extra field on `TargetingRule.Unit`. Siege units reach the
  same rule through their own resolver, `SiegeTargeting`, which decides nothing -- **one rule, two
  resolvers**, which is what SPEC 30.1 asks for; a siege unit is not in `defense_units` and owns no
  ground, so one resolver could not serve both. Four of the six carve-out tests assert what the
  exception does **not** reach, including **its own side's garrison**: SPEC 11.4 puts both cities'
  claims in the war zone, so an attacker's own guards are standing in it, and a Breacher that ate
  them would be a war-winning own goal. *Date:* 2026-08-10

- **[M19a]** SPEC 3 defines no table for siege units and SPEC 29 asks for none, but two rules make
  one unavoidable, so V25 adds `siege_units`. **The budget:** the entities carry SPEC 12.5's
  persistence flags, so they survive a restart -- held in memory alone, the tally would reset and
  hand an attacker a fresh 70 points while their existing army was still standing. **The despawn:**
  SPEC 29.4 removes every unit at war end and SPEC 29.5 removes a city's units when its camp falls;
  both need a list, and scanning the world for tagged mobs finds only the ones in loaded chunks.
  Deliberately **not** modelled on `defense_units`: a siege unit has no upkeep, no materialisation,
  no city cap and no life beyond its war, so sharing that table would mean an exception in every
  reader. A dead unit's row is **kept** and still counts against the budget, because SPEC 29.4
  refunds nothing -- summing only the living would turn SPEC 29.2's cap into a rate limit.
  *Date:* 2026-08-10

- **[M19a]** SPEC 29.5's camp is "visible on `/city map` to **both** sides, deliberately", and the
  tile has to sit **above** every ownership tile rather than beside them. A camp planted in the
  attacker's own claims -- which SPEC 29.5 permits -- would otherwise render as their territory and
  be invisible to the people it is aimed at, which is the exact failure SPEC's own sentence names:
  "a siege the defender cannot see is not a siege, it is an ambush." *Date:* 2026-08-10

- **[M19a]** SPEC 29.5 says "Attackers place a Siege Camp banner block" and never says whether a
  defender may. *Implemented default:* attacking side only, which includes allies who joined that
  side per SPEC 30.2 case 104. Reading it otherwise would give the defender a second garrison budget
  on top of SPEC 25.5's, which SPEC 29.2 never sized and which the anti-fortress ratio would then be
  measuring against itself. *Date:* 2026-08-10

- **[M19a]** SPEC 29.5 does not say whether a camp goes down whole or a chunk at a time, nor what
  happens to a camp in a war that resolves normally. *Implemented default:* a destroyed camp's row
  is **kept** with `destroyed_at` stamped, because it is the only record that the one rebuild SPEC
  allows has been spent -- deleting on destruction would hand every attacker unlimited rebuilds. At
  war end both camps and units are **deleted**, because a siege has no life beyond its war and no
  later question the rows could answer. The `markDestroyed` update is guarded on
  `destroyed_at IS NULL`, so two players landing the killing blow in one tick cannot both score SPEC
  29.5's 40 points; the in-memory half of that guarantee is in `SiegeCamp.damage` and is
  mutation-checked. *Date:* 2026-08-10

- **[M19a]** **Unverified surface, stated rather than left to be found.** `SiegeListener`,
  `SiegeSpawner` and `SiegeTick` have no tests -- camp damage, the Breacher's damage rewrite, the
  no-drops rule on a siege death, the Banner Bearer's aura and the spawn shaping are reviewed and
  compiled only. This is the same limitation M12d recorded for `DefenseSpawner`, and the same cause:
  MockBukkit reports the entity API these classes need as a **skip** rather than a failure. What is
  asserted is the arithmetic and the rules -- `SiegeCapacity`, `SiegeCatalogue`, `SiegePlacement`,
  `SiegeCamp` and the carve-out, two of them mutation-checked. **All of it needs the live pass, and
  M20a's balance sweep cannot substitute for it.** *Date:* 2026-08-10

- **[M19c]** SPEC 32.8 and SPEC 37 give different backup schemes, and for once the contradiction
  settles itself: SPEC 32.8 **names the design it replaces**. SPEC 37's `backup:` block ships
  `world-daily-hour: 5` and `world-keep-count: 7`; SPEC 32.8's opening sentence is "Part IV's
  original 'daily full world backup, keep 7' stops being viable within months once players scatter
  across hundreds of thousands of blocks", and it then tabulates three tiers. *Implemented default:*
  SPEC 32.8's three tiers -- weekly full keep 2, daily incremental keep 14 days, per-war snapshot.
  Worth recording because it is the **fourth** time SPEC 37 has been the stale side of a
  contradiction, after M3a's `border:` block, M4a's peacetime PvP and M4a's `combat-tag.seconds: 15`
  against SPEC 33.8's argued 30/120. This one needed no judgement call at all. *Date:* 2026-08-11

- **[M19c]** SPEC 32.8 says nothing about **saving the worlds before copying them**, and without it
  the whole feature is quietly wrong. A region file on disk holds what was last flushed to it, so a
  copy taken mid-session captures whatever state the chunks were in at the last autosave -- for a
  city somebody has been building in all evening, that is not the state the war starts from, and the
  error is invisible until an admin restores a snapshot and gets a building half-built.
  *Implemented default:* every tier calls `World.save()` on the server thread before handing the
  copy to an async task. That is a stall proportional to unsaved chunks -- once a week, once a day,
  and once per war -- and it is the price of the snapshot meaning what it says. *Date:* 2026-08-11

- **[M19c]** SPEC 32.8 says to "Refuse to start a war if free disk is under
  `world.backup.min-free-gb`" and does not say which moment "start" is. *Implemented default:*
  **declaration**, not the PREP-to-ACTIVE transition where the snapshot is actually taken. By that
  transition both wagers are escrowed (SPEC 11.3), the defender has spent 48 hours fortifying, and
  refusing achieves nothing but a war that cannot proceed and cannot be unwound. A declaration
  blocked at the door has cost nobody anything. The guard also **passes when no snapshot would be
  taken** -- backups disabled, or `war-zone-snapshot: false` -- because a rule that blocked wars to
  protect a copy nobody is making is a rule with no purpose behind it. *Date:* 2026-08-11

- **[M19c]** The unit of backup is the **region file**, and that has a consequence SPEC does not
  spell out. Minecraft stores 32x32 chunks in one `r.X.Z.mca` and there is no way to copy part of
  one, so a snapshot always covers **more** ground than the war zone, and restoring one rewinds
  every chunk in every region the fighting touched -- including neighbours who shared a file, and
  including anything built there since the war. *Implemented default:* it is a rewind, not an undo,
  and the confirmation message says exactly that rather than only asking twice. SPEC 32.8's
  "requires typing the war id twice" is the mechanism; the reason had to be written. *Date:* 2026-08-11

- **[M19c]** SPEC 32.8's reporting asks for "projected growth" and nothing records yesterday's world
  size. *Implemented default:* the newest incremental backup's size annualised, which is a
  first-order estimate and is labelled as one in the message. With no incremental yet it reports
  **zero rather than a guess**. Recording a size history would mean a table for a number nobody acts
  on hourly. *Date:* 2026-08-11

- **[M19c]** SPEC 32.8's snapshot retention is "until the war reaches RESOLVED plus 7 days", and the
  obvious column to measure from is the wrong one. *Implemented default:* `war_ends_at`, not
  `rollback_completed_at`. SPEC 11.8.5 allows a rollback to fail outright and leave that column
  null, and a snapshot pruned on a column that never fills would be kept forever -- for exactly the
  wars whose snapshot matters most, where the rollback went wrong. *Date:* 2026-08-11

- **[M19c]** The world backups go in their own `world-backups/` folder rather than joining the
  database backups in `backups/`. Two different things -- one is the plugin's data, the other is
  everybody's builds -- and an operator clearing a folder to reclaim disk should not have to work
  out which they are deleting. *Date:* 2026-08-11

- **[M19c]** **Unverified: nothing has ever been restored on a live server.** The tests drive fake
  `.mca` files in a temp directory, which is the correct scope for the rules they check -- which
  files are copied, which are pruned, whether a war zone collapses to a handful of regions -- and
  proves nothing about Minecraft reloading a region file swapped underneath it. SPEC 32.8's restore
  says the affected chunks "must be reloaded, which usually means a restart", and the message says
  so, but that has not been demonstrated. **This belongs on the launch checklist beside SPEC 18.3.**
  *Date:* 2026-08-11
