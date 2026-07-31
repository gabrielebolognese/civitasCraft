# Changelog

All notable changes to CivitasCraft. One section per milestone from `PLAN.md`.

## [Unreleased]

### M0, Project skeleton

Added:
- Gradle (Kotlin DSL) build with the Gradle 9.6.1 wrapper, a Java 21 toolchain and a
  shaded jar. Warnings are errors (`-Werror -Xlint:all`).
- `CivitasPlugin` entry point. Enables and disables cleanly with no state to unwind yet.
- `ConfigManager` and the six configuration files from SPEC 16: `config.yml`, `cities.yml`,
  `economy.yml`, `war.yml`, `defense.yml`, `events.yml`. Every numeric value in SPEC.md is
  a key in one of them. In-jar defaults are installed as the defaults tree on every load, so
  a key added by an update still resolves against an operator's older file.
- `LangManager` with `lang/en.yml` and `lang/it.yml`, MiniMessage rendering, and lookup that
  falls back active language to English to a visible missing-key marker. Placeholder values
  are inserted unparsed so player-supplied text cannot inject formatting.
- `Result<T>`, the sealed `Success` / `Failure` type that every service mutation returns
  (SPEC 2.3).
- The root command tree, registered through Brigadier and Paper's Lifecycle API. Every
  command from SPEC 9 is registered and permission-gated; each replies that it is not
  implemented yet, naming the milestone that will fill it in.
- `plugin.yml` with every permission node from SPEC 10 at its documented default.
- Tests: SPEC config defaults, language completeness across both languages, permission-node
  declarations, and `Result` semantics.
