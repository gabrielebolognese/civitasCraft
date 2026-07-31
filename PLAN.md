# CivitasCraft, Build Plan

> Agent: read `SPEC.md` in full at the start of every session, then this file.
> Update the status column as you go. Commit after every completed milestone.

## Current session focus

**Next milestone:** M4, Land protection

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
| 4 | Land protection | M3 | TODO | |
| 5 | Economy core | M2 | TODO | |
| 6 | Market and player shops | M5 | TODO | |
| 7 | GUI framework | M2 | TODO | |
| 8 | All GUI screens | M7, M3, M5 | TODO | |
| 9 | Income systems | M5 | TODO | |
| 10 | Outposts | M3, M5 | TODO | |
| 11 | City upgrades | M5, M8 | TODO | |
| 12 | Custom mobs | M5, M8 | TODO | |
| 13 | Diplomacy | M2 | TODO | |
| 14 | Leaderboards | M5 | TODO | |
| 15 | Contests | M14, M8 | TODO | |
| 16 | Server events | M5, M6 | TODO | |
| 17 | War: block logging | M4 | TODO | Benchmark 2000 writes/sec before marking done |
| 18 | War: rollback engine | M17 | TODO | Test with a synthetic block log, no war gameplay yet |
| 19 | War: lifecycle | M18, M13 | TODO | |
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
