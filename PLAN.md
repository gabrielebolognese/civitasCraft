# CivitasCraft, Build Plan

> Agent: read `SPEC.md` in full at the start of every session, then this file.
> Update the status column as you go. Commit after every completed milestone.

## Current session focus

**Next milestone:** none. M0 to M23 are DONE.

**Built after M23:** SPEC 17.7's scale sweep, the MySQL dialect pass, a config-integrity
sweep (`CONFIG.md`, **nineteen dead or mismatched keys**), and **SPEC 17.1
cases 1 to 3** — the inactivity sweep, which M2 deferred to M4, M4 deferred to "a later
milestone", and no milestone ever built. `cities.yml` had shipped its four numbers since
M2 with nothing reading them.

**Before a public launch:** SPEC 18.3's manual war protocol has still not been run --
it needs a live server, two accounts and three clean passes, and `WAR_TEST_PROTOCOL.md`
carries the checklist. The beehive NBT exception recorded at M17 needs a decision
first: SPEC 18.3 step 8 as literally written cannot pass for a hive without an
NMS-backed codec.

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
| 10 | Outposts | M3, M5 | DONE | SPEC 7 in full, including the 7.4 auto-conversion. Upkeep counts outposts now. Slot cap still reads the base until M11 builds Outpost Range. |
| 11 | City upgrades | M5, M8 | DONE | Six tracks, the vault, V6 migration. Fills the Population, Treasury Interest, Outpost Range and Market Access seams. **Fortification is stored but unread until M12, and SPEC 5.7 and 12.4 disagree on what it grants.** |
| 12 | Custom mobs | M5, M8 | DONE | Eight units, placement by egg, SPEC 12.3 behaviour, upkeep and deactivation. Fortification resolved at 2 units a level (SPEC 12.4 over 5.7). The leash is written and tested but unticked until M19. |
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

## Rules the agent must not break

- No database access on the main thread.
- No hardcoded numbers. Every value in SPEC.md is a config key.
- No hardcoded player-facing strings. Everything goes in `lang/`.
- Never modify SPEC.md Section 3 (data model) without writing a migration.
- Never mark a war milestone DONE without running its tests.
- `rollback.enabled` must never default to false.
