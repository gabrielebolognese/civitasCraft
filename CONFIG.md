# Configuration integrity

Every key this plugin ships is read by something. `ConfigKeyUsageTest` fails the build
otherwise.

That test exists because of what happened without it. SPEC 17.1's inactivity rules — an absent
mayor replaced, a dormant city's claims unprotected, a dead city expired — sat unimplemented
for **twenty-one milestones** while `cities.yml` shipped all four of their numbers, each with
the SPEC case it implemented in a comment beside it. The feature looked present to anyone
reading the file. Nothing read a key.

A dead config key is worse than a missing one. An operator who changes a setting and sees no
effect cannot tell that from having typed it wrong.

## What the sweep found

Nineteen keys, in four shapes.

### Mismatched pairs — the worst of them

The file shipped one name, the code read another. **Both sides were inert**, and the value was
permanently stuck at its hardcoded default. Invisible from either side alone.

| Shipped in yml | Code read | Consequence |
|---|---|---|
| `war.yml scoring.city-hall-hold-seconds` | `scoring.city-hall-reach-seconds` | SPEC 11.6's City Hall stand was always 30s |
| `war.yml peace.forfeit-percent` | `declaration.peace-forfeit-percent` | SPEC 8.8's peace forfeit was always 25% |
| *(nothing)* | `cities.yml admin.restore-window-days` | SPEC 9.4.2's restore window was always 14 days; the only shipped copy sat unread under `inactivity:` |

Fixed by moving the code to the shipped name, except the third, where the key was added under
`admin:` because it governs `/ca city delete` as much as SPEC 17.1's expiry.

### Dead twins

The setting existed twice; only one copy was read. An operator finding the other — usually the
one with SPEC's comment on it — got nothing.

- `war.yml allies.wager-percent-of-primary` beside the live `rewards.ally-wager-percent`
- `economy.yml market.war-winner-bonus-percent` / `-days` beside the live `war.yml rewards.winner-market-bonus-*`
- `cities.yml inactivity.restore-window-days` beside `admin.restore-window-days`

Duplicates removed, SPEC's comment moved onto the surviving key.

### Switches that did nothing

The five SPEC 16.3 `rollback.*` flags, shipped from M0 and consulted nowhere. Three now work:
`suppress-block-drops`, `restore-entities`, `restore-container-nbt`.

The other two are **declarations, not switches**. `loot-is-permanent: false` would mean
returning items carried out of a chest during a war and `vault-immune: false` would mean
letting the vault be looted — neither exists, and SPEC describes neither. Inventing them to
make a config key honest would be inventing a feature. So `RollbackPolicy` reads them, and an
operator who sets one to an unsupported value is **told at startup**.

`suppress-block-drops` is honoured *and* warned about, because SPEC 11.8.3 calls the no-drops
rule critical: turning it off means an attacker keeps 50,000 blocks of materials and the
rollback puts the blocks back anyway, creating resources from nothing.

### Settings that were never settings

Removed, because honouring them would be wrong or impossible:

| Key | Why it is gone |
|---|---|
| `economy.yml decimal-places` | Two places is the schema (SPEC 3's `DECIMAL(20,2)`), not a preference. Changing it corrupts every balance. |
| `economy.yml player-shops.tax-percent` | SPEC 15.2 lists untaxed shops as an **anti-toxicity mechanism**. A switch to disable one of those is not something to ship because a file looked incomplete. |
| `economy.yml bounties.claimable-only-during-war` | Same shape: SPEC 4.7 makes the war-only rule deliberate, "so bounties cannot be used to fund random murder". |
| `events.yml contests.entries-per-city` | Structural. One entry row exists per contest and city; the number could never be anything but 1. |
| `events.yml contests.vote-axes` | Structural. The three axes are a Java enum. |
| `war.yml scoring.capture-point-visible-range` | SPEC 11.6's particle column **is not implemented**. Shipping its range suggested it was. |

### SPEC 16.1 keys kept and made honest

Three keys SPEC 16.1 mandates, which this build cannot honour as written. Removing them would
deviate from SPEC; leaving them dead was the bug. So:

- **`performance.claim-cache-size`** is a **warning threshold, not a cap**. Evicting a claim
  means the chunk reads as wilderness on the next block event — somebody's city losing its
  protection to save a few kilobytes, against SPEC 17.7 case 81's measured 2.5 MB for 50,000
  claims. The server logs once if the cache exceeds it.
- **`performance.ledger-batch-size`** and **`ledger-flush-seconds`** are not honoured, and the
  server says so at startup. SPEC 1.5 makes the ledger the authority for every dispute, and a
  row waiting in a buffer is a row a crash loses, so it is written inside the transaction that
  moves the money.

## How the test decides

A key counts as read if **any suffix of its path** appears as a string literal in
`src/main/java`. Suffixes, because paths are routinely built: `defense.getDouble(path +
".speed")` for a unit, `section.getInt("weight")` for an iterated rank.

That is deliberately generous. This catches a key whose name appears **nowhere**, which is the
strongest cheap signal and the one all nineteen findings tripped. It cannot catch a dead key
whose leaf name coincides with a live one elsewhere, and does not claim to — the second test,
`noDeadTwins`, covers the specific case of one concept shipped under two names.

The allow-list holds exactly two entries, both enchantment levels inside a section the code
iterates with `getKeys(false)`, so the operator can add `SHARPNESS` without a code change.
An allow-list is where a test like this goes to die; anything added to it should be a value an
operator invents, never a setting somebody forgot to wire.
