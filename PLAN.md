# CivitasCraft, Build Plan

> Agent: read `SPEC.md` in full at the start of every session, then this file.
> Update the status column as you go. Commit after every completed milestone.

## Current session focus

**Next milestone:** M19, War: lifecycle

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
| 19 | War: lifecycle | M18, M13 | **IN PROGRESS** | Done: V12, the state machine (`WarState`, `War`, `WarZone`, `WarRegistry`), `WarService` (all 12 SPEC 11.3 preconditions + escrow + decline), `WarPayouts`, `WarScoring`, `WarPhaseTask` with outage catch-up, `Evacuation`, `WarRestrictions`, the no-drops listener, and **`RegistryWarZones` wired in — M17 now logs and M18 now restores for real**. Protection's grief and PvP seams closed. Resolution done: `WarResolution` decides and pays, loser immunity, winner market bonus (`WarRewards`), war record derived from the wars table. Four more seams closed: War Record board, market sell bonus, `DiplomacyService.isAtWar` and `isRecentEnemy`. Scoring done: `WarScoringListener` (kills, block breaks in enemy claims, defense units incl. SPEC 17.4 case 56) and `CapturePoints` (three points at the defender's extremes, 60s contested holds, the 30s City Hall stand). Allies and commands done: `WarAllies` (SPEC 11.10, PREP-only, 25% stake), `PeaceOffer` (SPEC 8.8 sue-for-peace), full `/war` tree. **Not done: item-theft log, living-entity restore, bounties, Wars GUI, scoreboard, and 8 remaining seam closures.** |
| 20 | War: hardening | M19 | TODO | Manual protocol SPEC 18.3 must pass 3x |
| 21 | Admin tooling | all | TODO | |
| 22 | Anti-toxicity pass | all | TODO | |
| 23 | Polish | all | TODO | |

## Rules the agent must not break

- No database access on the main thread.
- No hardcoded numbers. Every value in SPEC.md is a config key.
- No hardcoded player-facing strings. Everything goes in `lang/`.
- Never modify SPEC.md Section 3 (data model) without writing a migration.
- Never mark a war milestone DONE without running its tests.
- `rollback.enabled` must never default to false.
