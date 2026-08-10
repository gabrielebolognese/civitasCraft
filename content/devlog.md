# Devlog

## 2026-08-10

Six commits and a working tree: 37 files +1,105/−33 committed, 17 more +425/−10 uncommitted at the
time of writing. Two arcs. SPEC 33's war PvP and death, filling the seam M4a left empty in April;
then SPEC 29's siege, which is the attacker's half of a war and the reason a fortified city is
expensive rather than unattackable. The first ended with a milestone I had to reopen after closing
— I shipped three of the four parts I had listed and marked the row DONE — and the fourth turned
out to be unbuildable, which is a different problem from being unfinished. The second is the first
milestone where SPEC's own published table contradicts SPEC's own formula, and the table won.

### War PvP, death and the combat tag

- The combat tag: 30 seconds in peacetime, 120 in a war, blocking teleports, vault access and safe
  logout. Walking, boats, pearls and portals stay open.
  - Number: 11 tests, mutation-checked.
  - Hard part: it must **refresh, not stack**, and stacking is what you write without thinking.
    SPEC 33.8 argues the case itself — twenty arrows from one engagement would be a ten-minute
    lockout, and a harasser landing one hit every four minutes keeps a target tagged forever.
    Making it stack fails exactly the two named tests. A second, subtler rule: a war tag never
    shortens into a peacetime one while running, or an ally could poke you to cut a two-minute
    lockout to thirty seconds.
- `DeathPolicy`: peacetime player kills keep inventory, war kills drop everything, mobs and terrain
  drop as vanilla.
  - Hard part: the test worth having asserts a property about the **whole plugin**, not the class.
    Read with SPEC 11.7 (hand-looted container items are never restored by rollback), war is the
    *only* mechanism anywhere in the plugin by which a player permanently loses possessions to
    another player. Everything else the plugin takes is money, ranking or reputation — which is
    what makes SPEC 1.2's "destruction is never permanent" true everywhere else.
- Case 127 has its own test because a city will dislike it: a defense unit is a mob, not a war
  participant, so killing a raider with a 55,000-coin Colossus hands the city nothing.
- The war PvP check's fourth condition: a **neutral city's claims are never a battleground**. SPEC
  33.4's deliberate narrowing — without it, any city bordering a war becomes collateral for a fight
  its members never opted into.
- The action-bar countdown uses `ToggleCategory.WAR`, which SPEC 23.6 locks on, rather than the
  mutable `ACTIONBAR`.
  - Hard part: a countdown a player can mute turns a working teleport refusal into an unexplained
    failure. That is the exact surprise SPEC 33.8 forbids, and players report it as a bug.

### Two mistakes, both recorded rather than smoothed

- I closed M19b having built three of the four parts I had listed in the milestone statement. The
  milestone statement is the contract; marking the row DONE was wrong.
- The fourth part **cannot be built as written**. `war.unopposed-score-multiplier: 0.3` and
  `war.walkover-absence-percent: 70` appear in exactly one place across all 41 SPEC sections — a
  config block at SPEC 37 — and no prose anywhere says what either does. Not what an unopposed
  score is, not what counts as an absence, not what the 70% measures. Building them means inventing
  a feature. Not built; the keys are deliberately **not shipped**, because a key with no code behind
  it is the defect the config sweep found nineteen of. The one walkover rule SPEC does define
  (21.4 F4's 25% ranking threshold) M9a already built.
- SPEC 38's M19b row cites section numbers **stale by two throughout**: "(33.2)" for global war PvP
  is actually *Friendly fire*, "(33.3)" for keepInventory is *Peacetime*. Following its references
  lands on the wrong text every time. Same hazard as SPEC 37 shipping a `border:` block that SPEC
  32.3 explicitly rejects — the config appendix drifted out of sync with the design sections.
- I shipped a twin lang key (`combat.tagged-teleport` when `travel.combat-tagged` existed) and the
  orphan sweep caught it, not me. Second twin in two days.
  - Hard part: the interesting bit is *why* this one was caught and the config twin the day before
    was not. `LangKeyUsageTest`'s orphan half catches lang twins automatically, because the unused
    one goes dead. `ConfigKeyUsageTest` **structurally cannot** catch the config case — both keys
    are read, neither is dead. That is a real blind spot in the tooling, not a lapse in attention.

Not verified: `CombatTagListener` has no tests. The pure halves (`CombatTag`, `DeathPolicy`, 40
tests) are mutation-checked; the damage tagging, countdown timer and combat-logout kill are reviewed
and compiled only. Peacetime PvP also remains disabled, so SPEC 33.6's peacetime keepInventory row
is built, configured and unreachable until `pvp.peacetime` is flipped.

### Siege: the attacker's half of a war

- SPEC 29.2's Siege Capacity comes from the **defender's** defence capacity, not from the
  attacker's wealth or size. 70% of it, frozen at declaration.
  - Number: 100/150/225 defensive points at Fortification 0/2/5 becomes 70/105/157 of siege.
  - Hard part: the test worth having asserts the *property*, not the arithmetic. Fortifying must
    always raise the counter (a city cannot outbuild the attack) and must always still pay (the
    attacker's share never catches the defender's whole capacity, and the gap widens per level).
    Both are loops over every level rather than three magic numbers, so a future retune of the
    ratio either keeps the design intact or fails loudly.
- **SPEC 29.2's formula and SPEC 29.2's own table disagree, and I got it wrong first.** The formula
  says `round(defender_capacity * 0.70)`. At Fortification 5 that is `round(225 * 0.70)`, and
  225 × 0.70 is exactly 157.5, which rounds to 158 under any half-up convention. The published
  table says **157**. My first implementation used `Math.round` and produced 158.
  - Hard part: which side to believe is not obvious in the abstract, and the answer came from
    precedent rather than argument. SPEC 39.3's ambiguous `n` was settled the same way at M10 —
    against the tables, because the table is what a player is promised and the formula is a
    description of it. Truncation matches all three published rows; half-up matches two of three.
    It is also the conservative direction: the attacker never gets more siege than the exact share.
- The frozen capacity is a **column on `wars`** (V24), not a lookup.
  - Hard part: derived, it would be recomputed from the *current* Fortification level after any
    restart. A defender could then hand their attacker a larger army mid-war by buying an upgrade,
    or shrink an attack already planned by selling one. Neither is a decision a war should contain,
    and neither is visible until somebody does it.
- The Breacher's carve-out lives in `TargetingRule`, not in the siege package.
  - Number: 6 tests, 4 of them about what the exception does *not* reach.
  - Hard part: SPEC 30.1 forbids "unit-specific targeting logic anywhere else" and SPEC 29.4 needs
    exactly one exception to SPEC 26.4's "units never fight units". Those pull against each other,
    and the obvious implementation — write the exception next to the code that spawns Breachers —
    is the one SPEC forbids, because it becomes a second place deciding what may be attacked. Put
    in the rule, the prohibition and its one hole are a single readable statement. The test that
    matters most is `neverItsOwnSide`: SPEC 11.4 puts **both** cities' claims in the war zone, so
    an attacker's own guards are standing in it, and a Breacher without that check would eat the
    garrison of the city that bought it.
- Siege units get their own table (V25) even though SPEC 3 defines none and SPEC 29 asks for none.
  - Hard part: two independent reasons, and I nearly kept them in memory. The entities carry SPEC
    12.5's persistence flags, so they survive a restart — an in-memory budget would reset and hand
    an attacker a fresh 70 points while their existing army was still standing in the world. And
    SPEC 29.4's war-end despawn needs a *list*: scanning the world for tagged mobs finds only the
    ones in loaded chunks. Deliberately not folded into `defense_units`, which would have meant an
    exception in every reader — siege has no upkeep, no materialisation, no city cap, no life past
    its war.
- A dead siege unit's row is kept and still counts against the budget.
  - Hard part: summing only the living turns SPEC 29.2's cap into a rate limit. The cap is meant to
    be a commitment for the whole war, not a standing army size.

### What SPEC does not say about a siege camp

- SPEC 29.5 gives the camp "200 HP as a **block-entity**", and Bukkit has no such thing: a block is
  not damageable and carries no health.
  - Hard part: so the banner in the world is a **marker** and the camp is its row. Two consequences
    follow that are easy to get backwards. Mining the marker is refused outright — a 200 HP
    objective a diamond pickaxe removes in a second is not an objective, and SPEC 29.5 calls it "a
    real secondary objective". And *hitting* it is what does damage, which means inventing both a
    per-blow figure and a debounce, neither of which SPEC gives. Both are config keys that say in
    the file that they are mine.
- SPEC 29.5 says a camp "can be rebuilt once per war at half cost" and **never says what the whole
  cost is**. `camp-rebuild-cost-percent: 50` ships in SPEC 30.3; no camp price exists anywhere.
  - Hard part: a free camp would make SPEC's own percentage inert — half of nothing is nothing —
    which is exactly the dead-key defect the config sweep found nineteen of. So `siege.camp-cost`
    ships at 20,000, stated in the file as mine, sitting between the Siege Archer's 15,000 and the
    Siege Beast's 40,000.
- SPEC 29 describes camps and units in full and defines **no command and no GUI for either**. Not
  in SPEC 9.3's `/war` tree, not in SPEC 8.8's Wars menu.
  - Hard part: same call as `/ca warp set` at M3b and `/toggle` at M7a. A system with no way to
    drive it is inert configuration wearing a different hat, so `/war siege` ships and is recorded
    as beyond SPEC rather than slipped in.
- The camp's map tile sits **above** every ownership tile rather than beside them.
  - Hard part: SPEC 29.5 permits a camp in the attacker's own claims, so an ownership-first tile
    would render it as their territory — invisible to the people it is aimed at. Which is the exact
    failure SPEC's own sentence names: "a siege the defender cannot see is not a siege, it is an
    ambush."

### The build found three things review did not

- `siege.units-expired` was written into both language files and sent by nothing. SPEC 30.4 lists
  it; I shipped it and never wired it. `LangKeyUsageTest`'s orphan half caught it.
  - Hard part: third time that test has caught a *feature that lost a wire* rather than dead text —
    after M3c's silent mining-claim release and M10's fare regression. It reads like a tidiness
    check and it is not.
- Three lang keys I invented in `SiegeCommands` (`command.players-only`, `storage.not-ready`,
  `city.not-in-city`) when the plugin already had `command.player-only`, `plugin.starting` and
  `city.none`. Caught by the same sweep's other half.
- `SchemaTest` failed for the two new tables, which is never a defect — it is a deliberate
  conformance list and a new table earns an entry. Seventh instance of that shape, and again not
  "fixed" by deriving it: catching an undeclared table is the whole point of the test.

Not verified: `SiegeListener`, `SiegeSpawner` and `SiegeTick` have no tests. Camp damage, the
Breacher's damage rewrite, the no-drops rule on a siege death, the Banner Bearer's aura and the
spawn shaping are reviewed and compiled only — same limitation and same cause as M12d's
`DefenseSpawner`, where MockBukkit reports the entity API these classes need as a *skip* rather than
a failure. What is asserted is the arithmetic and the rules: `SiegeCapacity`, `SiegeCatalogue`,
`SiegePlacement`, `SiegeCamp` and the carve-out, two of them mutation-checked by breaking the
production code and confirming the named tests go red.

---

## 2026-08-09

Twelve commits, 103 files, +15,420/−753, 19 new test files. Three arcs: finishing the outpost GUI,
building the defense system's persistence and targeting foundations by hand, then handing the
remaining four defense milestones to an orchestrated multi-agent run. The through-line is that
every layer of verification caught something the layer above it missed — and the day's most
important finding came from the cheapest layer, an adversarial reviewer that read the diff.

### Outposts, finished

- SPEC 39.12's seven-slot Outposts screen, plus detail, cost-explainer, travel and waystations
  submenus.
  - Number: 12 tests, two mutation-checked.
  - Hard part: the milestone's whole point is SPEC 39.11's line — "a formula with four terms is
    opaque unless the game shows its work". So both buying buttons list every term rather than a
    total, and the explainer substitutes the city's **own** numbers; a worked example with invented
    figures explains the formula and answers nobody's actual question, which is "why is *this* one
    expensive". The distance term is live, so walking outward and watching the multiplier climb
    teaches the curve better than prose.
- A refused button stays visible and names the rule that failed, per SPEC 39.12's "disabled with a
  reason". Hiding it is the normal thing to do and leaves a player guessing at a rule they cannot
  see.
- The chunk diagram is anchored on the founding chunk, not centred on the player, so the shape
  stays still while they walk through their own outpost.

### Defense foundations, built by hand

- `Materialization`: a defense unit is a database row that becomes an entity only while a player is
  within 48 blocks, and returns to a row 30 seconds after they leave.
  - Number: SPEC 31's case 113 gate — 2,400 units, 40 players — met and binding.
  - Hard part: the published ceiling of 60 **cannot be met by a radius rule**. Forty players each
    inside a twelve-unit garrison are within 48 blocks of four hundred units, and no per-player rule
    makes forty players produce fewer than forty times what is in range. Making it binding meant a
    global *fleet budget* — which is what "server-wide" actually says — with war-zone units seated
    first so a defender never arrives to find their garrison missing because forty strangers stood
    in other cities. The cost is real and is not a tuning detail: on a busy server a player can walk
    up to their own garrison and find part of it absent.
- V22 adds `health` and `dormant_since`.
  - Hard part: null health reads as **full**, not zero. The column is added to a table with rows
    already in it; read as zero, the migration kills every defense unit on the server the moment an
    operator upgrades. Likewise `markAllDormant` clears dormancy at startup rather than trusting it,
    or a week of downtime would heal every damaged unit to full on boot — the healing SPEC 25.4
    disables *during* a war, arriving for free right after one.
- The superseded M12 was worse than "a coarse trigger": it respawned units on `ChunkLoadEvent` and
  had **no despawn path at all**. Every unit any player had ever walked past stayed loaded until the
  next restart. That is precisely the 2,400 permanently loaded entities SPEC 25.4 opens by refusing,
  and it had been shipped and passing its tests since M12.
- `TargetingRule`: SPEC 30.1's ordered table, the only place in the plugin that decides what a unit
  may attack.
  - Number: 27 tests, one per branch.
  - Hard part: the milestone's real content was **deletion**. SPEC 30.1 does not only ask for one
    handler, it forbids the alternative — "no unit-specific targeting logic anywhere else" — so
    `DefenseBehaviour`'s targeting methods had to go. Zero references remain, which makes the
    prohibition compiler-enforced rather than asserted in a comment.

### Three tests that were never running

- I reported three materialisation tests as passing. They had never executed.
  - Hard part: MockBukkit raises `UnimplementedOperationException` for unimplemented API, and JUnit
    records that as a **skip**, not a failure — so a suite where the milestone's central assertion
    never ran still prints `BUILD SUCCESSFUL`. My verification was a grep for `FAILED|BUILD`, and a
    skip is neither. The lesson is not "also grep for SKIPPED": mutation-checking proves a test *can
    fail*, which is a different question from whether it *ran*, and the second is cheaper to get
    wrong. Test counts are now read from `build/test-results/test/TEST-*.xml`.
  - The blocking call is `setRemoveWhenFarAway`, which is **exactly what SPEC case 106 mandates** and
    `DefenseSpawner` therefore calls on every spawn. The one line SPEC requires is the one that makes
    the spawner untestable. A `useSpawn` seam exists so the health round trip can be asserted at all.
- A nested test class named `Ordering` claimed to verify SPEC 30.1's positional order and did not.
  Moving the ownership check *after* the state checks fails nothing — it still cancels before any
  allow, so the decision is identical. Only deleting the check fails anything (seven tests). Renamed
  to what it actually guarantees. Third instance in one session of a test claiming more than it
  delivers, after M6c's concurrency lock and the skipped tests above.

### Four milestones by orchestration, and what the review caught

Survey (4 agents in parallel) → build (4 in strict sequence, sharing `DefenseCatalogue`,
`DefenseSpawner` and `defense.yml`) → adversarial review (3 lenses). 11 agents, 0 errors.

- **The review found a critical defect none of the four builders did.** Nothing anywhere called
  `UnitStates.hostile()`. Through an entire ACTIVE war every defense unit sat at PASSIVE,
  `TargetingRule` cancelled every enemy with `STATE_PASSIVE`, and `TrespassService` refuses
  violations during a war — so a city's whole garrison was **inert in the one situation SPEC 27
  built it for**. `UnitMaterializer.useWars` had never been wired either, so SPEC 25.4's war
  materialisation trigger was dead alongside it.
  - Hard part: the gap has a shape this project keeps producing — a rule assigned to a milestone
    that had already shipped. `UnitStates`' own javadoc said "M19 writes HOSTILE", and M19 closed
    before the defense system existed. Fixed with `reconcileWarStates` on every sweep (ALERTED left
    alone, or every sweep drops a trespass alert), **ACTIVE only and never PREP**, since
    `isEngaged()` covers both and would arm a garrison two days before anyone may fight it.
    Reverting the fix fails five named tests.
- An agent caught an error of mine: I briefed the run that `TrespassService` was committed. It was
  not — `acc37cf` contains only the tracker and phases. The agent checked `git show` rather than
  believing the brief.
- Trespass response: three violations in a **sliding** 30-second window, then a warning, then an
  alert against that one player.
  - Hard part: violations are fed from `ProtectionGuard`'s `NOT_A_MEMBER` refusal *only*. The obvious
    wiring — any protection refusal is a violation — is wrong in a way that would be extremely
    visible in play, because a city's own member lacking `CONTAINER` is refused identically to a
    stranger. Counting that has a city's guards warn and then hunt the people who live there.
    Trusted allies fall out for free: they are never refused at all.

Not verified: `DefenseSpawner` has still never executed under any test, and M12d added SCALE, four
attributes, dyed-leather colouring, collar colours and five behaviour flags to it.
`DefenseAbilityListener` and `DefenseTick` have no tests at all. The glow and roar paths are
review-only. This needs a live-server pass; M20a's balance pass cannot substitute for it.

---

## 2026-08-08

Fifteen commits, 146 files, +17,452/−675, 15 new test files. Four arcs: hardening the market against
the Part II threat model, building the world and travel layer SPEC Part IV added, rebuilding
outposts on SPEC 39, and starting waystations. The through-line is that most of the day's real
findings were about *config and test integrity* rather than features — keys that nothing read, tests
that could not fail, and a specification that contradicts itself in two places.

### Market hardening

- The buy list went from 19 items to 13, behind a code-level blacklist config cannot override.
  - Hard part: SPEC 21.9 lists 14 items and **one of them contradicts two other rules in the same
    Part**. Nautilus shell is priced at 200 with the note "review at launch", while SPEC 21.8's hard
    blacklist — which SPEC 21.10.4 makes unoverridable — forbids "all fishing loot and all fish",
    and SPEC 21.4's A11 forbids it again by name. A row a startup check would reject cannot be the
    intended reading, so it is not shipped.
- The daily sell quota, charged inside the sale's own transaction.
  - Hard part: a sale straddling the cap is **split at the boundary**. SPEC does not specify it, and
    it is the only reading under which selling in one go and selling in pieces pay the same —
    otherwise the quota becomes a puzzle about batch sizes, which is exactly the fiddly behaviour
    SPEC 21.5's "soft caps generate shrugs" is trying to avoid.
  - The concurrency test is weaker than it looks: removing the per-player lock leaves it green,
    because SQLite serialises writers and hands it the property for free. The lock matters on MySQL.
    Documented rather than overclaimed.

### World, travel, PvP

- `WorldRegistry`, `/rtp` with five rejection rules, `/warp`, mining claims.
  - Hard part: SPEC contradicts itself on the world border. SPEC 37 ships a `border:` block with
    seven keys; SPEC 32.3 rejects the design outright — "the plugin does not impose, expand, or
    manage a border of any kind" — and gives the reason ("emptiness is the atmosphere"). Later
    section wins, so the keys are not shipped and two tests hold that absence in place.
- SPEC also contradicts itself on whether peacetime PvP exists at all: 33.1/33.3/33.10 enable it,
  37 and 38 disable it. Shipped **disabled** as the conservative reading, behind one key. Still an
  open developer decision.

### Config and test integrity

- `ConfigKeyUsageTest`'s file list was a hardcoded literal, so `world.yml` shipped entirely outside
  the sweep that exists to catch exactly that.
  - Hard part: caught by suspecting a first-run green, not by any test. Now derived from
    `ConfigFile.values()`, and verified by planting a dead key to confirm the sweep fails. A second
    green proves nothing after a first one was wrong.
- `HelpPagesTest`'s root-command list was also a literal, so `/quota` and `/toggle` shipped
  undiscoverable in help for two milestones.
- The mojibake test I wrote in the morning was too narrow to catch the afternoon's own `puÃ²`. I had
  tested one *instance* of the defect and described it as the class. Widened to the real signature:
  a character in U+00C2..U+00DF immediately followed by U+0080..U+00BF.
  - Hard part: a test written from one failure tends to encode that failure rather than its class.

### Outposts rebuilt on SPEC 39

- Multi-chunk outposts on a square-root distance curve, priced from the city core; Part I's
  single-chunk design removed.
  - Number: 29 tests against every cell of SPEC 39.4's two published tables.
  - Hard part: SPEC 39.3's `n` is genuinely ambiguous — "the city's TOTAL chunk count… exactly as
    Part I 6.2 computes it", and Part I 6.2 indexes the chunk *being* claimed. The readings differ
    by about 6% in money. Settled against SPEC 39.4's published tables rather than by argument: a
    twenty-chunk city pays 31,721, which is `400 × 20^1.25 × 1.25 × 1.5`, so `n` is the count
    *before* the purchase. Testing against the formula rather than the tables would have passed
    either way.
- **No migration was needed**, which was not obvious: `claims.outpost_id` is a foreign key, so
  several claims sharing one already *is* a multi-chunk outpost. The Part I schema happened to
  support the design that replaced it.
- I shipped a dozen config keys nothing read — replacing `cities.yml`'s outpost block before writing
  the code that consumes it. This is the exact defect I had spent four milestones removing from
  earlier work. Lesson: config follows code, never leads it.
