# CivitasCraft, Build Plan

> Agent: read `SPEC.md` in full at the start of every session, then this file.
> Update the status column as you go. Commit after every completed milestone.

## Current session focus

**Next milestone:** #5 in the queue below, **M3a, World setup**.

**Part I (M0 to M23) is complete.** SPEC has since grown from 20 sections to 41, adding four
more Parts and **34 further milestones**, none of which were in this file until now. They are
tabled below, in SPEC's own section order.

**Two completed milestones are superseded and must be rebuilt.** SPEC 25's header: "This part
fully supersedes Part I Section 12. The eight-unit catalogue in Section 12.2 must not be
implemented. Implement Section 27 instead." SPEC 39's header: "This part replaces Part I
Section 7 in full. The single-chunk outpost design in Section 7 must not be implemented.
Implement Section 39 instead." M12 and M10 shipped exactly the designs those sentences
retire. Their rows are marked `SUPERSEDED` rather than deleted, because the code is still in
the tree and the replacement milestones have to remove it.

**Two ordering warnings already apply retroactively.** SPEC 24 says M7a "should be built
alongside the GUI framework, not after… the retrofit always misses cases", and M7 and M8 are
done. SPEC 38 and 41 say M3a "should come early, before M4 land protection is finished", and
M4 is done. Neither is fatal; both are now the expensive version of themselves, and that cost
is a consequence of the specification growing after the code, not of a decision taken here.

**Also superseded in place, without their own milestone:** SPEC 33 supersedes Part I 5.5 and
11.6 on PvP (delivered by 19b), and SPEC 32 resolves Part I Open Decisions 1 and 4 (delivered
by 3a).

**Built after M23, outside any milestone:** SPEC 17.7's scale sweep (`PERFORMANCE.md`), the
MySQL dialect pass (`MYSQL.md`), a config-integrity sweep (`CONFIG.md`, nineteen dead or
mismatched keys), and SPEC 17.1 cases 1 to 3, the inactivity sweep that M2 deferred to M4, M4
deferred to "a later milestone", and no milestone ever built.

**Before a public launch:** SPEC 18.3's manual war protocol has still not been run. It needs a
live server, two accounts and three clean passes; `WAR_TEST_PROTOCOL.md` carries the checklist.
The beehive NBT exception recorded at M17 needs a decision first, because SPEC 18.3 step 8 as
literally written cannot pass for a hive without an NMS-backed codec.

## Session protocol

1. Read `SPEC.md` fully. Read this file. Read `OPEN_QUESTIONS.md`.
2. Identify the first milestone below with status `TODO` whose dependencies are all `DONE`.
3. Work only on that milestone. Do not skip ahead, do not partially start later milestones.
4. Before writing code, restate the milestone's deliverable and list the files you will create or modify.
5. Write the code. Write the tests specified in SPEC Section 18 for that milestone.
6. Run `./gradlew build` and fix all errors and warnings.
7. Update the status here to `DONE` with a one-line note.
8. `git add -A && git commit -m "M<n>: <milestone name>"`
9. If you hit an ambiguity not covered by SPEC.md, append it to `OPEN_QUESTIONS.md` and implement the most conservative option. Do not invent features.

## Milestones

| M | Milestone | Depends on | Status | Notes |
|---|---|---|---|---|
| 0 | Project skeleton | none | DONE | Build, config, lang, Result, stub command tree. 48 tests. Enables cleanly on the test server. |
| 1 | Storage layer | M0 | DONE | Hikari, migration runner, all SPEC 3 tables, 23 DAOs, async guard, backups. 136 tests. MySQL path untested locally. |
| 2 | Core city model | M1 | DONE | Entities, registry, CityService, RankService, 22-flag bitmask, events, /city tree, chat tag. V2 migration. 275 tests. |
| 3 | Claim system | M2 | DONE | Cost engine, adjacency, contiguity, claim/unclaim/radius, packed-key cache, map, borders. 397 tests. |
| 4 | Land protection | M3 | DONE | All SPEC 5.5 listeners behind one pure ProtectionService. Bypass enforced. 487 tests. |
| 5 | Economy core | M2 | DONE | Balances, treasury with the 25% cap, ledger, /pay, upkeep sweep with delinquency and auto-unclaim, inflation tracking, PlaceholderAPI and Vault. |
| 6 | Market and player shops | M5 | DONE | Dynamic pricing, /shop /sell /worth, chest shops, V4 migration. /shop is chat until M8 gives it a menu. |
| 7 | GUI framework | M2 | DONE | Menu, Button, pagination, confirmations, YAML layouts, click hardening for cases 59 to 68. No screens: those are M8. |
| 8 | All GUI screens | M7, M3, M5 | DONE | SPEC 8.3 to 8.7 and 8.10, City Hall, spawn. **SPEC 8.8 (Wars) and 8.9 (Defense) have no screen**: their systems are M19 and M12. Their hub buttons refuse the click until then. |
| 9 | Income systems | M5 | DONE | Stipend with the SPEC 4.2.1 filter, daily login streaks, quests, city challenges. V5 migration. Replaces M2's unfiltered playtime placeholder. |
| 10 | Outposts | M3, M5 | SUPERSEDED | **Replaced by the new M10 (SPEC 39).** SPEC 39's header retires the single-chunk design in Part I Section 7 in full. The code it shipped still works and is still in the tree; the replacement milestone has to remove it. Original note: SPEC 7 in full, including the 7.4 auto-conversion. Upkeep counts outposts now. Slot cap still reads the base until M11 builds Outpost Range. |
| 11 | City upgrades | M5, M8 | DONE | Six tracks, the vault, V6 migration. Fills the Population, Treasury Interest, Outpost Range and Market Access seams. **Fortification is stored but unread until M12, and SPEC 5.7 and 12.4 disagree on what it grants.** |
| 12 | Custom mobs | M5, M8 | SUPERSEDED | **Replaced by M12a to M12f (SPEC 27 and 28).** SPEC 25's header retires the eight-unit catalogue in Part I Section 12.2. The code it shipped still works and is still in the tree; the replacement milestone has to remove it. Original note: Eight units, placement by egg, SPEC 12.3 behaviour, upkeep and deactivation. Fortification resolved at 2 units a level (SPEC 12.4 over 5.7). The leash is written and tested but unticked until M19. |
| 13 | Diplomacy | M2 | DONE | Relations, alliances with the 24h notice and 7-day cooldown, truces, trusted build access, /ally /truce /ac, GUI. V7 migration. |
| 14 | Leaderboards | M5 | DONE | Nine boards (SPEC 13.3's table, not its "seven"), `/leaderboard`, cached snapshot on a timer. V8 adds `player_stats` for Builder and Farmer. **Contest Champions and War Record report themselves unavailable** until M15 and M19. |
| 15 | Contests | M14, M8 | DONE | Full SPEC 13.4 cycle, marking, voting on three axes, weighted scoring, prizes. V9. Closes M14's Contest Champions seam. **The SPEC 13.4 "verified against block placement logs" check cannot run until M17 and says so at startup.** `/ca contest` is M21. |
| 16 | Server events | M5, M6 | DONE | All eight SPEC 13.5 events, scheduler with weights and cooldowns, boss bar, announcements, invasion waves. V10. **SPEC gives no schedule, so the interval, weights and cooldowns are this implementation's.** Gold Rush multiplies ore *drops*: generation cannot change after a chunk exists. |
| 17 | War: block logging | M4 | DONE | Every SPEC 11.8.1 source, ring buffer, async batching, crash-safe flush, per-war row ceiling. **Benchmark: 1,546,472/sec record path, 93,567/sec end to end against SPEC's 2,000 target.** No war gameplay, per SPEC 19's ordering note. **SPEC 11.8.1's NBT API does not exist in paper-api; capture is per-type Bukkit and a hive's bees do not round-trip.** |
| 18 | War: rollback engine | M17 | DONE | Reverse replay with physics suppressed, paging, throttling, checkpoint and resume, verification sampling, SPEC 11.8.4 chunk hashes, ROLLBACK_FAILED. V11. Driven by a synthetic log exactly as SPEC 19 asks. **Evacuation, living-entity restore and the no-drops rule are M19: all three need war start.** |
| 19 | War: lifecycle | M18, M13 | DONE | V12, the state machine, `WarService` (all 12 SPEC 11.3 preconditions + escrow + decline), payouts, scoring, `WarPhaseTask` with outage catch-up, evacuation, restrictions, no-drops, and `RegistryWarZones` wired in so **M17 logs and M18 restores for real**. Resolution, loser immunity, winner market bonus, war record. `WarScoringListener` and `CapturePoints` (three points at the defender's extremes, 60s holds, the 30s City Hall stand). `WarAllies` (SPEC 11.10) and `PeaceOffer` (SPEC 8.8), full `/war` tree, `WarsMenu` (SPEC 8.8's three faces) and `WarScoreboard` (SPEC 9.3). SPEC 11.7 item-theft log (`WarLootLog`, diffed across the open). SPEC 11.8.3 entity restore: hangings from M17's log, plus villagers and animals snapshotted at war start. SPEC 11.8.4 pre-war chunk hashes now taken. SPEC 4.7 bounties end to end. SPEC 12.3's leash ticked. SPEC 17.4 cases 41 and 48. **All 14 war seams closed and each tested refused-then-allowed.** 1406 tests. |
| 20 | War: hardening | M19 | DONE | Every SPEC 17.4 case now has a test that names it. **Three real bugs found and fixed:** overlapping wars corrupted each other's restores (SPEC's stated end-time ordering is not sufficient — `OverlapSeeder` now completes a new war's log from any older war it shares ground with), fluid could escape a zone into wilderness and never be restored (case 46 rested on a coincidence of the ownership rule), and `moveOut` sent a defender to a spawn inside the zone it was evacuating them from. `Spec18ProtocolTest` runs the automatable half of SPEC 18.3; `WAR_TEST_PROTOCOL.md` carries the rest. **MockBukkit does not rebuild a tile state after `setBlockData`, so chest contents, sign text, banner patterns and spawner types are verified by the manual protocol and by nothing else** — 5 tests skip with that reason attached. 1446 tests. **SPEC 18.3 has not been run: it needs a live server, two accounts and three clean passes, and the beehive exception needs a decision first.** |
| 21 | Admin tooling | all | DONE | All 54 SPEC 9.4 subcommands, in four increments. `AuditService` (SPEC 17.6 case 80) is unclearable by construction rather than by a guard. `/ca war verify` and `rollbackstatus` **make the SPEC 18.3 protocol runnable**. The six SPEC 17.6 case 79 heuristics — **one could never have fired as SPEC words it**; the outlier was its own baseline. RFC 4180 CSV export. **`/ca claim protect` closed the last seam in the plugin**, open since M3. V13 and V14. `/ca eco rollback` writes compensating entries and never deletes (SPEC 3.6), floors at zero and records debt (SPEC 17.3 case 35), and counts downstream by row id rather than by millisecond. SPEC 15.3 `/report` with a rate limit SPEC does not specify. **Every admin bypass is a separate method, never a flag — unfreezing a frozen city is the case that proves why.** 1562 tests. **`/ca perf` says which two of its four SPEC metrics nothing measures.** |
| 22 | Anti-toxicity pass | all | DONE | All sixteen SPEC 15 mechanisms audited, each asserted twice: **enforced**, and **configurable** — the second is what catches a rule sitting behind a key nothing reads. `Spec15AuditTest` (20 tests) and `ANTI_TOXICITY.md`, a table of mechanism, key, where enforced and what proves it. **No unenforced mechanism found**; the three first-run failures were all the test being wrong about the environment. `WarService.canDeclare` added so the audit — and SPEC 8.8's button — can ask without declaring. SPEC 15.2's "seven leaderboards" against SPEC 13.3's nine is settled by asserting what the row protects rather than the count. 1582 tests. |
| 23 | Polish | all | DONE | `/city help` (the command set declared in Java, the wording in `lang/`, so `HelpPagesTest` fails the build if a command loses its entry or gains one it does not have), the SPEC 9.1 rules book carrying SPEC 17.2 case 16 and SPEC 11.7's loot asymmetry, and `/cc` -- **the last stub in the plugin; `CommandRegistry.COMMANDS` is now empty**. `/ca perf` measures all four SPEC 9.4.6 figures: M21 named two as unmeasured and **the write rate and pool status were missing too**. `Timings` samples claim lookup 1-in-64 because SPEC 17.7 case 81 puts it on every block event. Tab completion swept -- 10 of 22 gaps closed, the rest declared free-form and held by `TabCompletionTest`; found **five copies** of the player provider, two lowercasing without a locale. Localisation asserted against **what the code passes**, not against the other language -- the first version's four findings were all wrong, the rewrite found six real ones, **two player-visible since M10** (outpost teleport showed a literal `<name>`). 1636 tests. |

## Remaining milestones, in build order

**This table is a queue.** `/next-milestone` takes the first `TODO` row whose dependencies are
all `DONE`, so the order of these rows *is* the build order. It is one table rather than four
because SPEC's four Parts are not four phases — they interleave, and reading them in section
order would build the City Warden before the world it stands in.

Every row's dependencies appear above it, so walking the table top to bottom never stalls.
`PlanOrderTest` asserts that, and fails the build if a future edit reorders a row above
something it needs.

| # | M | Milestone | SPEC | Depends on | Status | Notes |
|---|---|---|---|---|---|---|
| 1 | 6a | Crafting equivalence graph | 24 | M6 | DONE | `RecipeGraph` (directed, transitive reachability), `CraftingEdges` (SPEC 21.10.2's smelting and stonecutter tables **plus SPEC 21.3's 22 pairs as a floor**, because MockBukkit ships no vanilla recipes and the property would otherwise be unverifiable), `BukkitRecipeSource`, and `MarketSafetyCheck` as a latch above config (SPEC 21.10.4). **The relation is not transitive and not undirected** — raw iron and iron ore share a smelting output and are safely listable, so the check is pairwise. The shipped buy list passes, and the test now locks it. 1726 tests. |
| 2 | 6b | Market hardening | 24 | 6a | DONE | `HardBlacklist` (SPEC 21.8, code-level, categories from Bukkit tags so a food or fish added by Mojang is covered without an edit), `VillagerTrades` (21.10.1's disjointness assertion), and `SellGroups` — SPEC 21.6's catalogue is prose, not a table, so `economy.yml` prices ~30 **groups** that expand to 400+ materials. The buy list went from 19 items to **13**: SPEC 21.9 lists 14 and **Nautilus shell contradicts two other rules in the same section**, so it is not shipped. `MarketItem.serverBuys` splits the two directions, and `sell` refuses a sell-only item with its own reason — SPEC 21.6: "every item the server buys is a potential money faucet. Every item the server *sells* is a money sink and carries no exploit risk at all." **SPEC 21.11's inline `# automatable:` comment does not survive a config write**; moved above the entry. 1748 tests. |
| 3 | 6c | Daily sell quota | 24 | 6b | DONE | `SellQuota` + V16, charged **inside the sale's own transaction** so a crash cannot pay a player and forget it. A sale straddling the cap is **split at the boundary**, which SPEC does not specify and is the only reading under which selling in one go and selling in pieces pay the same. Counter measures value at full rate, resets at `quota-reset-hour`, survives a restart. `/quota` (SPEC 22.3, a High-severity omission from Part I) and SPEC 23.5.1's three messages. **The concurrency test is weaker than it looks** — removing the lock leaves it green, because SQLite serialises writers; the lock matters on MySQL and its comment says so. **SPEC 21.5 presupposes the newcomer bonus applies to market sales and it never has.** 1774 tests. |
| 4 | 9a | Anti-abuse layer | 24 | M9 | DONE | All seven SPEC 21.4 Class F mitigations. `PlacedBlockCache` (F9/F10, per-chunk, LRU, 24h TTL — **eviction fails toward counting a block**, never toward robbing a player of credit; ripe crops exempt or "harvest 256 wheat" becomes uncompletable). F11 adds distinct **minutes** to the distinct-kinds rule that was already there. F12 raises the income floor to 60 min and adds V17's daily-activity baseline. F16's 72h withdrawal hold. **F6 supersedes SPEC 17.1 case 10's even disband split and F7 supersedes M19's reading of SPEC 4.7** — both carry a test naming the older rule. F4 filters the board, not the war. **Two real bugs found by the tests, both the same shape: a write inside a transaction that then returns a Failure is rolled back** — the bounty refund and the daily baseline. SPEC 21.11's `disband-treasury-split: EVEN` is deliberately not shipped. 1826 tests. |
| 5 | 3a | World setup | 41 | M3 | TODO | Multi-world config, per-world claim rules, world whitelist enforcement in **every** protection listener. **No border management** — this is where SPEC 41 differs from SPEC 38's 3a. SPEC wanted this before M4; it is now a retrofit, so it goes as early as the queue allows. |
| 6 | 7a | Message framework | 24 | M7 | TODO | Palette as MiniMessage tag resolvers, prefix system, channel router, per-player toggles, action-bar throttling, startup placeholder validation, formatters. SPEC wanted this alongside M7. Also a retrofit now, and every milestone after it that prints a string is one more thing to convert — hence its position. |
| 7 | 4a | PvP policy | 38 | M4 | TODO | Peacetime PvP disabled globally, exclusion zones, join and respawn grace. SPEC 33 supersedes Part I 5.5 and 11.6 here. |
| 8 | 3b | Travel | 41 | 3a | TODO | `/spawn`, `/rtp` with the 15k radius and full safe-location validation, `/warp`, warmups, cooldowns, cancellation. |
| 9 | 3c | Mining claims | 41 | 3a, M4, M5 | TODO | SPEC 32.6, `/mine` tree, protection reusing the M4 listeners, personal-balance upkeep. |
| 10 | 10 | Outposts, rebuild | 41 | M3, M5, 3a | TODO | SPEC 39.1 to 39.9 in full. Multi-chunk claims, internal contiguity, merging, placement rules, distance-scaled upkeep and teleport, delinquency release order. **Cost engine unit-tested against every value in the 39.4 tables, not just the formula.** Removes the superseded Part I M10. |
| 11 | 10a | Waystations | 41 | 10 | TODO | SPEC 39.10, separate pool, resource world placement, own distance constant. |
| 12 | 10b | Outpost GUI and cost transparency | 41 | 10, M8 | TODO | SPEC 39.12, `/city outpost cost`, the formula explainer screen. |
| 13 | 12a | Unit persistence layer | 31 | M5, M8 | TODO | `defense_units` schema, materialize and dematerialize (SPEC 25.4), health checkpointing, dormant regeneration, chunk-load and restart recovery. **No combat behaviour yet.** Benchmark case 113 before proceeding. |
| 14 | 12b | Central targeting handler | 31 | 12a | TODO | The single `EntityTargetLivingEntityEvent` handler from SPEC 30.1, all four states, the never-target list from 26.4, unit tests for every branch. |
| 15 | 12c | Trespass response | 31 | 12b, M4 | TODO | Violation tracking with a sliding window, warning phase, alert phase, de-escalation, alert network, `audit_log` entries, all `trespass.*` messages. |
| 16 | 12d | Core roster | 31 | 12c | TODO | Frost Sentry, Watchtower Keeper, Warhound, Archer, City Guard, Colossus. Dyed leather city colours, all abilities, all counterplay, edge cases 105 to 112. |
| 17 | 12e | Defense Capacity | 31 | 12d, M11 | TODO | Points budget, Defense GUI, purchase and placement flow, per-chunk cap, leash, upkeep integration, downgrade handling (case 101). |
| 18 | 12f | City Warden | 31 | 12e | TODO | SPEC 28 in full. Sonic boom disabled **and verified**, vibration anger disabled **and verified**, peacetime recovery, dormant burrow state, core-chunk confinement. |
| 19 | 19b | War PvP and death | 38 | M19, 4a | TODO | Global war PvP (33.2), scoped keepInventory (33.3), combat tagging (33.4), unopposed score multiplier and walkover (33.5). |
| 20 | 19a | Siege units and camps | 31 | 12f, M19 | TODO | SPEC 29 in full. Siege Capacity computed at declaration, camp placement and destruction, war-end despawn. |
| 21 | 19c | World backups | 38 | 3a, M18 | TODO | Daily world backup, pre-war zone region snapshot, `/ca world restore`, disk guard. |
| 22 | 19d | Discontiguous war zones | 41 | 10, M18, M19 | TODO | War zone computation, block logging, pre-war snapshot and rollback verified against a war where an outpost is over 500,000 blocks from the city. Case 136. |
| 23 | 20a | Combat balance pass | 31 | 19a, M20 | TODO | Verify Rule 1 empirically: an attacking force equal to the defender's active member count beats a full garrison at Fortification 0, 2 and 5, three trials each. Tune and record in the spec. |
| 24 | 14a | Money supply accounting | 24 | M14 | TODO | Hourly `money_supply` snapshots, `/ca eco supply`, `sources`, `top`, inflation dashboard. |
| 25 | 14b | Circuit breakers | 24 | 14a | TODO | Every trigger in SPEC 21.7, automatic market sell freeze, admin alerting, `/ca breaker status` and `reset`, `/ca market volume`. |
| 26 | 9b | Onboarding | 38 | M9, M8 | TODO | First-join flow, Guide Book, starter quest chain, contextual tips, recruitment board GUI, `/guide`. |
| 27 | 14c | Seasons | 38 | M14 | TODO | Season state machine, leaderboard reset **scoped to rankings only**, Hall of Fame, rewards, `/season`, admin commands. |
| 28 | 15a | Contest visit warps | 41 | M15 | TODO | SPEC 40.1, temporary warps generated on submission, deleted on close. |
| 29 | 21a | Investigative admin tooling | 24 | M21 | TODO | SPEC 22.7.1 and 22.7.2 in full. `/ca history` is the priority. |
| 30 | 21b | Metrics and API | 38 | M21 | TODO | bStats, `server_stats` table, `/ca stats server`, public read-only API, PlaceholderAPI, optional Vault provider. |
| 31 | 22a | Command completeness pass | 24 | all above | TODO | Every command in SPEC 22 implemented with full tab completion, plus a test asserting every registered command has a completer for every argument. |
| 32 | 23a | Message catalogue | 24 | all above | TODO | Every key in SPEC 23.5 implemented and wired, plus a test asserting **every** state-mutating service method fires at least one message. |
| 33 | 23b | Italian localisation | 24 | 23a | TODO | `it.yml` complete, key-parity check green. |
| 34 | 23c | Accessibility | 38 | 23a | TODO | Colourblind palette, symbol-plus-colour audit across every message and GUI element. |

### Why this order, where it departs from SPEC's section numbering

- **6a and 6b first.** SPEC 24 makes them hard blockers on M6, and the market has been live
  since M6: "if the market ships first 'just to test it', the test server's economy will be
  broken within an hour and every subsequent balance measurement you take will be meaningless."
- **3a and 7a next, ahead of their SPEC section order.** Both were meant to precede finished
  work — 3a before M4, 7a alongside M7 — so both are already retrofits. Every milestone built
  after them is one more set of listeners to make world-aware and one more set of strings to
  convert, so they go as early as their dependencies allow.
- **Outposts before defense.** The new M10 changes what a claim is, and 12c's trespass response
  and 12e's per-chunk unit cap are both written against claim geometry. Building the roster
  first would mean writing it twice.
- **19b before 19a.** SPEC 38: "M19b must land in the same session block as M19." M19 is
  already done, so the next best thing is to land it before anything else touches war.
- **20a last of the combat work.** It is an empirical balance pass and needs every unit and
  siege piece in place to mean anything.
- **The four completeness passes last**, because each is defined as "every command", "every
  message key", and cannot be finished while more of either are still being added.


## Rules the agent must not break

- No database access on the main thread.
- No hardcoded numbers. Every value in SPEC.md is a config key.
- No hardcoded player-facing strings. Everything goes in `lang/`.
- Never modify SPEC.md Section 3 (data model) without writing a migration.
- Never mark a war milestone DONE without running its tests.
- `rollback.enabled` must never default to false.
