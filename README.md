# CivitasCraft

CivitasCraft is a server plugin for city-building Minecraft servers. Players found cities, claim land chunk by chunk, run a shared treasury, trade on a server market, form alliances and fight scheduled wars, after which every block broken in the war zone is put back. It targets Paper 1.21.x on Java 21 and is server-side only, so players need no client mod. It is meant for server operators who want a complete towns-and-wars ruleset in a single jar.

## Features

**Cities and land**
- Found a city (`/city create`), invite or openly admit members, rename, transfer or disband it.
- Claim 16x16 chunks one at a time, automatically while walking, or by radius. Claim cost rises with city size and distance; unclaiming refunds part of the cost.
- Contiguity and buffer rules between cities, an in-chat chunk map and particle borders.
- Default ranks Mayor, Co-Mayor, Architect, Citizen and Recruit, plus custom ranks with per-rank permission flags.
- Land protection covering blocks, containers, entities, explosions, pistons, fire and fluids.
- Outposts: detached holdings of one to four chunks away from the home city, with teleport, and waystations.
- Mining claims: a personal chunk in a resource world (one per player by default), paid from the owner's own balance and available to players without a city.
- Six city upgrade tracks: population, vault, treasury interest, outpost range, fortification and market access.
- A shared city vault, a City Hall block, city spawn and message of the day.
- Inactivity handling: mayor transfer, dormancy and soft deletion of abandoned cities.

**Economy**
- A coin currency with personal balances, `/pay`, and a city treasury with deposits and capped daily withdrawals.
- Daily upkeep based on land value, with a grace period and automatic unclaiming of outer chunks for delinquent cities.
- A server market with dynamic prices (`/shop`, `/sell`, `/worth`), a daily sell quota and an item blacklist.
- Player chest shops driven by signs.
- Income from an activity-filtered stipend, daily login streaks, daily quests and weekly city challenges.
- Bounties, paid out during wars.
- An append-only transaction ledger, money-supply accounting, inflation tracking and circuit breakers that halt suspicious flows.

**War**
- `/war declare <city> <wager>` with preconditions on city size, age and treasury; both sides stake the wager.
- A state machine of declaration, a preparation phase (48 hours by default) and an active phase (7 days by default), then rollback and resolution.
- Scoring from kills, capture points, destroyed defenders, block breaks and reaching the City Hall.
- Peace offers, allies joining a war during preparation, a war sidebar scoreboard and war history.
- Full world rollback: every block change in the war zone is logged and replayed in reverse with physics suppressed, with checkpoints, resume after a restart, sampled verification and chunk-hash checks. Entities, hanging items and container contents are snapshotted and restored.
- Items taken out of chests by hand during a war are not returned; the city vault is immune.

**Defenses and sieges**
- Six purchasable defender units (Frost Sentry, Watchtower Keeper, Warhound, Archer, City Guard, Colossus) bought against a Defense Capacity points budget that grows with the fortification upgrade.
- A trespass response that warns, then alerts, then de-escalates.
- A City Warden guardian per city.
- Siege camps and four attacker units (Siege Beast, Breacher, Siege Archer, Banner Bearer).

**Diplomacy and combat rules**
- Alliances with notice and cooldown periods, truces, trusted build access, and alliance chat (`/ac`).
- A single PvP policy with zones, grace periods, combat tagging and a death policy.

**Community systems**
- Building contests with plot marking, submission, visit warps and voting on creativity, technical skill and theme fit.
- Eight server events: market boom, market crash, harvest festival, gold rush, invasion, founders week, double upkeep and tax holiday.
- Nine leaderboards and seasons with standings and rewards.
- Onboarding: an optional starter chain and a guide book (`/guide`), plus an in-game rules book (`/civitas rules`).
- Travel: `/spawn`, random teleport (`/rtp`), public warps, with warmups and cooldowns.
- Player reports to a moderation queue (`/report`).

**Interface**
- Inventory menus for the city hub, claims, members, ranks and permissions, treasury, upgrades, outposts, waystations, defense, wars, diplomacy, quests, challenges, contests and settings. Several layouts are editable in `gui/*.yml`.
- MiniMessage-formatted messages loaded from `lang/`, with per-player notification toggles (`/toggle`).

**Administration**
- `/cityadmin` (alias `/ca`) covers cities, claims, economy, wars and rollbacks, events, contests, block inspect mode, ledger audit with fraud heuristics and CSV export, staff notes, performance reports, reload, backup and debug.
- Scheduled SQLite database backups.

**Integrations**
- Optional PlaceholderAPI placeholders and an optional Vault economy provider. The plugin runs without either.
- A read-only `CivitasApi` for other plugins, and Bukkit events for city and claim changes.
- Anonymous bStats metrics, which can be disabled through bStats' own configuration.

## Getting started

### Prerequisites

- A JDK to run Gradle. The build compiles with a Java 21 toolchain, which Gradle provisions automatically if one is not installed.
- A Paper 1.21.x server to run the plugin (the plugin declares `api-version: 1.21` and builds against `paper-api 1.21.11`).
- No separate Gradle install: the repository ships the Gradle wrapper.

### Download

```bash
git clone https://github.com/gabrielebolognese/civitasCraft.git
cd civitasCraft
```

### Build

```bash
./gradlew build
```

On Windows use `.\gradlew.bat build`. Dependencies are downloaded by Gradle on the first run. The build compiles, runs the test suite and produces the shaded jar at `build/libs/CivitasCraft-0.1.0.jar`. Compilation uses `-Xlint:all,-serial,-processing` with `-Werror`, so any other compiler warning fails the build.

### Configuration

There are no environment variables. On first start the plugin writes its configuration into its data folder under `plugins/`:

| File | Covers |
| --- | --- |
| `config.yml` | Storage, enabled worlds, language, message limits, performance, moderation |
| `cities.yml` | City creation, claims, upkeep, members, ranks, outposts, inactivity |
| `economy.yml` | Income, market, player shops, bounties, audit, money supply, circuit breakers |
| `war.yml` | Declaration rules, phases, scoring, rewards, rollback, block log |
| `defense.yml` | Defender units, trespass, capacity, City Warden, siege units |
| `combat.yml` | PvP policy |
| `world.yml` | World roles, travel, mining claims, world backups |
| `events.yml` | Server events, contests, leaderboards, seasons |
| `onboarding.yml` | Starter chain and guide |
| `gui/*.yml`, `lang/*.yml` | Menu layouts and message text |

Storage is SQLite by default (`storage.type: SQLITE`, file `civitas.db` in the plugin data folder). To use MySQL or MariaDB, set `storage.type: MYSQL` and fill in `storage.mysql.host`, `port`, `database`, `username` and `password` in `config.yml`. Schema migrations for both dialects run automatically at startup. Built-in backups apply to SQLite only; a MySQL operator must arrange their own (see [`MYSQL.md`](MYSQL.md)).

Set the worlds where cities are allowed under `worlds` in `config.yml` and the world roles in `world.yml`. The language is chosen with `language` in `config.yml`; `en` and `it` files are shipped, and any missing key falls back to English.

`rollback.enabled` in `war.yml` must stay `true` on a live server.

## Install and deploy

1. Build the jar as above.
2. Copy `build/libs/CivitasCraft-0.1.0.jar` into the `plugins/` folder of a Paper 1.21.x server.
3. Optionally install PlaceholderAPI and Vault.
4. Start the server, then edit the generated configuration files and restart or run `/ca reload`.

For local development, `deploy.sh` (Linux and macOS) and `deploy.ps1` (Windows) build the plugin and copy the jar into `../testserver/plugins/`, a test server directory expected next to the repository.

### Running tests

```bash
./gradlew test                                                         # whole suite
./gradlew test --tests "dev.civitas.core.claim.ClaimCostEngineTest"    # one class
./gradlew test --tests "dev.civitas.core.claim.*"                      # one package
```

Tests use JUnit 5 and MockBukkit. The MySQL dialect tests are skipped unless a server is named with the `civitas.test.mysql.url`, `civitas.test.mysql.user`, `civitas.test.mysql.password` and `civitas.test.dialect` system properties; the named schema is dropped and recreated, so point them at a throwaway database. Details are in [`MYSQL.md`](MYSQL.md).

## Usage

In game, `/city help` shows the paginated command help and `/city` opens the city menu for a player who belongs to a city. The main commands:

| Command | What it does |
| --- | --- |
| `/city` | City menu. Subcommands include `create`, `info`, `list`, `join`, `invite`, `kick`, `leave`, `claim`, `unclaim`, `map`, `border`, `here`, `spawn`, `setspawn`, `deposit`, `withdraw`, `vault`, `rank`, `outpost`, `upgrade`, `defense`, `rename`, `transfer`, `disband`, `hall` |
| `/cc`, `/ac` | City chat and alliance chat |
| `/money` (`/balance`), `/pay`, `/transactions` | Balance, transfers and your own ledger |
| `/shop`, `/sell`, `/worth`, `/quota` | Server market, selling, live prices and the daily sell quota |
| `/quests`, `/challenges` | Daily quests and weekly city challenges |
| `/war` | `declare`, `status`, `decline`, `peace`, `accept`, `join`, `history`, `scoreboard`, `siege` |
| `/ally`, `/truce` | Alliances and truces |
| `/bounty` | Put a bounty on a player |
| `/contest` | Building contest: mark, submit, visit, vote |
| `/leaderboard`, `/season` | Rankings and the current season |
| `/spawn`, `/rtp`, `/warp` | Travel |
| `/mine` | Personal mining claims in resource worlds |
| `/guide`, `/civitas rules`, `/playtime`, `/toggle`, `/report` | Guide, rules book, playtime, notification settings, reports |
| `/cityadmin` (`/ca`) | Admin tree; run `/ca` on its own for the subcommand list |

Permissions are declared in `plugin.yml`. Player nodes such as `civitas.use`, `civitas.city.create` and `civitas.market.use` default to everyone; `civitas.admin.*` and `civitas.bypass.*` default to operators. Numeric limit nodes (`civitas.limit.shops.<n>`, `civitas.limit.miningclaims.<n>`) are resolved at runtime.

## Project structure

| Path | Contents |
| --- | --- |
| `src/main/java/dev/civitas/CivitasPlugin.java` | Plugin entry point and service wiring |
| `src/main/java/dev/civitas/core/` | Business logic by domain: city, claim, economy, market, war, defense, siege, diplomacy, contest, events, income, outpost, travel and others |
| `src/main/java/dev/civitas/command/` | Brigadier command trees (player, city, war, diplomacy, admin) |
| `src/main/java/dev/civitas/gui/` | Menu framework and menu screens |
| `src/main/java/dev/civitas/listener/` | Bukkit event listeners (protection, war logging, combat, shops) |
| `src/main/java/dev/civitas/storage/` | Connection pool, SQL dialects, DAOs, row types, migration runner, backups |
| `src/main/java/dev/civitas/api/` | Public read-only API and events |
| `src/main/java/dev/civitas/integration/` | PlaceholderAPI, Vault and bStats hooks |
| `src/main/resources/` | `plugin.yml`, configuration files, `lang/`, `gui/` layouts, `migrations/sqlite` and `migrations/mysql` |
| `src/test/java/` | JUnit and MockBukkit test suite |
| `deploy.sh`, `deploy.ps1` | Build and copy the jar to a local test server |

Commands and menus call services; services own the rules and call DAOs. All database access runs off the server thread.

## Tech stack

- Java 21, Paper API 1.21.x, Brigadier commands through Paper's Lifecycle API
- Gradle (Kotlin DSL) with the Shadow plugin; shaded dependencies are relocated
- HikariCP with SQLite (default) or MySQL/MariaDB via MySQL Connector/J
- Configurate YAML, fastutil, Adventure MiniMessage
- bStats; optional PlaceholderAPI and Vault
- JUnit 5 and MockBukkit for tests

## Further documentation

| File | What it is |
| --- | --- |
| [`SPEC.md`](SPEC.md) | The full technical specification |
| [`PLAN.md`](PLAN.md) | The milestone tracker |
| [`CONFIG.md`](CONFIG.md) | Configuration integrity notes |
| [`MYSQL.md`](MYSQL.md) | The MySQL path, how it was tested and its limits |
| [`COMBAT_BALANCE.md`](COMBAT_BALANCE.md) | Combat balance notes |
| [`PERFORMANCE.md`](PERFORMANCE.md) | Performance measurements |
| [`ANTI_TOXICITY.md`](ANTI_TOXICITY.md) | The rules that keep wars from turning into griefing |
| [`WAR_TEST_PROTOCOL.md`](WAR_TEST_PROTOCOL.md) | The manual war test to run on a real server |
| [`OPEN_QUESTIONS.md`](OPEN_QUESTIONS.md) | Ambiguities found during the build and how they were resolved |
| [`CHANGELOG.md`](CHANGELOG.md) | Changes by milestone |
| [`content/devlog.md`](content/devlog.md) | Development log |

## Status

Version 0.1.0, in development and not released. According to `PLAN.md`, three milestones remain open: the full message catalogue, the complete Italian localisation and the accessibility pass. The manual war protocol in `WAR_TEST_PROTOCOL.md` has not yet been run; it must pass cleanly three times in a row on a live server before a public launch. A known limitation is that a beehive's bees do not survive a war rollback. The MySQL path was verified against MariaDB only.
