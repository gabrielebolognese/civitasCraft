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
