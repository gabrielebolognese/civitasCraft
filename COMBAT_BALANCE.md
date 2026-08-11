# Combat balance, SPEC 25.2 Rule 1

> M20a. What was measured, how, and what it found.
>
> PLAN's row asks to "verify Rule 1 empirically… three trials each" and to "record the results in
> the spec". This file is that record. It is a sibling document rather than an edit to `SPEC.md`,
> matching `PERFORMANCE.md`, `MYSQL.md`, `CONFIG.md` and `ANTI_TOXICITY.md` — measured results live
> beside the specification rather than inside it, so the specification stays the thing they are
> measured against.

## The rule

SPEC 25.2 Rule 1, in full:

> A defending city's full garrison, at any Fortification level, must be beatable by an attacking
> force **equal in size to the defender's active member count**, equipped with good gear and
> coordinating. If a configuration exists where this is false, that configuration is a bug.

The last sentence is what decided the shape of this pass. A rule stated over *configurations*
cannot be checked by three trials, because three trials check three configurations. So Rule 1 is
verified here by a deterministic model — `CombatModel` — run over every garrison each budget can
buy, and `CombatBalanceTest` asserts the result.

**This does not replace the live pass.** A model cannot find a unit that never acquires a target,
a pathfinding failure, or a Warden that ignores its confinement. It finds the composition nobody
thought to build. The two are complementary and the live half has not been run.

## The model

`CombatModel` is calibrated against the only combat arithmetic SPEC publishes, and reproduces both
tables exactly rather than approximately:

| Source | What it fixes | Agreement |
|---|---|---|
| SPEC 28.4's damage table | Vanilla armour, toughness and Protection reduction | All 8 cells, to 0.1 |
| SPEC 28.5's sword rows | Sustained DPS at 1.6 swings a second | 28.4s and 34.7s, to 0.1 |

Everything the model omits helps the attacker — no shields, no critical hits, no knockback, no
terrain, no potions beyond sustained regeneration. A garrison it calls beatable is beatable in
play; one it calls unbeatable may still fall.

### Three assumptions that are not SPEC's

Named because the verdict turns on them.

1. **Healing at 0.8 HP/s per attacker.** A raider chaining golden apples holds Regeneration II,
   which is one heart every 25 ticks. This is the conservative end of "good gear"; Notch apples are
   several times better and are not modelled. Its effect is quantified below.
2. **Nine defenders can reach the attackers at once.** Derived from
   `defense.placement.max-units-per-chunk` (3) across roughly three chunks. A garrison of nineteen
   is spread over at least seven chunks by SPEC 27.8's own cap, and an attacker who walks into the
   middle of all of it has chosen to.
3. **A sprinting player moves at 0.33** on SPEC 27's speed scale. Anchored on SPEC 27's own two
   statements — the Warhound at 0.42 is "faster than a sprinting player", the City Guard at 0.28 is
   not — and no unit in the roster has a speed inside that band, so any value there gives the same
   verdicts.

### Per engagement, not per war

The first version of the model resolved a garrison as one continuous fight and concluded that Rule
1 fails at Fortification 2 and above. That was the model being wrong, not the numbers. SPEC 11.2
gives a war **seven days**; Rule 1 says the garrison must be "beatable", not beatable without
withdrawing. An attacking force picks where it engages, kills what it can reach, withdraws, heals,
and returns — which is exactly what "coordinating" in Rule 1 describes.

## Results

Three attackers, which is SPEC 11.3's floor for a defender and therefore the binding case: a bigger
city fields more attackers against the same garrison, because Fortification buys the garrison and
membership does not.

Attacker kit is SPEC 28.4's middle row — full diamond, Protection II, Sharpness III.

| Fortification | Budget | Deadliest garrison | Per-engagement clear | Survive | Margin | Rule 1 |
|---|---|---|---|---|---|---|
| 0 | 100 | 8x Warhound | 8.3s | 32.6s | **3.9x** | holds |
| 2 | 150 | 12x Warhound | 9.4s | 25.3s | **2.7x** | holds |
| 5 | 225 | 19 mixed | 9.4s | 25.3s | **2.7x** | holds |

Every single-unit garrison at every level also holds, and so does a maxed garrison **plus a City
Warden** — which costs nothing against SPEC 25.5's budget and so sits on top of a full roster.

### What healing is worth

| Fortification | Margin with healing | Margin dry |
|---|---|---|
| 0 | 3.9x | 1.7x |
| 2 | 2.7x | 1.3x |
| 5 | 2.7x | 1.3x |

Rule 1 holds in both columns. But 1.3x is close enough that one mistake loses the engagement,
where 2.7x is not — so a raid that turns up without consumables is doing something much harder
than the numbers suggest, and that is worth knowing before tuning anything.

## Findings

### 1. Coordination is load-bearing, not decorative

A maxed garrison fought head-on, standing and trading on the defender's ground, **beats an
equal-numbered force**: 19.4s of work against 8.4s of survival at Fortification 5.

This is not a bug. SPEC 27 writes a stated counterplay for every unit and SPEC 25.2 Rule 3 makes
having one a shipping gate, so "coordinating" in Rule 1 is doing real work. But it means the rule
should be read as *beatable by a force that uses the counterplay*, and a server whose players
charge straight in will experience the defence as much stronger than the design intends.

`CombatBalanceTest.headOnLoses` asserts this **fails**, deliberately. If it ever starts passing,
the roster has drifted to the point where the counterplay stopped mattering.

### 2. The optimal defensive build is Warhound spam, and it is the un-kiteable one

The Warhound has the best damage per point in the roster (6 damage / 12 points = 0.50, against the
City Guard's 0.40 and the Colossus's 0.36) **and** it is the only unit SPEC 27.4 deliberately makes
faster than a sprinting player. So the composition a defender optimising for the fight would build
is also the one immune to the counterplay that every other unit has.

Rule 1 survives it, because the per-chunk cap bounds how many can engage at once and 45 HP dies
fast. It is recorded because it is a structural fact about the roster rather than an accident of
these numbers: any future increase to the Warhound's health or damage attacks Rule 1 from the one
direction the counterplay cannot answer.

### 3. Nothing needed tuning

The pass was expected to produce config changes and did not. Every level passes with a margin above
2.5x under the engagement rules SPEC itself describes. No unit's cost, health, damage or points
value was altered by this milestone.

## What is still unverified

- **The live half.** PLAN asks for three trials at each of three levels on a running server. Not
  run. It is the half that would find a unit that never fights, a Warden that leaves its chunk, or
  a Colossus that cannot be out-walked because of terrain.
- **Ranged units in practice.** The Archer and the Frost Sentry are modelled by their damage
  figures; SPEC 27.5's fire-rate penalty inside 5 blocks and SPEC 27.2's Mining Fatigue are not,
  because neither changes melee DPS. On a live server the Sentry's Mining Fatigue does slow the
  attacker's *block breaking*, which the model has no notion of.
- **The alert network.** SPEC 27.6 pulls every City Guard within 3 chunks, which is roughly the
  concurrency figure the model already uses — so it is represented in effect and not in mechanism.
