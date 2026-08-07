# War test protocol

SPEC 18.3, as a checklist to run by hand. **SPEC 18.3 is explicit: do not launch publicly
until step 8 passes cleanly three times in a row.**

## Why this exists and cannot be replaced by tests

Most of the war system is covered by the automated suite. Two things are not, and both are
the kind that only fail on a real server:

1. **Tile payloads through the applier.** The rollback restores blocks with
   `setBlockData(data, false)`, because SPEC 11.8.2 step 4 requires physics to be suppressed.
   MockBukkit builds a block's state class when the *material* is set and does not rebuild it
   when block data is written, so under test a restored chest is a chest with no chest state.
   The capture-and-restore round trip is proved in `TilePayloadCodecTest`; the two halves
   working *together* are proved only here.

   **This means the contents of chests, the text on signs, banner patterns and spawner types
   are verified by this document and by nothing else.**

2. **Crash behaviour.** `RollbackCrashSafetyTest` proves the engine resumes from a checkpoint.
   It cannot prove that a real JVM killed mid-write leaves the database in a state the engine
   can read.

## Known limitation, decide before signing off

**A beehive's bees do not survive rollback.** Paper's `EntityBlockStorage` reports how many
bees a hive holds and will not hand them over, and paper-api exposes no vanilla NBT
(see OPEN_QUESTIONS.md, M17). A hive rolls back empty.

SPEC 18.3 step 2 lists a beehive with bees and step 8 requires that everything "matches the
pre-war screenshots exactly". **As literally written, step 8 cannot pass for a hive.** Either
accept the hive as a stated exception, or resolve the NBT question first. Do not quietly mark
step 8 passed with a hive in the structure.

---

## Setup

- A test server running the build under test. `../testserver/` is configured for this.
- **Two accounts minimum**, three is better (SPEC 11.3 requires 3 members per city, so use
  `/ca city forceadd` or accept that declaration will refuse until the cities are big enough).
- `rollback.enabled: true` in `war.yml`. It ships true and must never be false on a live
  server.
- Console access, so you can read the rollback log lines and kill the process.

## Step 1 — two cities

- [ ] Found two cities, at least 10 claims each (SPEC 11.3 precondition 3).
- [ ] At least 3 members each (precondition 2).
- [ ] Attacker at least 14 days old, or adjust `war.yml` `declaration.min-city-age-days` for
      the test and note that you did.
- [ ] Both treasuries funded well past the 50,000 C minimum wager.

## Step 2 — build the structure

Inside the **defender's** claims. Record exact coordinates for every element, and screenshot
each one.

| # | Element | Coordinates | Screenshot |
|---|---|---|---|
| 1 | Chest with a distinctive mix of items | | |
| 2 | Furnace mid-smelt (fuel burning, item cooking) | | |
| 3 | Sign with text on both sides | | |
| 4 | Item frame with an item in it, rotated | | |
| 5 | Beehive with bees inside | | **see the limitation above** |
| 6 | Spawner (change its mob from the default) | | |
| 7 | Banner with a multi-layer pattern | | |
| 8 | Redstone circuit in a known state | | |
| 9 | Water source | | |
| 10 | Lava source | | |
| 11 | Sand suspended on a torch | | |

The sand on a torch is not decoration. It is the single fastest way to catch a rollback that
applies physics: restore the torch with physics on and the sand falls before you can look at
it.

## Step 3 — record

- [ ] Screenshots of every element above, from a fixed vantage point.
- [ ] `/ca info <defender>` output saved.
- [ ] Note the exact coordinates of the City Hall.

## Step 4 — declare and wait out PREP

- [ ] `/war declare <defender> 50000`
- [ ] Confirm both treasuries dropped by the wager (SPEC 11.3 escrows at declaration).
- [ ] Confirm the defender can decline within 6 hours, then let the window pass.
- [ ] Confirm during PREP: no grief is possible, members cannot leave or join, claiming is
      refused, upgrades are refused, outposts are refused.
- [ ] Shorten `phases.prep-hours` for the test if you like; note that you did.

## Step 5 — fight

During ACTIVE, in the defender's claims:

- [ ] Break every element from step 2.
- [ ] Confirm **nothing drops** when you break it (SPEC 11.8.3's no-drops rule).
- [ ] Blow up part of the structure with TNT.
- [ ] Set fire to part of it.
- [ ] Flood part of it with lava.
- [ ] **Loot the chest by hand** — take items out rather than breaking it. This is the
      SPEC 11.7 exception and the one thing that must *not* come back.
- [ ] Kill an enemy player at least once and confirm the score moves.
- [ ] Stand on a capture point for 60 seconds and confirm it scores.
- [ ] Confirm lava does not flow outside the war zone.

## Step 6 — restart mid-war

- [ ] Stop the server cleanly while the war is ACTIVE.
- [ ] Start it again.
- [ ] Confirm the war is still ACTIVE with the correct time remaining.
- [ ] Confirm further damage is still logged (break one more block and check the row count
      rises).

## Step 7 — end the war

- [ ] Let the timer run out, or `/ca war forceend`.
- [ ] Confirm everyone inside the zone was teleported out.
- [ ] Confirm the zone is closed to entry while the restore runs.
- [ ] Watch the console for the rollback's progress and completion lines.

## Step 8 — verify, the step that decides everything

Against the step 3 screenshots, element by element:

- [ ] Every block is back, in the right place, of the right type.
- [ ] The chest exists and contains **everything except what you took by hand**.
- [ ] The furnace has its fuel and its cooking item, with its timings.
- [ ] The sign's text is exactly as it was, both sides.
- [ ] The item frame holds its item, at the same rotation.
- [ ] The beehive is back — **its bees are not, see the limitation above**.
- [ ] The spawner spawns the mob you set, not the default.
- [ ] The banner has every layer of its pattern.
- [ ] The redstone is in the state it was in.
- [ ] The water and lava are where they were.
- [ ] **The sand is still on the torch, not on the ground.**
- [ ] Any animals or villagers that were killed are back, and villagers kept their trades.
- [ ] The City Hall was never breakable at any point.

## Step 9 — the integrity check

- [ ] `/ca war verify <id>` reports zero mismatches. *(This command is M21; until then, read
      the `war_rollback_issues` table directly — an empty result is the same statement.)*
- [ ] `/ca war rollbackstatus <id>` shows no chunk-hash mismatches.

## Step 10 — kill it mid-rollback

- [ ] Run the war again, or trigger a fresh rollback with `/ca war rollback <id>`.
- [ ] **SIGKILL the server process while the rollback is running** (`kill -9`, or End Task).
- [ ] Start it again.
- [ ] Confirm the rollback resumes rather than restarting, and that it resumes from a
      checkpoint rather than from the beginning.
- [ ] Confirm the zone stays closed until it finishes.
- [ ] Repeat step 8's verification. The result must be identical.

---

## Sign-off

SPEC 18.3: **three clean passes in a row before any public launch.**

| Pass | Date | Server build | Result | Notes |
|---|---|---|---|---|
| 1 | | | | |
| 2 | | | | |
| 3 | | | | |

A pass is clean when every box in steps 8 and 9 is ticked. The beehive exception, if you have
accepted it, is written in the notes column of every pass rather than left implicit.
