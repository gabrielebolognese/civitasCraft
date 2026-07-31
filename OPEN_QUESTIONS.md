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
