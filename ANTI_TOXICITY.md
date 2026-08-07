# Anti-toxicity mechanisms

SPEC Section 15, audited in M22. Every mechanism below is enforced in code, configurable from
a yml file, and covered by a test that proves both.

The second column is what an operator changes. The fourth is the test that fails if the
mechanism stops working — not merely if the config key disappears, which is a weaker thing to
check and the reason this audit exists at all.

## Why this document exists

A config key with nothing reading it looks exactly like a working feature. This project has
already shipped two of those and caught them later: SPEC 17.6 case 79's percentile rule looked
implemented and could never fire, and SPEC 17.4 case 46 held by coincidence of the ownership
rule rather than by its own. Both passed every test written at the time.

So each mechanism is asserted twice: that the rule refuses, **and** that changing the key
changes the behaviour. A rule enforced with a literal `14` passes the first and fails the
second.

## SPEC 15.1 — newcomer protection

| Mechanism | Config key | Enforced in | Proved by |
|---|---|---|---|
| Personal income ×1.5 for 14 days | `economy.yml` `income.newcomer.multiplier`, `.days` | `IncomeMultipliers` | `Spec15AuditTest.incomeBonus`, `.windowIsConfigurable` |
| Claim costs ×0.75 for cities under 14 days | `cities.yml` `claims.new-city-discount`, `.new-city-days` | `ClaimCostEngine.newcomerMultiplier` | `.youngCityDiscount`, `.discountReachesThePrice` |
| Cities under 5 members exempt from cities over 20 | `war.yml` `declaration.large-vs-small-block`, `.large-city-member-threshold`, `.small-city-member-threshold` | `WarService.checkSizeMismatch` | `.largeCannotFarmSmall`, `.sizeMismatchIsConfigurable` |

The third rule is asymmetric on purpose: a small city may still declare on a large one. The
test asserts that direction explicitly, because reversing it would protect exactly the wrong
cities and nothing else would notice.

## SPEC 15.2 — structural protections

| Mechanism | Prevents | Config key | Proved by |
|---|---|---|---|
| Rollback | Permanent build loss | `war.yml` `rollback.*` | `Spec18ProtocolTest`, `RollbackEngineTest`, `OverlappingWarsTest` |
| War immunity, 7 days | Serial harassment of one city | `war.yml` `rewards.immunity-days` | `Spec15AuditTest.immunity` |
| 21-day same-opponent cooldown | Targeted bullying | `war.yml` `declaration.same-opponent-cooldown-days` | `.rematchCooldown` |
| Wager capped at 25% of the **smaller** treasury | Wealth-based coercion | `war.yml` `declaration.max-wager-percent-of-smaller-treasury` | `.wagerCap` |
| Defender decline option | Forced participation | `war.yml` `declaration.decline-window-hours`, `.decline-penalty-percent` | `.declineWindow` |
| Member divisor on claim cost | Solo whales outpacing communities | `cities.yml` `claims.member-divisor-per-member` | `.memberDivisor` |
| Several leaderboards | Wealth being the only status ladder | — (nine boards, see note) | `.manyLeaderboards` |
| No passive income from land | Rich-get-richer compounding | — (absence, plus upkeep) | `.noPassiveIncome` |
| Dynamic market pricing | One player monopolising an income source | `economy.yml` `market.*` per item | `.dynamicPricing` |
| Player shops untaxed | Encouraging inter-city trade | — (no tax path exists) | `.shopsUntaxed` |
| Minimum 3 members to declare war | Alt-account war spam | `war.yml` `declaration.min-members` | `.minimumMembers` |
| 24h city-switch cooldown | Mercenary hopping | `cities.yml` `members.switch-cooldown-hours` | `.switchCooldown` |
| Maximum 3 allies | Server-wide dominant blocs | `cities.yml` `diplomacy.max-allies` | `.allyCap` |

### Two rows worth reading twice

**"Wager capped at 25% of the smaller treasury."** The word *smaller* is the mechanism. Capping
against the attacker's treasury would let a rich city name a figure the defender cannot match,
which is the coercion the row exists to prevent rather than a defence against it.

**"Player shops untaxed."** M22 recorded this as an explicit `0` in the config and claimed
"an operator who wants to tax shops can". **That was wrong**, and the M23 config sweep found
it: `player-shops.tax-percent` was read by nothing, so the zero was decoration. The key is gone
and the guarantee is now the stronger one — there is no code path that could take a cut, so the
rate cannot drift from zero by configuration or by accident.

### The leaderboard count

SPEC 15.2 says "seven leaderboards" and SPEC 13.3's table lists **nine**. M14 implemented all
nine, because only the table says *which*, and dropping two would have meant choosing which two
on no authority. The audit asserts what SPEC 15.2 is actually protecting — that there is more
than one ladder and that wealth is not the only one — rather than a count the specification
gives twice and differently. Recorded in `OPEN_QUESTIONS.md` at M14.

## SPEC 15.3 — reporting

Built in M21. `/report` files to a queue, `/ca reports` reads it, and the reported player's
recent ledger and war activity are attached when a moderator opens the report rather than
copied in when it is filed. Proved by `ReportServiceTest`.

`/report` is rate-limited, which SPEC does not specify: an unthrottled report command lets one
player bury the queue and makes the feature useless for everybody. `config.yml`
`moderation.reports-per-window` and `.report-window-hours`.

## What this audit does not cover

- **Rollback** has no assertion in `Spec15AuditTest`. It has three milestones of its own —
  M18 built it, M20 hardened it, and `Spec18ProtocolTest` drives it end to end — and duplicating
  that here would add a weaker test of the same thing.
- **SPEC 18.3's manual protocol has not been run.** Four elements of a rollback (chest contents,
  sign text, banner patterns, spawner types) cannot be verified in CI at all; see
  `WAR_TEST_PROTOCOL.md`. Anti-toxicity's headline mechanism is therefore proved by automated
  tests and by a protocol nobody has executed yet.
