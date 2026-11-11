# CivitasCraft

A Minecraft server plugin for city-building servers: claims, an economy, wars with full world rollback, custom mobs and contests.

It runs on Paper 1.21.x with Java 21 and is server-side only, so players need no client mod.

## Features

- **Cities and claims:** players found a city, claim 16x16 chunks around a core chunk, and hold up to 4 detached outposts
- **Ranks:** Mayor, Co-Mayor, Architect, Citizen and Recruit, each with its own permissions
- **Economy:** a coin currency, city treasuries, daily upkeep based on land value, income and a market
- **Wars:** a 7-day war week between two cities, with sieges and defenses, after which the world is restored to its pre-war state
- **Diplomacy, contests and events**
- **Admin tools:** an append-only ledger of transactions for auditing
- **Menus:** an in-game GUI alongside player, city and admin commands

Every numeric value is a default exposed in `config.yml`, not a hardcoded constant.

## Building

```bash
./gradlew build
```

The shaded plugin jar is written to `build/libs/`. Copy it into the `plugins/` folder of a Paper 1.21.x server.

## Storage

SQLite by default, or MySQL (see [`MYSQL.md`](MYSQL.md)). Migrations for both live in `src/main/resources/migrations/`.

PlaceholderAPI and Vault are optional: the plugin works on a server that has neither.

## Documentation

| File | What it is |
| --- | --- |
| [`SPEC.md`](SPEC.md) | The full technical specification and the source of truth |
| [`PLAN.md`](PLAN.md) | The milestone tracker |
| [`CONFIG.md`](CONFIG.md) | Configuration reference |
| [`COMBAT_BALANCE.md`](COMBAT_BALANCE.md) | Combat balance notes |
| [`PERFORMANCE.md`](PERFORMANCE.md) | Performance notes |
| [`ANTI_TOXICITY.md`](ANTI_TOXICITY.md) | Rules that keep wars from turning into griefing |
| [`WAR_TEST_PROTOCOL.md`](WAR_TEST_PROTOCOL.md) | The manual war test to run on a real server |
| [`OPEN_QUESTIONS.md`](OPEN_QUESTIONS.md) | Ambiguities found during the build |
| [`CHANGELOG.md`](CHANGELOG.md) | Changes by version |

## Status

Version 0.1.0, in development. The war rollback has parts that only a real server can prove, so `WAR_TEST_PROTOCOL.md` must pass cleanly three times in a row before a public launch.
