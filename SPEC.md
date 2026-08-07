# CivitasCraft, Technical Specification v1.0

> **Working name:** CivitasCraft
> **Platform:** Paper 1.21.x, Java 21
> **Type:** Server-side plugin (no client mod required)
> **Status:** Design specification, pre-implementation

---

## 0. How to use this document

This document is the single source of truth for the plugin. It is written to be read by an AI coding agent (Claude Code) across multiple independent sessions, and by a human developer.

**Rules for the agent:**

1. Read this entire file at the start of every session, plus `PLAN.md`.
2. Never invent behaviour that is not specified here. If something is genuinely ambiguous, add an entry to `OPEN_QUESTIONS.md` and implement the most conservative option.
3. Every numeric value in this document is a **default config value**, not a hardcoded constant. All of them must be exposed in `config.yml`.
4. Update `PLAN.md` after every completed milestone. Commit to git after every milestone that compiles and passes tests.
5. Never modify Section 3 (Data Model) without also writing a migration.

**Terminology used throughout:**

| Term | Meaning |
|---|---|
| City | The primary player organisation. Owns claims, a treasury, members. |
| Claim | A single 16x16 chunk owned by a city. |
| Core Chunk | The founding chunk of a city. Cannot be unclaimed. Contains the City Hall. |
| Outpost | A detached claim, not adjacent to the city body. Max 4 per city. |
| Mayor | The single owner of a city. |
| Rank | A permission group inside a city (Mayor, Co-Mayor, Architect, Citizen, Recruit). |
| Coin | The currency unit. Displayed as `C`. |
| Upkeep | Daily treasury cost proportional to the city's land value. |
| War Week | The 7-day window during which two cities may grief each other. |
| Rollback | Automatic restoration of the world to its pre-war state at war end. |
| Ledger | The append-only transaction log used for admin fraud auditing. |

---

## 1. Design pillars

These five pillars decide every ambiguous design call. When two features conflict, the pillar higher in this list wins.

### 1.1 No hard walls

The single biggest failure of existing claim plugins is exponential claim cost, which makes expansion impossible past roughly 50 chunks. This plugin uses **polynomial** cost growth (`n^1.25`), not exponential. A 500-chunk city is expensive but genuinely reachable. Growth should always feel like a slope, never a cliff.

### 1.2 Destruction is never permanent

War exists so cities can fight without anyone quitting the server over lost builds. Every block broken in war is restored. The stakes of war are **reputation, ranking, and money**, never blocks. This is the plugin's defining feature and its highest-risk subsystem.

### 1.3 A new player must be able to matter

A player joining on day 90 must be able to contribute meaningfully within their first session. This is achieved through:
- Pooled city treasuries, so contribution is additive
- Claim cost divided by active member count, so recruiting beats hoarding
- Multiple leaderboards, so wealth is not the only axis of status
- Catch-up multipliers for new and small cities

### 1.4 Building and farming are the point

Combat is a scheduled event, not the default state of the world. Outside of declared wars, the world is fully protected. Income is dominated by production (farming, crafting, trading), not by killing.

### 1.5 Everything is auditable

Every coin movement, every claim, every war action is logged to an append-only ledger. Admins can reconstruct any dispute. This is not optional, it is a core requirement for running a competitive server without constant drama.

---

## 2. Technical stack and project structure

### 2.1 Stack

| Component | Choice | Reason |
|---|---|---|
| Server API | Paper 1.21.x | Modern API, Adventure, Brigadier, better async |
| Language | Java 21 | Records, sealed interfaces, pattern matching, virtual threads |
| Build | Gradle (Kotlin DSL) | Paperweight-userdev support |
| Database | SQLite (default), MySQL/MariaDB (optional) | Single-server default, scalable option |
| Connection pool | HikariCP | Standard |
| Config | Paper's built-in YAML + Configurate for complex trees | |
| Text | Adventure + MiniMessage | All player-facing text, no legacy color codes |
| Commands | Brigadier via Paper Lifecycle API | Native tab completion, argument validation |
| Testing | JUnit 5 + MockBukkit | Unit tests for all pure logic |
| Scheduling | Paper's regionised scheduler where applicable, Bukkit scheduler otherwise | |

**Hard rules:**
- Zero database access on the main thread. Ever. All I/O is async, results are applied back on the main thread.
- All player-facing strings live in `lang/en.yml` and `lang/it.yml`. No hardcoded strings.
- No NMS unless unavoidable. If required, isolate it behind an interface with a version-specific implementation.

### 2.2 Project structure

```
civitascraft/
├── build.gradle.kts
├── settings.gradle.kts
├── SPEC.md                        # this file
├── PLAN.md                        # milestone tracker, agent-maintained
├── OPEN_QUESTIONS.md              # agent-maintained
├── CHANGELOG.md
└── src/main/
    ├── java/dev/civitas/
    │   ├── CivitasPlugin.java             # entry point, lifecycle
    │   ├── api/                           # public API for other plugins
    │   ├── core/
    │   │   ├── city/                      # City, Member, Rank, CityService
    │   │   ├── claim/                     # Claim, ClaimService, cost engine
    │   │   ├── outpost/
    │   │   ├── economy/                   # Balance, Treasury, Ledger, Market
    │   │   ├── war/                       # WarService, BlockLogger, Rollback
    │   │   ├── diplomacy/                 # Alliance, Truce, Relation
    │   │   ├── defense/                   # Custom mobs, city defense
    │   │   ├── progression/               # Quests, challenges, contests
    │   │   └── events/                    # Server-wide scheduled events
    │   ├── gui/
    │   │   ├── framework/                 # Menu, Button, PaginatedMenu, Session
    │   │   └── menus/                     # One class per screen
    │   ├── command/
    │   │   ├── player/
    │   │   ├── city/
    │   │   └── admin/
    │   ├── listener/
    │   ├── storage/
    │   │   ├── dao/
    │   │   └── migration/                 # V1__init.sql, V2__..., etc.
    │   ├── config/
    │   ├── lang/
    │   └── util/
    └── resources/
        ├── plugin.yml
        ├── config.yml
        ├── cities.yml
        ├── economy.yml
        ├── war.yml
        ├── defense.yml
        ├── events.yml
        ├── gui/                           # one yml per menu, fully data-driven
        └── lang/
```

### 2.3 Architectural rules

- **Service layer pattern.** Commands and GUIs never touch DAOs directly. They call services. Services own all business logic and validation.
- **Every mutation returns a `Result<T>`,** a sealed type of `Success(T)` or `Failure(reason, messageKey)`. No exceptions for expected failures.
- **Cache-first.** Cities, claims, and balances live in memory. The database is a persistence target, not a read path. Claims are stored in a `Long2ObjectMap<Claim>` keyed by a packed `(worldId, chunkX, chunkZ)` long for O(1) lookup, because this is queried on every block event.
- **Event-driven.** The plugin fires its own custom events (`CityCreateEvent`, `ChunkClaimEvent`, `WarDeclareEvent`, etc.) all cancellable, so future modules and third-party plugins hook cleanly.

---

## 3. Data model

All tables use `snake_case`. All timestamps are UTC epoch millis (`BIGINT`). All UUIDs are stored as `CHAR(36)`.

### 3.1 `players`

| Column | Type | Notes |
|---|---|---|
| uuid | CHAR(36) PK | |
| last_known_name | VARCHAR(16) | Updated on join |
| balance | DECIMAL(20,2) | Personal wallet |
| city_id | INT NULL | FK cities.id |
| rank_id | INT NULL | FK city_ranks.id |
| first_join | BIGINT | |
| last_seen | BIGINT | |
| total_playtime_ms | BIGINT | |
| active_playtime_ms | BIGINT | Anti-AFK filtered, drives stipend |
| daily_streak | INT | |
| last_daily_claim | BIGINT | |
| newcomer_until | BIGINT | Timestamp when catch-up bonus expires |
| frozen | BOOLEAN | Admin-frozen, cannot transact |

### 3.2 `cities`

| Column | Type | Notes |
|---|---|---|
| id | INT PK AUTOINCREMENT | |
| name | VARCHAR(24) UNIQUE | Case-insensitive unique |
| display_name | VARCHAR(48) | MiniMessage allowed, admin-approved |
| tag | VARCHAR(5) UNIQUE | Short prefix shown in chat |
| mayor_uuid | CHAR(36) | |
| founded_at | BIGINT | |
| treasury | DECIMAL(20,2) | |
| core_world | VARCHAR(64) | |
| core_chunk_x | INT | |
| core_chunk_z | INT | |
| spawn_x, spawn_y, spawn_z | DOUBLE | Must be inside a claim |
| spawn_yaw, spawn_pitch | FLOAT | |
| open_join | BOOLEAN | If true, anyone may join without invite |
| motd | VARCHAR(128) | |
| upkeep_due | BIGINT | Next upkeep charge timestamp |
| delinquent_since | BIGINT NULL | Non-null if upkeep unpaid |
| war_protection_until | BIGINT | Post-war immunity timestamp |
| frozen | BOOLEAN | Admin freeze, blocks all mutations |
| deleted_at | BIGINT NULL | Soft delete, 14-day restore window |

### 3.3 `city_ranks`

| Column | Type | Notes |
|---|---|---|
| id | INT PK | |
| city_id | INT FK | |
| name | VARCHAR(16) | |
| weight | INT | Higher outranks lower. Mayor is always 100. |
| permissions | BIGINT | Bitmask, see Section 5.4 |
| is_default | BOOLEAN | Rank assigned to new joiners |

### 3.4 `claims`

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | |
| city_id | INT FK | |
| world | VARCHAR(64) | |
| chunk_x | INT | |
| chunk_z | INT | |
| claimed_at | BIGINT | |
| claimed_by | CHAR(36) | |
| cost_paid | DECIMAL(20,2) | Used for refund calculation |
| type | ENUM('CORE','NORMAL','OUTPOST') | |
| outpost_id | INT NULL | FK outposts.id, non-null if type=OUTPOST |

**Unique index on `(world, chunk_x, chunk_z)`.** This is the physical guarantee that two cities can never own the same chunk.

### 3.5 `outposts`

| Column | Type | Notes |
|---|---|---|
| id | INT PK | |
| city_id | INT FK | |
| name | VARCHAR(24) | |
| tp_x, tp_y, tp_z | DOUBLE | |
| tp_yaw, tp_pitch | FLOAT | |
| created_at | BIGINT | |

### 3.6 `ledger` (append-only, never UPDATE or DELETE)

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | |
| timestamp | BIGINT | |
| type | VARCHAR(32) | See Section 4.6 for full type list |
| actor_uuid | CHAR(36) NULL | Null for system transactions |
| target_uuid | CHAR(36) NULL | |
| city_id | INT NULL | |
| amount | DECIMAL(20,2) | Signed |
| balance_after | DECIMAL(20,2) | Snapshot for reconciliation |
| metadata | TEXT | JSON blob, context-specific |

### 3.7 `wars`

| Column | Type | Notes |
|---|---|---|
| id | INT PK | |
| attacker_city_id | INT | |
| defender_city_id | INT | |
| declared_at | BIGINT | |
| prep_ends_at | BIGINT | |
| war_ends_at | BIGINT | |
| state | ENUM('PREP','ACTIVE','ROLLING_BACK','RESOLVED','CANCELLED') | |
| attacker_score | INT | |
| defender_score | INT | |
| winner_city_id | INT NULL | |
| wager | DECIMAL(20,2) | Escrowed from both treasuries |
| rollback_completed_at | BIGINT NULL | |

### 3.8 `war_block_log` (the rollback engine, highest write volume)

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | |
| war_id | INT | |
| sequence | BIGINT | Monotonic, defines replay order |
| world | VARCHAR(64) | |
| x, y, z | INT | |
| old_block_data | TEXT | Serialized BlockData string |
| new_block_data | TEXT | |
| old_nbt | BLOB NULL | Serialized TileEntity NBT if applicable |
| actor_uuid | CHAR(36) NULL | |
| timestamp | BIGINT | |

**Composite index on `(war_id, sequence DESC)`** because rollback replays in reverse order.

### 3.9 Remaining tables

| Table | Purpose |
|---|---|
| `city_members` | uuid, city_id, rank_id, joined_at, contributed_total |
| `city_invites` | city_id, invitee_uuid, inviter_uuid, expires_at |
| `alliances` | city_a_id, city_b_id, state, formed_at |
| `truces` | city_a_id, city_b_id, expires_at |
| `war_participants` | war_id, city_id, side, is_ally |
| `war_kills` | war_id, killer_uuid, victim_uuid, timestamp, location |
| `market_stock` | material, current_stock, target_stock, base_price |
| `player_quests` | uuid, quest_id, progress, assigned_at, completed_at |
| `contests` | id, theme, starts_at, ends_at, state |
| `contest_entries` | contest_id, city_id, plot_region, submitted_at, score |
| `contest_votes` | contest_id, voter_uuid, entry_id, score |
| `city_upgrades` | city_id, upgrade_key, level |
| `defense_units` | id, city_id, type, spawn_x/y/z, upkeep, active |
| `audit_log` | Admin actions only, separate from ledger |

---

## 4. Economy

### 4.1 Design intent

The economy must produce these properties:

1. **Income scales with effort, not with wealth.** No passive compounding. A rich player does not earn faster than a poor one for the same activity.
2. **Land is a sink, not an asset.** Claims cost money to acquire and money to keep. Land does not generate income directly.
3. **Cooperation beats hoarding.** A 10-person city expands roughly 2.6x cheaper per chunk than a solo player.
4. **Prices self-correct.** The server market uses dynamic pricing so the first player to sell pumpkins gets rich and the hundredth does not.

### 4.2 Income sources

| Source | Amount | Cap | Notes |
|---|---|---|---|
| Starting balance | 2,000 C | once | On first join |
| Active playtime stipend | 40 C per 15 min | 640 C/day | Requires activity check, see 4.2.1 |
| Daily login | 250 C, +125 per streak day, max 1,000 C | 1/day | Streak resets after 48h absence |
| Server market sales | Dynamic, see 4.4 | none | Primary income source |
| Player shops | Peer-to-peer | none | See 4.5 |
| Daily quests | 3 quests, 300 to 800 C each | 3/day | See Section 13.1 |
| Weekly challenges | 2 challenges, 2,500 to 8,000 C | 2/week | |
| Building contest | 1st: 50,000 C, 2nd: 25,000 C, 3rd: 12,000 C | biweekly | Paid to city treasury |
| War victory | The escrowed wager pool | per war | See Section 11.9 |
| Bounties | Player-set | none | See 4.7 |
| Newcomer bonus | All income x1.5 | first 14 days | See Section 16.1 |

#### 4.2.1 Anti-AFK activity check

The playtime stipend only accrues during a 15-minute interval in which the player performed at least **three** of the following distinct actions:
- Moved more than 32 blocks cumulative distance
- Broke a block
- Placed a block
- Opened an inventory
- Sent a chat message or command
- Damaged or was damaged by an entity

This is deliberately loose enough that a genuinely active farmer always qualifies, and tight enough that a water-clock AFK machine does not. Rotating in place, jump-clicking, and single-key macros all fail the check.

**Config:** `economy.stipend.required-actions: 3`, `economy.stipend.interval-minutes: 15`

### 4.3 Money sinks

| Sink | Amount |
|---|---|
| City creation | 10,000 C |
| Chunk claim | See 6.2 |
| Daily upkeep | 0.4% of total land value per day |
| Outpost creation | 25,000 C, plus 3x the normal chunk cost |
| Outpost upkeep | 2,000 C/day each |
| War declaration | 50,000 C minimum wager, matched by defender |
| Defense unit purchase | 8,000 to 60,000 C, see Section 12 |
| Defense unit upkeep | 400 to 3,000 C/day each |
| City upgrades | 20,000 to 500,000 C, see 5.7 |
| Market sale tax | 5% of sale value, deleted from circulation |
| City rename | 15,000 C |
| Outpost teleport | 100 C per use |
| Fast city spawn teleport | Free, 30s cooldown |

**Design note:** total daily sink for a mature 100-chunk, 10-member city is roughly 22,700 C upkeep plus defense costs, against roughly 60,000 to 100,000 C daily member income. Margin is deliberately positive but not huge, so a city that stops playing slowly bleeds out rather than existing forever as an abandoned monument.

### 4.4 Server market (dynamic pricing)

The server market is an infinite buyer and seller with prices that respond to supply. This is what prevents one crop becoming the "meta" and killing the rest of the economy.

**Price formula:**

```
price = base_price * clamp( (target_stock / (current_stock + 1)) ^ elasticity , 0.25 , 3.0 )
```

- `base_price`: the reference value of the item
- `target_stock`: equilibrium supply, per item
- `current_stock`: how many the market currently holds
- `elasticity`: default 0.45, tunable per item

**Behaviour:** selling increases `current_stock`, which drops the price. Buying decreases it, raising the price. Stock decays toward `target_stock` at 2% per hour, so prices recover overnight and no item stays permanently dead.

**Buy/sell spread:** the market buys from players at `price` and sells to players at `price * 1.35`. This spread is the arbitrage guard.

**Example item table (config-defined, this is a starting point, not exhaustive):**

| Item | base_price | target_stock | elasticity |
|---|---|---|---|
| Wheat | 3 | 20,000 | 0.40 |
| Carrot | 3 | 20,000 | 0.40 |
| Potato | 3 | 20,000 | 0.40 |
| Pumpkin | 5 | 12,000 | 0.45 |
| Melon Slice | 2 | 30,000 | 0.40 |
| Sugar Cane | 4 | 15,000 | 0.45 |
| Cocoa Beans | 6 | 8,000 | 0.50 |
| Bamboo | 1 | 40,000 | 0.35 |
| Iron Ingot | 45 | 6,000 | 0.55 |
| Gold Ingot | 70 | 4,000 | 0.55 |
| Diamond | 400 | 1,500 | 0.60 |
| Emerald | 250 | 2,000 | 0.60 |
| Netherite Scrap | 3,500 | 200 | 0.70 |
| Oak Log | 4 | 25,000 | 0.40 |
| Stone | 1 | 50,000 | 0.30 |
| Beef (raw) | 8 | 10,000 | 0.45 |
| Leather | 12 | 6,000 | 0.50 |
| Honey Bottle | 25 | 3,000 | 0.55 |
| Nether Wart | 8 | 8,000 | 0.50 |

**Hard rule:** the market must never buy items obtainable from a fully automatic zero-input farm at a price that makes AFK farming the top income source. Every item added to the market table must be reviewed against this. Items explicitly **excluded** from the market: cobblestone from generators, items from raid farms, gunpowder from mob farms, and anything produced by an iron farm. These are tradeable player-to-player but the server does not buy them.

### 4.5 Player shops

Players may create chest shops inside claims where they have `BUILD` permission.

- Created by placing a sign on a chest with the format specified below
- Line 1: `[Shop]`
- Line 2: quantity (integer)
- Line 3: `B <price>` to buy, `S <price>` to sell, or `B <p> : S <p>` for both
- Line 4: auto-filled with owner name

Shop transactions are logged to the ledger with type `PLAYER_SHOP`. There is no tax on player shops, deliberately, to make the peer economy more attractive than the server market and encourage actual trade between cities.

### 4.6 Ledger transaction types

Every one of these writes a ledger row. This list is exhaustive and must be kept in sync with the `TransactionType` enum.

```
STARTING_BALANCE, PLAYTIME_STIPEND, DAILY_LOGIN, QUEST_REWARD, CHALLENGE_REWARD,
CONTEST_PRIZE, MARKET_SELL, MARKET_BUY, MARKET_TAX, PLAYER_SHOP, PLAYER_PAY,
CITY_CREATE_FEE, CITY_RENAME_FEE, CHUNK_CLAIM, CHUNK_UNCLAIM_REFUND,
OUTPOST_CREATE, OUTPOST_TELEPORT_FEE, UPKEEP_CHARGE, UPKEEP_FAILED,
TREASURY_DEPOSIT, TREASURY_WITHDRAW, DEFENSE_PURCHASE, DEFENSE_UPKEEP,
UPGRADE_PURCHASE, WAR_WAGER_ESCROW, WAR_WAGER_PAYOUT, WAR_WAGER_REFUND,
BOUNTY_PLACE, BOUNTY_CLAIM, BOUNTY_REFUND, EVENT_REWARD,
ADMIN_GIVE, ADMIN_TAKE, ADMIN_SET, ADMIN_ROLLBACK
```

### 4.7 Bounties

Any player may place a bounty on any other player: `/bounty <player> <amount>`, minimum 1,000 C. The money is escrowed immediately. The bounty is claimed by whoever kills the target **during an active war**, deliberately, so bounties cannot be used to fund random murder outside of the sanctioned combat window. Bounties expire after 30 days and refund.

### 4.8 Inflation control

The plugin tracks total circulating currency and logs it hourly. If circulation grows more than 15% week-over-week, an admin warning is broadcast to console. The primary automatic control is the market tax plus upkeep. There is no automatic money deletion beyond those two, deliberately, because silent balance reduction destroys player trust.

---

## 5. City system

### 5.1 Creating a city

**Command:** `/city create <name>`

**Preconditions, checked in this order:**

1. Player is not already in a city
2. Player has been on the server at least 2 hours of active playtime (anti-alt spam)
3. Player balance >= 10,000 C
4. Name is 3 to 24 characters, matches `^[A-Za-z0-9_]+$`
5. Name is not taken (case-insensitive) and not on the blocked-names list
6. Current chunk is unclaimed
7. Current chunk is at least `min-city-distance` (default 5 chunks) from any other city's nearest claim
8. Current chunk is not inside a protected world or admin region
9. Current world is a `city-enabled` world (config list)

**On success:**

1. Deduct 10,000 C, ledger type `CITY_CREATE_FEE`
2. Create city row, treasury = 0
3. Claim current chunk as `CORE`, cost 0
4. Create the five default ranks (see 5.4)
5. Assign player as Mayor
6. Set city spawn to the player's current location
7. Place a **City Hall** block at the player's feet if the block there is air or replaceable, otherwise instruct the player to place it manually (they receive the item). See Section 8.1.
8. Broadcast: `<gold>The city of <name> has been founded by <player>!`
9. Fire `CityCreateEvent`

### 5.2 Joining a city

Two paths:

**Invite (default):** Mayor or a rank with `INVITE` permission runs `/city invite <player>`. The invitee receives a clickable MiniMessage prompt and has 5 minutes to `/city accept <city>` or `/city deny <city>`. Invites are stored in `city_invites` and expire automatically.

**Open join:** if the city has `open_join = true`, any player may run `/city join <name>` with no invite. Mayors toggle this in the Settings menu.

**Preconditions for joining:** not currently in a city, not on the city's ban list, city is not at its member cap (see 5.7 upgrades), city is not frozen.

**Cooldown:** a player who leaves a city cannot join a different city for 24 hours. This prevents war-day mercenary hopping. Rejoining the *same* city has no cooldown.

### 5.3 Leaving, kicking, and transferring

- `/city leave` requires typing `/city leave confirm`. The Mayor cannot leave, they must transfer or disband.
- `/city kick <player>` requires `KICK` permission and outranking the target.
- `/city transfer <player>` transfers mayorship. Requires the target to be online and to accept within 60 seconds. Logged to audit log.
- `/city disband` requires typing the city name exactly as confirmation. Soft-deletes the city (14-day admin restore window), unclaims all chunks, and refunds **50%** of `cost_paid` on each claim to the mayor's personal balance.

**Disband is blocked entirely if the city is in an active war or in `PREP` state.**

### 5.4 Ranks and permission bitmask

Five default ranks, fully editable by the Mayor.

| Rank | Weight | Default permissions |
|---|---|---|
| Mayor | 100 | ALL |
| Co-Mayor | 80 | All except DISBAND, TRANSFER, MANAGE_RANKS |
| Architect | 60 | BUILD, CONTAINER, CLAIM, SET_SPAWN, OUTPOST_TP, DEPOSIT |
| Citizen | 40 | BUILD, CONTAINER, OUTPOST_TP, DEPOSIT |
| Recruit | 20 | CONTAINER (read-only chests), DEPOSIT, OUTPOST_TP |

**Permission bitmask flags:**

```
BUILD              1 << 0    Place and break blocks in claims
CONTAINER          1 << 1    Open chests, furnaces, hoppers
CONTAINER_READONLY 1 << 2    Open but not remove items
INTERACT           1 << 3    Doors, buttons, levers, beds
CLAIM              1 << 4    Claim new chunks (spends treasury)
UNCLAIM            1 << 5    Unclaim chunks
INVITE             1 << 6    Invite players
KICK               1 << 7    Kick players
MANAGE_RANKS       1 << 8    Create, edit, assign ranks
DEPOSIT            1 << 9    Deposit into treasury
WITHDRAW           1 << 10   Withdraw from treasury
SET_SPAWN          1 << 11   Move city spawn
OUTPOST_MANAGE     1 << 12   Create and delete outposts
OUTPOST_TP         1 << 13   Teleport to outposts
DECLARE_WAR        1 << 14   Declare war
MANAGE_DIPLOMACY   1 << 15   Alliances, truces
MANAGE_DEFENSE     1 << 16   Buy and place defense units
MANAGE_UPGRADES    1 << 17   Purchase city upgrades
EDIT_SETTINGS      1 << 18   MOTD, open join, display name
CONTEST_SUBMIT     1 << 19   Submit city entry to contests
TRANSFER           1 << 20   Transfer mayorship
DISBAND            1 << 21   Disband city
```

**Rule:** a member may never grant a permission they do not themselves hold, and may never edit a rank with weight >= their own.

### 5.5 Land protection

Inside a claim, the following are blocked for non-members and for members lacking the relevant flag:

- Block break and place
- Container access (chest, barrel, shulker, furnace, hopper, dispenser, dropper, brewing stand, beacon)
- Interaction (doors, trapdoors, buttons, levers, pressure plates, item frames, armor stands, beds, anvils, enchanting tables)
- Bucket use (fill and empty)
- Entity damage (except in war, except hostile mobs)
- Fire spread and lava flow across claim borders
- TNT and end crystal explosions (fully disabled outside war)
- Piston movement across claim boundaries (blocked entirely, prevents grief and dupe vectors)
- Fluid flow across claim boundaries into a foreign claim
- Farmland trampling by non-members
- Villager trading (config toggle, default allowed)

**PvP inside claims:** disabled outside of war. Enabled only inside the claims of cities that are party to an active war.

### 5.6 City spawn and teleporting

- `/city spawn` teleports to the city spawn. 5-second warmup, cancelled by movement or damage. 30-second cooldown.
- Spawn must be inside a claim owned by the city. If the chunk containing spawn is ever unclaimed, spawn resets to the core chunk center and the Mayor is notified.
- Teleporting is fully blocked during a war for cities party to that war, except teleporting *to* your own city spawn, which has a 15-second warmup instead of 5.

### 5.7 City upgrades

Permanent purchasable improvements, paid from treasury. Each has 5 levels.

| Upgrade | Effect per level | Cost (L1 to L5) |
|---|---|---|
| Population | +5 member cap (base 10) | 20k, 50k, 120k, 280k, 600k |
| Vault | +1 shared vault page (27 slots) | 30k, 70k, 150k, 320k, 700k |
| Treasury Interest | Reduces upkeep by 4% | 40k, 90k, 200k, 420k, 900k |
| Outpost Range | +1 max outpost (base 2, max 6) | 60k, 140k, 300k, 650k, 1.4M |
| Fortification | +5% defense unit health, +1 max unit | 50k, 110k, 240k, 500k, 1.1M |
| Market Access | -0.8% market tax | 45k, 100k, 220k, 460k, 1M |

**Note on outposts:** base is 2, so the user-requested maximum of 4 is reached at Outpost Range level 2, and the cap extends to 6 at max level. This gives outposts a progression curve instead of being immediately available.

---

## 6. Claim system

### 6.1 Adjacency rule

A new normal claim must share an **edge** (not merely a corner) with an existing claim of the same city. Diagonal-only adjacency is rejected. This forces genuinely contiguous, defensible city shapes.

The core chunk is exempt (it is the seed). Outposts are exempt (Section 7).

**Contiguity invariant:** the set of non-outpost claims of a city must always form a single edge-connected component. This is enforced on **unclaim**, not just claim: unclaiming a chunk that would split the city into two disconnected pieces is rejected with a clear message showing which chunks would be orphaned.

Implementation note: run a flood-fill from the core chunk over the claim set minus the candidate, and compare the visited count to `claims.size() - 1`. This is cheap enough to run synchronously for cities under ~2,000 chunks. Above that, cache the articulation points.

### 6.2 Claim cost formula

```
if (chunk_index <= 8):
    base_cost = 500                                  # flat starter plot
else:
    base_cost = 400 * (chunk_index ^ 1.25)

distance_mult = 1 + 0.05 * max(0, chebyshev_distance_from_core - 4)

member_divisor = 1 + 0.18 * (active_members - 1)     # active = seen in last 14 days

newcomer_mult = 0.75 if city_age < 14 days else 1.0

final_cost = base_cost * distance_mult * newcomer_mult / member_divisor
```

Where `chunk_index` is the number of the chunk being claimed (the 9th chunk has index 9).

**Reference values at `distance_mult = 1`, solo player:**

| Chunk # | Cost | Cumulative |
|---|---|---|
| 1 to 8 | 500 each | 4,000 |
| 9 | 6,235 | 10,235 |
| 15 | 11,808 | 62,000 |
| 20 | 16,918 | 141,032 |
| 30 | 28,084 | 366,000 |
| 50 | 53,183 | 1,190,649 |
| 75 | 88,285 | 3,000,000 |
| 100 | 126,491 | 5,667,308 |
| 150 | 209,978 | 13,900,000 |
| 200 | 300,848 | 26,874,751 |
| 300 | 499,415 | 79,000,000 |
| 500 | 945,742 | 210,619,976 |

**Why this solves the stated problem:** with exponential growth at 1.5^n, chunk 50 would cost roughly 6 x 10^8 times chunk 1, which is the wall the user described. With polynomial growth at n^1.25, chunk 500 costs only 1,890x chunk 1. Growth remains meaningful (a large city is genuinely expensive) but never becomes impossible. Combined with the member divisor, a 15-member city pays 268,000 C for chunk 500, which is roughly 3 days of that city's collective income.

### 6.3 Claiming

**Command:** `/city claim` (current chunk), `/city claim auto` (toggles auto-claim as you walk), `/city claim radius <n>` (claims an NxN square around you, max n=5, atomic: either all succeed or none do).

**Preconditions:**
1. Player has `CLAIM` permission
2. Chunk is unclaimed
3. Chunk is adjacent to an existing claim of the city (edge-sharing)
4. Chunk is in a `city-enabled` world
5. Chunk is not within `min-city-distance` of a *different* city's claims (default 5 chunks buffer, config `claims.buffer-chunks`; set to 0 to allow bordering cities)
6. Treasury has sufficient funds
7. City is not delinquent on upkeep
8. City is not frozen
9. Not currently in a war `PREP` or `ACTIVE` state (no expanding mid-war)
10. Chunk does not overlap an admin-protected region

**On success:** deduct from treasury, insert claim row, ledger `CHUNK_CLAIM`, show a particle border outline of the newly claimed chunk to the claimer for 10 seconds.

### 6.4 Unclaiming

**Command:** `/city unclaim`, `/city unclaim radius <n>`

**Refund:** 50% of `cost_paid`, to the **treasury**, not to the player. This prevents claim-flipping as an income strategy.

**Blocked when:** chunk is CORE, chunk contains the city spawn, unclaim would break contiguity (6.1), city is in war PREP or ACTIVE, city is frozen.

**Blocks and builds are not removed on unclaim.** The land simply becomes unprotected. This is deliberate: a city that shrinks leaves ruins, which is thematically good and mechanically simpler.

### 6.5 Claim visualisation

- `/city map` prints a 31x13 ASCII chunk map in chat, using MiniMessage colors: green for your city, red for enemy cities at war, yellow for allies, gray for other cities, white for wilderness, and a highlighted center for the player's position.
- `/city border` toggles a particle outline of all claim borders within 64 blocks, client-visible for 60 seconds.
- Entering and leaving a claim shows an action-bar title: `Entering <city display_name>` / `Leaving <city display_name>` / `Wilderness`.

---

## 7. Outposts

### 7.1 Definition

An outpost is a **single chunk** claim not adjacent to the city body. It exists to give cities access to distant biomes and resources without requiring a contiguous land bridge across the map.

### 7.2 Rules

| Rule | Value |
|---|---|
| Max outposts | 2 base, up to 6 via Outpost Range upgrade |
| Size | Exactly 1 chunk. Outposts cannot be expanded. |
| Creation cost | 25,000 C flat + 3x the current normal chunk cost |
| Daily upkeep | 2,000 C each |
| Min distance from own city | 32 chunks (prevents using outposts to bypass adjacency) |
| Min distance from any other city's claims | 8 chunks |
| Teleport | `/city outpost tp <name>`, 100 C, 8-second warmup, 3-minute cooldown |
| War status | Outposts of a warring city ARE valid war targets and ARE rolled back |

### 7.3 Commands

```
/city outpost create <name>       Creates outpost at current chunk
/city outpost delete <name>       Deletes, refunds 50% of creation cost to treasury
/city outpost tp <name>           Teleport
/city outpost list                Lists all with distance and coordinates
/city outpost setwarp <name>      Sets the teleport destination within the outpost chunk
/city outpost rename <old> <new>
```

### 7.4 Edge cases

- If an outpost chunk later becomes adjacent to the city body (because the city expanded toward it), the outpost **automatically converts** to a normal claim, frees an outpost slot, and refunds nothing. The Mayor is notified.
- Outpost teleport is disabled entirely during a war involving that city, to prevent instant reinforcement.
- If the outpost teleport destination is unsafe (suffocating, in lava, in the void), the player is placed at the highest safe Y in that chunk instead.

---

## 8. GUI system

All city management must be possible without memorising a single command. The GUI is the primary interface, commands are the power-user path. Every GUI is defined in a YAML file under `resources/gui/` so layouts can be changed without recompiling.

### 8.1 The City Hall block

On city creation, the founder receives (or has placed) a **City Hall** block: a Lodestone renamed to `<gold><bold>City Hall`, with persistent data `civitas:city_hall = <city_id>`.

- Right-clicking it opens the Main Menu
- It cannot be broken by anyone below Co-Mayor rank
- It cannot be broken at all during a war
- If somehow destroyed (world edit, admin), `/city hall` gives the Mayor a replacement, once, for free
- Only one may exist per city; placing a second does nothing

`/city` (no arguments) also opens the Main Menu from anywhere, for convenience.

### 8.2 GUI framework requirements

- 6-row (54-slot) double-chest inventories
- Slot 45 is always **Back** (Arrow, `<gray>Back`), slot 49 is always **Close** (Barrier, `<red>Close`)
- Border filler: Gray Stained Glass Pane, display name is a single space, no lore, not clickable
- Buttons the player lacks permission for render as **Barrier** with lore explaining the missing permission, and clicking does nothing but play `BLOCK_NOTE_BLOCK_BASS`
- All click handling is server-side validated. Never trust the clicked slot alone, always re-verify permission at execution time. A player can spoof clicks.
- Menus refresh automatically every 20 ticks if they display live data (treasury, member online status)
- Pagination uses slot 48 (previous, Spectral Arrow) and 50 (next, Spectral Arrow)
- Confirmation dialogs for all destructive actions: Lime Concrete `<green>Confirm` at slot 29, Red Concrete `<red>Cancel` at slot 33

### 8.3 Main Menu (`gui/main.yml`)

Title: `<dark_gray>City Hall <gray>| <gold><city_name>`

| Slot | Item | Label | Lore preview | Opens |
|---|---|---|---|---|
| 10 | Beacon | `<gold>City Overview` | Name, tag, mayor, founded date, member count, claim count, rank | Overview |
| 12 | Grass Block | `<green>Land & Claims` | Claims: X, Next chunk cost: Y, Contiguity: OK | Claims |
| 14 | Gold Ingot | `<yellow>Treasury` | Balance, daily upkeep, days until insolvent | Treasury |
| 16 | Player Head | `<aqua>Members` | Online X/Y, open list | Members |
| 20 | Iron Golem Spawn Egg | `<gray>Defense` | Units active, total upkeep | Defense |
| 22 | Netherite Sword | `<red>Wars` | Active war status or "At peace" | Wars |
| 24 | Written Book | `<light_purple>Diplomacy` | Allies: X, Truces: Y | Diplomacy |
| 28 | Anvil | `<blue>Upgrades` | X/30 levels purchased | Upgrades |
| 30 | Ender Chest | `<dark_purple>City Vault` | Pages unlocked | Vault |
| 32 | Filled Map | `<green>Outposts` | X/Y used | Outposts |
| 34 | Painting | `<gold>Contests` | Current theme, time remaining | Contests |
| 40 | Ender Pearl | `<aqua>City Spawn` | Teleport home | (action) |
| 42 | Comparator | `<gray>Settings` | | Settings |
| 49 | Barrier | `<red>Close` | | (action) |

### 8.4 Claims Menu (`gui/claims.yml`)

Title: `<dark_gray>Land Management`

| Slot | Item | Label | Action |
|---|---|---|---|
| 11 | Grass Block | `<green>Claim This Chunk` | Lore shows exact cost breakdown: base, distance mult, member divisor, final. Click to claim. |
| 13 | Dirt | `<red>Unclaim This Chunk` | Confirmation required. Shows refund amount and contiguity warning if applicable. |
| 15 | Map | `<yellow>View Chunk Map` | Closes GUI, prints `/city map` output |
| 20 | Lead | `<aqua>Toggle Auto-Claim` | Enchant glint when active |
| 22 | Glowstone | `<gold>Show Borders` | Runs `/city border` |
| 24 | Filled Map | `<green>Manage Outposts` | Opens Outposts menu |
| 29-33 | Paper | `<gray>Claim Statistics` | Total claims, total invested, avg cost, land value, upkeep contribution |

Bottom row displays a **live 9-slot mini-map** of the 3x3 chunks around the player, using colored concrete: Lime (own), Red (enemy), Yellow (ally), Gray (other city), White (wilderness), with the center slot showing the player's head.

### 8.5 Treasury Menu (`gui/treasury.yml`)

| Slot | Item | Label | Notes |
|---|---|---|---|
| 4 | Gold Block | `<gold>Treasury: <amount> C` | Live-updating |
| 19 | Emerald | `<green>Deposit 1,000` | |
| 20 | Emerald Block | `<green>Deposit 10,000` | |
| 21 | Diamond | `<green>Deposit 100,000` | |
| 22 | Chest | `<green>Deposit Custom` | Opens sign-input or anvil-input for amount |
| 23 | Redstone | `<red>Withdraw 1,000` | Requires WITHDRAW perm |
| 24 | Redstone Block | `<red>Withdraw 10,000` | |
| 25 | Netherite Ingot | `<red>Withdraw Custom` | |
| 31 | Book | `<yellow>Transaction History` | Paginated ledger, last 100 city transactions |
| 37 | Clock | `<gray>Upkeep Info` | Daily cost, next charge time, days of runway remaining, delinquency warning |
| 40 | Player Head | `<aqua>Contribution Leaderboard` | Ranks members by lifetime treasury deposits |

**Withdrawal limit:** a single member may withdraw at most 25% of the treasury per 24 hours, unless they are the Mayor. This is an anti-fraud measure, see Section 18.9.

### 8.6 Members Menu (`gui/members.yml`)

Paginated list of member heads. Each head's lore shows: rank, joined date, last seen, lifetime contribution, online status.

**Click actions on a member head:**
- Left click: open Member Actions submenu
- Member Actions submenu: Promote (Emerald), Demote (Redstone), Kick (Barrier, confirm required), Set Rank (Name Tag, opens rank picker), View Ledger (Book)

Slot 45: `<green>Invite Player` (Player Head), opens an anvil-input for the name.
Slot 46: `<red>Ban List` (Barrier), manages the city ban list.

### 8.7 Ranks Menu (`gui/ranks.yml`)

One row per rank, showing weight and member count. Clicking a rank opens the **Permission Editor**: a 54-slot grid where each of the 22 permission flags is a toggle button, Lime Dye when granted, Gray Dye when not. Clicking toggles it, with immediate validation against the "cannot grant what you lack" rule.

### 8.8 Wars Menu (`gui/wars.yml`)

**When at peace:**
- Slot 22: Netherite Sword `<red>Declare War`, opens city selector, then wager selector, then a confirmation screen showing full terms
- Slot 31: Book `<gray>War History`, paginated past wars with results

**When in PREP:**
- Countdown timer to war start (Clock, live-updating)
- Slot 20: Shield `<blue>Defense Preparation`, links to Defense menu
- Slot 24: Ender Pearl `<yellow>Rally Point`, sets a defensive rally location
- Slot 40: Barrier `<red>Sue for Peace`, sends a peace offer to the opponent, requires both mayors to accept, forfeits 25% of your wager

**When ACTIVE:**
- Live scoreboard: attacker score vs defender score, time remaining
- Slot 11: Player Head `<red>Enemy Members Online`
- Slot 13: Iron Sword `<gold>Kill Feed`, last 20 kills
- Slot 15: Beacon `<aqua>Capture Point Status`, see Section 11.6
- Slot 40: Barrier `<red>Sue for Peace`

### 8.9 Defense Menu (`gui/defense.yml`)

Grid of purchasable defense units (see Section 12), each showing cost, daily upkeep, stats, and current count. Clicking purchases and gives the player a **spawn egg item** which must be placed inside a claim, so placement is deliberate and visible.

### 8.10 Settings Menu (`gui/settings.yml`)

| Slot | Item | Setting |
|---|---|---|
| 10 | Name Tag | Change display name (color codes, admin-reviewed) |
| 12 | Paper | Set MOTD |
| 14 | Oak Door | Toggle Open Join |
| 16 | Compass | Set City Spawn (to current location) |
| 20 | Bell | Toggle join/leave broadcasts |
| 22 | Banner | City banner (used on the map and in contests) |
| 28 | Writable Book | Rename city (15,000 C) |
| 30 | Player Head | Transfer mayorship |
| 34 | TNT | `<dark_red>Disband City`, double confirmation, type-name-to-confirm |

---

## 9. Command reference

### 9.1 Player commands (no city required)

| Command | Permission | Description |
|---|---|---|
| `/city` | `civitas.use` | Opens Main Menu, or city info if not in a city |
| `/city help [page]` | `civitas.use` | Paginated help |
| `/city create <name>` | `civitas.city.create` | Found a city |
| `/city info [name]` | `civitas.use` | City details |
| `/city list [page]` | `civitas.use` | All cities, sortable by size, wealth, members |
| `/city join <name>` | `civitas.use` | Join an open-join city |
| `/city accept <name>` | `civitas.use` | Accept an invite |
| `/city deny <name>` | `civitas.use` | Deny an invite |
| `/city map` | `civitas.use` | ASCII chunk map |
| `/city here` | `civitas.use` | Who owns this chunk |
| `/money` / `/balance [player]` | `civitas.economy.balance` | Check balance |
| `/pay <player> <amount>` | `civitas.economy.pay` | Transfer money |
| `/shop` | `civitas.market.use` | Open the server market GUI |
| `/sell hand [amount]` | `civitas.market.use` | Quick-sell held item |
| `/sell all <material>` | `civitas.market.use` | Sell all of a material from inventory |
| `/worth [item]` | `civitas.market.use` | Current market price |
| `/quests` | `civitas.quests.use` | Quest GUI |
| `/challenges` | `civitas.quests.use` | Weekly challenges |
| `/leaderboard [type]` | `civitas.use` | See Section 13.3 for types |
| `/contest` | `civitas.contest.use` | Current contest info and submission |
| `/bounty <player> <amount>` | `civitas.bounty.use` | Place a bounty |
| `/bounty list` | `civitas.bounty.use` | Active bounties |
| `/civitas rules` | `civitas.use` | Server rules book |

### 9.2 City member commands

| Command | City permission | Description |
|---|---|---|
| `/city spawn` | member | Teleport to city spawn |
| `/city leave confirm` | member | Leave city |
| `/city deposit <amount>` | DEPOSIT | Deposit to treasury |
| `/city withdraw <amount>` | WITHDRAW | Withdraw from treasury |
| `/city vault [page]` | CONTAINER | Open shared vault |
| `/city chat <message>` / `/cc` | member | City-only chat |
| `/city ally chat <msg>` / `/ac` | member | Alliance chat |
| `/city claim [auto\|radius <n>]` | CLAIM | Claim chunks |
| `/city unclaim [radius <n>]` | UNCLAIM | Unclaim chunks |
| `/city border` | member | Toggle border particles |
| `/city invite <player>` | INVITE | Invite |
| `/city kick <player>` | KICK | Kick |
| `/city rank set <player> <rank>` | MANAGE_RANKS | Assign rank |
| `/city rank create <name> <weight>` | MANAGE_RANKS | New rank |
| `/city rank delete <name>` | MANAGE_RANKS | Delete rank |
| `/city rank perm <rank> <flag> <on\|off>` | MANAGE_RANKS | Toggle a permission |
| `/city setspawn` | SET_SPAWN | Move spawn |
| `/city outpost <sub>` | OUTPOST_* | See Section 7.3 |
| `/city upgrade <key>` | MANAGE_UPGRADES | Purchase upgrade |
| `/city defense <sub>` | MANAGE_DEFENSE | Buy and manage units |
| `/city setmotd <text>` | EDIT_SETTINGS | Set MOTD |
| `/city open <true\|false>` | EDIT_SETTINGS | Toggle open join |
| `/city rename <name>` | EDIT_SETTINGS | Rename, 15,000 C |
| `/city transfer <player>` | TRANSFER | Transfer mayorship |
| `/city disband` | DISBAND | Disband |

### 9.3 War and diplomacy commands

| Command | City permission | Description |
|---|---|---|
| `/war declare <city> <wager>` | DECLARE_WAR | Declare war |
| `/war status [city]` | member | Current war state, scores, timers |
| `/war peace <city>` | DECLARE_WAR | Offer peace |
| `/war accept <city>` | DECLARE_WAR | Accept a peace offer |
| `/war history [city]` | any | Past wars |
| `/war scoreboard` | member | Toggle war scoreboard sidebar |
| `/ally invite <city>` | MANAGE_DIPLOMACY | Propose alliance |
| `/ally accept <city>` | MANAGE_DIPLOMACY | Accept alliance |
| `/ally break <city>` | MANAGE_DIPLOMACY | Break alliance, 7-day cooldown before re-allying |
| `/ally list` | member | Current allies |
| `/truce offer <city> <days>` | MANAGE_DIPLOMACY | Offer a non-aggression pact |
| `/truce accept <city>` | MANAGE_DIPLOMACY | Accept |

### 9.4 Admin commands

Base: `/cityadmin`, alias `/ca`. All require `civitas.admin` plus the specific node.

#### 9.4.1 Inspection and audit

| Command | Permission | Description |
|---|---|---|
| `/ca info <city>` | `civitas.admin.info` | Full city dump: all fields, all claims, all members, all upgrades |
| `/ca player <player>` | `civitas.admin.info` | Full player dump: balance, city, rank, playtime, IP-shared accounts |
| `/ca ledger player <player> [days]` | `civitas.admin.audit` | Every transaction by a player |
| `/ca ledger city <city> [days]` | `civitas.admin.audit` | Every transaction of a city |
| `/ca ledger type <type> [days]` | `civitas.admin.audit` | All transactions of a type, server-wide |
| `/ca ledger export <target> <days>` | `civitas.admin.audit` | Dumps to a CSV in `plugins/CivitasCraft/exports/` |
| `/ca audit suspicious [days]` | `civitas.admin.audit` | Runs the fraud heuristics in Section 18.9 and reports hits |
| `/ca inspect` | `civitas.admin.inspect` | Toggle inspect mode: clicking any block shows owning city, claim date, claimer |
| `/ca alts <player>` | `civitas.admin.audit` | Accounts sharing an IP or login fingerprint |
| `/ca economy stats` | `civitas.admin.economy` | Total circulation, top 20 balances, weekly inflation delta |

#### 9.4.2 City management

| Command | Permission | Description |
|---|---|---|
| `/ca city setmayor <city> <player>` | `civitas.admin.city` | Force mayorship transfer (offline-safe) |
| `/ca city rename <city> <name>` | `civitas.admin.city` | Force rename, free |
| `/ca city delete <city>` | `civitas.admin.city` | Soft delete, requires typing the city name |
| `/ca city restore <city>` | `civitas.admin.city` | Restore a soft-deleted city within 14 days |
| `/ca city freeze <city> <reason>` | `civitas.admin.city` | Blocks ALL mutations: no claims, no transactions, no war, no member changes. City becomes read-only. Members are notified with the reason. |
| `/ca city unfreeze <city>` | `civitas.admin.city` | Unfreeze |
| `/ca city forceadd <city> <player>` | `civitas.admin.city` | Force-add a member (bypasses cooldowns and caps) |
| `/ca city forceremove <city> <player>` | `civitas.admin.city` | Force-remove |
| `/ca city setupkeep <city> <mult>` | `civitas.admin.city` | Temporary upkeep multiplier, e.g. for a returning-player grace period |
| `/ca city forgivedebt <city>` | `civitas.admin.city` | Clears delinquency without payment |

#### 9.4.3 Claim management

| Command | Permission | Description |
|---|---|---|
| `/ca claim info` | `civitas.admin.claim` | Info on current chunk |
| `/ca claim force <city>` | `civitas.admin.claim` | Force-claim current chunk for a city, ignoring adjacency, distance, cost, and contiguity |
| `/ca claim unclaim` | `civitas.admin.claim` | Force-unclaim current chunk, no refund |
| `/ca claim transfer <city>` | `civitas.admin.claim` | Move ownership of current chunk to another city |
| `/ca claim protect <on\|off>` | `civitas.admin.claim` | Marks the chunk as admin-protected: unclaimable, unbuildable, war-immune |
| `/ca claim purge <city>` | `civitas.admin.claim` | Removes all claims of a city, no refund. Requires confirmation. |
| `/ca claim fixcontiguity <city>` | `civitas.admin.claim` | Detects and reports orphaned claim components, offers to unclaim the smaller ones |

#### 9.4.4 Economy management

| Command | Permission | Description |
|---|---|---|
| `/ca eco give <player> <amt> [reason]` | `civitas.admin.economy` | Ledger type `ADMIN_GIVE`, reason is mandatory in strict mode |
| `/ca eco take <player> <amt> [reason]` | `civitas.admin.economy` | `ADMIN_TAKE` |
| `/ca eco set <player> <amt> [reason]` | `civitas.admin.economy` | `ADMIN_SET` |
| `/ca eco treasury <city> <give\|take\|set> <amt>` | `civitas.admin.economy` | Same for city treasuries |
| `/ca eco rollback <player> <txn_id>` | `civitas.admin.economy` | Reverses a single transaction and everything downstream of it, writing compensating entries. Never deletes ledger rows. |
| `/ca eco freeze <player>` | `civitas.admin.economy` | Player cannot send or receive money |
| `/ca market setprice <material> <base>` | `civitas.admin.economy` | Adjust base price |
| `/ca market setstock <material> <n>` | `civitas.admin.economy` | Adjust stock, moves the price |
| `/ca market reload` | `civitas.admin.economy` | Reload market config |

#### 9.4.5 War management

| Command | Permission | Description |
|---|---|---|
| `/ca war list` | `civitas.admin.war` | All wars in any state |
| `/ca war cancel <id> <reason>` | `civitas.admin.war` | Cancels a war, refunds both wagers in full, **triggers immediate rollback** |
| `/ca war forceend <id> <winner\|draw>` | `civitas.admin.war` | Ends a war early with a specified result, runs rollback |
| `/ca war extend <id> <hours>` | `civitas.admin.war` | Extends the war window |
| `/ca war rollback <id>` | `civitas.admin.war` | Manually triggers or re-triggers rollback for a war |
| `/ca war rollbackstatus <id>` | `civitas.admin.war` | Progress: blocks restored / total, ETA, errors |
| `/ca war verify <id>` | `civitas.admin.war` | Dry-run integrity check of the block log, reports any entries that would fail to restore and why |
| `/ca war immunity <city> <hours>` | `civitas.admin.war` | Grants war immunity |

#### 9.4.6 System

| Command | Permission | Description |
|---|---|---|
| `/ca reload [module]` | `civitas.admin.system` | Reloads config. Modules: all, economy, war, gui, lang, defense, events |
| `/ca backup` | `civitas.admin.system` | Forces a database backup to `plugins/CivitasCraft/backups/` |
| `/ca debug <on\|off>` | `civitas.admin.system` | Verbose logging |
| `/ca perf` | `civitas.admin.system` | Timings: avg claim lookup, block-log write rate, GUI open time, DB pool status |
| `/ca migrate check` | `civitas.admin.system` | Reports pending schema migrations |
| `/ca event start <key>` | `civitas.admin.event` | Manually start a server event |
| `/ca event stop <key>` | `civitas.admin.event` | Stop it |
| `/ca contest start <theme> <days>` | `civitas.admin.contest` | Start a contest |
| `/ca contest end` | `civitas.admin.contest` | End and score the current contest |
| `/ca contest disqualify <city> <reason>` | `civitas.admin.contest` | Remove an entry |

---

## 10. Bukkit permission nodes

```
civitas.use                      default: true    Base access
civitas.city.create              default: true
civitas.economy.balance          default: true
civitas.economy.pay              default: true
civitas.market.use               default: true
civitas.quests.use               default: true
civitas.contest.use              default: true
civitas.bounty.use               default: true
civitas.bypass.claim             default: op      Build anywhere
civitas.bypass.cooldown          default: op      No teleport or command cooldowns
civitas.bypass.war               default: op      Immune to war PvP and restrictions
civitas.bypass.economy           default: op      Free purchases
civitas.admin                    default: op      Parent node for all admin
civitas.admin.info               default: op
civitas.admin.audit              default: op
civitas.admin.inspect            default: op
civitas.admin.city               default: op
civitas.admin.claim              default: op
civitas.admin.economy            default: op
civitas.admin.war                default: op
civitas.admin.event              default: op
civitas.admin.contest            default: op
civitas.admin.system             default: op
civitas.admin.*                  default: op      Wildcard
```

**Limits scale by permission node**, so donor ranks or trusted players can be given more without code changes:

```
civitas.limit.cities.1           Can be in 1 city (default)
civitas.limit.homes.<n>          Personal home limit
civitas.limit.shops.<n>          Player shop limit, default 5
```

---

## 11. War system

**This is the defining feature of the plugin and the highest-risk subsystem. Read this section fully before writing any war code.**

### 11.1 Design goal

Wars must be high-stakes without being destructive. A player must be able to lose a war badly, log off angry, and log back on the next morning to find their build exactly as they left it. The stakes are money, ranking, and reputation. Never blocks.

If the rollback is unreliable, the entire plugin's core promise is broken and players will refuse to participate in wars. **Rollback correctness is more important than rollback speed.**

### 11.2 War lifecycle

```
  DECLARED
     |
     v
   PREP  (48 hours)          Defenders build, buy units, rally
     |
     v
  ACTIVE (7 days)            Grief permitted inside war zone
     |
     v
ROLLING_BACK                 World restored, no player access to war zone
     |
     v
  RESOLVED                   Scores final, wager paid, 7-day immunity applied
```

### 11.3 Declaration

**Command:** `/war declare <city> <wager>`

**Preconditions:**
1. Declarer has `DECLARE_WAR` permission
2. Both cities have at least 3 members (no war on 1-person cities)
3. Both cities have at least 10 claims
4. Attacker city is at least 14 days old
5. Defender is not under war immunity (7 days after any war ends)
6. Defender is not already in an active war (a city fights at most one war at a time)
7. No truce exists between the two cities
8. The two cities are not allied
9. Wager >= 50,000 C and <= 25% of the *smaller* city's treasury. This prevents a rich city bankrupting a poor one via forced wager.
10. Both treasuries can cover the wager
11. Neither city is frozen or delinquent
12. Attacker and defender have not fought each other in the last 21 days (anti-harassment cooldown)

**On declaration:**
- Both wagers are escrowed immediately (ledger `WAR_WAGER_ESCROW`). The money leaves both treasuries and is held by the war.
- **The defender may decline within 6 hours.** Declining costs the defender 30% of the wager, paid to the attacker, and grants the attacker no score. This gives smaller cities an exit and prevents forced participation.
- If not declined, PREP begins.
- Server-wide broadcast with a countdown.

### 11.4 The war zone

The war zone is the union of:
- All claims of the attacker city
- All claims of the defender city
- All outposts of both
- A 1-chunk perimeter around each of the above

**Nothing outside the war zone is ever affected.** No logging, no grief permission, no rollback. This boundary must be checked on every single block event during a war, so it is precomputed into a `LongOpenHashSet` of packed chunk keys at war start and refreshed only if claims change (which they cannot during war, see 6.3 precondition 9, so it is effectively immutable).

### 11.5 PREP phase (48 hours)

During PREP:
- No grief permitted, normal protection applies
- Both cities may buy and place defense units
- Both cities may build freely inside their own claims (defensive fortification)
- **Claiming and unclaiming are blocked** for both cities, so the war zone cannot shift
- Members cannot leave either city (prevents rat-fleeing before the fight)
- New members cannot join either city (prevents mercenary stacking)
- Allies may formally join the war via `/war join <war_id>` (see 11.10)
- A `PREP` scoreboard shows the countdown to all members of both cities

### 11.6 ACTIVE phase (7 days)

**What becomes permitted inside the war zone, and only there:**

| Action | Permitted for |
|---|---|
| Block break | Members of the opposing city and its allies |
| Block place | Same |
| Container access | Same, **but see 11.7 on item theft** |
| PvP | Members of either side, and allies |
| TNT and end crystals | Yes, damage is logged and rolled back |
| Fire spread | Yes, logged |
| Bucket use (lava, water) | Yes, logged |
| Piston use across borders | Yes, logged |

**What remains blocked even in war:**
- Breaking the City Hall block
- Breaking a defense unit spawner
- Anything in an admin-protected chunk
- Anything outside the war zone
- Teleporting into or out of the war zone via outpost warps
- `/city spawn` for the *attacking* city into the *defending* city (attackers must travel)

**Scoring** (config-tunable, `war.yml`):

| Event | Points |
|---|---|
| Kill an enemy player | +10 |
| Die (to enemy player) | 0 (no negative, deaths are not punished) |
| Hold a capture point for 60 continuous seconds | +25 |
| Destroy an enemy defense unit | +15 |
| Break a block inside enemy claims | +0.1, capped at 500 points total per war |
| Reach the enemy City Hall chunk and stand there 30s | +100, once per war per city |

**Capture points:** three are auto-generated at war start, placed at the geometric extremes of the defender's claim set (north-most, south-most, and the chunk furthest from the core). Each is marked by a Beacon-like particle column visible from 100 blocks. Holding one means having more of your members than the enemy's inside that chunk.

**Design note on the block-break score cap:** the cap exists so that a war is decided by fighting, not by whoever mines the most dirt. Breaking blocks contributes a little to score but a team that only demolishes will lose to a team that fights and captures.

### 11.7 Item theft policy

**Items removed from containers during war are NOT returned by rollback.** This is a deliberate, explicit exception to "destruction is never permanent," and it must be communicated clearly to players.

Rationale: if stolen items are refunded, wars have no material stakes at all and no reason to raid. If blocks are restored but loot is not, wars become high-reward heists rather than pointless demolition, which is exactly the behaviour we want to encourage.

**Mitigation:** cities have a **City Vault** (Section 5.7 Vault upgrade) which is **completely immune to war looting**. Anything a city cannot afford to lose goes in the vault before the war. This turns "protect your loot" into a pre-war strategic decision rather than a disaster.

Container access during war is logged to a separate `war_container_log` table (war_id, x/y/z, actor, item, quantity, timestamp) purely for the post-war report and for admin dispute resolution.

### 11.8 The rollback engine

**This is the most important code in the plugin.**

#### 11.8.1 Logging strategy

Diff-based, not snapshot-based. Reasons: snapshots of a 200-chunk war zone are hundreds of megabytes and take minutes to write; a diff log of an active 7-day war is typically a few hundred thousand rows, which is trivial in comparison. Diff logging also gives a full audit trail of who destroyed what.

**Every block change inside the war zone during ACTIVE is logged**, with the old state, the new state, and the actor. Sources that must all be captured:

| Source | Listener |
|---|---|
| Player break | `BlockBreakEvent` |
| Player place | `BlockPlaceEvent` |
| Multi-block place | `BlockMultiPlaceEvent` |
| TNT and creeper | `EntityExplodeEvent`, `BlockExplodeEvent` |
| Fire spread and burn | `BlockBurnEvent`, `BlockSpreadEvent` |
| Fluid flow | `BlockFromToEvent` |
| Piston push and pull | `BlockPistonExtendEvent`, `BlockPistonRetractEvent` |
| Bucket fill and empty | `PlayerBucketFillEvent`, `PlayerBucketEmptyEvent` |
| Block physics (sand, gravel falling) | `EntityChangeBlockEvent` |
| Farmland trample | `PlayerInteractEvent` on physical |
| Entity block change (endermen, wither, ravager) | `EntityChangeBlockEvent` |
| Crop growth and decay | `BlockGrowEvent`, `LeavesDecayEvent`, `BlockFadeEvent` |
| Sign edits | `SignChangeEvent` |
| Item frame and armor stand changes | `HangingBreakEvent`, `HangingPlaceEvent`, `PlayerArmorStandManipulateEvent` |
| Anything else that changes a block | **Fail-safe: a periodic chunk-hash comparison, see 11.8.4** |

**Serialization:** store `BlockData.getAsString()` for the block state. For tile entities (chests, signs, furnaces, spawners, banners, beehives, shulkers) additionally serialize the full NBT to `old_nbt` using Paper's `BlockState` snapshot serialization.

**Performance:** block log writes go to an in-memory ring buffer, flushed to the database asynchronously in batches every 2 seconds or every 500 entries, whichever comes first. The buffer must survive a batch failure by retrying, and must be flushed on plugin disable. Target: sustain 2,000 block changes per second with zero main-thread impact.

#### 11.8.2 Rollback execution

At war end, state becomes `ROLLING_BACK`.

1. **Evacuate.** All players inside the war zone are teleported to their own city spawn (or server spawn if their city is party to the war and its spawn is inside the zone). The war zone is then closed: entry is blocked with a message.
2. **Freeze logging.** No further entries accepted for this war.
3. **Read the log in reverse sequence order**, paginated at 5,000 rows per page to bound memory.
4. **For each entry, restore `old_block_data`.** Apply with `setBlockData(data, false)` to suppress physics, which is essential: applying physics during rollback causes cascading sand falls, water flows, and redstone activation that corrupt the restoration.
5. **Restore NBT for tile entities** after the block is placed.
6. **Throttle.** Restore at most `war.rollback.blocks-per-tick` (default 400) blocks per tick, spread across ticks, so the server never freezes. A 300,000-block rollback at 400/tick completes in roughly 12.5 seconds of server time. Chunks are loaded on demand and unloaded after processing.
7. **After all blocks are restored, run a second pass** applying physics updates only at the boundary of restored regions, so lighting and redstone settle correctly.
8. **Verify.** Re-read a random 2% sample of logged positions and confirm they match `old_block_data`. Log any mismatch as an error, do not silently ignore.
9. **Set `rollback_completed_at`**, transition to `RESOLVED`, reopen the zone, broadcast completion.

#### 11.8.3 Entity restoration

Blocks are not the only thing destroyed. Also handled:

| Entity type | Policy |
|---|---|
| Item frames, paintings, armor stands | Logged on break and restored with full NBT (contents, rotation, pose) |
| Villagers, animals in the war zone | Snapshotted at war start (type, position, NBT, name, profession, trades) and respawned if killed |
| Defense units | Not restored, they are consumed resources. Cost is a war expense. |
| Player-dropped items | Not restored |
| Minecarts, boats | Logged on break, restored with contents |
| Dropped items from broken blocks | **Suppressed entirely during war.** Breaking a block in a war zone drops nothing. This prevents infinite resource farming during war and removes an entire class of duplication exploit. |

**The no-drops rule is critical.** Without it, a war becomes a free strip-mining event: attackers break 50,000 blocks of the enemy city, keep all the materials, and the rollback restores the blocks anyway, creating resources from nothing. Blocks broken in war simply vanish and later reappear.

#### 11.8.4 Fail-safe chunk hashing

Because no listener list is ever truly exhaustive, the plugin additionally computes a lightweight hash of each war-zone chunk (a rolling checksum over block state IDs) at war start and again after rollback. Any chunk whose post-rollback hash does not match its pre-war hash is flagged in `/ca war rollbackstatus` with its coordinates, so an admin can inspect and manually correct.

This does not fix the problem automatically, it makes the problem **visible**, which is the difference between a bug players report as "the plugin ate my house" and one an admin catches before anyone notices.

#### 11.8.5 Crash safety

- The war state machine is persisted. On plugin enable, any war found in `ACTIVE` whose `war_ends_at` has passed immediately transitions to `ROLLING_BACK`.
- Any war found in `ROLLING_BACK` on startup **resumes rollback from the last completed sequence number**, which is checkpointed to the `wars` table every 5,000 restored blocks.
- If the block log is corrupt or unreadable, the war is flagged `ROLLBACK_FAILED`, admins are alerted on console and in-game, and the zone stays closed until an admin resolves it manually. It must never silently give up and reopen a griefed city.

### 11.9 Resolution and rewards

**Winner:** higher score at war end. A draw occurs if scores are within 5% of each other.

| Result | Payout |
|---|---|
| Win | Winner receives their own wager back plus 80% of the loser's wager |
| Loss | Loser receives 20% of their own wager back |
| Draw | Both wagers refunded in full |
| Defender declined | Attacker receives their wager back plus 30% of defender's wager |
| Admin cancelled | Both wagers refunded in full |

The remaining 20% of the loser's wager is **deleted from circulation**, acting as an economic sink proportional to war activity.

**Non-monetary rewards:**
- War record (W/L/D) shown on city info and on the War leaderboard
- Winner gets a 7-day `+10%` market sell price bonus for its members
- Loser gets 7 days of war immunity (they cannot be re-declared on), which is protection, not punishment

### 11.10 Allies in war

An allied city may join a war on either side via `/war join <war_id> <side>` during PREP only.

- Joining an ally's war escrows a wager of 25% of the primary wager from the ally's treasury
- Allied claims become part of the war zone and are subject to grief and rollback
- Allied members score for their side
- On victory, allies split 30% of the payout pool proportional to their score contribution
- A city may not join a war against its own ally. If two allies end up on opposite sides, the alliance is automatically broken and both are notified.

### 11.11 War restrictions summary

Blocked for both cities during PREP and ACTIVE:
- Claiming and unclaiming
- Disbanding
- Members joining or leaving
- Mayorship transfer
- Outpost creation, deletion, and teleport
- Declaring another war
- Breaking an alliance (must wait until RESOLVED)
- Purchasing city upgrades

---

## 12. Custom mobs and city defense

### 12.1 Concept

Defense units are purchased from the treasury, placed inside claims as physical entities, cost daily upkeep, and defend the city during wars. They are consumed resources, not permanent buildings.

Outside of war, defense units are **passive decorations** that attack only hostile mobs. They never attack visiting players. This keeps the world friendly for the building-and-farming default state.

### 12.2 Unit catalogue

All units are vanilla mobs with attribute modifiers, equipment, custom names, and persistent data tags. No NMS required.

| Unit | Base mob | Health | Damage | Speed | Equipment | Cost | Upkeep/day |
|---|---|---|---|---|---|---|---|
| Watchman | Zombie | 40 (20 hearts) | 5 | 0.25 | Chainmail set, iron sword | 8,000 | 400 |
| City Guard | Zombie | 100 (50 hearts) | 9 | 0.30 | Full iron, iron sword, shield | 20,000 | 900 |
| Elite Guard | Zombie | 160 (80 hearts) | 14 | 0.32 | Full diamond, diamond sword | 45,000 | 2,000 |
| Archer | Skeleton | 60 | ranged 8 | 0.28 | Leather, Power III bow | 15,000 | 700 |
| Sharpshooter | Skeleton | 90 | ranged 13 | 0.28 | Chainmail, Power V bow | 32,000 | 1,400 |
| Warhound | Wolf | 45 | 7 | 0.42 | Tamed to city, Wolf Armor | 10,000 | 500 |
| Siege Golem | Iron Golem | 250 | 22 | 0.22 | none | 60,000 | 3,000 |
| Sentry | Snow Golem variant | 30 | slow debuff | static | none | 6,000 | 300 |

All stat values are `defense.yml` config entries. The Fortification upgrade adds +5% health per level.

### 12.3 Behaviour rules

| Situation | Behaviour |
|---|---|
| Peacetime, visitor in claim | Ignore completely |
| Peacetime, hostile mob in claim | Attack it |
| War, enemy member in war zone | Attack on sight within 24 blocks |
| War, ally or own member | Ignore |
| Unit killed | Removed permanently, upkeep stops, `defense_units.active = false` |
| Unit outside its claim | Teleported back if it wanders more than 8 blocks past the claim border |
| Owning city disbands | All units despawn |
| Upkeep unpaid | Units deactivate (despawn but persist in DB) and reactivate when upkeep is paid |

### 12.4 Placement rules

- Units must be placed inside a claim owned by the purchasing city
- Maximum active units: `5 + (2 per Fortification level)`, so 5 to 15
- Maximum 3 units per chunk (prevents stacking a death-blob on the City Hall)
- Units placed during ACTIVE war cost double, so defense must be planned in PREP

### 12.5 Implementation notes

- Store the unit's DB id in the entity's `PersistentDataContainer` so it survives chunk unload and server restart
- Set `setRemoveWhenFarAway(false)` and `setPersistent(true)`
- On chunk load, verify the entity still exists; if it was lost (chunk corruption, /kill), respawn it from the DB row
- Custom names use MiniMessage, e.g. `<gray>[<gold>Roma<gray>] <white>City Guard`, with name visible only within 16 blocks
- Units must not count toward the mob cap. Track them in a set excluded from natural spawn calculations where possible.

---

## 13. Progression: quests, challenges, contests, leaderboards

### 13.1 Daily quests

Three per player per day, drawn from a weighted pool, reset at 00:00 server time.

**Pool categories and examples:**

| Category | Example | Reward range |
|---|---|---|
| Farming | Harvest 256 wheat | 300 to 600 |
| Farming | Breed 20 animals | 300 to 500 |
| Building | Place 512 blocks of any type | 400 to 700 |
| Building | Craft 64 of any decorative block | 350 to 600 |
| Mining | Mine 128 iron ore | 400 to 700 |
| Trading | Sell 5,000 C worth to the market | 400 to 800 |
| Social | Deposit 2,000 C to your city treasury | 300 to 500 |
| Exploration | Visit 3 different biomes | 350 to 600 |

Quest difficulty scales mildly with player playtime so veterans do not trivially clear beginner quests, but rewards scale with it too, so the effort-to-reward ratio stays flat. **Rewards never scale with wealth.**

### 13.2 Weekly challenges

Two per week, city-wide (progress pooled across all members), reset Monday 00:00.

Examples: "Your city collectively harvests 25,000 crops", "Your city places 50,000 blocks", "Your city sells 500,000 C to the market", "Your city builds a structure at least 40 blocks tall".

Rewards go to the **treasury**, 2,500 to 8,000 C, reinforcing cooperative play.

### 13.3 Leaderboards

Wealth is deliberately **not** the headline leaderboard. There are seven, all shown with equal prominence in `/leaderboard`:

| Leaderboard | Metric |
|---|---|
| Wealth | Personal balance |
| Cities by Treasury | City treasury |
| Cities by Size | Claim count |
| Cities by Population | Active member count |
| Contest Champions | Cumulative contest points |
| War Record | Wins, with losses as a tiebreaker |
| Contribution | Lifetime treasury deposits (personal) |
| Builder | Blocks placed (excluding war zones) |
| Farmer | Crops harvested |

**Design note:** the user's stated goal of "competitive but not toxic" is directly served here. A player who cannot compete on wealth can be the server's top Builder or Farmer, which is a real, visible status. Servers where wealth is the only ladder become toxic because there is exactly one way to matter.

### 13.4 Building contests

Biweekly, 14-day cycles, run automatically.

**Cycle:**
1. **Day 0:** theme announced (from a config pool, e.g. "Medieval Market", "Underground Base", "Floating Island", "Grand Library", "Harbour Town"). Broadcast server-wide.
2. **Days 0 to 11:** cities build. Entry is a region inside their own claims, marked via `/contest mark` at two corners (max 64x64x64).
3. **Day 11:** submissions close. `/contest submit` finalises the entry and snapshots it.
4. **Days 11 to 13:** voting. Any player may visit entries via `/contest visit <n>` (teleports to a viewing platform above the build, spectator-ish, no build permission) and score them 1 to 10 across three axes: Creativity, Technical Skill, Theme Fit.
5. **Day 14:** scores tallied, prizes paid to treasuries, winners announced with a server broadcast and a permanent entry on the Contest Champions leaderboard.

**Anti-abuse:**
- Players cannot vote for their own city
- Votes from accounts with under 5 hours playtime are weighted 0.25x
- Votes from accounts sharing an IP with a member of the entered city are discarded
- Admins may disqualify entries via `/ca contest disqualify`
- A city may submit one entry per contest
- Entries must be built during the contest window (verified against block placement logs)

### 13.5 Server events

Automatic, scheduled, config-driven global events that create shared server moments.

| Event | Duration | Effect |
|---|---|---|
| Market Boom | 6h | Market sell prices +40% |
| Market Crash | 6h | Market sell prices -30%, buy prices -30% |
| Harvest Festival | 24h | Crop growth rate x2, farming quest rewards x2 |
| Gold Rush | 12h | Ore generation bonus via a temporary loot modifier, mining quests x2 |
| Invasion | 4h | Waves of hostile mobs spawn near city borders server-wide. Cities earn treasury rewards proportional to mobs killed inside their claims. Defense units earn their keep here. This is the PvE outlet for combat that does not require war. |
| Founders' Week | 7d | Claim costs -25%, city creation free |
| Double Upkeep | 24h | Upkeep doubled (a rare sink event, announced 48h in advance) |
| Tax Holiday | 24h | Market tax 0% |

Events are announced 30 minutes in advance with a countdown, and shown in a boss bar while active.

---

## 14. Diplomacy

### 14.1 Relations

Every ordered city pair has exactly one relation state:

| State | Meaning | Effects |
|---|---|---|
| NEUTRAL | Default | Normal protection, war may be declared |
| ALLY | Mutual, formal | Shared alliance chat, may join each other's wars, cannot declare war on each other, may grant reciprocal build access |
| TRUCE | Time-limited non-aggression | War cannot be declared until expiry |
| AT_WAR | Active war | See Section 11 |
| ENEMY | Post-war marker | Cosmetic only, shown in `/city info`, decays after 30 days |

### 14.2 Alliances

- `/ally invite <city>`, accepted with `/ally accept <city>`, both require `MANAGE_DIPLOMACY`
- Maximum 3 allies per city, so no server-wide mega-blocs form and the political map stays interesting
- Breaking an alliance (`/ally break`) has a 24-hour notice period, during which the alliance still holds. This prevents backstabbing an ally the instant a war is declared.
- After breaking, the two cities cannot re-ally for 7 days
- Allies may optionally grant reciprocal build access with `/ally trust <city> <on|off>`, which gives allied members `BUILD` and `INTERACT` (never `CONTAINER`) in each other's claims

### 14.3 Truces

- `/truce offer <city> <days>`, 1 to 30 days
- Cannot be cancelled early by either party (that is the whole point of a truce)
- A truce blocks war declaration in both directions
- Truces are automatically created for 7 days after a war ends between the two participants

---

## 15. Anti-toxicity systems

Consolidated here because these span multiple modules and must be implemented consistently.

### 15.1 Newcomer protection

- First 14 days after first join: all personal income multiplied by 1.5
- Cities under 14 days old: claim costs multiplied by 0.75
- Cities with fewer than 5 members: exempt from being declared upon by cities with more than 20 members (large cities cannot farm small ones)

### 15.2 Structural protections

| Mechanism | Prevents |
|---|---|
| Rollback | Permanent build loss, the number one reason players quit servers |
| War immunity (7 days) | Serial harassment of one city |
| 21-day same-opponent cooldown | Targeted bullying |
| Wager capped at 25% of the smaller treasury | Wealth-based coercion |
| Defender decline option | Forced participation |
| Member divisor on claim cost | Solo whales outpacing communities |
| Seven leaderboards | Wealth being the only status ladder |
| No passive income from land | Rich-get-richer compounding |
| Dynamic market pricing | One player monopolising an income source |
| Player shops untaxed | Encouraging inter-city trade over isolation |
| Minimum 3 members to declare war | Alt-account war spam |
| 24h city-switch cooldown | Mercenary hopping |
| Max 3 allies | Server-wide dominant blocs |

### 15.3 Reporting

`/report <player> <reason>` writes to a moderation queue visible via `/ca reports`, with automatic attachment of the reported player's last 50 ledger entries and last 50 war actions, so admins have context without asking.

---

## 16. Configuration files

### 16.1 `config.yml`

```yaml
storage:
  type: SQLITE            # SQLITE or MYSQL
  mysql:
    host: localhost
    port: 3306
    database: civitas
    username: ""
    password: ""
    pool-size: 10
  backup:
    enabled: true
    interval-hours: 6
    keep-count: 28

worlds:
  city-enabled:
    - world
  blacklisted:
    - world_the_end
    - world_nether       # nether claimable is a design choice, off by default

language: en

performance:
  claim-cache-size: 100000
  gui-refresh-ticks: 20
  ledger-batch-size: 200
  ledger-flush-seconds: 5
```

### 16.2 `cities.yml`

```yaml
creation:
  cost: 10000
  min-playtime-hours: 2
  name-min-length: 3
  name-max-length: 24
  name-pattern: "^[A-Za-z0-9_]+$"
  blocked-names: [admin, staff, server, console, null, undefined]
  min-distance-chunks: 5

claims:
  starter-flat-count: 8
  starter-flat-cost: 500
  formula-base: 400
  formula-exponent: 1.25
  distance-multiplier-per-chunk: 0.05
  distance-free-radius: 4
  member-divisor-per-member: 0.18
  active-member-days: 14
  new-city-discount: 0.75
  new-city-days: 14
  buffer-chunks: 5
  unclaim-refund-percent: 50
  enforce-contiguity: true
  radius-claim-max: 5

upkeep:
  enabled: true
  percent-of-land-value-per-day: 0.4
  charge-hour: 4                 # 04:00 server time
  grace-period-days: 3
  delinquent-auto-unclaim: true
  delinquent-unclaim-per-day: 3  # outermost chunks first

members:
  base-cap: 10
  switch-cooldown-hours: 24
  invite-expiry-minutes: 5

outposts:
  base-max: 2
  creation-cost-flat: 25000
  creation-cost-multiplier: 3.0
  upkeep-per-day: 2000
  min-distance-from-own-city: 32
  min-distance-from-other-city: 8
  teleport-cost: 100
  teleport-warmup-seconds: 8
  teleport-cooldown-seconds: 180
```

### 16.3 `war.yml`

```yaml
declaration:
  min-members: 3
  min-claims: 10
  min-city-age-days: 14
  min-wager: 50000
  max-wager-percent-of-smaller-treasury: 25
  same-opponent-cooldown-days: 21
  decline-window-hours: 6
  decline-penalty-percent: 30
  large-vs-small-block: true
  large-city-member-threshold: 20
  small-city-member-threshold: 5

phases:
  prep-hours: 48
  active-days: 7

zone:
  perimeter-chunks: 1

scoring:
  kill: 10
  capture-point-hold: 25
  capture-hold-seconds: 60
  destroy-defense-unit: 15
  block-break: 0.1
  block-break-score-cap: 500
  city-hall-reach: 100
  draw-threshold-percent: 5

rewards:
  winner-wager-share-percent: 80
  loser-refund-percent: 20
  burn-percent: 20
  ally-payout-share-percent: 30
  winner-market-bonus-percent: 10
  winner-market-bonus-days: 7
  immunity-days: 7

rollback:
  enabled: true                  # NEVER set false on a live server
  blocks-per-tick: 400
  checkpoint-every-blocks: 5000
  verify-sample-percent: 2
  chunk-hash-failsafe: true
  suppress-block-drops: true
  restore-entities: true
  restore-container-nbt: true
  loot-is-permanent: true        # stolen items not returned, see 11.7
  vault-immune: true
```

### 16.4 `economy.yml`, `defense.yml`, `events.yml`

Contain, respectively: the income and sink tables from Section 4, the unit catalogue from Section 12.2, and the event definitions from Section 13.5. All values from those tables are config keys with the documented defaults.

---

## 17. Edge cases

This section is exhaustive by design. Every case below must have an explicit test.

### 17.1 City lifecycle

| # | Case | Required behaviour |
|---|---|---|
| 1 | Mayor goes inactive for 30 days | Mayorship auto-transfers to the highest-weight member with the most recent login. Old mayor is demoted to Co-Mayor, notified on next login. |
| 2 | Entire city inactive 60 days | City is flagged `dormant`, claims become unprotected but are not removed. On any member login, protection restores instantly. |
| 3 | Entire city inactive 120 days | City is soft-deleted, claims released, treasury burned. 14-day admin restore window. |
| 4 | Mayor is the only member and leaves | Blocked. Must disband or transfer. |
| 5 | Mayor is banned from the server | City continues. Admin should use `/ca city setmayor`. |
| 6 | Two cities try to create with the same name simultaneously | Database unique constraint rejects the second. Handle the constraint violation and return a clean "name taken" message, do not leak an SQL error. |
| 7 | Player creates a city, immediately disbands, repeats | City creation has a 24-hour cooldown per player after any disband. |
| 8 | City name contains a slur or impersonates staff | Blocked-names list plus admin `/ca city rename`. |
| 9 | Mayor transfers to an offline player | Blocked. Target must be online and accept. Admin override exists. |
| 10 | City disbands with a positive treasury | Treasury is split evenly among all members, ledger `TREASURY_WITHDRAW`. |
| 11 | City disbands while a member is inside the GUI | Open GUIs referencing that city are force-closed with a message. |

### 17.2 Claims

| # | Case | Required behaviour |
|---|---|---|
| 12 | Unclaiming a chunk would split the city in two | Rejected. Message lists the chunks that would be orphaned. |
| 13 | Claiming the last available chunk exactly empties the treasury | Allowed. City immediately enters upkeep grace period. Warn the claimer before confirming. |
| 14 | Two players in the same city claim the same chunk at the same tick | Service layer holds a per-city lock. Second attempt fails cleanly. |
| 15 | Two different cities claim the same chunk in the same tick | Unique DB index rejects the second. The paying city is refunded automatically. |
| 16 | A player claims into a chunk containing another city's build (unclaimed but built) | Allowed. Builds do not confer ownership. Documented in the rules book. |
| 17 | Claim radius command where 3 of 9 chunks are invalid | Atomic: all 9 succeed or none do. Report which failed and why. |
| 18 | City has zero claims (all admin-purged) | City enters `homeless` state. Cannot claim (no adjacency anchor). Admin must `/ca claim force` or the city must disband. |
| 19 | Core chunk is force-unclaimed by an admin | The oldest remaining claim is promoted to CORE. If none, city becomes `homeless`. |
| 20 | Player claims in a chunk that is partially inside a world border | Allowed if the chunk origin is inside. |
| 21 | World is removed from `city-enabled` while claims exist there | Existing claims persist and remain protected. New claims blocked. Warn on startup. |
| 22 | City spawn chunk is unclaimed | Spawn resets to core chunk center, mayor notified. |
| 23 | Player attempts to claim in another server's world via multiverse | World name is part of the claim key, so it is naturally isolated. |

### 17.3 Economy

| # | Case | Required behaviour |
|---|---|---|
| 24 | Negative balance from a race condition | Balance is `DECIMAL`, all mutations go through a single synchronised service method with a pre-check. A negative result throws, is logged as a critical error, and rolls back the transaction. |
| 25 | Player pays themselves | Rejected. |
| 26 | Player pays an amount with more than 2 decimal places | Rounded down (floor) to 2 places. |
| 27 | Integer overflow on very large balances | `DECIMAL(20,2)` gives headroom to 10^18. A hard config cap (`economy.max-balance`, default 10^12) rejects anything beyond. |
| 28 | Market stock hits zero | Price clamps at 3.0x base. Buying is still allowed (the market is infinite), stock goes negative internally but the clamp holds the price. |
| 29 | Player sells an item with NBT (enchanted, named, damaged) | Only vanilla, undamaged, un-enchanted, un-named items are accepted by the market. Everything else is rejected with a clear message. |
| 30 | Shulker box full of items sold | Rejected. Containers with contents are never accepted by the market. |
| 31 | Upkeep charge fires while the server is offline | On startup, catch-up: charge for each missed cycle, up to a max of 7 cycles, then reset the timer. |
| 32 | Treasury cannot pay upkeep | Enter `delinquent` state. 3-day grace with escalating warnings to all members. After grace, auto-unclaim 3 outermost chunks per day (with 50% refund credited to the treasury, which may itself clear the debt) until solvent. Core chunk is never auto-unclaimed. |
| 33 | Two upkeep cycles fire in the same tick after a lag spike | Idempotency: `upkeep_due` is compared and advanced atomically. A cycle can only be charged once. |
| 34 | Player logs off mid-transaction | All economy operations are atomic at the service layer and do not depend on the player remaining online. |
| 35 | Admin gives money then rolls it back after the player spent it | `/ca eco rollback` writes compensating entries and may take the player negative. Balance floors at 0 and the remainder is recorded as `debt` in metadata for admin follow-up. |

### 17.4 War

| # | Case | Required behaviour |
|---|---|---|
| 36 | Server crashes mid-war | State persisted. On restart, war resumes with corrected timers. |
| 37 | Server crashes mid-rollback | Resume from last checkpoint. Zone stays closed until rollback completes. |
| 38 | Defender city disbands during PREP | Blocked, disband is disabled during PREP and ACTIVE. |
| 39 | Defender's last member quits the server permanently mid-war | War continues to its natural end. Attacker wins by default at the timer. Rollback still runs. |
| 40 | Attacker city goes bankrupt during war | Irrelevant, the wager was already escrowed at declaration. |
| 41 | A player joins the server for the first time during a war and is standing in the war zone | Not a member of either city, so they are not a valid target and cannot grief. They are teleported out of the zone on join with an explanatory message. |
| 42 | A block is placed in war, then broken in war, then placed again | Reverse-order replay handles this correctly by construction. Test explicitly. |
| 43 | A chest is broken in war while containing items | Items do not drop (drops suppressed). The chest and its full NBT contents are restored by rollback. Net effect: the defender loses nothing, the attacker gains nothing, which is the correct outcome for a *destroyed* container. |
| 44 | A chest is *opened* in war and items removed by hand | Items are permanently lost (11.7). The chest itself is restored empty of the taken items, because the container NBT logged is the state at the time of the block change, and no block change occurred. Explicitly document this asymmetry to players: **destroying storage is pointless, looting it is not.** |
| 45 | TNT chain reaction destroys 40,000 blocks in one tick | `EntityExplodeEvent` gives the full block list. Log all of them in one batch. Throttle nothing during logging, only during rollback. |
| 46 | Lava flows from the war zone into an adjacent non-war city | Fluid flow across the war zone boundary is cancelled outright. |
| 47 | War ends while 30 players are inside the zone | All are teleported out during the evacuation step before rollback begins. |
| 48 | A player logs off inside the war zone and logs back in after rollback | On join, if their stored location is inside a zone that was rolled back, they are moved to the nearest safe location. Prevents suffocation in a restored block. |
| 49 | Rollback tries to restore a block in an unloaded chunk | Chunk is loaded on demand, restored, then unloaded. Never assume a chunk is loaded. |
| 50 | Rollback tries to restore a block where a player is standing | Restore anyway, then run a safe-location check on all nearby players. |
| 51 | Two wars overlap geographically (city A vs B, and city C vs D, with adjacent claims) | Each war has its own zone and its own block log. A chunk in both zones logs to both. Rollback order is by war end time. Test this explicitly, it is the most likely source of a corrupt restore. |
| 52 | A city is in a war and its ally is in a different war simultaneously | Allowed. A city may only be in one war, but its allies may be in others. |
| 53 | War declared, then an admin unclaims chunks from the defender | Zone is precomputed at war start and does not change. The unclaimed chunks remain part of the zone. |
| 54 | Wager exceeds 25% of the smaller treasury because the treasury shrank between declaration and PREP end | Irrelevant, the escrow happened at declaration. |
| 55 | Both cities score exactly zero | Draw. Both wagers refunded. |
| 56 | Defense unit is killed by its own city's member | Allowed (removing a unit refunds nothing). No score awarded to anyone. |
| 57 | Rollback verification sample finds a mismatch | Log ERROR with coordinates, continue the rollback, then surface the mismatch list in `/ca war rollbackstatus`. Do not abort. |
| 58 | The war block log grows beyond a configured size limit (e.g. 5 million rows) | Warn admins at 80% of the limit. At 100%, stop accepting new grief (block breaking in the zone is cancelled with a message) rather than risk an incomplete rollback. Correctness over gameplay. |

### 17.5 GUI

| # | Case | Required behaviour |
|---|---|---|
| 59 | Player's permission is revoked while their GUI is open | Every click re-validates permission server-side at execution time. The stale GUI cannot be exploited. |
| 60 | Player is kicked from the city while the city GUI is open | GUI force-closes on the next refresh tick. |
| 61 | Player drags items in a GUI inventory | All `InventoryDragEvent` and `InventoryClickEvent` in plugin GUIs are cancelled unconditionally before any handling. |
| 62 | Player shift-clicks from their own inventory into a GUI | Cancelled. |
| 63 | Player uses a number key to swap a hotbar item into a GUI slot | Cancelled (`ClickType.NUMBER_KEY` must be explicitly handled, it is a common exploit vector). |
| 64 | Player opens a GUI and the server lags for 10 seconds | GUI data is snapshotted at open; refresh reconciles. Actions validate against live data, never the snapshot. |
| 65 | Two members edit the same rank simultaneously | Last write wins, but both are notified of the change. Rank permission is a single bitmask column so there is no partial-write risk. |
| 66 | Player closes the GUI mid-confirmation | Treated as cancel. No pending state persists. |
| 67 | Amount-input anvil GUI receives non-numeric text | Rejected with a message, GUI reopens. |
| 68 | Amount-input receives a negative number or scientific notation | Parsed strictly, rejected. |

### 17.6 Fraud, cheating, and abuse

| # | Case | Detection and response |
|---|---|---|
| 69 | Alt accounts created to inflate a city's member count (lowering claim cost) | The `active_members` divisor counts only accounts with 2+ hours of active playtime AND a login in the last 14 days. `/ca alts` surfaces IP-linked accounts. |
| 70 | Alt accounts farming daily login and quest rewards | Same playtime gate on all income sources. New accounts earn nothing for the first 30 minutes of active playtime. |
| 71 | A member drains the treasury and leaves | 25% per-24h withdrawal cap for non-mayors (5.5). Full ledger trail. `/ca eco rollback` reverses it. |
| 72 | Vote manipulation in contests | 13.4 anti-abuse rules. IP-matched votes discarded, low-playtime votes weighted down. |
| 73 | Duplication exploit through war rollback | The no-drops rule (11.8.3) removes the primary vector. Additionally: an item-count audit compares total server-wide item counts before and after each rollback and logs a warning on unexplained growth. |
| 74 | Claim-flipping for profit | Unclaim refunds 50% to treasury, never to a player, and cost is monotonically increasing, so flipping is always a strict loss. |
| 75 | Market arbitrage via the buy/sell spread | 1.35x spread makes instant buy-then-sell a guaranteed 26% loss. |
| 76 | A city declares war purely to grief with no intent to fight | The block-break score cap (500 points) makes pure demolition a losing strategy, rollback makes it pointless, and the wager makes it expensive. |
| 77 | Player uses `/city spawn` to escape combat | Teleport has a 15-second warmup during war and is cancelled by damage. |
| 78 | Player stores valuables in a chest just outside the war zone | Working as intended, this is legitimate strategy. The vault exists for the same purpose. |
| 79 | Suspicious-transaction heuristics | `/ca audit suspicious` flags: single withdrawals over 40% of treasury, more than 5 transfers to the same player in 1 hour, a player receiving more than 3x their lifetime earnings in 24h, a new account receiving over 100k C, treasury dropping over 60% in under 10 minutes, and any player whose income rate exceeds the 99th percentile by more than 3x. |
| 80 | Admin abuse | All admin actions write to `audit_log` with actor, target, timestamp, and reason. `/ca ledger type ADMIN_GIVE` surfaces every admin grant. The audit log is separate from the ledger and cannot be cleared in-game. |

### 17.7 Performance and scale

| # | Case | Required behaviour |
|---|---|---|
| 81 | 50,000 claims across 200 cities | Claim lookup is O(1) via a packed-long hash map. Memory: roughly 50 bytes per claim, so 2.5 MB. Fine. |
| 82 | 100 players online, all breaking blocks in a war zone | Block log writes are buffered and async. Target 2,000 writes/sec sustained. Benchmark this before launch. |
| 83 | A single war produces 2 million logged block changes | 2M rows at ~200 bytes is 400 MB. Rollback at 400 blocks/tick takes ~83 seconds of server time. Acceptable. Warn admins above 1M. |
| 84 | Plugin disable during an active war | Flush the log buffer synchronously in `onDisable()`. Never lose buffered entries. |
| 85 | Database connection lost mid-war | Buffer entries in memory (bounded, 100k), retry with backoff. If the buffer fills, stop accepting grief rather than lose log entries. |
| 86 | 500 players open GUIs simultaneously | GUI construction is cheap and cached per-layout. Only dynamic values are recomputed. |

---

## 18. Testing requirements

Every item below is a required test before the corresponding milestone is considered complete.

### 18.1 Unit tests (JUnit 5, no server required)

- Claim cost formula: all reference values in the Section 6.2 table, to within 1 C
- Member divisor at 1, 5, 10, 25 members
- Distance multiplier at distances 0, 4, 5, 20
- Contiguity flood-fill: a straight line, an L shape, a ring, a ring with the connecting chunk removed (must fail), a city with outposts (outposts excluded from the check)
- Permission bitmask: grant, revoke, "cannot grant what you lack", "cannot edit equal or higher weight"
- Market price formula at stock 0, at target, at 10x target, and clamp boundaries
- Upkeep calculation for cities of 8, 50, 200 claims
- War score tally including the block-break cap
- Payout math for win, loss, draw, decline, and ally splits

### 18.2 Integration tests (MockBukkit)

- Full city creation flow including all 9 preconditions failing individually
- Claim, unclaim, and contiguity rejection
- Treasury deposit, withdraw, and the 25% cap
- Rank creation, assignment, and permission enforcement on a block break
- GUI click validation with a revoked permission

### 18.3 Manual war test protocol (mandatory before any public launch)

Run on a test server with at least 2 accounts:

1. Create two cities, 10+ claims each, 3+ members each (use alt accounts)
2. Build a distinctive structure in the defender city containing: a chest with items, a furnace mid-smelt, a sign with text, an item frame with an item, a beehive with bees, a spawner, a banner with a pattern, redstone circuitry, water, lava, sand suspended on a torch
3. Take screenshots and record exact coordinates of every element
4. Declare war, wait out PREP
5. During ACTIVE: break every element above, blow up part of the structure with TNT, set fire to it, flood it with lava, loot the chest by hand
6. Restart the server mid-war to verify state persistence
7. End the war
8. **Verify:** every block, sign text, item frame contents, furnace contents, banner pattern, spawner type, and redstone state matches the pre-war screenshots exactly. The chest exists and contains everything except what was hand-looted.
9. Run `/ca war verify <id>` and confirm zero mismatches
10. Repeat with the server killed (SIGKILL) mid-rollback, confirming resume-from-checkpoint

**Do not launch publicly until step 8 passes cleanly three times in a row.**

---

## 19. Build plan for Claude Code sessions

Each milestone is one or more sessions. Do not start a milestone before the previous one compiles, passes its tests, and is committed.

| M | Milestone | Deliverable | Depends on |
|---|---|---|---|
| 0 | Project skeleton | Gradle build, `plugin.yml`, main class, config loading, MiniMessage setup, lang files, empty command tree, plugin enables cleanly on a Paper server | none |
| 1 | Storage layer | SQLite + MySQL support, HikariCP, migration runner, all DAOs, all tables from Section 3, async access patterns, backup task | M0 |
| 2 | Core city model | City, Member, Rank entities. CityService: create, disband, join, leave, kick, transfer. Permission bitmask. Ranks. Unit tests. | M1 |
| 3 | Claim system | Claim cost engine, adjacency, contiguity flood-fill, claim/unclaim/radius, claim cache, `/city map`, border particles, enter/leave messages. Full unit test coverage on the cost formula. | M2 |
| 4 | Land protection | All listeners from Section 5.5. This is where the plugin becomes actually usable. | M3 |
| 5 | Economy core | Balances, treasury, ledger, `/pay`, deposit, withdraw, withdrawal cap, upkeep task, delinquency and auto-unclaim | M2 |
| 6 | Market and player shops | Dynamic pricing engine, `/shop` GUI, `/sell`, `/worth`, chest shops, market tax | M5 |
| 7 | GUI framework | Menu base classes, pagination, confirmation dialogs, YAML-driven layouts, click validation hardening (cases 59 to 68) | M2 |
| 8 | All GUI screens | Every menu in Section 8, City Hall block | M7, M3, M5 |
| 9 | Income systems | Playtime stipend with anti-AFK, daily login, quests, weekly challenges | M5 |
| 10 | Outposts | Full outpost system, Section 7 | M3, M5 |
| 11 | City upgrades | Section 5.7, including the vault | M5, M8 |
| 12 | Custom mobs | Defense unit catalogue, placement, behaviour, persistence, upkeep | M5, M8 |
| 13 | Diplomacy | Alliances, truces, relations, alliance chat | M2 |
| 14 | Leaderboards | All seven leaderboards, `/leaderboard`, caching | M5 |
| 15 | Contests | Full biweekly contest cycle, marking, submission, voting, anti-abuse, scoring | M14, M8 |
| 16 | Server events | Event scheduler, all events from Section 13.5, boss bar, announcements | M5, M6 |
| 17 | **War: block logging** | The logger only. Every source in 11.8.1. Ring buffer, async batching, crash-safe flush. **No war gameplay yet.** Benchmark to 2,000 writes/sec. | M4 |
| 18 | **War: rollback engine** | Reverse replay, NBT restore, throttling, checkpointing, resume-on-startup, verification sampling, chunk-hash failsafe. Test it by manually populating a block log and rolling it back, still with no war gameplay. | M17 |
| 19 | **War: lifecycle** | Declaration, PREP, ACTIVE, resolution, wagers, scoring, capture points, zone computation, all restrictions from 11.11 | M18, M13 |
| 20 | **War: hardening** | Every case in 17.4. The manual test protocol in 18.3. Overlapping wars. Crash recovery. | M19 |
| 21 | Admin tooling | Every command in Section 9.4, audit log, fraud heuristics, inspect mode, CSV export | all |
| 22 | Anti-toxicity pass | Section 15, verify each mechanism is actually implemented and configurable | all |
| 23 | Polish | Localisation completeness, tab completion everywhere, help pages, rules book, performance profiling, `/ca perf` | all |

**Critical ordering note:** milestones 17 and 18 build and test the rollback engine *before* any war gameplay exists. This is deliberate. If rollback is built last, it will be tested under time pressure with players waiting, which is exactly how a plugin ends up eating someone's castle.

---

## 20. Open decisions

Items the developer should decide before the relevant milestone. The agent should implement the marked default and record any change here.

| # | Decision | Default | Milestone |
|---|---|---|---|
| 1 | Are the Nether and End claimable? | No | M3 |
| 2 | Is there a personal `/home` system alongside city spawn? | No, city spawn only | M2 |
| 3 | Does the market buy mob drops? | No, see 4.4 exclusion list | M6 |
| 4 | Can a city hold territory in multiple worlds? | Yes, but contiguity is per-world and the core defines the primary world | M3 |
| 5 | Should war allow the attacker to permanently capture chunks on victory? | No. Contradicts pillar 1.2. | M19 |
| 6 | Chat format integration (prefix with city tag) | Yes, via a low-priority `AsyncChatEvent` handler, disableable for compatibility | M2 |
| 7 | Vault and PlaceholderAPI integration | Yes for PlaceholderAPI, optional Vault economy provider | M5 |
| 8 | Discord webhook for wars and contests | Post-1.0 | later |
| 9 | Dynmap or BlueMap claim rendering | Post-1.0, but design the claim API to support it | later |

---

# PART II, Economy Hardening, Command Completeness, and Player Feedback

> Appended to SPEC.md. Sections 21 to 24 continue the numbering of Part I.
> **Section 21 supersedes Section 4.4 where the two conflict.** The market item
> table in 4.4 is retained only as a historical record of the original design and
> must not be implemented. Implement Section 21.10 instead.

---

## 21. Economy threat model

### 21.1 Why this section exists

The economy in Part I was designed for balance but not for adversaries. Two audits were run against it. Both found breaking flaws. This section documents the flaws, the reasoning, and the corrected design.

The governing principle: **a Minecraft economy is not broken by players spending too much, it is broken by money being created faster than it is destroyed.** Every exploit below is a variant of "a player found a way to create money at a rate the designer did not anticipate." Sinks do not save you, because sinks scale with what a player chooses to spend and sources scale with what a machine can produce while the player sleeps.

The second governing principle: **automation is the enemy of a priced economy.** Minecraft is a game about building machines. Any item a machine can produce without a human present will eventually be produced in unlimited quantity. Pricing such an item at any non-zero value creates an infinite money generator. This is not a hypothetical, it is the single most common cause of death for server economies.

### 21.2 Audit result 1: the market table in Section 4.4 is unusable

Measured throughput of standard vanilla farms, priced against the Part I market table:

| Farm | Output per hour | Value per hour | Value per AFK day (20h) |
|---|---|---|---|
| Raid farm (emeralds) | 600 | 150,000 C | **3,000,000 C** |
| Nether portal gold farm | 2,000 | 140,000 C | **2,800,000 C** |
| Iron farm, 4 module | 1,600 | 72,000 C | **1,440,000 C** |
| Bonemeal wheat farm | 6,000 | 18,000 C | 360,000 C |
| Iron farm, 1 module | 400 | 18,000 C | 360,000 C |
| Sugarcane farm | 4,000 | 16,000 C | 320,000 C |
| Melon farm | 8,000 | 16,000 C | 320,000 C |
| Bamboo farm | 12,000 | 12,000 C | 240,000 C |
| **Human farming, actively playing** | 900 | 2,700 C | 54,000 C |

Part I targets roughly 60,000 to 100,000 C per day for an entire ten-member city. A single player with one raid farm produces **thirty to fifty times the intended income of an entire city**, while asleep.

This is not a tuning problem that smaller numbers fix. Lowering emerald price to 1 C still yields 12,000 C/day for zero effort, and any price that makes a raid farm reasonable makes manual mining worthless. **The item must not be purchasable by the server at any price.**

Note the ratio in the last two rows: an automated iron farm and an actively-playing human farmer both produce 360,000 C/day. The machine wins because it runs 20 hours a day and the human does not. Any economy where these compete is an economy that punishes playing the game.

### 21.3 Audit result 2: dynamic pricing creates crafting arbitrage

This flaw is subtle and would not have been found by playtesting until someone exploited it for millions.

The 1.35x buy/sell spread protects against wash trading the *same* item. It does **not** protect across a crafting recipe, because the two items have independent prices that move independently.

Take iron ingots (base 45) and iron blocks (base 405, priced fairly at 9x). Nine ingots craft into one block, and one block crafts back into nine ingots.

| Market state | Ingot spot | Block spot | Buy 9 ingots, craft block, sell | Buy block, craft 9 ingots, sell |
|---|---|---|---|---|
| Both at equilibrium | 45.00 | 405.00 | -141.75 | -141.75 |
| Ingots dumped to floor | 11.25 | 405.00 | **+268.31** | -445.50 |
| Blocks dumped to floor | 45.00 | 101.25 | -445.50 | **+268.31** |
| Ingots bought to cap | 135.00 | 405.00 | -1235.25 | **+668.25** |

Three of four states contain an infinite money loop. Worse, **a player can create the exploitable state deliberately** by dumping one side of the pair, then farming the loop until the prices converge. The dynamic pricing that was supposed to protect the economy is the mechanism that breaks it.

The same flaw applies to every reversible recipe in the game:

```
iron ingot <-> iron block          gold ingot <-> gold block
diamond <-> diamond block          emerald <-> emerald block
lapis <-> lapis block              redstone <-> redstone block
coal <-> coal block                copper ingot <-> copper block
netherite <-> netherite block      slimeball <-> slime block
bone meal <-> bone block           wheat <-> hay bale
dried kelp <-> dried kelp block    snowball <-> snow block
amethyst shard <-> amethyst block  quartz <-> quartz block
raw iron <-> raw iron block        raw gold <-> raw gold block
raw copper <-> raw copper block    honeycomb <-> honeycomb block
glowstone dust <-> glowstone       wheat seeds, melon, and all 1:9 pairs
```

And to every irreversible recipe where both sides are traded (log to planks, sand to glass via smelting, raw ore to ingot via smelting, cane to paper).

**Fix, and it is structural rather than numerical:** the market trades **exactly one item per crafting equivalence class**, always the most raw form. If ingots are tradeable, blocks are not. If logs are tradeable, planks are not. If raw iron is tradeable, ingots are not. With only one side listed, **no loop exists in the graph**, and arbitrage is impossible by construction rather than by careful price tuning.

This must be enforced in code, not left to config discipline. See 21.10.4.

### 21.4 Threat catalogue

Every threat below must have a test in Section 18 and a mitigation implemented.

#### Class A: automated production

| # | Vector | Rate | Mitigation |
|---|---|---|---|
| A1 | Iron farm | 400 to 1,600 ingot/h | Iron not purchasable |
| A2 | Nether portal gold farm | 2,000 ingot/h | Gold not purchasable |
| A3 | Raid farm | 600 emerald/h plus totems | Emeralds not purchasable, ever |
| A4 | Villager trading hall | unbounded | See Class D |
| A5 | Mob grinder (drops) | 5,000 items/h | No mob drops purchasable |
| A6 | Sugarcane, bamboo, cactus, kelp | 4,000 to 12,000/h | Not purchasable |
| A7 | Melon and pumpkin farm | 8,000/h | Not purchasable |
| A8 | Bonemeal-accelerated crop farm | 6,000/h | Crops purchasable but quota-capped, see 21.5 |
| A9 | Automatic tree farm | 3,000 log/h | Logs quota-capped, planks not purchasable |
| A10 | Honey and cocoa farm | fully auto | Not purchasable |
| A11 | AFK fish farm (treasure loot) | 1 item/30s plus enchanted books | Fishing loot not purchasable |
| A12 | Squid and glow squid ink farm | fully auto | Not purchasable |
| A13 | Cobblestone and stone generator | unbounded | Not purchasable |
| A14 | Amethyst budding farm | semi-auto | Not purchasable |
| A15 | Wither skeleton skull farm | slow but auto | Not purchasable |
| A16 | Creeper and gunpowder farm | fully auto | Not purchasable |
| A17 | Chicken and egg cooker | fully auto | Not purchasable |
| A18 | Villager-based automatic crop harvester | fully auto | Crops are quota-capped, which bounds this |

**The rule that generalises all of the above:** before any item is added to the market buy list, the developer must answer in writing, in a comment next to the config entry, the question *"can this item be produced by a machine with no player present?"* If the answer is yes or unknown, the item is not purchasable. This comment is a required part of the config file format.

#### Class B: crafting and smelting arbitrage

| # | Vector | Mitigation |
|---|---|---|
| B1 | Reversible n:1 recipes (see 21.3 list) | One item per equivalence class |
| B2 | Irreversible recipes with both sides listed (log to plank) | One item per equivalence class |
| B3 | Smelting arbitrage (raw ore to ingot, sand to glass, log to charcoal) | Smelting counts as a crafting edge for equivalence purposes |
| B4 | Stonecutter arbitrage (1 stone to 1 slab, slabs craft back at 2:1 loss but stonecutter is 1:1 for many) | Stonecutter recipes included in the recipe graph |
| B5 | Multi-step laundering (A to B to C where A and C are both listed) | Graph reachability check, not just direct-edge check |
| B6 | Crafting-table 2x2 vs 3x3 asymmetries | Covered by the graph check |

#### Class C: duplication glitches

Vanilla and near-vanilla duplication exists in 1.21 and will not be fully patched. Anything dupeable must be worthless to the server.

| # | Vector | Mitigation |
|---|---|---|
| C1 | TNT duping | TNT, gunpowder, and sand not purchasable |
| C2 | Sand and gravel duping | Not purchasable |
| C3 | Carpet, rail, and string duping | Not purchasable |
| C4 | Shulker box item duplication variants | Containers with contents rejected by market (Part I case 30) |
| C5 | Bedrock breaking to reach void loot | No mitigation needed, no items involved |
| C6 | Any future unknown dupe | **Circuit breaker, see 21.7** |

C6 is the important one. You cannot enumerate future exploits. The circuit breaker is the mitigation for the exploits you have not thought of yet, and it is the single most valuable safety mechanism in this section.

#### Class D: the villager economy

Villagers are a parallel economy with fixed prices that the plugin does not control. They are the most common way a server economy is destroyed after automated farms.

| # | Vector | Mechanism | Mitigation |
|---|---|---|---|
| D1 | Farmer villager crop-to-emerald | 20 carrots buys 1 emerald, infinitely | Emeralds not purchasable |
| D2 | Emerald-to-item then item-to-market | Buy any villager good with farmed emeralds, sell to market | Nothing a villager sells is purchasable by the market |
| D3 | Librarian book farming | Mending and other books have real value | Enchanted items not accepted by market (Part I case 29) |
| D4 | Cleric rotten flesh to emerald | Zombie farm becomes an emerald farm | Emeralds not purchasable |
| D5 | Fletcher stick to emerald | Tree farm becomes an emerald farm | Emeralds not purchasable |
| D6 | Wandering trader rare items | Low volume | Not purchasable |

**Rule:** the set of items the server market buys and the set of items obtainable from villager trades must be **disjoint**. This must be validated at plugin startup against a hardcoded list of villager trade outputs, and the plugin must log a severe warning and refuse to enable the market module if the sets intersect. This is a startup assertion, not a documentation note.

#### Class E: market manipulation

Tested and analysed in detail.

| # | Vector | Analysis | Verdict |
|---|---|---|---|
| E1 | Wash trading one item | Buy at spot x 1.35, sell at spot. Net loss at every price level (-3.94 at floor, -15.75 at equilibrium, -47.25 at cap per unit). | **Safe** |
| E2 | Dump to crash, rebuy cheap, wait for recovery | Stock decay pulls stock toward target, so a dumped market's stock falls back and price rises. The rebuy therefore happens at a higher price than the dump average. | **Safe** |
| E3 | Buy out stock to spike price, then sell own reserves | The buy-out itself costs 1.35x on a rising curve. Selling into the spike immediately depresses it. Modelled net is negative. | **Safe, but see E4** |
| E4 | Coordinated multi-player corner (10 players buy out, 1 sells) | Cost is socialised across attackers, profit concentrated. Theoretically positive for the seller if the buyers act as an unpaid cartel. | **Mitigated by the daily quota, which caps the seller's extraction regardless** |
| E5 | Cross-item arbitrage | See Class B | Fixed structurally |
| E6 | Timing the stock decay | Decay is 2%/hour toward target, deterministic and public. A player could sell just before decay raises the price. Gain is a few percent. | **Acceptable, this is legitimate market play** |

E1 through E3 confirm the spread plus mean reversion design from Part I is sound in isolation. The flaws were never in the pricing curve, they were in the item list and the crafting graph.

#### Class F: exploits internal to this plugin

| # | Vector | Analysis | Mitigation |
|---|---|---|---|
| F1 | Claim then unclaim for profit | Refund is 50% of `cost_paid`, and the next claim at the same index costs full price. Strictly a 50% loss every cycle. | Safe by design, no change |
| F2 | Claim cheap during Founders' Week, unclaim later | Refund is 50% of the discounted price paid, not of current price. | Safe, but the implementation **must** refund from `cost_paid`, never recompute. Add a test. |
| F3 | Unclaim before upkeep, reclaim after | Each cycle costs 50% of the chunk price. Upkeep is 0.4% of land value per day. Dodging costs roughly 125x more than paying. | Safe by design |
| F4 | Wash-warring for the War leaderboard | Two friendly cities alternate wins. The 21-day cooldown caps this at roughly 17 wars per year, and each burns 20% of the wager. | Add: a war only counts toward the leaderboard if the losing side scored at least 25% of the winner's score. Collusive wars with a walkover score are recorded but not ranked. |
| F5 | Wash-warring to transfer money past the withdrawal cap | 20% is burned each transfer, making it a bad laundering channel. | Acceptable, and the burn makes it a net sink |
| F6 | Treasury laundering via disband | Create a city with alts, deposit, disband, treasury splits evenly among members. Bypasses the 25% withdrawal cap. | On disband, the treasury is split **proportionally to lifetime contribution**, not evenly. A member who contributed nothing receives nothing. |
| F7 | Bounty self-claiming | Place a bounty on an alt, kill the alt, reclaim. | A player may not claim a bounty they placed. A player may not claim a bounty on an account sharing their IP. Both are silent rejections that refund to the placer. |
| F8 | Contest prize farming with alt cities | Covered in Part I 13.4, but incomplete. | Add: a city must have at least 3 members with 5+ hours playtime to submit. Prize is paid to treasury, and treasury disband now splits by contribution (F6), closing the extraction path. |
| F9 | Quest exploit: place-and-break the same block | "Place 512 blocks" completed by placing and breaking one block 512 times. | Blocks placed by a player are tagged in a per-chunk placed-block cache. Breaking a player-placed block does not count for mining quests, and re-placing in a recently-broken position does not count for building quests. Cache TTL 24 hours. |
| F10 | Quest exploit: silk-touch ore replacement | "Mine 128 iron ore" completed by placing and mining the same ore repeatedly. | Same placed-block cache. Player-placed ore never counts. |
| F11 | Stipend farming with a macro or AFK pool | Part I requires 3 distinct actions per 15 minutes. A single macro could satisfy this. | Strengthened: the 3 actions must be of **3 different types**, and must occur in at least 3 different minutes of the 15-minute window. A single repeating macro produces one action type and fails. |
| F12 | Alt farming daily login and quests | Part I gates on playtime. | Strengthened: no income of any kind for the first 60 minutes of *active* playtime on a new account, and daily login rewards require 30 minutes of active playtime that day before paying out. |
| F13 | Selling into the market from a city vault during war | Vault is war-immune, so it is a safe store. Not an exploit, this is intended. | No change, working as designed |
| F14 | Upgrade purchase then city disband for refund | Upgrades are not refundable. | Explicitly: upgrades refund nothing on disband. Document in the GUI lore. |
| F15 | Outpost create then delete cycling | 50% refund of creation cost to treasury. Strict loss. | Safe by design |
| F16 | Joining a city, withdrawing 25%, leaving, repeat | The 24h city-switch cooldown limits this, but a rank with WITHDRAW should never be given to a new member. | Add: a member cannot withdraw from the treasury during their first 72 hours in a city, regardless of rank. Mayor is exempt. |

#### Class G: money supply accounting

The plugin must be able to answer, at any moment, "how much money exists and where did it come from." Without this you cannot detect an exploit you did not predict.

Every hour the plugin records a snapshot to a `money_supply` table: total player balances, total treasury balances, total escrowed (wagers and bounties), sum of all income by ledger type for the hour, and sum of all sinks by ledger type for the hour.

### 21.5 The daily sell quota, primary defense

This is the single most important mechanism in the revised economy, because it is **exploit-agnostic**. It bounds money creation regardless of how clever the exploit is.

**Rule:** each player may sell at most `economy.market.daily-sell-quota` (default **25,000 C**) of value to the server market per day. Past the quota, sell prices are multiplied by `economy.market.over-quota-multiplier` (default **0.2**).

Design notes:
- It is a **soft cap, not a hard block.** A player who hits it can still sell, just at a fifth of the value. Hard blocks feel like punishment and generate support tickets. Soft caps feel like diminishing returns and generate shrugs.
- Quota is measured in **value**, not item count, so it cannot be gamed by switching items.
- Quota resets at 00:00 server time along with quests.
- The newcomer 1.5x multiplier applies **within** the quota, not to the quota itself.
- **Player-to-player shops are not subject to the quota**, deliberately. Peer trade moves money, it does not create it. This makes the peer economy strictly more attractive than the server market for high-volume producers, which is exactly the behaviour we want: a player with a huge farm should be selling to other players, not to the void.

**What this buys you:** total daily money creation becomes a known, bounded, predictable number.

| Online players | Market cap/day | Stipend + login + quests | Total money creation/day |
|---|---|---|---|
| 10 | 250,000 | 34,400 | 284,400 |
| 30 | 750,000 | 103,200 | 853,200 |
| 60 | 1,500,000 | 206,400 | 1,706,400 |
| 100 | 2,500,000 | 344,000 | 2,844,000 |

Against this, a mature 100-chunk city sinks roughly 22,700 C/day in upkeep alone. The economy is now dimensionable: you can compute in advance whether the server inflates or deflates, and tune one number to fix it.

Compare this to the unbounded design: a single undetected exploit could add 3,000,000 C/day. With the quota, that same exploit adds at most 25,000 C/day per participating account, which the circuit breaker in 21.7 will catch within hours.

### 21.6 The market should mostly sell, not buy

A course correction that follows directly from pillar 1.4 (building and farming are the point).

Part I framed the market as primarily a *buyer* of player goods. That framing is what created the exploit surface, because every item the server buys is a potential money faucet. Every item the server *sells* is a money sink and carries no exploit risk at all.

**Revised framing:** the server market is primarily a **shop for builders**. It sells decorative and building blocks that are tedious or impossible to gather in quantity, in exchange for money. It buys only a narrow whitelist.

This does three things at once. It makes the market a large, permanent money sink instead of a faucet. It directly serves a building-focused server, because a player designing a cathedral wants 20,000 quartz blocks and does not want to mine them. And it collapses the exploit surface to the small buy list.

**Sell-only catalogue (server sells to players, no exploit risk, priced as a sink):** all stone and deepslate variants, all wood types and processed wood, terracotta and glazed terracotta, all concrete and powder, all wool and carpet, glass and stained glass and panes, quartz blocks and variants, prismarine, purpur, copper and its oxidation and waxed states, all slabs, stairs, walls, and fences, all dyes, banners and patterns, flowers and foliage, candles, amethyst blocks, mud and mangrove variants, tuff and its 1.21 variants, bricks, and every decorative block added in 1.20 and 1.21.

Price these at 1.5x to 4x the value of their raw inputs. A builder buying 20,000 quartz blocks removes a large amount of money from circulation in one transaction, which is exactly what a sink should do.

### 21.7 Circuit breakers

Mitigation for the exploits nobody has thought of yet. This is not optional.

The plugin monitors and acts automatically:

| Trigger | Threshold (config) | Automatic action |
|---|---|---|
| Server-wide money creation in 1 hour | > 3x the 7-day hourly average | Log SEVERE, alert online admins, **freeze all market sell operations server-wide**, broadcast a maintenance notice |
| Single player income in 24h | > 10x their own 30-day daily average | Log WARNING, flag in `/ca audit suspicious`, apply the over-quota multiplier immediately |
| Single item's server-wide sell volume in 1 hour | > 20x its 7-day hourly average | Automatically remove that item from the buy list, log SEVERE, alert admins |
| Total circulation growth | > 15% week over week | Console warning (already in Part I 4.8) |
| Total circulation growth | > 40% week over week | Freeze market sells, alert admins |
| Item count audit after a war rollback | Unexplained server-wide item growth | Log SEVERE with the item and delta (Part I case 73) |

**Freezing sells rather than the whole economy is deliberate.** Players can still buy, trade with each other, claim, and play. Only the money faucet closes. A server that halts entirely because of a suspected exploit does more damage than the exploit.

Every circuit breaker trip writes to `audit_log` and produces an in-game message to online admins and a console message with the full triggering data.

### 21.8 Removed from the market entirely

Never purchasable by the server, at any price, under any config. This list is enforced in code as a hardcoded blacklist that config cannot override, because a well-meaning admin editing a yml is exactly how a server dies.

```
HARD BLACKLIST (code-enforced, config cannot add these to the buy list):

  Emeralds and emerald blocks
  Iron (raw, ingot, nugget, block) and gold (raw, ingot, nugget, block)
  All mob drops: rotten flesh, bone, bonemeal, string, spider eye, gunpowder,
    blaze rod, ender pearl, slimeball, magma cream, phantom membrane, leather,
    feather, ink sac, glow ink sac, wither skeleton skull, shulker shell,
    all raw and cooked meat, all eggs
  Sugar cane, paper, sugar, bamboo, cactus, kelp and dried kelp, sea pickle
  Melon and melon slice, pumpkin and carved pumpkin
  Honey, honeycomb, cocoa beans, sweet berries, glow berries
  Cobblestone, stone, deepslate, sand, red sand, gravel, TNT, string, rails,
    carpet, snow and snowballs, ice of all kinds
  All fishing loot and all fish
  Nether wart, chorus fruit
  Any enchanted item, any damaged item, any renamed item, any container with
    contents, any item with custom NBT
  Any item obtainable from any villager trade
  Any item that is the non-raw side of a crafting equivalence class
```

Note what is missing from Part I's list that should have been there: **iron and gold**, which Part I priced at 45 and 70. Those two entries alone would have ended the server's economy within a week of launch.

### 21.9 What the market may buy

A deliberately small list. Everything here is either impossible to fully automate in vanilla, or is quota-bounded manual labour.

| Item | Base price | Target stock | Elasticity | Automatable? |
|---|---|---|---|---|
| Diamond | 400 | 1,500 | 0.60 | No, requires mining |
| Ancient debris | 2,500 | 150 | 0.70 | No |
| Nether quartz | 12 | 8,000 | 0.50 | No, requires mining |
| Wheat | 3 | 20,000 | 0.40 | Semi, quota-bounded |
| Carrot | 3 | 20,000 | 0.40 | Semi, quota-bounded |
| Potato | 3 | 20,000 | 0.40 | Semi, quota-bounded |
| Beetroot | 4 | 15,000 | 0.40 | Semi, quota-bounded |
| Oak/Birch/Spruce/etc log | 4 | 25,000 | 0.40 | Semi, quota-bounded |
| Sniffer-grown plants (torchflower, pitcher) | 300 | 300 | 0.65 | No |
| Archaeology pottery sherds | 400 | 200 | 0.70 | No |
| Trial chamber loot keys | 600 | 300 | 0.70 | No, requires combat |
| Echo shard | 800 | 200 | 0.70 | No, ancient city only |
| Heart of the sea | 1,200 | 100 | 0.75 | No |
| Nautilus shell | 200 | 500 | 0.60 | Only via AFK fishing, so quota-bounded, review at launch |

Fourteen entries instead of nineteen, but the five removed were the five that broke everything.

**Design intent of this list:** the highest-value income is exploration and mining, which requires a player to be present and actively playing. Farming is present but quota-capped, providing a reliable floor income rather than a path to wealth. The path to real wealth is the **player economy**, selling to other players, which creates no money at all and is therefore unbounded and safe.

### 21.10 Implementation requirements

These are code requirements, not guidance. Each has a test.

**21.10.1 Startup validation.** On enable, the market module runs these assertions and refuses to enable (logging SEVERE with the specific failure) if any fails:
- No item in the buy list appears in the hard blacklist
- No item in the buy list is obtainable from any villager trade (validated against a hardcoded trade-output list)
- No two items in the buy list are in the same crafting equivalence class
- Every buy-list entry has the required `# automatable: no|semi` comment parsed from config

**21.10.2 The crafting equivalence graph.** Built at startup by walking Bukkit's recipe iterator plus a hardcoded smelting and stonecutter table. Two items are in the same class if either is reachable from the other by any sequence of crafting, smelting, or stonecutting. The check is **transitive reachability, not direct-edge**, so multi-step laundering (A to B to C) is caught.

**21.10.3 The quota tracker.** A per-player daily counter, persisted, reset at 00:00. Every market sell checks the counter before pricing and applies the multiplier past the threshold. Must be exact under concurrency, so it goes through the same synchronised service method as balance mutation.

**21.10.4 Config cannot override safety.** The hard blacklist, the equivalence check, and the villager-disjointness check are code-level and are not readable from config. An admin can change prices, quotas, and elasticity. An admin cannot add emeralds to the buy list, even by editing the yml, even by editing the database.

**21.10.5 The placed-block cache.** A bounded per-chunk cache of positions a player placed a block at, TTL 24 hours, used by F9 and F10. Memory-bounded with LRU eviction. This is also useful for contest verification (Part I 13.4).

### 21.11 Revised `economy.yml` additions

```yaml
market:
  daily-sell-quota: 25000
  over-quota-multiplier: 0.2
  quota-reset-hour: 0
  spread: 1.35
  stock-decay-percent-per-hour: 2.0
  price-floor-multiplier: 0.25
  price-cap-multiplier: 3.0

  # Every buy entry REQUIRES an automatable comment. Startup fails without it.
  buy:
    DIAMOND:        { base: 400,  target: 1500,  elasticity: 0.60 }  # automatable: no
    ANCIENT_DEBRIS: { base: 2500, target: 150,   elasticity: 0.70 }  # automatable: no
    QUARTZ:         { base: 12,   target: 8000,  elasticity: 0.50 }  # automatable: no
    WHEAT:          { base: 3,    target: 20000, elasticity: 0.40 }  # automatable: semi
    # ... see 21.9

circuit-breaker:
  enabled: true
  hourly-creation-multiplier-trigger: 3.0
  player-income-multiplier-trigger: 10.0
  item-volume-multiplier-trigger: 20.0
  weekly-inflation-warn-percent: 15
  weekly-inflation-freeze-percent: 40
  action-on-trip: FREEZE_SELLS   # FREEZE_SELLS | WARN_ONLY

anti-abuse:
  new-account-income-block-minutes: 60
  daily-login-requires-active-minutes: 30
  treasury-withdraw-member-age-hours: 72
  stipend-required-distinct-action-types: 3
  stipend-required-distinct-minutes: 3
  placed-block-cache-ttl-hours: 24
  war-leaderboard-min-loser-score-percent: 25
  disband-treasury-split: BY_CONTRIBUTION   # BY_CONTRIBUTION | EVEN
```

---

## 22. Complete command reference

### 22.1 Audit of Part I

Part I Section 9 was incomplete. It covered the *actions* a player can take but not the *questions* a player asks. A player spends far more time asking "how much do I have," "what did I sell," "who is in my city," and "when is upkeep due" than they spend claiming chunks.

Commands missing from Part I, now added below:

| Missing | Severity | Why it matters |
|---|---|---|
| `/buy` | **Critical** | Part I had `/sell` but no way to buy from the market by command at all. The market was write-only. |
| `/transactions` | **Critical** | A player had no way to see their own transaction history. Only admins could. |
| `/city treasury` | High | Treasury was GUI-only. No command path. |
| `/city members`, `/city online` | High | Member list was GUI-only. |
| `/city upkeep` | High | A player could not check when upkeep is due or how much runway the city has. |
| `/city claims` | Medium | No list of owned chunks. |
| `/city perms` | Medium | No way to see what a rank can do without opening the editor. |
| `/city invites` | Medium | No way to see pending invites. |
| `/city log` | Medium | No city-level action history. |
| `/toggle` | High | Section 23 adds many messages. Without a toggle, chat becomes unusable. |
| `/playtime` | Low | Gates several systems, so players need to see it. |
| `/market history` | Medium | Dynamic pricing is invisible without a price history view. |
| `/quota` | High | The quota in 21.5 is meaningless if players cannot see their remaining quota. |
| `/city relations` | Low | Diplomacy state was GUI-only. |
| `/ca history` | High | Admins needed a market-filtered view, not the full ledger. |
| `/ca eco top` | Medium | Wealth concentration is the first thing to check for an exploit. |
| `/ca spy` | Medium | City chat monitoring for moderation. |
| `/ca quota` | Medium | Inspect and reset a player's quota. |

### 22.2 Command design rules

1. Every command has a tab completion for every argument. No exceptions.
2. Every command with a destructive effect requires an explicit confirm argument or a GUI confirmation.
3. Every command prints something. A command that succeeds silently is a bug.
4. Every command that fails explains **why** and **what to do next**, never just "you cannot do that."
5. Every list command is paginated at 10 entries with clickable page navigation.
6. Every command works from the GUI too. The GUI is the primary interface, commands are the power-user path.
7. Aliases are short and predictable. `/c` for city, `/b` for balance.
8. Amounts accept suffixes: `10k`, `1.5m`, `2b`, and `all` where meaningful.

### 22.3 Player economy commands

| Command | Aliases | Description | Output |
|---|---|---|---|
| `/balance [player]` | `/bal`, `/money`, `/b` | Own or another player's balance | Balance, plus today's net change |
| `/pay <player> <amount>` | | Transfer money | Confirms amount, recipient, new balance |
| `/transactions [page]` | `/txn`, `/history` | Own transaction history, last 30 days | Paginated, newest first, with type icons |
| `/transactions <type> [page]` | | Filtered by ledger type | Same |
| `/quota` | | Remaining daily market sell quota | Used, remaining, reset time, current multiplier |
| `/playtime [player]` | | Total and active playtime | Both figures, plus what it unlocks |
| **Market** | | | |
| `/shop` | `/market` | Opens the market GUI | |
| `/buy <item> [amount]` | | Buy from the server market | Item, qty, unit price, total, new balance |
| `/sell hand [amount]` | | Sell held item | Item, qty, unit price, total, tax, quota status |
| `/sell all <item>` | | Sell all of an item in inventory | Same |
| `/sell all` | | Sell everything sellable in inventory | Itemised breakdown, then total |
| `/worth [item]` | `/price` | Current buy and sell price | Both prices, stock level, 24h change |
| `/market history <item>` | | Price history | ASCII sparkline, 7-day high, low, current |
| `/market list [page]` | `/sellable` | Everything the market buys, with prices | Paginated |
| `/market buylist [page]` | | Everything the market sells | Paginated |
| **Player shops** | | | |
| `/shops [player]` | | List a player's chest shops with locations | |
| `/shops find <item>` | | Find player shops selling an item, nearest first | Distance, price, stock |
| **Bounties** | | | |
| `/bounty <player> <amount>` | | Place a bounty | |
| `/bounty list [page]` | | Active bounties | |
| `/bounty cancel <player>` | | Cancel own bounty, refunded | |

### 22.4 City information commands

All readable by anyone unless noted.

| Command | Aliases | Description |
|---|---|---|
| `/city` | `/c`, `/town` | Main GUI, or own city info if GUI disabled |
| `/city info [name]` | | Full city info: mayor, founded, members, claims, treasury, upkeep, war record, relations |
| `/city list [sort] [page]` | | All cities. Sorts: `size`, `wealth`, `members`, `age`, `name` |
| `/city members [name] [page]` | | Member list with ranks, last seen, contribution |
| `/city online [name]` | | Online members only |
| `/city claims [page]` | | Own city's chunk list with coordinates and claim dates |
| `/city map [size]` | | ASCII chunk map |
| `/city here` | `/city who` | Owner of the current chunk, and your permissions in it |
| `/city treasury` | `/city bank` | Balance, daily upkeep, days of runway, next charge time |
| `/city upkeep` | | Upkeep breakdown: land value, base rate, upgrade discounts, outpost costs, total, next charge, runway |
| `/city log [page]` | | City action log: joins, leaves, claims, withdrawals, rank changes, last 30 days |
| `/city perms [rank]` | | What each rank can do. With no argument, lists all ranks and member counts |
| `/city ranks` | | Rank list with weights |
| `/city invites` | | Pending invites you have received, and (with INVITE perm) pending invites your city has sent |
| `/city relations [name]` | | Allies, truces, wars, enemies, with remaining durations |
| `/city upgrades` | | Purchased upgrades, current levels, next level cost and effect |
| `/city outpost list` | | Outposts with names, coordinates, distance, upkeep |
| `/city outpost info <name>` | | Single outpost detail |
| `/city top [type]` | `/leaderboard`, `/lb` | Leaderboards, all seven types from Part I 13.3 |
| `/city stats [name]` | | Aggregate stats: blocks placed, crops harvested, total earned, war record, contest history |

### 22.5 City action commands

Unchanged from Part I 9.2 and 9.3, with these additions:

| Command | City permission | Description |
|---|---|---|
| `/city deposit all` | DEPOSIT | Deposit entire personal balance |
| `/city withdraw max` | WITHDRAW | Withdraw up to the remaining daily cap |
| `/city invite cancel <player>` | INVITE | Cancel a sent invite |
| `/city ban <player>` / `/city unban` | KICK | City-level ban list |
| `/city banlist` | member | View it |
| `/city rank clone <src> <new>` | MANAGE_RANKS | Copy a rank's permissions |
| `/city vault sort` | CONTAINER | Sort the shared vault |
| `/city outpost setwarp <name>` | OUTPOST_MANAGE | Already in Part I 7.3, restated here for completeness |

### 22.6 Preference commands

New. Required because Section 23 introduces a large number of messages.

| Command | Description |
|---|---|
| `/toggle` | Opens the notification preferences GUI |
| `/toggle <category> [on\|off]` | Toggle one category. Categories in 23.6 |
| `/toggle list` | Show all categories and current state |
| `/toggle compact [on\|off]` | Compact mode: one-line messages instead of multi-line |
| `/toggle sounds [on\|off]` | Mute plugin sounds |
| `/toggle actionbar [on\|off]` | Mute action bar feedback |
| `/lang <code>` | Set language, `en` or `it` |

### 22.7 Admin commands, additions to Part I 9.4

Part I 9.4 covered management. These add the **investigative** commands an admin actually needs when someone reports "player X has too much money."

#### 22.7.1 Financial investigation

| Command | Permission | Description |
|---|---|---|
| `/ca balance <player>` | `civitas.admin.info` | Player balance, plus 7-day and 30-day net change |
| `/ca treasury <city>` | `civitas.admin.info` | City treasury, plus 7-day and 30-day net change |
| `/ca history <player> [days]` | `civitas.admin.audit` | **Market activity only**, default 7 days: every buy and sell with item, quantity, unit price, total, timestamp, running balance. This is the command for "what did this player sell." |
| `/ca history city <city> [days]` | `civitas.admin.audit` | Same, aggregated for a city's members |
| `/ca history item <material> [days]` | `civitas.admin.audit` | Every transaction of one item server-wide, sorted by volume. Finds the exploit. |
| `/ca eco top [count]` | `civitas.admin.economy` | Richest players and cities, with 7-day change and percentage of total circulation |
| `/ca eco supply [days]` | `civitas.admin.economy` | Money supply over time: created, destroyed, net, by ledger type. The inflation dashboard. |
| `/ca eco sources <player> [days]` | `civitas.admin.audit` | Income broken down by source for one player. Answers "where did this come from." |
| `/ca quota <player>` | `civitas.admin.economy` | Current quota usage and history |
| `/ca quota reset <player>` | `civitas.admin.economy` | Reset a player's daily quota |
| `/ca quota set <player> <amount>` | `civitas.admin.economy` | Temporary quota override |
| `/ca market volume [hours]` | `civitas.admin.economy` | Sell volume by item, sorted, with deviation from the 7-day average. Circuit breaker view. |
| `/ca market audit` | `civitas.admin.economy` | Re-runs the 21.10.1 startup validations on the live config and reports failures |
| `/ca breaker status` | `civitas.admin.economy` | Circuit breaker state, recent trips, current thresholds |
| `/ca breaker reset` | `civitas.admin.economy` | Clear a tripped breaker and resume market sells |

#### 22.7.2 Moderation

| Command | Permission | Description |
|---|---|---|
| `/ca spy [on\|off]` | `civitas.admin.info` | See all city and alliance chat |
| `/ca reports [page]` | `civitas.admin.info` | Moderation queue from `/report` |
| `/ca reports view <id>` | `civitas.admin.info` | Report with auto-attached ledger and war context |
| `/ca reports close <id> <note>` | `civitas.admin.info` | Resolve |
| `/ca notes <player>` | `civitas.admin.info` | Staff notes on a player |
| `/ca notes add <player> <text>` | `civitas.admin.info` | Add a note |

#### 22.7.3 Data

| Command | Permission | Description |
|---|---|---|
| `/ca export ledger <days>` | `civitas.admin.audit` | Full ledger CSV |
| `/ca export cities` | `civitas.admin.audit` | All cities with stats CSV |
| `/ca export market <days>` | `civitas.admin.audit` | All market transactions CSV |
| `/ca whoami` | `civitas.admin` | Lists which admin permissions you hold. Useful on a staff team with tiers. |

### 22.8 Tab completion requirements

Non-negotiable, and a frequent source of bad plugin UX.

- Player name arguments complete against online players first, then known offline players
- City name arguments complete against existing cities, filtered by relevance (own city first, then allies, then all)
- Item arguments complete against the **market list**, not against all Minecraft materials. Suggesting 1,200 materials when 14 are sellable is hostile.
- Amount arguments suggest `100`, `1k`, `10k`, `100k`, `all`, and `max` where applicable
- Rank arguments complete against the player's own city's ranks
- Permission flag arguments complete against the 22 flags in Part I 5.4
- Admin subcommands complete only for players who hold the specific permission node, so a junior moderator does not see commands they cannot run
- Enum arguments (sort orders, leaderboard types, toggle categories) complete against their valid values

### 22.9 Command aliases summary

```
/c      -> /city              /bal, /money, /b -> /balance
/t      -> /city (town)       /txn, /history   -> /transactions
/cc     -> /city chat         /market          -> /shop
/ac     -> /ally chat         /price           -> /worth
/lb     -> /leaderboard       /sellable        -> /market list
/ca     -> /cityadmin
```

Alias conflicts with EssentialsX and similar plugins are likely. All aliases must be configurable in `config.yml` under `commands.aliases`, and the plugin must log a warning at startup listing any alias already registered by another plugin rather than silently losing the command.

---

## 23. Message and feedback system

### 23.1 Principles

1. **Every action produces feedback.** If a player does something and nothing appears, they assume it failed and do it again. Silent success is the most common bug in plugin UX.
2. **Feedback is private by default.** Transaction messages go to the actor and nobody else. A player selling 3 diamonds does not spam a 40-player server.
3. **Numbers are always shown.** Never "you sold your items." Always "you sold 64 wheat at 3.12 C each for 199.68 C, minus 9.98 C tax, new balance 12,847.22 C."
4. **The important number is visually distinct.** In any message, exactly one thing is the subject. That thing is bold and coloured. Everything else is gray.
5. **Failure messages state the reason and the remedy.** "You cannot claim this chunk" is useless. "This chunk is not adjacent to your city. Your nearest claim is 3 chunks north." is useful.
6. **Frequency-matched channel.** Constant feedback goes to the action bar, transactional feedback goes to chat, momentous feedback goes to a title, ongoing state goes to a boss bar.
7. **Everything is toggleable and everything is translatable.** No hardcoded strings, ever.
8. **Colour has consistent meaning.** Green is always money coming in. Red is always money going out or an error. A player should be able to read the colour before reading the words.

### 23.2 Colour palette

Defined once as MiniMessage tag resolvers, referenced by semantic name everywhere. Changing the palette must never require touching a message string.

| Token | Hex | MiniMessage | Meaning |
|---|---|---|---|
| `<pos>` | `#4ADE80` | green | Money in, success, gain |
| `<neg>` | `#F87171` | red | Money out, failure, loss |
| `<money>` | `#FBBF24` | gold | Currency amounts, always |
| `<subject>` | `#FFFFFF` + bold | white bold | The one thing the message is about |
| `<body>` | `#9CA3AF` | gray | All ordinary text |
| `<dim>` | `#4B5563` | dark gray | Brackets, separators, secondary detail |
| `<city>` | `#38BDF8` | aqua | City names |
| `<land>` | `#4ADE80` | green | Claims and territory |
| `<war>` | `#DC2626` | dark red | War |
| `<quest>` | `#C084FC` | light purple | Quests, challenges, contests |
| `<ally>` | `#FCD34D` | yellow | Allies, diplomacy |
| `<admin>` | `#EF4444` | red | Admin actions |
| `<link>` | `#60A5FA` + underlined | blue underlined | Clickable elements |

**Rule:** a message contains at most **three** coloured spans besides `<body>`. More than that and it reads as noise. If a message needs more emphasis than that, it is two messages or a GUI.

### 23.3 Prefixes

```yaml
prefix:
  economy: "<dim>[</dim><money>$</money><dim>]</dim> "
  city:    "<dim>[</dim><city>City</city><dim>]</dim> "
  land:    "<dim>[</dim><land>Land</land><dim>]</dim> "
  war:     "<dim>[</dim><war>War</war><dim>]</dim> "
  quest:   "<dim>[</dim><quest>Quest</quest><dim>]</dim> "
  ally:    "<dim>[</dim><ally>Diplomacy</ally><dim>]</dim> "
  server:  "<dim>[</dim><ally>Server</ally><dim>]</dim> "
  admin:   "<dim>[</dim><admin>Admin</admin><dim>]</dim> "
  error:   "<dim>[</dim><neg>!</neg><dim>]</dim> "
  success: "<dim>[</dim><pos>+</pos><dim>]</dim> "
```

Compact mode (`/toggle compact`) replaces word prefixes with single characters: `$`, `C`, `L`, `W`, `Q`, `D`, `!`, `+`.

### 23.4 Channels

| Channel | Use for | Rules |
|---|---|---|
| **Chat** | Transactions, state changes, anything the player may want to scroll back to | Default for everything in 23.5 unless marked otherwise |
| **Action bar** | Transient status, repeated events, positional info | Never for anything with a number the player needs to remember |
| **Title / subtitle** | War start, war end, city founded, contest results | Maximum 4 per hour per player, hard-limited in code |
| **Boss bar** | Ongoing timed state: war countdown, active server event, rollback progress | One at a time, priority ordered |
| **Sound** | Reinforcement only, never the sole carrier of information | See 23.8 |
| **Toast / advancement** | Never | Cannot be styled or disabled reliably |

### 23.5 Message catalogue

**Audience codes:** `SELF` actor only, `CITY` all online city members, `CITY-RANK` members with a permission, `BOTH` both cities in a war, `ALLY` allies, `SERVER` everyone, `ADMIN` online admins.

#### 23.5.1 Economy, market

| Key | Trigger | Audience | Channel | Template |
|---|---|---|---|---|
| `market.sell.success` | Sell completes | SELF | Chat | `{p.economy}<body>Sold <subject>{qty}x {item}</subject> at <money>{unit}</money> each for <pos>+{gross}</pos><body>, tax <neg>-{tax}</neg><body>. Balance: <money>{balance}</money>` |
| `market.sell.multi` | `/sell all` | SELF | Chat | Header line, then one `<dim>  •</dim> <body>{qty}x {item} <dim>→</dim> <pos>+{total}</pos>` per item, then a total line |
| `market.sell.quota_warn` | Crossing 80% of quota | SELF | Chat | `{p.economy}<body>You have used <subject>{pct}%</subject><body> of today's sell quota. <dim>{remaining} C remaining, resets in {time}.</dim>` |
| `market.sell.quota_hit` | Crossing 100% | SELF | Chat | `{p.economy}<neg>Daily sell quota reached.</neg><body> Further sales earn <subject>{mult}x</subject><body> value until reset in <subject>{time}</subject><body>. <dim>Tip: sell to other players instead, player shops have no quota.</dim>` |
| `market.sell.over_quota` | Each sale past quota | SELF | Chat | `{p.economy}<body>Sold <subject>{qty}x {item}</subject> for <pos>+{total}</pos> <dim>(reduced, over quota)</dim><body>. Balance: <money>{balance}</money>` |
| `market.buy.success` | Buy completes | SELF | Chat | `{p.economy}<body>Bought <subject>{qty}x {item}</subject> at <money>{unit}</money> each for <neg>-{total}</neg><body>. Balance: <money>{balance}</money>` |
| `market.price_moved` | Price moved >10% during a bulk sale | SELF | Chat | `{p.economy}<dim>Price of {item} moved from {old} to {new} during this sale.</dim>` |
| `market.error.not_sellable` | Item not on buy list | SELF | Chat | `{p.error}<body>The market does not buy <subject>{item}</subject><body>. <dim>Use /market list to see what sells, or sell it to another player.</dim>` |
| `market.error.enchanted` | Enchanted or damaged | SELF | Chat | `{p.error}<body>The market only accepts undamaged, unenchanted, unnamed items.</body>` |
| `market.error.container` | Container with contents | SELF | Chat | `{p.error}<body>Empty the <subject>{item}</subject> before selling it.</body>` |
| `market.error.funds` | Cannot afford | SELF | Chat | `{p.error}<body>You need <money>{needed}</money><body> but have <money>{have}</money><body>. <dim>Short by {short}.</dim>` |
| `market.error.inventory_full` | No space | SELF | Chat | `{p.error}<body>Not enough inventory space for <subject>{qty}x {item}</subject><body>. <dim>{fits} would fit.</dim>` |
| `market.error.frozen` | Market frozen by breaker | SELF | Chat | `{p.error}<body>Market sales are temporarily suspended. <dim>Staff have been notified.</dim>` |

#### 23.5.2 Economy, transfers and income

| Key | Trigger | Audience | Channel | Template |
|---|---|---|---|---|
| `pay.sent` | `/pay` succeeds | SELF | Chat | `{p.economy}<body>Sent <neg>-{amount}</neg><body> to <subject>{target}</subject><body>. Balance: <money>{balance}</money>` |
| `pay.received` | Incoming | SELF (target) | Chat + sound | `{p.economy}<subject>{sender}</subject><body> sent you <pos>+{amount}</pos><body>. Balance: <money>{balance}</money>` |
| `pay.error.self` | Paying self | SELF | Chat | `{p.error}<body>You cannot pay yourself.</body>` |
| `pay.error.frozen` | Either party frozen | SELF | Chat | `{p.error}<body>That account cannot receive payments right now.</body>` |
| `income.stipend` | 15-min stipend paid | SELF | **Action bar** | `<pos>+{amount}</pos> <dim>playtime</dim>` |
| `income.stipend.capped` | Daily stipend cap reached | SELF | Chat | `{p.economy}<dim>Daily playtime earnings capped. Resets in {time}.</dim>` |
| `income.stipend.inactive` | Failed the activity check | SELF | **Action bar** | `<dim>No playtime earnings while inactive</dim>` |
| `income.daily` | Daily login | SELF | Chat + sound + title | `{p.economy}<body>Daily reward: <pos>+{amount}</pos><body>. Streak: <subject>{streak} days</subject><body>. <dim>Tomorrow: {next}</dim>` |
| `income.daily.streak_broken` | Streak reset | SELF | Chat | `{p.economy}<body>Your <subject>{old}-day</subject> streak ended. <dim>Starting over at day 1.</dim>` |
| `income.newcomer` | While bonus active | SELF | Chat | `{p.economy}<dim>Newcomer bonus applied (+50%). {days} days remaining.</dim>` |
| `bounty.placed` | Bounty set | SELF + SERVER | Chat | SELF: confirmation. SERVER: `{p.economy}<subject>{placer}</subject><body> placed a <money>{amount}</money><body> bounty on <subject>{target}</subject><body>.` |
| `bounty.claimed` | Killed a bounty target | SELF + SERVER | Chat + sound | `{p.economy}<subject>{killer}</subject><body> claimed the <money>{amount}</money><body> bounty on <subject>{victim}</subject><body>.` |
| `bounty.expired` | 30 days | SELF | Chat | `{p.economy}<body>Your bounty on <subject>{target}</subject><body> expired. Refunded <pos>+{amount}</pos><body>.` |

#### 23.5.3 Player shops

| Key | Trigger | Audience | Channel | Template |
|---|---|---|---|---|
| `shop.created` | Sign shop made | SELF | Chat | `{p.economy}<body>Shop created: <subject>{qty}x {item}</subject><body> for <money>{price}</money><body>. <dim>{used}/{max} shops used.</dim>` |
| `shop.sold` | Someone buys from you, online | SELF | Chat + sound | `{p.economy}<subject>{buyer}</subject><body> bought <subject>{qty}x {item}</subject><body> for <pos>+{amount}</pos><body>. <dim>{stock} left in stock.</dim>` |
| `shop.sold.offline` | Someone buys while offline | SELF | Chat on next join | `{p.economy}<body>While you were away, your shops earned <pos>+{total}</pos><body> across <subject>{count}</subject><body> sales. <dim>/transactions for detail.</dim>` |
| `shop.bought` | You buy from a shop | SELF | Chat | `{p.economy}<body>Bought <subject>{qty}x {item}</subject><body> from <subject>{owner}</subject><body> for <neg>-{amount}</neg><body>.` |
| `shop.out_of_stock` | Empty | SELF | Chat | `{p.error}<body>That shop is out of stock.</body>` |
| `shop.owner_full` | Owner's chest full | SELF | Chat | `{p.error}<body>That shop's storage is full.</body>` |
| `shop.stock_low` | Below 10% | SELF (owner) | Chat | `{p.economy}<body>Shop low on stock: <subject>{item}</subject><body>, <subject>{stock}</subject><body> left.</body>` |

#### 23.5.4 Land and claims

| Key | Trigger | Audience | Channel | Template |
|---|---|---|---|---|
| `claim.success` | Chunk claimed | SELF | Chat | `{p.land}<body>Claimed <subject>({x}, {z})</subject><body> for <neg>-{cost}</neg><body>. <dim>Chunk {n} of your city. Next chunk: {next}. Treasury: {treasury}.</dim>` |
| `claim.success.broadcast` | Chunk claimed | CITY | Chat | `{p.land}<subject>{player}</subject><body> claimed a chunk at <subject>({x}, {z})</subject><body>. <dim>City now has {total} chunks.</dim>` |
| `claim.radius` | Radius claim | SELF | Chat | `{p.land}<body>Claimed <subject>{count} chunks</subject><body> for <neg>-{cost}</neg><body>. <dim>Treasury: {treasury}.</dim>` |
| `claim.error.owned` | Already claimed | SELF | Chat | `{p.error}<body>This chunk belongs to <city>{city}</city><body>.` |
| `claim.error.adjacency` | Not adjacent | SELF | Chat | `{p.error}<body>Chunks must border your city. <dim>Your nearest claim is {dist} chunks {dir}.</dim>` |
| `claim.error.funds` | Treasury short | SELF | Chat | `{p.error}<body>Treasury has <money>{have}</money><body>, this chunk costs <money>{need}</money><body>. <dim>Short by {short}. Use /city deposit.</dim>` |
| `claim.error.buffer` | Too close to another city | SELF | Chat | `{p.error}<body>Too close to <city>{city}</city><body>. <dim>Minimum distance is {min} chunks, this is {actual}.</dim>` |
| `claim.error.war` | In war | SELF | Chat | `{p.error}<body>Cannot claim during a war.</body>` |
| `claim.error.delinquent` | Upkeep unpaid | SELF | Chat | `{p.error}<body>Cannot claim while upkeep is unpaid. <dim>Owed: {owed}.</dim>` |
| `unclaim.success` | Unclaimed | SELF + CITY | Chat | `{p.land}<body>Unclaimed <subject>({x}, {z})</subject><body>. Refunded <pos>+{refund}</pos><body> to treasury. <dim>{total} chunks remaining.</dim>` |
| `unclaim.error.contiguity` | Would split city | SELF | Chat | `{p.error}<body>Unclaiming this would cut off <subject>{count} chunks</subject><body> from your city. <dim>Nearest orphan: ({x}, {z}).</dim>` |
| `unclaim.error.core` | Core chunk | SELF | Chat | `{p.error}<body>The core chunk cannot be unclaimed. <dim>Use /city disband to remove the city.</dim>` |
| `unclaim.error.spawn` | Contains spawn | SELF | Chat | `{p.error}<body>City spawn is in this chunk. <dim>Move it with /city setspawn first.</dim>` |
| `claim.enter` | Entering a claim | SELF | **Action bar** | `<city>{city}</city> <dim>|</dim> <body>{relation}</body>` |
| `claim.leave` | Entering wilderness | SELF | **Action bar** | `<dim>Wilderness</dim>` |
| `claim.enter.enemy` | Entering an enemy claim in war | SELF | **Action bar** + sound | `<war>{city}</war> <dim>|</dim> <war>HOSTILE TERRITORY</war>` |
| `protect.denied.build` | Build blocked | SELF | **Action bar**, 3s cooldown | `<neg>You cannot build in {city}</neg>` |
| `protect.denied.container` | Container blocked | SELF | **Action bar**, 3s cooldown | `<neg>You cannot open containers in {city}</neg>` |
| `protect.denied.rank` | Own city, insufficient rank | SELF | **Action bar**, 3s cooldown | `<neg>Your rank ({rank}) cannot do that</neg>` |

**Note on protection denials.** These fire on every blocked click and would flood chat instantly. Action bar with a per-player 3-second cooldown per message type. This is a required implementation detail, not a suggestion.

#### 23.5.5 City membership

| Key | Trigger | Audience | Channel | Template |
|---|---|---|---|---|
| `city.created` | City founded | SELF + SERVER | Title + Chat | SERVER: `{p.city}<body>The city of <city>{name}</city><body> has been founded by <subject>{player}</subject><body>!` |
| `city.invite.sent` | Invite sent | SELF | Chat | `{p.city}<body>Invited <subject>{target}</subject><body>. <dim>Expires in 5 minutes.</dim>` |
| `city.invite.received` | Invite received | SELF | Chat + sound | `{p.city}<subject>{inviter}</subject><body> invited you to <city>{city}</city><body>. <link>[Accept]</link> <link>[Decline]</link> <dim>(5 min)</dim>` |
| `city.invite.expired` | Timeout | SELF | Chat | `{p.city}<dim>Invite from {city} expired.</dim>` |
| `city.member.joined` | Player joins | CITY | Chat + sound | `{p.city}<subject>{player}</subject><body> joined the city! <dim>{count} members.</dim>` |
| `city.member.joined.self` | You join | SELF | Title + Chat | `{p.city}<body>Welcome to <city>{city}</city><body>. Your rank: <subject>{rank}</subject><body>. <dim>/city to open the city menu.</dim>` |
| `city.member.left` | Player leaves | CITY | Chat | `{p.city}<subject>{player}</subject><body> left the city. <dim>{count} members.</dim>` |
| `city.member.kicked` | Kicked | CITY | Chat | `{p.city}<subject>{player}</subject><body> was kicked by <subject>{actor}</subject><body>.` |
| `city.member.kicked.self` | You were kicked | SELF | Chat + sound | `{p.city}<neg>You were removed from {city}.</neg><body> <dim>You may join another city in 24 hours.</dim>` |
| `city.rank.changed` | Rank change | SELF + CITY | Chat | SELF: `{p.city}<body>Your rank is now <subject>{rank}</subject><body>. <dim>/city perms {rank} to see what you can do.</dim>` |
| `city.rank.promoted` | Promotion | CITY | Chat + sound | `{p.city}<subject>{player}</subject><body> was promoted to <subject>{rank}</subject><body>.` |
| `city.mayor.transferred` | Transfer | CITY + SERVER | Chat | `{p.city}<city>{city}</city><body> is now led by <subject>{player}</subject><body>.` |
| `city.disbanded` | Disband | SERVER | Chat | `{p.city}<body>The city of <city>{name}</city><body> has been disbanded.</body>` |
| `city.frozen` | Admin freeze | CITY | Chat + title | `{p.admin}<neg>Your city has been frozen by staff.</neg><body> Reason: <subject>{reason}</subject><body>. <dim>All city actions are suspended.</dim>` |

#### 23.5.6 Treasury and upkeep

| Key | Trigger | Audience | Channel | Template |
|---|---|---|---|---|
| `treasury.deposit` | Deposit | SELF | Chat | `{p.economy}<body>Deposited <neg>-{amount}</neg><body> to <city>{city}</city><body>. Treasury: <money>{treasury}</money><body>. <dim>Your lifetime contribution: {lifetime}.</dim>` |
| `treasury.deposit.broadcast` | Deposit over 10k | CITY | Chat | `{p.city}<subject>{player}</subject><body> deposited <pos>+{amount}</pos><body>. Treasury: <money>{treasury}</money><body>.` |
| `treasury.withdraw` | Withdrawal | SELF | Chat | `{p.economy}<body>Withdrew <pos>+{amount}</pos><body> from <city>{city}</city><body>. Treasury: <money>{treasury}</money><body>.` |
| `treasury.withdraw.broadcast` | **Every** withdrawal | CITY | Chat | `{p.city}<subject>{player}</subject><body> withdrew <neg>-{amount}</neg><body> from the treasury. Treasury: <money>{treasury}</money><body>.` |
| `treasury.withdraw.cap` | Cap hit | SELF | Chat | `{p.error}<body>Daily withdrawal limit reached. <dim>You may withdraw {remaining} more in {time}.</dim>` |
| `treasury.withdraw.new_member` | Under 72h | SELF | Chat | `{p.error}<body>New members cannot withdraw for the first 72 hours. <dim>{time} remaining.</dim>` |
| `upkeep.charged` | Daily charge | CITY-RANK | Chat | `{p.city}<body>Upkeep paid: <neg>-{amount}</neg><body>. Treasury: <money>{treasury}</money><body>. <dim>{days} days of runway.</dim>` |
| `upkeep.warning` | Runway under 3 days | CITY | Chat + sound | `{p.city}<neg>Treasury low.</neg><body> <subject>{days} days</subject><body> until the city cannot pay upkeep. <dim>Daily cost: {cost}.</dim>` |
| `upkeep.failed` | Cannot pay | CITY | Chat + title | `{p.city}<neg>UPKEEP UNPAID.</neg><body> Owed: <money>{owed}</money><body>. <dim>Grace period: {days} days, then chunks will be lost.</dim>` |
| `upkeep.auto_unclaim` | Chunks lost | CITY | Chat + sound | `{p.city}<neg>Lost {count} chunks</neg><body> to unpaid upkeep. <dim>{remaining} chunks left. Deposit to stop this.</dim>` |
| `upkeep.recovered` | Debt cleared | CITY | Chat | `{p.city}<pos>Upkeep is current again.</pos><body> <dim>Treasury: {treasury}.</dim>` |

**Note on `treasury.withdraw.broadcast`.** Every withdrawal is announced to the whole city, always, with no toggle. This is deliberate and is the primary anti-fraud mechanism in the plugin. Social transparency prevents treasury theft far more effectively than any permission system, because the thief knows everyone will see it happen in real time.

#### 23.5.7 War

| Key | Trigger | Audience | Channel | Template |
|---|---|---|---|---|
| `war.declared` | Declaration | SERVER | Chat + title to BOTH + sound | `{p.war}<war>WAR DECLARED.</war><body> <city>{attacker}</city><body> has declared war on <city>{defender}</city><body>. Wager: <money>{wager}</money><body>. <dim>Preparation: 48 hours.</dim>` |
| `war.declined` | Defender declines | SERVER | Chat | `{p.war}<city>{defender}</city><body> declined war with <city>{attacker}</city><body>, forfeiting <money>{amount}</money><body>.` |
| `war.prep.countdown` | 24h, 6h, 1h, 10m | BOTH | Chat + boss bar | `{p.war}<body>War begins in <subject>{time}</subject><body>. <dim>Buy defenses with /city defense.</dim>` |
| `war.started` | ACTIVE begins | BOTH + SERVER | Title + Chat + sound | Title: `<war>WAR</war>` / subtitle: `<body>{attacker} vs {defender}</body>` |
| `war.kill` | Player killed | BOTH | Chat | `{p.war}<subject>{killer}</subject> <dim>(<city>{city}</city>)</dim> <body>killed</body> <subject>{victim}</subject><body>. <dim>+{points} points.</dim>` |
| `war.capture.started` | Capture begins | BOTH | Chat + boss bar | `{p.war}<city>{city}</city><body> is capturing <subject>Point {n}</subject><body>. <dim>{time} to hold.</dim>` |
| `war.capture.complete` | Held 60s | BOTH | Chat + sound | `{p.war}<city>{city}</city><body> captured <subject>Point {n}</subject><body>. <dim>+{points} points.</dim>` |
| `war.score` | Every 10% score change | BOTH | **Boss bar** | `<war>{attacker} {aScore} - {dScore} {defender}</war> <dim>| {time} left</dim>` |
| `war.cityhall.reached` | Enemy at City Hall | BOTH | Chat + title + sound | `{p.war}<war>{city} has reached the enemy City Hall!</war><body> <dim>+100 points.</dim>` |
| `war.ended` | Timer expires | BOTH + SERVER | Title + Chat | `{p.war}<body>War over. <city>{winner}</city><body> wins <subject>{aScore} to {dScore}</subject><body>.` |
| `war.rollback.started` | Rollback begins | BOTH + SERVER | Chat + boss bar | `{p.war}<body>Restoring the world. <dim>The war zone is closed until this completes.</dim>` |
| `war.rollback.progress` | Every 10% | BOTH | **Boss bar** | `<body>Restoring: {pct}% <dim>({done}/{total} blocks)</dim>` |
| `war.rollback.complete` | Done | BOTH + SERVER | Chat + title + sound | `{p.war}<pos>The world has been restored.</pos><body> <subject>{count}</subject><body> blocks returned to their original state. <dim>Nothing was permanently lost.</dim>` |
| `war.rollback.failed` | Error | ADMIN + BOTH | Chat + console SEVERE | `{p.admin}<neg>Rollback incomplete.</neg><body> <subject>{count}</subject><body> blocks could not be restored. Staff have been notified. <dim>/ca war rollbackstatus {id}</dim>` |
| `war.payout` | Resolution | BOTH | Chat | Winner: `{p.war}<body>Victory. Treasury received <pos>+{amount}</pos><body>. <dim>Market bonus +10% for 7 days.</dim>` Loser: `{p.war}<body>Defeat. Recovered <pos>+{amount}</pos><body> of your wager. <dim>7 days of war immunity granted.</dim>` |
| `war.loot.reminder` | On war start | BOTH | Chat | `{p.war}<body>Reminder: destroyed blocks are restored, but <subject>items taken from chests are gone for good</subject><body>. <dim>Move valuables to the city vault, which is war-immune.</dim>` |

**`war.rollback.complete` is the most important message in the plugin.** It is the moment the core promise is delivered. It gets a title, a sound, and explicit wording that nothing was lost, because that reassurance is the entire reason players will agree to fight.

#### 23.5.8 Diplomacy, quests, events

| Key | Trigger | Audience | Channel |
|---|---|---|---|
| `ally.proposed` / `ally.accepted` / `ally.broken` / `ally.break_notice` | Alliance changes | Both cities + SERVER on accept | Chat |
| `truce.offered` / `truce.accepted` / `truce.expiring` (24h warning) / `truce.expired` | Truce changes | Both cities | Chat |
| `quest.assigned` | Daily reset | SELF | Chat on join |
| `quest.progress` | Every 25% | SELF | **Action bar** |
| `quest.complete` | Done | SELF | Chat + sound |
| `quest.reward` | Paid | SELF | Chat |
| `challenge.progress` | Every 25% | CITY | Chat |
| `challenge.complete` | Done | CITY | Chat + sound |
| `contest.announced` | New theme | SERVER | Chat + title |
| `contest.deadline` | 48h, 24h, 1h | SERVER | Chat |
| `contest.submitted` | Entry made | CITY | Chat |
| `contest.voting_open` | Voting begins | SERVER | Chat |
| `contest.results` | Scored | SERVER | Chat + title to winners |
| `event.warning` | 30 min before | SERVER | Chat |
| `event.started` | Begins | SERVER | Chat + title + **boss bar** |
| `event.ended` | Ends | SERVER | Chat |
| `leaderboard.overtaken` | Lost a top-3 spot | SELF | Chat |
| `leaderboard.entered` | Entered top 10 | SELF + SERVER if top 3 | Chat + sound |

#### 23.5.9 Errors, generic

| Key | Template |
|---|---|
| `error.no_permission` | `{p.error}<body>You need <subject>{permission}</subject><body> to do that.</body>` |
| `error.no_city_permission` | `{p.error}<body>Your rank (<subject>{rank}</subject><body>) lacks <subject>{flag}</subject><body>. <dim>Ask a {higher_rank} to grant it.</dim>` |
| `error.not_in_city` | `{p.error}<body>You are not in a city. <dim>/city create <name> to found one, or ask for an invite.</dim>` |
| `error.cooldown` | `{p.error}<body>Wait <subject>{time}</subject><body> before doing that again.</body>` |
| `error.player_not_found` | `{p.error}<body>No player named <subject>{name}</subject><body>.` |
| `error.city_not_found` | `{p.error}<body>No city named <subject>{name}</subject><body>. <dim>/city list to see all cities.</dim>` |
| `error.invalid_amount` | `{p.error}<body>"<subject>{input}</subject>" is not a valid amount. <dim>Try 1000, 10k, or all.</dim>` |
| `error.frozen` | `{p.error}<body>This action is unavailable while your account or city is frozen.</body>` |
| `error.usage` | `{p.error}<body>Usage: <subject>{usage}</subject><body>` |
| `error.internal` | `{p.error}<body>Something went wrong. <dim>Staff have been notified. Error ID: {id}</dim>` |

**`error.internal` must include a generated error ID that is also written to console with the full stack trace.** A player reporting "it broke" with an ID is a bug report. A player reporting "it broke" without one is a mystery.

### 23.6 Toggle categories

```yaml
toggles:
  economy_personal:  { default: on,  locked: false }   # own transactions
  economy_city:      { default: on,  locked: false }   # deposits by others
  treasury_withdraw: { default: on,  locked: TRUE  }   # ALWAYS ON, anti-fraud
  land_own:          { default: on,  locked: false }   # own claims
  land_city:         { default: on,  locked: false }   # others' claims
  membership:        { default: on,  locked: false }   # joins, leaves, ranks
  upkeep:            { default: on,  locked: false }
  upkeep_critical:   { default: on,  locked: TRUE  }   # ALWAYS ON, city survival
  war:               { default: on,  locked: TRUE  }   # ALWAYS ON, safety
  diplomacy:         { default: on,  locked: false }
  quests:            { default: on,  locked: false }
  contests:          { default: on,  locked: false }
  events:            { default: on,  locked: false }
  leaderboard:       { default: on,  locked: false }
  shop_sales:        { default: on,  locked: false }
  actionbar:         { default: on,  locked: false }
  sounds:            { default: on,  locked: false }
  compact:           { default: off, locked: false }
```

Four categories are locked on. War messages, critical upkeep warnings, and treasury withdrawals cannot be muted, because muting them enables either fraud or an avoidable loss of the player's own city. Everything else is the player's choice.

### 23.7 Language file structure

```
resources/lang/
├── en.yml
└── it.yml
```

Requirements:
- Keys match the catalogue above exactly
- Placeholders use `{name}` and are validated at startup. A missing or misspelled placeholder logs a warning with the key name, and the message falls back to English rather than rendering broken text.
- A startup check compares every language file against `en.yml` and logs which keys are missing.
- Numbers are formatted by a single central formatter. Currency always shows two decimals with thousands separators. Large numbers abbreviate above 1,000,000 (`1.25M`) in action bars and boss bars only, never in chat, because a player reading a transaction wants the exact figure.
- Durations use a single formatter: `2d 4h`, `18m`, `45s`.
- Coordinates always as `(x, z)` for chunks and `(x, y, z)` for blocks.

### 23.8 Sound design

Sound reinforces, never carries information alone. Every sound respects `/toggle sounds`.

| Event | Sound | Pitch |
|---|---|---|
| Money received | `ENTITY_EXPERIENCE_ORB_PICKUP` | 1.2 |
| Money spent | `BLOCK_NOTE_BLOCK_BASS` | 0.8 |
| Transaction failed | `BLOCK_NOTE_BLOCK_BASS` | 0.5 |
| Claim success | `BLOCK_AMETHYST_BLOCK_CHIME` | 1.0 |
| Quest or challenge complete | `ENTITY_PLAYER_LEVELUP` | 1.0 |
| City member joins | `BLOCK_NOTE_BLOCK_CHIME` | 1.4 |
| Invite received | `BLOCK_NOTE_BLOCK_PLING` | 1.2 |
| War declared | `ENTITY_ENDER_DRAGON_GROWL` | 0.7 |
| War kill | `ENTITY_PLAYER_ATTACK_CRIT` | 1.0 |
| Capture point taken | `BLOCK_BEACON_ACTIVATE` | 1.0 |
| **Rollback complete** | `UI_TOAST_CHALLENGE_COMPLETE` | 1.0 |
| Upkeep critical | `BLOCK_BELL_USE` | 0.6 |
| Contest win | `UI_TOAST_CHALLENGE_COMPLETE` | 1.0 |
| GUI button denied | `BLOCK_NOTE_BLOCK_BASS` | 0.5 |
| GUI page turn | `ITEM_BOOK_PAGE_TURN` | 1.0 |

---

## 24. Additional milestones

Append to PLAN.md. These slot into the existing sequence rather than following it.

| M | Milestone | Deliverable | Depends on | Slots after |
|---|---|---|---|---|
| 6a | **Crafting equivalence graph** | Recipe graph builder from Bukkit's recipe iterator plus hardcoded smelting and stonecutter tables. Transitive reachability check. Startup validation from 21.10.1. Unit tests proving the reversible pairs in 21.3 are detected, including multi-step (A to B to C). **The market module must not enable if validation fails.** | M6 | before market goes live |
| 6b | **Market hardening** | Hard blacklist (21.8) as code, not config. Villager-disjointness startup assertion. Revised buy list (21.9). Sell-only builder catalogue (21.6). | M6a | |
| 6c | **Daily sell quota** | Per-player quota tracker, soft cap multiplier, `/quota`, persistence, concurrency-safe, resets at 00:00 | M6b | |
| 9a | **Anti-abuse layer** | Placed-block cache (21.10.5), strengthened stipend check (F11), new-account income gate (F12), 72-hour withdrawal hold (F16), contribution-proportional disband split (F6), bounty self-claim block (F7), war leaderboard score threshold (F4) | M9 | |
| 14a | **Money supply accounting** | Hourly `money_supply` snapshots, `/ca eco supply`, `/ca eco sources`, `/ca eco top`, inflation dashboard | M14 | |
| 14b | **Circuit breakers** | All triggers in 21.7, automatic market sell freeze, admin alerting, `/ca breaker status` and `reset`, `/ca market volume` | M14a | |
| 7a | **Message framework** | Palette as MiniMessage tag resolvers, prefix system, channel router (chat, action bar, title, boss bar, sound), per-player toggle store, action-bar cooldown throttling, placeholder validation at startup, number and duration formatters, `en.yml` complete | M7 | build alongside the GUI framework |
| 23a | **Message catalogue** | Every key in 23.5 implemented and wired. A test that asserts **every** service method that mutates state fires at least one message. | all feature milestones | |
| 23b | **Italian localisation** | `it.yml` complete, key-parity check green | M23a | |
| 21a | **Investigative admin tooling** | Everything in 22.7.1 and 22.7.2. `/ca history` is the priority. | M21 | |
| 22a | **Command completeness pass** | Every command in Section 22 implemented with full tab completion. A test that asserts every registered command has a completer for every argument. | all | |

**Ordering note.** M6a and M6b are **hard blockers on M6**. The market must not be playable before the equivalence graph and the blacklist exist. If the market ships first "just to test it," the test server's economy will be broken within an hour and every subsequent balance measurement you take will be meaningless.

M7a should be built alongside the GUI framework, not after. Retrofitting a message framework onto twenty modules that already print strings directly is significantly more work than building it once up front, and the retrofit always misses cases.

---

# PART III, Defense Units, the City Warden, and Siege

> Appended to SPEC.md. Sections 25 to 31 continue the numbering of Parts I and II.
> **This part fully supersedes Part I Section 12.** The eight-unit catalogue in
> Section 12.2 must not be implemented. Implement Section 27 instead.

---

## 25. Combat doctrine

### 25.1 Why Part I Section 12 is replaced

The original catalogue listed eight units. Watchman, City Guard, and Elite Guard were one unit at three price points. Archer and Sharpshooter were one unit at two price points. Five of eight entries performed two jobs, and the only decision a player made was how much to spend.

A roster is interesting when units do **different things** and composition is the decision. Vertical scaling belongs to the Fortification upgrade, not to parallel tiers of the same mob.

### 25.2 The four rules

Every unit, ability, and number in this part is subject to these. Where a design conflicts with a rule, the rule wins.

**Rule 1: defense makes attacking expensive, never impossible.**
The balance target is explicit and testable: a defending city's full garrison, at any Fortification level, must be beatable by an attacking force **equal in size to the defender's active member count**, equipped with good gear and coordinating. If a configuration exists where this is false, that configuration is a bug.

An unbeatable city is worse than no defense at all, because nobody declares war on it, the wager system goes unused, and the rollback engine (the plugin's defining feature) never runs.

**Rule 2: peacetime is safe.**
Part I Section 13.4 requires players to travel to other cities to view and vote on contest entries. A defense system that attacks visitors makes contest voting impossible and kills build tourism. On a building-focused server, that is fatal. Units are passive by default. See Section 26.

**Rule 3: every unit has a stated counterplay.**
No unit is immune to any damage type. Each entry in Section 27 names, in its own row, the specific way an attacker beats it. A unit without a written counterplay does not ship.

**Rule 4: units are consumable, not permanent.**
Units die permanently in war and cost money to replace. This means every war costs the defender real currency and prevents indefinite turtling. It also means defense spending is a recurring sink, which the economy in Part II needs.

### 25.3 What is technically available

No resource pack exists in this project and none is planned, so every unit must be a recognisable vanilla mob, restyled. The available toolbox:

| Capability | Mechanism | Notes |
|---|---|---|
| Stats | Attribute API | Health, damage, speed, armor, toughness, knockback resistance, follow range |
| Size | `Attribute.SCALE` | 1.20.5+. A 1.8x Iron Golem is one attribute set, no model needed |
| Appearance | Equipment slots, drop chance 0 | **Dyed leather in the city's colour** is the single highest-value cosmetic here |
| Persistent buffs | Hidden potion effects | `ambient=true, particles=false, icon=false` |
| Custom AI | Paper Goal API (`com.destroystokyo.paper.entity.ai`) | Add, remove, and register goals without NMS |
| Movement | `entity.getPathfinder().moveTo()` | Patrol routes, rally behaviour |
| Targeting control | `EntityTargetLivingEntityEvent` | **The central hook.** Every targeting decision passes through here |
| Auras and on-hit effects | Repeating task plus `EntityDamageByEntityEvent` | Slows, buffs, reveals, weak points |
| Nameplates and health bars | Text Display entities (1.19.4+) | No pack required |
| Team colour outlines | Scoreboard teams plus Glowing | City colour visible through walls during war |
| Warden control | `Warden#setAnger`, `#clearAnger`, `DamageCause.SONIC_BOOM` | Anger system and sonic boom are both controllable from the API |
| Daylight burning | `Zombie#setShouldBurnInDay(false)`, same for Skeleton | Required, or guards ignite every morning |

Not available: custom models, custom hitboxes, genuinely new mob types. Every design below respects that.

### 25.4 Units are data, not entities

**This is an architectural requirement, not an optimisation.**

Two hundred cities times twelve units is 2,400 permanently loaded entities. That will destroy tick rate long before the plugin is feature-complete.

Units live in the `defense_units` table. They **materialize** as real entities only when:

- any player is within `defense.materialize-radius` (default 48 blocks), **or**
- the owning city is in a war in `PREP` or `ACTIVE` state and the chunk is in the war zone

They **dematerialize** when no player has been within that radius for `defense.dematerialize-delay-seconds` (default 30).

Materialization restores full state from the database row: position, current health, target, and cooldowns. Dematerialization writes current health back. A unit at 40% health that dematerializes returns at 40% health.

**Health regeneration while dematerialized:** units regain `defense.dormant-regen-percent-per-hour` (default 10%) while nobody is nearby, capped at full. This is disabled entirely during a war, so damage dealt in a war sticks.

Consequences to handle explicitly: a unit killed while materialized is dead in the database immediately, before any dematerialization. A server restart while materialized must not lose health state, so health is checkpointed every 30 seconds during combat.

### 25.5 Defense Capacity

Part I capped defense by unit count ("5 units plus 2 per Fortification level"). A count permits fifteen Colossi. A points budget does not.

```
Defense Capacity = defense.base-capacity + (defense.capacity-per-fortification * fortification_level)
                 = 100 + 25 * level        (0 to 5)
                 = 100 to 225
```

| Unit | Cost in points |
|---|---|
| Frost Sentry | 8 |
| Watchtower Keeper | 10 |
| Warhound | 12 |
| Archer | 18 |
| City Guard | 20 |
| Colossus | 45 |
| **City Warden** | **0, excluded from the budget** |

| Fortification | Capacity | Example garrisons |
|---|---|---|
| 0 | 100 | 5 City Guards, or 2 Colossi plus a Sentry |
| 2 | 150 | 7 City Guards, or 3 Colossi |
| 5 | 225 | 11 City Guards, or 5 Colossi, or a mixed garrison of 12 units |

The Warden is excluded from the budget because it is gated three other ways: one per city ever, Fortification level 5 required, and an enormous purchase price. Charging it against capacity would force a city to choose between its flagship and having any garrison at all, which makes the flagship feel like a punishment.

---

## 26. Aggression model

### 26.1 States

Every unit is in exactly one of four states.

```
DORMANT      Not materialized. No player nearby. Regenerating.
   |
   v
PASSIVE      Materialized, visible, ignores players entirely.
   |         Attacks hostile mobs inside the claim. Default peacetime state.
   |
   v
ALERTED      A trespass threshold was crossed. Targets one specific player
   |         for a limited time. Reverts to PASSIVE when it expires.
   |
   v
HOSTILE      War only. Targets all members of enemy cities on sight.
```

### 26.2 Trespass response

This is what replaces "attacks foreigners on sight."

A **violation** is any of the following by a non-member inside the city's claims:

- A blocked block break or place
- A blocked container access attempt
- A blocked interaction with a door, chest, or protected entity
- Damaging a city member
- Damaging a defense unit
- Killing a city-owned animal or villager

When a single player commits `defense.trespass.violations` (default **3**) violations within `defense.trespass.window-seconds` (default **30**), the city enters trespass response against that player:

1. **Warning phase.** All materialized units in the affected chunk plus a 2-chunk radius roar or play their alert sound. Units glow in the city colour. The trespasser receives a clear chat and title warning naming the city. Duration: `defense.trespass.warning-seconds` (default **5**).
2. **Alerted phase.** If the trespasser is still inside the city's claims when the warning ends, units enter ALERTED against that player only. Duration `defense.trespass.duration-seconds` (default **45**).
3. **Reset.** Leaving the city's claims immediately begins a 10-second de-escalation. Units revert to PASSIVE and return to their posts.

Notes that matter:

- ALERTED is **per player**, never per group. A trespasser's teammates standing peacefully nearby are not attacked.
- The warning phase exists so that no player is ever killed without being told, in plain language, that they are about to be. This matters most for the Warden, which is lethal to an unarmored player.
- Violations decay. The counter is a sliding window, not a running total.
- City members and allies with `trust` never generate violations.
- Violations are logged to `audit_log`, so an admin investigating a grief report can see the pattern.

### 26.3 War aggression

During `ACTIVE`, and only inside the war zone, units of a city party to the war enter HOSTILE toward all members of enemy cities and their allies. Targeting range is per-unit. Trespass response is suspended during war because everything is hostile anyway.

During `PREP`, units remain PASSIVE. Prep is a building phase, not a fighting phase.

### 26.4 Never targeted

Units never target, under any state:

- Members of the owning city
- Members of allied cities
- Players with `civitas.bypass.war`
- Players in Creative or Spectator
- Players within 5 seconds of joining the server or respawning
- Any player during the warning phase
- Other defense units, including enemy ones. **Units never fight units.** Only players kill units. This is deliberate: unit-versus-unit combat produces unwatchable clumps of AI and makes wars resolve without players present.

---

## 27. The roster

Seven units. Every one performs a job no other performs. Two deal no damage at all.

All values are `defense.yml` config keys. Health is in half-hearts (20 = 10 hearts).

### 27.1 Summary

| Unit | Base mob | Role | Cost | Upkeep/day | Points | HP | Damage |
|---|---|---|---|---|---|---|---|
| Frost Sentry | Snow Golem | Area denial | 6,000 | 300 | 8 | 30 | **0** |
| Watchtower Keeper | Armor Stand | Detection | 9,000 | 350 | 10 | n/a | **0** |
| Warhound | Wolf | Interceptor | 10,000 | 500 | 12 | 45 | 6 |
| Archer | Skeleton | Ranged | 16,000 | 700 | 18 | 55 | 7 ranged |
| City Guard | Zombie | Line holder | 20,000 | 900 | 20 | 90 | 8 |
| Colossus | Iron Golem @1.8x | Heavy tank | 55,000 | 2,600 | 45 | 220 | 16 |
| **City Warden** | Warden | **Flagship** | **750,000** | **8,000** | **0** | **500** | **10** |

### 27.2 Frost Sentry

**Base:** Snow Golem. **Role:** area denial, non-lethal.

| Property | Value |
|---|---|
| Health | 30 |
| Damage | **0**, deals no damage ever |
| Speed | Static, does not move from its post |
| Range | 16 blocks |
| Ability | Snowballs apply Slowness II for 3 seconds and Mining Fatigue I for 2 seconds |

Mining Fatigue is the interesting half. During a war it directly slows block-breaking, which is the attacker's main activity. It costs almost nothing and shapes a fight without killing anyone.

**Counterplay:** 30 HP, dies to two arrows. Melts in lava or near fire. Any attacker who spends five seconds on it removes it.

**Implementation:** vanilla snow golem AI throws snowballs already. Cancel the snowball's damage in `EntityDamageByEntityEvent`, apply the effects in the same handler. Set `Snowman#setDerp(false)`. Water and rain damage must be disabled via `EntityDamageEvent` cancellation, otherwise sentries die in the first storm.

### 27.3 Watchtower Keeper

**Base:** Armor Stand with arms, dyed leather in city colour, holding a spyglass. **Role:** detection. **Cannot fight and cannot be targeted by mobs.**

| Property | Value |
|---|---|
| Health | Invulnerable outside war, 40 during war |
| Damage | **0** |
| Detection radius | 32 blocks |
| Ability | Applies Glowing to non-members and non-allies within radius, 3-second refresh. Posts a message to city chat when an unknown player enters, rate-limited to once per player per 5 minutes. |

This is the unit that makes a city feel inhabited and watched without any threat. It is also genuinely useful in war, because Glowing renders through walls and defeats hiding.

**Counterplay:** during war it has 40 HP and no defense. Killing the Keepers first is the correct opening move for any competent attacker, which creates a real tactical opening beat at the start of a siege.

**Implementation:** `ArmorStand` with `setInvulnerable(true)` outside war, `setGravity(false)`, `setBasePlate(false)`. Detection is a repeating task, not AI. Glowing via a scoreboard team so the outline is the city's colour.

### 27.4 Warhound

**Base:** Wolf, with a dyed collar in the city colour. **Role:** fast interceptor.

| Property | Value |
|---|---|
| Health | 45 |
| Damage | 6 |
| Speed | 0.42, faster than a sprinting player |
| Range | Chases up to 24 blocks from post |
| Ability | Bite applies Slowness I for 2 seconds. Prioritises the **lowest-health** valid target rather than the nearest. |

Targeting the weakest enemy is what makes it feel like a hunting animal rather than a generic melee mob, and it punishes attackers who retreat without healing.

**Counterplay:** 45 HP and no armor. Dies in two hits from any decent weapon. Its speed means it arrives first and therefore dies first.

### 27.5 Archer

**Base:** Skeleton, full dyed leather in the city colour, bow with Power III. **Role:** ranged damage.

| Property | Value |
|---|---|
| Health | 55 |
| Damage | 7 per arrow |
| Range | **20 blocks, hard capped** |
| Ability | Will not fire without line of sight. Fire rate halves while any enemy is within 5 blocks. |

The 20-block cap is a deliberate balance decision: a player bow out-ranges it. An attacker who engages archers with a bow from 40 blocks wins for free, which rewards preparation over gear.

**Counterplay:** out-range it, or close to melee where its fire rate halves. Weak in close quarters by design.

**Implementation:** `Skeleton#setShouldBurnInDay(false)` is mandatory. Cap range by clearing targets beyond 20 blocks in the targeting handler rather than relying on `FOLLOW_RANGE` alone.

### 27.6 City Guard

**Base:** Zombie, full dyed leather in the city colour, iron sword, shield in offhand. **Role:** melee line holder. The backbone unit.

| Property | Value |
|---|---|
| Health | 90 |
| Damage | 8 |
| Speed | 0.28 |
| Armor | 8 points, plus 2 toughness |
| Ability | **Alert network.** Damaging one City Guard causes every City Guard within 3 chunks to target the attacker for 20 seconds, regardless of trespass state. |

The alert network is what makes guards feel like a garrison instead of independent mobs. Picking them off one at a time does not work.

**Counterplay:** slow enough to kite. Knockback works normally. The alert network is also its weakness, since pulling one guard pulls a predictable group that can be fought in a chokepoint of the attacker's choosing.

**Implementation:** `Zombie#setShouldBurnInDay(false)`. Disable baby zombie variants. Disable zombie reinforcement spawning, which will otherwise create free extra zombies during a fight.

### 27.7 Colossus

**Base:** Iron Golem with `Attribute.SCALE` at 1.8. **Role:** heavy tank.

| Property | Value |
|---|---|
| Health | 220 |
| Damage | 16 |
| Speed | 0.20, very slow |
| Knockback resistance | 1.0, cannot be knocked back |
| Ability | **Slam.** On hit, targets within 3 blocks of the impact take 4 splash damage and heavy knockback. Arrows dealing under 8 damage are reduced by 80%. |

A 1.8x scale Iron Golem is genuinely imposing on screen and costs one attribute assignment. This is the best value in the entire toolbox.

**Counterplay:** the slowest unit in the game at 0.20 speed. It can be walked away from at any time and cannot climb. Leading a Colossus away from the objective and simply leaving it there is the intended and correct play. Its arrow resistance is explicitly capped at 8 damage so a fully charged Power V bow still hurts it.

### 27.8 Placement

- Units are purchased from the Defense GUI and delivered as a **spawn item** that must be placed inside a claim. Placement is deliberate and visible.
- Maximum `defense.max-units-per-chunk` (default **3**) per chunk. No stacking a death-blob on the City Hall.
- A unit is bound to the chunk it is placed in. It may move up to `defense.leash-blocks` (default **8**) past that chunk's border, and is teleported back if it exceeds it.
- Units placed during `ACTIVE` war cost **double** and enter a 60-second inactive period before functioning, so defense is a preparation decision, not a mid-fight purchase.
- Removing a unit voluntarily refunds nothing.

---

## 28. The City Warden

The flagship. One per city, ever. Present at all times, not war-gated.

### 28.1 Design intent

The Warden exists to be **the thing your city is known for**. It is a prestige unlock and a landmark, not a weapon. Its purpose is to be nearly impossible to remove and genuinely intimidating to approach, while being incapable of dominating a fight.

The design achieves this through an asymmetry: **full Warden health, drastically reduced damage.** A trespasser without armor is in real danger. A geared raider is barely scratched but faces a 500 HP obstacle standing between them and the City Hall.

### 28.2 Acquisition

| Requirement | Value |
|---|---|
| Fortification upgrade | **Level 5**, roughly 2,000,000 C of prior investment |
| Purchase cost | **750,000 C** |
| Daily upkeep | **8,000 C** |
| Limit | One per city, permanently. Never two, even if the first is destroyed. |
| Defense Capacity cost | **0**, excluded from the budget |
| Placement | Must be placed in the **core chunk**. Cannot be moved afterwards. |

Total investment to field one is close to 2.75 million coins, which at the Part II money-supply figures is many weeks of a large city's total income. It should be the most significant purchase in the game, and only a handful of cities on a server should ever have one.

### 28.3 Statistics

| Property | Value | Vanilla | Reason for the change |
|---|---|---|---|
| Health | **500** | 500 | Unchanged. This is the point of the unit. |
| Melee damage | **10** | 30 | See the tuning table in 28.4 |
| **Sonic boom** | **Disabled entirely** | 10 to 15, ignores armor and shields | Unblockable, uncounterable ranged damage has no place in a defense unit |
| Darkness aura | 10 block radius, ALERTED only | 20 blocks, always | Atmosphere without blinding peaceful visitors |
| Speed | 0.25 | 0.30 | A sprinting player can always escape |
| Knockback resistance | 1.0 | 1.0 | Unchanged |
| Movement | Confined to the core chunk plus 6 blocks | free roaming | It guards the City Hall, it does not patrol the city |
| Vibration targeting | **Disabled** | core mechanic | Vibration anger would aggro on peaceful visitors walking nearby |
| Despawn | Disabled | 60s without target | Must persist |

### 28.4 Why 10 damage

Damage taken by a player after vanilla armor and Protection reduction:

| Warden raw damage | Unarmored | Full iron | Full diamond Prot II | Full netherite Prot IV |
|---|---|---|---|---|
| 30 (vanilla) | 30.0, **0.7 hits to kill** | 26.4, 0.8 hits | 10.2, 2.0 hits | 4.8, 4.2 hits |
| 15 | 15.0, 1.3 hits | 10.5, 1.9 hits | 3.6, 5.6 hits | 1.7, 11.6 hits |
| **10 (chosen)** | **10.0, 2.0 hits** | **6.0, 3.3 hits** | **2.0, 9.8 hits** | **1.0, 19.8 hits** |
| 6 | 6.0, 3.3 hits | 3.1, 6.4 hits | 1.1, 18.9 hits | 0.5, 37.3 hits |

At 10 damage the unit is a **serious threat to an unequipped trespasser** (two hits) and **nearly harmless to a prepared raider** (twenty hits). That asymmetry is the entire design. It punishes casual intrusion and does not decide wars.

Vanilla 30 damage would kill an unarmored player in under one hit and still take out a netherite player in four, which is the "wall" outcome. Dropping to 6 makes it meaningless to everyone.

### 28.5 Time to remove it

500 HP with no sonic boom and 0.25 speed:

| Attack method | Solo | Three players |
|---|---|---|
| Netherite sword, Sharpness V | 28.4s | 9.5s |
| Diamond sword, Sharpness III | 34.7s | 11.6s |
| Netherite axe, Sharpness V | 48.1s | 16.0s |
| Power V bow, fully charged | 63.6s | 21.2s |

A solo raider can kill it in about half a minute of sustained melee while taking 1 damage per hit, which is survivable but requires committing to standing in the core chunk of an enemy city for thirty seconds. Three coordinated players do it in ten. Neither is trivial, neither is impossible.

### 28.6 Peacetime protection

**Outside a war, the City Warden cannot be permanently killed.**

At 0 health in peacetime it plays the burrow animation, dematerializes, and enters a **recovery period** of `defense.warden.recovery-hours` (default **6**), after which it re-emerges at full health. The city is notified when it goes down and when it returns.

The reasoning: a 2.75 million coin asset must not be removable by a single griefer outside the sanctioned combat window. Making it merely *drivable underground* preserves the achievement of killing it without letting a stranger delete a month of a city's investment.

**Inside a war, it dies permanently** like every other unit, and must be repurchased at full price. This is the correct stake, and it means a city with a Warden has a very strong reason to actually win its wars.

### 28.7 States

| State | Behaviour |
|---|---|
| DORMANT | Burrowed. Not rendered. The core chunk shows a subtle sculk particle effect and a faint heartbeat within 16 blocks. |
| PASSIVE | Emerged, standing, stationary. Ignores all players. Ambient Warden sounds. Visitors can walk right past it. |
| ALERTED | Trespass response. Emerges with the full roar, applies Darkness within 10 blocks to the trespasser only, pursues within the core chunk plus 6 blocks. |
| HOSTILE | War. Attacks enemy members entering the core chunk. |
| RECOVERING | Killed in peacetime. Absent for 6 hours. |

The dormant state uses the Warden's own vanilla burrow and emerge animations, which is a rare case where the vanilla mob already contains exactly the behaviour needed.

### 28.8 Implementation requirements

- `Warden#clearAnger()` every tick, and drive targeting **exclusively** from the plugin. The vibration-based anger system must never influence targets, or the Warden will aggro on a member walking past.
- Cancel `EntityDamageEvent` with `DamageCause.SONIC_BOOM` unconditionally, and cancel the sonic boom goal via the Paper Goal API so the animation never plays. Players must never see a windup for an attack that does nothing.
- `setPersistent(true)`, `setRemoveWhenFarAway(false)`.
- Darkness is applied by the plugin to specific players, not by the Warden's aura. Remove the vanilla aura.
- The heartbeat and ambient audio are played by the plugin at controlled volume, so the city can hear its Warden without it being audible across the server.
- Health is checkpointed to the database every 10 seconds while in combat.

### 28.9 Synergy with the war scoring system

Part I awards **+100 points** for standing in the enemy City Hall chunk for 30 seconds, the single largest score event in a war. The Warden is confined to that exact chunk.

That is the intended loop: the highest-value objective in the game is guarded by the most expensive unit in the game, and taking it requires holding ground for 30 seconds against a 500 HP obstacle while enemy players contest you. This is the plugin's climax fight and every number in this section is tuned toward making it work.

---

## 29. Siege units

### 29.1 Why attackers need them

With defense units on one side and nothing on the other, war math tilts toward turtling. Fewer declarations, fewer wars, and the rollback engine goes unused. Siege units restore the balance and, importantly, make wars cost the **attacker** money too, which the economy needs.

### 29.2 Siege Capacity scales with the defender

```
Siege Capacity = round(defender_defense_capacity * defense.siege.budget-ratio)
               = round(defender_capacity * 0.70)
```

| Defender Fortification | Defense Capacity | Attacker Siege Capacity |
|---|---|---|
| 0 | 100 | 70 |
| 2 | 150 | 105 |
| 5 | 225 | 157 |

This is self-balancing and is the mechanism that structurally prevents an unattackable fortress: **the more a city fortifies, the more siege its attacker is permitted to field.** A city cannot outbuild the counter, it can only make the war more expensive for both sides.

Siege Capacity is computed once, at war declaration, and frozen. Allies joining an attack share the attacker's budget rather than adding their own.

### 29.3 Roster

Three units plus one support. Deliberately fewer than the defensive roster, because the attacker's real advantages are choosing the timing, having full player mobility, and being able to retreat.

| Unit | Base mob | Role | Cost | Points | HP | Damage |
|---|---|---|---|---|---|---|
| Siege Beast | Ravager | Breaker | 40,000 | 40 | 180 | 14 |
| Breacher | Vindicator | Anti-unit specialist | 18,000 | 20 | 70 | 9 |
| Siege Archer | Pillager | Ranged support | 15,000 | 16 | 50 | 7 ranged |
| Banner Bearer | Pillager (captain) | Buff, **no attack** | 25,000 | 25 | 60 | **0** |

**Siege Beast (Ravager).** Heavy, knocks back hard, 1.0 knockback resistance. Its slam clears space. Counterplay: slow, and a Colossus matches it in a straight fight, so committing one commits the defender's tank too.

**Breacher (Vindicator).** Deals **2x damage to defense units** and **0.6x damage to players**. This is the key balance lever: it exists to break a garrison, not to kill players. It makes the attacker's mob budget a tool against the defender's mob budget without turning wars into mob-versus-mob spectacles.

**Siege Archer (Pillager).** Crossbow, 22-block range, no daylight burning. Mirrors the defensive Archer.

**Banner Bearer.** Deals no damage. Grants **Strength I and Speed I** to attacking players within 12 blocks. The presence of an ominous banner is thematically perfect for a siege, and a support unit that buffs players rather than fighting keeps the emphasis where it belongs, on the players.

### 29.4 Siege rules

| Rule | Value |
|---|---|
| When purchasable | `PREP` and `ACTIVE` only, by a city party to the war |
| Where placed | Inside a **Siege Camp**, see 29.5 |
| Lifetime | Despawn at war end. **No refund, ever.** |
| Movement | May enter enemy claims inside the war zone. Never leaves the war zone. |
| Targeting | Enemy players and enemy defense units. Never neutral players. |
| Rollback | Siege units are not restored. They are consumed. |
| Unit versus unit | Only the Breacher engages defense units directly. Others prioritise players. |

### 29.5 Siege Camp

Attackers place a **Siege Camp** banner block in wilderness or their own claims, within `defense.siege.max-camp-distance` (default 12 chunks) of the enemy city. It is the spawn and rally point for all siege units.

- One camp per attacking city
- Visible on `/city map` to **both** sides, deliberately. The defender should know where the attack is staging, because a siege the defender cannot see is not a siege, it is an ambush.
- Destroying the camp (it has 200 HP as a block-entity) despawns all siege units of that city and awards the defender **+40 war points**. It can be rebuilt once per war at half cost.

This creates a real secondary objective and gives defenders something to attack rather than only something to defend.

---

## 30. Implementation, edge cases, and config

### 30.1 Central targeting handler

Every targeting decision in the plugin flows through one handler on `EntityTargetLivingEntityEvent`. There must be exactly one such handler and no unit-specific targeting logic anywhere else.

```
onTarget(unit, candidate):
  if candidate is not a Player            -> allow only if hostile mob and unit is PASSIVE
  if candidate is member of owning city    -> CANCEL
  if candidate is member of allied city    -> CANCEL
  if candidate has civitas.bypass.war      -> CANCEL
  if candidate is Creative or Spectator    -> CANCEL
  if candidate joined/respawned < 5s ago   -> CANCEL
  if unit state is DORMANT or PASSIVE      -> CANCEL
  if unit state is ALERTED                 -> allow only if candidate == alerted target
  if unit state is HOSTILE                 -> allow if candidate is enemy in war zone
  if distance > unit.range                 -> CANCEL
  otherwise                                -> ALLOW
```

### 30.2 Edge cases

Continues the numbering from Part I Section 17.

| # | Case | Required behaviour |
|---|---|---|
| 87 | Unit materialized when server restarts | Health checkpointed every 30s during combat, 10s for the Warden. On startup, all units are DORMANT until a player approaches. |
| 88 | Chunk containing a unit is unclaimed | Unit is refunded 50% to treasury and removed. Mayor notified. |
| 89 | City disbands with units placed | All units removed, no refund. |
| 90 | Unit is killed by a city member | Allowed, no refund, no war score. Logged to `audit_log`, since it is a plausible sabotage vector. |
| 91 | Unit is killed by `/kill` or an admin | Same as 90, plus an `audit_log` entry naming the admin. |
| 92 | Unit wanders past its leash | Teleported back to post. If teleport fails three times, dematerialized and re-materialized at post. |
| 93 | Unit is trapped in a hole by an attacker | Intended tactic, allowed. The leash teleport only triggers on distance, not on being stuck. |
| 94 | Trespasser logs out during ALERTED | Alert state persists for the remaining duration. Logging back in inside the claims resumes it. |
| 95 | Trespasser is the only player and logs out | Units dematerialize normally after the delay. Alert state expires. |
| 96 | War ends while units are mid-combat | Units revert to PASSIVE immediately at war end, before rollback evacuation. |
| 97 | Warden is killed on the final day of a war | Permanent death. Must be repurchased at full price. |
| 98 | Warden killed in peacetime, then a war is declared during the 6h recovery | Recovery continues. The city fights that war without it. Recovery is not accelerated by war. |
| 99 | Core chunk is admin-transferred while a Warden is placed | Warden is removed and the city is refunded 100%, because this is an admin action, not a player outcome. |
| 100 | A city reaches Fortification 5, buys a Warden, then an admin downgrades the city | Warden persists. Downgrades do not retroactively remove purchased units. |
| 101 | Defense Capacity is exceeded after a Fortification downgrade | Units over budget are marked inactive (dematerialized, upkeep suspended) newest-first until within budget. Not deleted. |
| 102 | Siege Camp destroyed in the last 60 seconds of a war | Points awarded normally. Units despawn immediately. |
| 103 | Attacker places a Siege Camp inside a third city's claims | Blocked. Camps go in wilderness or the attacker's own claims only. |
| 104 | Two allied attacking cities both place camps | Allowed, one per city. Each holds its own units. |
| 105 | Unit drops equipment on death | **Never.** All equipment drop chances are 0.0. A guard in full dyed leather must not become a loot piñata. |
| 106 | Units contribute to the vanilla mob cap | They must not. Exclude from spawn calculations and set `setRemoveWhenFarAway(false)`. |
| 107 | Snow Golem sentry in rain or a warm biome | Water and melting damage cancelled explicitly, or every sentry dies in the first storm. |
| 108 | Zombie or Skeleton unit at sunrise | `setShouldBurnInDay(false)` on spawn, verified on every materialization. |
| 109 | Zombie unit spawns reinforcements when damaged | Disabled. Zombie reinforcement produces free untracked mobs. |
| 110 | Player uses a name tag on a defense unit | Blocked. Renaming would break the identity display. |
| 111 | Player leads a Warhound away with a lead | Blocked. Units cannot be leashed by players. |
| 112 | Unit pathfinds into lava or off a cliff | Damage from environment applies normally, but the unit teleports back to post at 20% health rather than dying, once per hour. Prevents terrain from deleting paid assets. |
| 113 | 200 cities each with 12 units, 40 players online | Materialization architecture (25.4) means only units near those 40 players exist. Benchmark: no more than 60 materialized units server-wide at 40 players. |

### 30.3 `defense.yml`

```yaml
capacity:
  base: 100
  per-fortification-level: 25

materialization:
  radius-blocks: 48
  dematerialize-delay-seconds: 30
  dormant-regen-percent-per-hour: 10
  regen-disabled-during-war: true
  health-checkpoint-seconds: 30
  warden-health-checkpoint-seconds: 10

placement:
  max-units-per-chunk: 3
  leash-blocks: 8
  war-purchase-cost-multiplier: 2.0
  war-purchase-inactive-seconds: 60

trespass:
  violations: 3
  window-seconds: 30
  warning-seconds: 5
  duration-seconds: 45
  de-escalation-seconds: 10
  alert-radius-chunks: 2

units:
  frost_sentry:
    cost: 6000       ; upkeep: 300   ; points: 8
    health: 30       ; damage: 0
    slowness-level: 2 ; slowness-seconds: 3
    mining-fatigue-level: 1 ; mining-fatigue-seconds: 2
  watchtower_keeper:
    cost: 9000       ; upkeep: 350   ; points: 10
    war-health: 40   ; detection-radius: 32
    chat-alert-cooldown-minutes: 5
  warhound:
    cost: 10000      ; upkeep: 500   ; points: 12
    health: 45       ; damage: 6     ; speed: 0.42
    chase-range: 24  ; target-priority: LOWEST_HEALTH
  archer:
    cost: 16000      ; upkeep: 700   ; points: 18
    health: 55       ; damage: 7     ; range: 20
    melee-firerate-penalty: 0.5
  city_guard:
    cost: 20000      ; upkeep: 900   ; points: 20
    health: 90       ; damage: 8     ; speed: 0.28
    armor: 8         ; toughness: 2
    alert-network-chunks: 3 ; alert-network-seconds: 20
  colossus:
    cost: 55000      ; upkeep: 2600  ; points: 45
    health: 220      ; damage: 16    ; speed: 0.20
    scale: 1.8       ; knockback-resistance: 1.0
    slam-radius: 3   ; slam-damage: 4
    arrow-resist-threshold: 8 ; arrow-resist-percent: 80

warden:
  enabled: true
  cost: 750000
  upkeep: 8000
  points: 0
  required-fortification-level: 5
  health: 500
  damage: 10
  speed: 0.25
  sonic-boom: false          ; NEVER set true. See 28.3.
  darkness-radius: 10
  leash-blocks: 6
  recovery-hours: 6
  killable-in-peacetime: false
  killable-in-war: true

siege:
  budget-ratio: 0.70
  max-camp-distance-chunks: 12
  camp-health: 200
  camp-destroy-points: 40
  camp-rebuild-cost-percent: 50
  units:
    siege_beast:  { cost: 40000, points: 40, health: 180, damage: 14 }
    breacher:     { cost: 18000, points: 20, health: 70,  damage: 9,
                    damage-vs-units: 2.0, damage-vs-players: 0.6 }
    siege_archer: { cost: 15000, points: 16, health: 50,  damage: 7, range: 22 }
    banner_bearer:{ cost: 25000, points: 25, health: 60,  damage: 0,
                    buff-radius: 12, buffs: [STRENGTH_1, SPEED_1] }
```

### 30.4 New message keys

Added to the Section 23 catalogue.

| Key | Audience | Channel | Template |
|---|---|---|---|
| `defense.purchased` | SELF | Chat | `{p.city}<body>Purchased <subject>{unit}</subject> for <neg>-{cost}</neg><body>. <dim>Place it inside a claim. Capacity: {used}/{total}.</dim>` |
| `defense.placed` | CITY | Chat | `{p.city}<subject>{player}</subject><body> stationed a <subject>{unit}</subject><body> at <subject>({x}, {z})</subject><body>.` |
| `defense.capacity_full` | SELF | Chat | `{p.error}<body>Defense capacity full: <subject>{used}/{total}</subject><body>. <dim>Upgrade Fortification or remove a unit.</dim>` |
| `defense.unit_killed` | CITY | Chat + sound | `{p.city}<neg>{unit} destroyed</neg><body> at <subject>({x}, {z})</subject><body> by <subject>{killer}</subject><body>.` |
| `trespass.warning` | SELF (trespasser) | **Title + Chat + sound** | Title `<war>WARNING</war>` / sub `<body>{city} defenses are activating</body>` / chat `{p.city}<neg>Leave {city} territory within 5 seconds.</neg>` |
| `trespass.alerted` | SELF (trespasser) | Chat | `{p.error}<neg>{city} defenses are hostile to you for {seconds} seconds.</neg>` |
| `trespass.city_notice` | CITY | Chat + sound | `{p.city}<neg>Trespasser detected:</neg> <subject>{player}</subject><body> at <subject>({x}, {z})</subject><body>.` |
| `warden.purchased` | SERVER | **Title + Chat** | `{p.city}<city>{city}</city><body> has awakened a <subject>City Warden</subject><body>.` |
| `warden.emerged` | SELF (trespasser) | **Title + sound** | Title `<war>The Warden stirs</war>` |
| `warden.defeated_peacetime` | CITY | Chat + sound | `{p.city}<neg>Your City Warden was driven underground</neg><body> by <subject>{player}</subject><body>. <dim>It returns in {hours} hours.</dim>` |
| `warden.returned` | CITY | Chat + sound | `{p.city}<pos>Your City Warden has returned.</pos>` |
| `warden.destroyed_war` | BOTH + SERVER | **Title + Chat** | `{p.war}<war>The City Warden of {city} has fallen.</war>` |
| `siege.camp_placed` | BOTH | Chat + map marker | `{p.war}<city>{city}</city><body> established a siege camp at <subject>({x}, {z})</subject><body>.` |
| `siege.camp_destroyed` | BOTH | Chat + sound | `{p.war}<body>Siege camp destroyed. <subject>{city}</subject><body> loses all siege units. <dim>+{points} to defenders.</dim>` |
| `siege.units_expired` | SELF (attacker) | Chat | `{p.war}<dim>Siege units dismissed at war end.</dim>` |

New sounds: Warden emerge `ENTITY_WARDEN_EMERGE` at 0.8, trespass warning `ENTITY_WARDEN_ROAR` at 1.0, unit destroyed `ENTITY_IRON_GOLEM_DEATH` at 1.2, siege camp destroyed `ENTITY_GENERIC_EXPLODE` at 0.7.

---

## 31. Milestones

Replaces Part I milestone M12, which referenced the superseded Section 12 roster.

| M | Milestone | Deliverable | Depends on |
|---|---|---|---|
| 12a | **Unit persistence layer** | `defense_units` schema, materialize and dematerialize architecture (25.4), health checkpointing, dormant regeneration, chunk-load and server-restart recovery. **No combat behaviour yet.** Benchmark case 113 before proceeding. | M5, M8 |
| 12b | **Central targeting handler** | The single `EntityTargetLivingEntityEvent` handler from 30.1, all four states, the never-target list from 26.4. Unit tests for every branch. | 12a |
| 12c | **Trespass response** | Violation tracking with sliding window, warning phase, alert phase, de-escalation, alert network, `audit_log` entries, all `trespass.*` messages | 12b, M4 |
| 12d | **Core roster** | Frost Sentry, Watchtower Keeper, Warhound, Archer, City Guard, Colossus. Dyed leather city colours, all abilities, all counterplay behaviour, edge cases 105 to 112. | 12c |
| 12e | **Defense Capacity** | Points budget, Defense GUI, purchase and placement flow, per-chunk cap, leash, upkeep integration, downgrade handling (case 101) | 12d, M11 |
| 12f | **City Warden** | Full Section 28. Sonic boom disabled and verified, vibration anger disabled and verified, peacetime recovery, dormant burrow state, core-chunk confinement. | 12e |
| 19a | **Siege units and camps** | Full Section 29. Siege Capacity computed at declaration, camp placement and destruction, war-end despawn. | 12f, M19 |
| 20a | **Combat balance pass** | Verify Rule 1 empirically: an attacking force equal to the defender's active member count beats a full garrison at Fortification 0, 2, and 5. Three trials each. Tune and record results in the spec. | 19a, M20 |

**M12f depends on M12e, not the reverse.** Do not build the Warden first because it is the interesting one. It is the unit with the most ways to go wrong, and it should be built on a targeting system already proven by six simpler units.

**M20a is not optional.** Rule 1 is the only balance claim in this part that cannot be verified by reading code, and a defense system that is accidentally unbeatable will not be discovered until players stop declaring wars, which is a slow and confusing failure to diagnose.

---

# PART IV, World Architecture, Death and PvP, Onboarding, and Seasons

> Appended to SPEC.md. Sections 32 to 38 continue the numbering of Parts I to III.
> **Section 33 supersedes Part I 5.5 and 11.6 on the subject of PvP.**
> **Section 32 resolves Part I Open Decisions 1 and 4.**

---

## 32. World architecture

### 32.1 Why this section exists

Parts I to III specified everything inside a city and nothing about the world those cities sit in. Three questions were unanswered and all three are load-bearing: where players mine, how a new player reaches unclaimed ground, and how far apart cities are.

### 32.2 World layout

| World | Name | Claims | Purpose |
|---|---|---|---|
| Main overworld | `world` | **Yes** | Cities, outposts, building, farming, contests, wars |
| Main nether | `world_nether` | **No** | Travel, structures, and long-distance transit |
| Main end | `world_the_end` | **No** | Endgame content, dragon, elytra |
| Resource overworld | `resource` | Waystations and mining claims only | Mining, quarrying, raw extraction |
| Resource nether | `resource_nether` | Waystations and mining claims only | Ancient debris, quartz, blaze rods |

This resolves **Part I Open Decision 1** (Nether and End are not claimable) and **Open Decision 4** (a city and its outposts exist in `world` only).

### 32.3 No world border

**The vanilla Minecraft border stands, unchanged, at roughly 30 million blocks. The plugin does not impose, expand, or manage a border of any kind.**

An earlier draft of this specification proposed a dynamic border that grew with city count, sized so that claimed land stayed between four and seven percent of the map. That design is rejected. It solved a problem that does not exist and destroyed something valuable in the process.

**Extremely low density is the point.** A world where a player can travel two hundred thousand blocks and build entirely alone is doing something no curated server can offer. Density is not a metric to optimise. Emptiness is the atmosphere, and the ability to disappear into it is a feature.

The practical consequences are accepted deliberately:

- Cities may be arbitrarily far apart, and often will be
- There is no map-full condition, ever, so land scarcity never becomes a design constraint
- The settled world is defined by where players actually are, not by a line in a config file
- **Outposts become genuinely load-bearing**, since they are the only mechanism that makes remote territory reachable and useful. See Section 39.

### 32.4 The frontier is created by `/rtp`, not by a border

Structure comes from where the game *sends* people, not from where it *stops* them.

`/rtp` places a player at a random safe location within `travel.rtp.max-radius` (default **15,000 blocks**) of world origin. That single number produces the world's shape:

- **Inside 15k: the settled core.** Every player who ever used `/rtp` started here. Cities cluster, borders touch, the recruitment board matters, and travel between cities is practical.
- **Beyond 15k: the frontier.** Reachable only by deliberate travel, nether transit, or elytra. A city out here chose to be out here. It is quieter, safer from casual visitors, and requires outposts to function.

A player who wants to found a city eight hundred thousand blocks out is free to do so and always will be. They simply have to walk, and that cost is paid in effort rather than enforced by a wall.

**Rules for `/rtp`:**

| Rule | Value |
|---|---|
| Radius | 15,000 blocks from origin, main overworld |
| Never lands inside | Any claim, any claim buffer, any outpost, any admin-protected region |
| Never lands within | 200 blocks of another player |
| Safe location | Solid ground, breathable, not in lava, not in the void, sky access preferred |
| Cost | 500 C |
| Cooldown | 5 minutes |
| Failure | If 40 candidate locations fail validation, report honestly and refund |

`/rtp resource` and `/rtp nether` use `travel.rtp.resource-max-radius` (default **25,000**), larger because the resource worlds exist to be dug into and spreading arrivals wider slows the depletion of ground near spawn.

### 32.5 The resource world, and why it does not reset on a schedule

Standard practice is a monthly resource world reset. **This project does not do that.** Mines are infrastructure. A player who spends three weeks digging a proper quarry with rail lines, lighting, storage, and sorting has built something, and wiping it monthly punishes exactly the long-term investment a building-focused server should reward.

| Property | Value |
|---|---|
| Reset schedule | **None automatic** |
| Manual reset | `/ca world reset resource`, admin only, expected roughly every 6 months |
| Reset notice | Mandatory 14-day in-game and out-of-game warning, enforced in code. The command refuses to run without a scheduled notice already active. |
| City claims | Blocked entirely |
| Waystations | Permitted, see 39.10 |
| Mining claims | Permitted, see 32.6 |
| PvP | **Disabled at all times, including during wars**, see 33.5 |
| Market | Fully functional, so a player can sell from a mining trip |
| Mob spawning | Vanilla rates, unmodified |

An unreset resource world will be strip-mined near its spawn over time. This is accepted. `/rtp resource` scatters arrivals across 25,000 blocks, and players naturally push outward, which is the intended progression.

### 32.6 Mining claims

The resource worlds have no city territory, but leaving them entirely unprotected means every mine base is griefable and nobody builds there.

**A player may hold one personal Mining Claim**, independent of city membership. This is the only form of land ownership available to a player with no city, and it is deliberately available to them.

| Property | Value |
|---|---|
| Size | 1 chunk |
| Limit | 1 per player, 2 with `civitas.limit.miningclaims.2` |
| Cost | 15,000 C |
| Upkeep | 500 C/day from personal balance |
| Worlds | `resource` and `resource_nether` only |
| Protection | Full block and container protection, Part I 5.5 rules |
| Trust | `/mine trust <player>`, max 4 |
| War | Never part of a war zone, never rolled back |
| Unpaid upkeep | 7-day grace, then released. Blocks are not removed. |

Commands: `/mine claim`, `/mine unclaim`, `/mine info`, `/mine trust`, `/mine untrust`, `/mine tp`.

### 32.7 Spawn and travel

**Server spawn** is a built hub in `world` inside an admin-protected region. PvP is disabled there under all circumstances including active wars. It holds the onboarding path from Section 34 and the city recruitment board.

| Command | Cost | Cooldown | Warmup | Notes |
|---|---|---|---|---|
| `/spawn` | free | 60s | 5s | Blocked for war participants during ACTIVE |
| `/rtp` | 500 C | 5 min | 5s | 15,000 block radius, main overworld |
| `/rtp resource` | free | 2 min | 5s | 25,000 block radius |
| `/rtp nether` | free | 2 min | 5s | 25,000 block radius |
| `/city spawn` | free | 30s | 5s | Part I 5.6, 15s warmup during war |
| `/city outpost tp <name>` | scaled | 3 min | 8s | See 39.5 |
| `/mine tp` | 100 C | 3 min | 8s | Own mining claim |
| `/warp <name>` | free | 30s | 5s | Admin-defined public warps |

All teleports are cancelled by movement or damage during warmup, and all are blocked while combat tagged, per 33.8.

This resolves **Part I Open Decision 2**: there is no personal `/home`. City spawn, outpost teleports, mining claim, and RTP cover every legitimate need, and a home system would undercut the value of city membership.

### 32.8 Backups in an unbounded world

An unbounded world means region files accumulate wherever anyone has ever travelled. Part IV's original "daily full world backup, keep 7" stops being viable within months once players scatter across hundreds of thousands of blocks.

| Requirement | Value |
|---|---|
| **Full world backup** | Weekly, keep 2 |
| **Incremental backup** | Daily, region files modified since the last run only, keep 14 days |
| **Pre-war zone snapshot** | Immediately before every war enters ACTIVE. Region files for the war zone only, which is bounded by definition. |
| Snapshot retention | Until the war reaches RESOLVED plus 7 days |
| Restore | `/ca world restore war <war_id>`, requires typing the war id twice |
| Disk guard | Refuse to start a war if free disk is under `world.backup.min-free-gb` (default 10) |
| Reporting | `/ca backup status` shows world size, region file count, last full, last incremental, and projected growth |

The pre-war zone snapshot is the safety net beneath the safety net. The diff-based rollback in Part I 11.8 is the primary mechanism and handles every normal case. The snapshot exists for the case where it does not, and it turns "the plugin ate my castle" from a catastrophe into a fifteen-minute admin fix.

---

## 33. PvP, death, and combat

> **This section replaces the earlier PvP rules in Part I 5.5 and 11.6 in full.**

### 33.1 The model

PvP is governed by two questions: **where you are**, and **whether you and your attacker are on opposing sides of an active war**. Nothing else.

| Location | Peacetime | Opposing war participants |
|---|---|---|
| Wilderness, main overworld | **PvP ON**, keepInventory **ON** | **PvP ON**, keepInventory **OFF** |
| Main Nether and End (unclaimed) | **PvP ON**, keepInventory **ON** | **PvP ON**, keepInventory **OFF** |
| Claimed chunks of a city **in this war** | PvP OFF | **PvP ON**, keepInventory **OFF** |
| Claimed chunks of any **neutral** city | PvP OFF | **PvP OFF**, see 33.4 |
| Resource overworld and resource nether | **PvP OFF** | **PvP OFF** |
| Mining claims | PvP OFF | PvP OFF |
| Server spawn and admin-protected regions | PvP OFF | PvP OFF |

Two design consequences worth stating explicitly:

**Peacetime PvP has no material stakes.** keepInventory is on, so killing someone in the wilderness gains the killer nothing and costs the victim nothing but time. It exists for skirmishing, bounty hunting, and the tension of travel, not for looting. This is what keeps peacetime wilderness PvP compatible with pillar 1.4.

**War is the only place items are ever lost to another player.** Combined with Part I 11.7 (hand-looted container items are not restored), war is the sole mechanism in the entire plugin by which a player permanently loses possessions to another player. Everything else is money, ranking, and reputation.

### 33.2 Friendly fire

**Members of the same city can never damage each other.** This is absolute and applies to every damage source attributable to a player: melee, projectiles, splash and lingering potions, TNT, end crystals, fire, lava placement, fall damage caused by knockback, and bed and respawn anchor explosions.

Implementation: friendly-fire cancellation happens in a single handler that resolves the **damage source to an owning player** first, then checks city membership. Handling only `EntityDamageByEntityEvent` with a direct player attacker is insufficient and will leave potions, TNT, and arrows as friendly-fire vectors.

**Allied cities.** Allies also cannot damage each other by default. This is `pvp.ally-friendly-fire: false`. Cities that want to spar can enable it mutually with `/ally sparring <city> <on|off>`, which requires both mayors to agree and reverts automatically when the alliance ends.

### 33.3 Peacetime

PvP is enabled in all unclaimed land in the main overworld, nether, and end. keepInventory and keepLevel are on for these deaths.

**Anti-harassment.** Because peacetime kills produce no loot, the only motives are recreation and harassment. Two rules address the latter:

- **Repeat-kill decay.** After a player kills the same victim `pvp.repeat-kill-threshold` (default **3**) times within `pvp.repeat-kill-window-minutes` (default **60**), further kills of that victim award no bounty, no statistics, and no leaderboard progress, and each one writes an entry to `audit_log`. The kills still function mechanically; they simply stop being worth anything.
- **New player immunity.** A player cannot be damaged by another player until they reach `pvp.immunity-playtime-hours` (default **2**) of active playtime. The immune player also cannot damage others. Status is visible with `/pvp status` and cannot be waived early.

**Bounties in peacetime.** Part I restricted bounty claims to active wars. With peacetime wilderness PvP now enabled, bounties are claimable in unclaimed land at any time. This gives peacetime PvP a purpose beyond recreation and gives the bounty system somewhere to live. All the Part II F7 protections still apply: a player cannot claim a bounty they placed, nor one on an IP-linked account, and repeat-kill decay applies to bounty payouts.

### 33.4 War

For the duration of an `ACTIVE` war, members of the two warring cities and their formally joined war allies may damage each other:

- In **all unclaimed land**, in every world where PvP is enabled
- Inside the **claimed chunks of any city party to that war**, including outposts

**Neutral cities are not battlegrounds.** Inside the claims of a city that is not party to the war, PvP remains off for everyone. Two enemies who meet inside a neutral city cannot fight there. This protects uninvolved players from having their city turned into an arena and prevents wars from spilling onto people who did not opt in.

This departs from a strictly literal reading of "PvP inside and outside chunks," and it is a deliberate narrowing. Without it, any city adjacent to a war becomes collateral, and the anti-toxicity pillar does not survive that.

**Participants versus non-participants.** A war participant meeting a non-participant in the wilderness falls under **peacetime rules**: PvP is on, keepInventory is on for both. War rules apply only when both parties are on opposing sides of the same active war.

### 33.5 Resource worlds

**PvP is disabled everywhere in `resource` and `resource_nether`, at all times, including during wars.**

The resource worlds are extraction zones. Their entire purpose is to be the place where the economy's mining income happens, and that requires being able to mine without watching for ambushes. Enabling war PvP there would make the primary income source unavailable to whichever side is losing a war, which compounds a defeat into an economic collapse.

**Block protection there is a separate question from PvP, and the two are easy to confuse.** No PvP does not mean blocks are safe: another player can still fill a mine with lava or wall off a tunnel. Two consistent positions exist, and one must be chosen:

- **Mining Claims enabled** (Section 32.5, current default): a player may protect one chunk for 15,000 C plus upkeep, which covers a mine entrance, storage, and base. Everything outside it is unprotected. This is the recommended option, because an unreset resource world with persistent mines will accumulate real infrastructure that players will be upset to lose.
- **Fully unprotected**: no claims of any kind, and the Guide Book states plainly that nothing built in the resource worlds is safe. Simpler, and consistent with treating the resource world as purely extractive.

Set by `worlds.resource.mining-claims-enabled`, default **true**.

### 33.6 Death and item loss

| Situation | Result |
|---|---|
| Killed by a player, peacetime, anywhere | **keepInventory and keepLevel** |
| Killed by an opposing war participant during `ACTIVE` | **Full vanilla drop.** Items and XP drop. |
| Killed by an opposing participant's TNT, fire, lava, or crystal | **Full vanilla drop**, attributed within `pvp.attribution-window-seconds` (default 30) |
| Killed by a mob, including defense units | Vanilla drop |
| Killed by environment (lava, fall, void, drowning, suffocation, starvation) | Vanilla drop, in war and out |
| Combat logging while tagged | Character is killed, items drop under whichever rule applies to the tagger |

**The honest tradeoff in "no keepInventory during war."**

This makes war genuinely high-stakes and gives raiding real weight, which is a legitimate and defensible design. It also means **wealth converts into war power**, because a city that can replace full netherite kits fifteen times has a durable advantage over one that cannot. That is in tension with pillar 1.3.

Three things already limit the damage. Part I 15.1 blocks cities over 20 members from declaring on cities under 5. The wager is capped at 25% of the *smaller* treasury. And in practice, nobody can afford to lose a top-tier kit repeatedly across a seven-day war, so both sides tend to converge on mid-tier gear, which is levelling.

If the tension proves real in practice, the intended remedy is 33.7 rather than turning keepInventory back on.

### 33.7 War graves, optional alternative

`pvp.war-graves: false` by default. This section documents the alternative so it can be enabled without redesign if 33.6 proves too punishing.

When enabled, a player killed by an opposing war participant does not drop items. Instead a **grave** is created at the death location:

| Property | Value |
|---|---|
| Appearance | A player head block with a Text Display showing the owner's name and a countdown |
| Contents | The full inventory and XP |
| Owner access | The owner may recover it by returning and right-clicking |
| Enemy access | Any opposing war participant may loot it after `pvp.grave-enemy-delay-seconds` (default 60) |
| Lifetime | `pvp.grave-lifetime-minutes` (default 30), after which contents drop normally |
| Protection | The grave block itself cannot be broken, only interacted with |

This preserves real stakes, since a grave can absolutely be lost to the enemy, while turning body recovery into a contested objective. Fighting over a fallen teammate's grave is one of the better emergent dynamics available in team PvP, and it converts pure attrition into a tactical decision.

### 33.8 Combat tag

A player is combat tagged when they deal or receive damage from another player.

| Context | Duration |
|---|---|
| Peacetime | `pvp.combat-tag-seconds` (default **30**) |
| War, opposing participants | `pvp.war-combat-tag-seconds` (default **120**) |

**120 seconds, not 300.** Five minutes is long enough that a single arrow from an unseen archer locks a player out of teleporting for the length of a real activity, and a harasser who lands one hit every four minutes can keep a target tagged indefinitely. Two minutes is long enough that fleeing an ambush by teleport is impossible, which is the actual goal.

**The tag refreshes, it does not stack.** Each new hit resets the timer to its full duration rather than adding to it.

While tagged:

| Blocked | Notes |
|---|---|
| All teleports | `/spawn`, `/rtp`, `/city spawn`, `/mine tp`, `/warp`, `/city outpost tp` |
| City vault access | Prevents banking loot mid-fight |
| Safe logout | Logging out kills the character; items drop under the applicable rule |
| Leaving the war zone by teleport | Covered by the blanket teleport block |

Not blocked: walking, riding, boats, ender pearls, nether portals. Escaping by moving is always legitimate. Escaping by menu is not.

The remaining tag time is shown continuously on the action bar, with a distinct colour for the war duration. A player must never be surprised that a teleport was refused.

### 33.9 Edge cases

Continues from Part III Section 30.2.

| # | Case | Required behaviour |
|---|---|---|
| 114 | Player is tagged, then their war ends | Tag immediately shortens to the peacetime remainder |
| 115 | Player is tagged in peacetime, then a war they are in becomes ACTIVE | Tag extends to the war duration |
| 116 | War participant is killed inside a neutral city's claims | Cannot happen; PvP is off there. If a damage event somehow resolves, cancel it. |
| 117 | Player is hit at the exact moment they cross a claim boundary | Evaluated at the moment damage is applied, using the **victim's** location |
| 118 | Splash potion thrown from wilderness lands inside a claim | Resolved by the victim's location, so it is cancelled |
| 119 | TNT placed in wilderness during peacetime damages a player | Attributed to the placer, treated as peacetime PvP, keepInventory applies |
| 120 | Same-city member is caught in a member's TNT | Cancelled entirely, per 33.2 |
| 121 | Player logs out at 29 seconds of a 30-second tag | Killed. There is no near-miss grace. |
| 122 | Player is killed while combat logging in war | Items drop at the logout location. Killer is credited and scores. |
| 123 | New player under PvP immunity attacks someone | Blocked in both directions. Immunity is not a one-way shield. |
| 124 | Player under PvP immunity joins a city that goes to war | Immunity is overridden by war participation. Joining a warring city is a choice to fight. |
| 125 | Bounty target is killed in the resource world | No PvP there, so it cannot happen |
| 126 | Bounty target is killed by the same player four times in an hour | Fourth kill pays nothing, per 33.3 repeat-kill decay |
| 127 | Player dies to a defense unit during war | Vanilla drop. Defense units are mobs, not war participants. |
| 128 | Damage source is a player-owned wolf or tamed mob | Resolved to the owning player, then all rules apply normally |
| 129 | A war ends while a player is mid-flight with an ender pearl in a war zone | Lands normally, then peacetime rules apply on arrival |
| 130 | Player enters the resource world while combat tagged | Allowed by walking or portal, since only teleports are blocked. The tag continues to run and PvP remains off there. |

### 33.10 Configuration

```yaml
# combat.yml
pvp:
  peacetime-wilderness: true
  peacetime-claims: false
  war-in-war-zone-claims: true
  war-in-neutral-claims: false
  resource-worlds: false

  friendly-fire-same-city: false
  ally-friendly-fire: false
  ally-sparring-opt-in: true

  immunity-playtime-hours: 2
  repeat-kill-threshold: 3
  repeat-kill-window-minutes: 60

  attribution-window-seconds: 30

  combat-tag-seconds: 30
  war-combat-tag-seconds: 120
  tag-refreshes-not-stacks: true
  tag-blocks: [TELEPORT, VAULT, SAFE_LOGOUT]
  tag-actionbar: true

  war-graves: false
  grave-enemy-delay-seconds: 60
  grave-lifetime-minutes: 30

death:
  keep-inventory-peacetime-pvp: true
  keep-inventory-war-pvp: false
  keep-inventory-environment: false
  keep-inventory-mob: false

bounty:
  claimable-in-peacetime: true
  claimable-in-war: true
  requires-unclaimed-land: true
```

---

## 34. Onboarding

### 34.1 The gap

Parts I to III assume the player already understands cities, claims, quotas, wars, and contests. Nothing describes what happens in a new player's first ten minutes, and nothing answers whether a player who never joins a city is a supported audience or a bug.

**They are a supported audience.** A player may play indefinitely without a city: they can earn, sell, quest, hold a mining claim, and vote in contests. They cannot claim territory, hold a treasury, or fight wars. Roughly 30% of players on any server will never join a group, and designing them out is designing out 30% of the server.

### 34.2 First session

Sequence on first join, all timings in `onboarding.yml`:

1. **Spawn** at the server hub, in a protected region, facing an information board.
2. **Title:** welcome, plus a subtitle naming the server. One title only. No wall of chat text.
3. **Chat**, staggered over 20 seconds so it is readable: three lines maximum, telling the player what the server is, that `/guide` exists, and that `/rtp` finds land.
4. **Guide Book** placed in inventory slot 8. A written book, not a wall of chat. Chapters: Money, Cities, Land, War, Contests, Commands. Every chapter has clickable commands. Re-obtainable with `/guide`.
5. **Starting balance** of 2,000 C paid, with the message explaining where money comes from.
6. **Starter quest chain** assigned, see 34.3.
7. **No forced tutorial, ever.** The player can walk away from all of it. Everything above is a nudge, not a gate.

### 34.3 Starter quest chain

Five steps, once per account, distinct from the daily quests in Part I 13.1. Each pays modestly and teaches exactly one system.

| # | Task | Teaches | Reward |
|---|---|---|---|
| 1 | Sell any item to the market | Income, `/sell` | 500 C |
| 2 | Use `/rtp` and travel 500 blocks from spawn | Travel, finding land | 500 C |
| 3 | Visit any existing city | Cities exist, claims are visible | 750 C |
| 4 | Mine 32 iron ore in the resource world | Where mining happens | 750 C |
| 5 | Either found a city **or** claim a mining claim | Both paths are valid | 2,500 C |

Step 5 is deliberately branched. It is the moment the game tells the player that not joining a city is a real choice and not a failure state.

Total: 5,000 C plus the 2,000 starting balance, which is 70% of a city founding fee. A motivated new player can found a city in their first session or two, which is the correct pace.

### 34.4 Discoverability

- `/guide` reissues the book at any time
- `/help` and `/city help` are paginated with clickable commands
- Every GUI has a **Help** button (Book icon, slot 8) explaining that screen and its buttons
- Every error message names the remedy, per Part II 23.1 rule 5
- A one-line contextual tip is shown on join, cycling through features, disableable with `/toggle tips`
- **City recruitment board** at spawn: a GUI listing cities with `open_join` enabled, sorted by member count ascending, so new players are steered toward small cities rather than the biggest one. This directly serves pillar 1.3.

### 34.5 New player protections

Consolidating and extending Part I 15.1 and Part II F12:

| Protection | Duration |
|---|---|
| Income multiplier 1.5x | 14 days |
| Cannot be killed in war PvP | Until 2 hours of active playtime |
| No income at all | First 60 minutes of active playtime (Part II F12) |
| Exempt from being declared upon if their city has under 5 members and the attacker has over 20 | Permanent, Part I 15.1 |
| Cannot be invited to a city in `PREP` or `ACTIVE` war | Always, Part I 11.5 |

---

## 35. Seasons

### 35.1 The problem

Part I has seven leaderboards with permanently cumulative totals. Six months in, the founding cities hold every top slot and a player who joins in month seven cannot ever appear on any of them.

The multi-axis leaderboard design in Part I 13.3 existed specifically to give newcomers a path to visible status. Permanent accumulation removes that path and quietly breaks pillar 1.3.

### 35.2 Season structure

| Property | Value |
|---|---|
| Length | 90 days, `seasons.length-days` |
| What resets | **Leaderboard rankings only** |
| What never resets | Cities, claims, treasuries, balances, builds, upgrades, member rosters, the world |
| Hall of Fame | Permanent record of every season's winners in each category, viewable with `/season history` |
| End of season | 7-day announcement, final standings ceremony, rewards paid |

**Nothing a player built or owns is ever taken away.** This is not a wipe. It is a scoreboard reset. That distinction must be stated explicitly and repeatedly in-game, because "season" on most servers means "your stuff is deleted" and players will assume the worst.

### 35.3 Season rewards

Paid at season end, cosmetic and titular rather than economic, so seasons do not become the dominant income source.

| Placement | Reward |
|---|---|
| 1st in any category | A permanent city banner pattern, a chat title, and a Hall of Fame entry |
| Top 3 | Chat title for the following season |
| Top 10 | Hall of Fame entry |
| Participation (any category, any rank) | A commemorative item, non-tradeable |

Small currency prizes are permitted but capped at `seasons.max-currency-prize` (default 100,000 C to a treasury), because a large seasonal payout distorts the money supply accounting in Part II.

### 35.4 Season-long content

Each season has an announced **theme** that shapes the events in Part I 13.5 and the contest themes in 13.4. Examples: a season of exploration weights Gold Rush and mining contests, a season of war reduces war cooldowns and adds a war-focused leaderboard.

This gives an existing server a reason to feel different every three months without any code changes, which is the cheapest possible source of long-term retention.

### 35.5 Commands

| Command | Description |
|---|---|
| `/season` | Current season, day, days remaining, theme, own standings |
| `/season history [n]` | Past seasons and winners |
| `/season rewards` | What is on offer this season |
| `/ca season start <name> <theme>` | Admin, begins a season |
| `/ca season end` | Admin, ends and scores |
| `/ca season extend <days>` | Admin |

---

## 36. Remaining policies

Short answers to the questions Parts I to III left open. Each is a real decision that must be implemented, not a note.

### 36.1 Wilderness building

Anything built outside a claim in `world` is **unprotected**. This is stated plainly in the Guide Book and on the information board at spawn, because a new player's first house being destroyed is a common reason people quit.

Two mitigations, no more:
- The starter quest chain (34.3) pushes the player toward a city or a mining claim by step 5, so the window in which they own an unprotected build is short
- `/city here` in wilderness prints an explicit warning that the chunk is unprotected

**Wilderness griefing is not a punishable offence.** Unclaimed land is unclaimed. Making it a rules matter creates an unenforceable moderation burden, and the claim system is the answer the plugin already provides.

### 36.2 Mob spawning in claims

| Setting | Value |
|---|---|
| Hostile spawning in claims | Vanilla rules, unmodified |
| Reason | Light your city. Removing hostile spawns removes a core reason to build lighting, decorate with lanterns, and construct walls, all of which are the point of a building server. |
| Exception | The core chunk has hostile spawning disabled, so the City Hall is always safe |

### 36.3 Anticheat

The plugin does not ship an anticheat. It must, however, be compatible with one:

- All plugin-caused teleports (leash returns, rollback safe-placement, RTP, war evacuation) must be flagged so an external anticheat does not read them as cheating
- Defense unit knockback (the Colossus slam) applies via the Bukkit velocity API, which anticheats generally tolerate
- The `/ca perf` output includes a note on any detected anticheat plugin
- Document known-good configurations for the common anticheats in the README once one is chosen

### 36.4 Public API

Part I created an `api/` package with no contents specified.

The plugin exposes: read-only accessors for cities, claims, members, ranks, balances, and war state, and the full set of cancellable custom events already listed in Part I 2.3. All economy mutation goes through the service layer and is **not** exposed, deliberately, so no third-party plugin can create money outside the ledger.

An optional Vault economy provider may be registered so other plugins can read balances, with a config flag defaulting to on. PlaceholderAPI placeholders are provided for city name, tag, rank, balance, treasury, claim count, and war state.

### 36.5 Accessibility

The message system in Part II 23.2 leans heavily on green and red, which is the most common form of colour blindness.

- `/toggle colorblind` switches to a deuteranopia-safe palette (blue and orange in place of green and red)
- **No message ever conveys meaning by colour alone.** Every positive amount carries a `+`, every negative carries a `-`. A player reading in greyscale loses nothing.
- All GUI status indicators pair colour with a distinct item, never two dyes of different colours

### 36.6 Metrics

- bStats integration, standard anonymous plugin metrics, disableable
- `/ca stats server` reports registered players, active players over 7 and 30 days, city count, total claims, average city size, war count, and contest participation
- These are written to a daily `server_stats` table so a server owner can see trends, which is the only way to notice retention problems before they are terminal

### 36.7 Endgame

A maxed city at Fortification 5 with a Warden and 200 chunks has, in Parts I to III, nothing left to pursue. Three answers, all cheap:

1. **Seasons** (Section 35) provide a recurring competitive objective independent of progression
2. **Contest Champion and Hall of Fame status** are permanent and unbounded
3. **A Prestige track**, post-1.0: a maxed city may reset one upgrade line in exchange for a permanent cosmetic marker and a small permanent bonus. Deferred, but the upgrade schema should not preclude it.

---

## 37. Configuration additions

```yaml
# world.yml
worlds:
  main: world
  main-nether: world_nether
  main-end: world_the_end
  resource: resource
  resource-nether: resource_nether
  claimable: [world]
  mining-claimable: [resource, resource_nether]

border:
  dynamic: true
  base-radius: 2000
  expand-per-bracket: 500
  bracket-size: 25
  max-radius: 6000
  nether-ratio: 0.125
  announce-expansion: true

resource-world:
  auto-reset: false
  reset-notice-days: 14
  reset-requires-active-notice: true

mining-claims:
  cost: 15000
  upkeep-per-day: 500
  base-limit: 1
  max-trusted: 4
  grace-days: 7

travel:
  spawn:        { cost: 0,   cooldown: 60,  warmup: 5 }
  rtp:          { cost: 500, cooldown: 300, warmup: 5, min-player-distance: 200 }
  rtp-resource: { cost: 0,   cooldown: 120, warmup: 5 }
  mine-tp:      { cost: 100, cooldown: 180, warmup: 8 }

backup:
  world-daily-hour: 5
  world-keep-count: 7
  war-zone-snapshot: true
  war-snapshot-retention-days: 7
  min-free-gb: 10

# combat.yml
pvp:
  peacetime: false
  war-global: true
  exclusion-zones: [SPAWN, ADMIN_PROTECTED, MINING_CLAIM]
  respawn-grace-seconds: 10
  join-grace-seconds: 10

death:
  keep-inventory-on-enemy-kill: true
  keep-inventory-on-environment: false
  keep-inventory-on-mob: false
  enemy-attribution-window-seconds: 30

combat-tag:
  seconds: 15
  block-logout: true
  block-teleport: true
  block-vault: true

war:
  unopposed-score-multiplier: 0.3
  walkover-absence-percent: 70

# onboarding.yml
onboarding:
  starting-balance: 2000
  give-guide-book: true
  guide-book-slot: 8
  message-stagger-seconds: 20
  starter-quests-enabled: true
  new-player-pvp-immunity-playtime-hours: 2
  recruitment-board-sort: MEMBERS_ASC

# seasons.yml
seasons:
  enabled: true
  length-days: 90
  announce-days-before-end: 7
  reset: [LEADERBOARDS]
  never-reset: [CITIES, CLAIMS, TREASURY, BALANCES, BUILDS, UPGRADES]
  max-currency-prize: 100000
```

---

## 38. Milestones

| M | Milestone | Deliverable | Depends on |
|---|---|---|---|
| 3a | **World architecture** | Multi-world setup, world whitelist enforcement, dynamic border with expansion tracking, border expansion event and announcement, per-world claim rules | M3 |
| 3b | **Travel** | `/spawn`, `/rtp` with claim-and-buffer-aware safe location search, `/warp`, warmups, cooldowns, cancellation on movement and damage | 3a |
| 3c | **Mining claims** | Full Section 32.5, `/mine` command tree, protection reusing the M4 listeners, upkeep from personal balance, grace and release | 3a, M4, M5 |
| 4a | **PvP policy** | Peacetime PvP disabled globally, exclusion zones, join and respawn grace | M4 |
| 19b | **War PvP and death** | Global war PvP (33.2), scoped keepInventory (33.3), combat tagging (33.4), unopposed score multiplier and walkover (33.5) | M19, 4a |
| 19c | **World backups** | Daily world backup, pre-war zone region snapshot, `/ca world restore`, disk guard | 3a, M18 |
| 9b | **Onboarding** | First-join flow, Guide Book, starter quest chain, contextual tips, recruitment board GUI, `/guide` | M9, M8 |
| 14c | **Seasons** | Season state machine, leaderboard reset scoped to rankings only, Hall of Fame, rewards, `/season`, admin commands | M14 |
| 23c | **Accessibility** | Colourblind palette, symbol-plus-colour audit across every message and GUI element | M23a |
| 21b | **Metrics and API** | bStats, `server_stats` table, `/ca stats server`, public read-only API, PlaceholderAPI, optional Vault provider | M21 |

**M19b must land in the same session block as M19.** Global war PvP changes what the war zone means, and building the war lifecycle against the old zonal assumption and then retrofitting is more work than doing it once.

**M3a should come early, before M4 land protection is finished.** Every protection listener needs to know which worlds are claimable, and retrofitting world-awareness into finished listeners is a tedious pass over a lot of files.

---

# PART V, Outposts

> Appended to SPEC.md. Sections 39 to 41 continue the numbering of Parts I to IV.
> **This part replaces Part I Section 7 in full.** The single-chunk outpost design
> in Section 7 must not be implemented. Implement Section 39 instead.

---

## 39. Outposts

### 39.1 Why Part I Section 7 is replaced

Part I designed outposts for a bounded world: one chunk, a teleport pad, a flat 25,000 C, capped at four. In a world with a managed border and cities a few hundred blocks apart, that was adequate.

Section 32.3 removed the border. Cities may now be a million blocks apart, and a single chunk with a warp pad is no longer a serious answer to the question of how a city projects itself across that distance. Outposts are now the **only** mechanism that makes remote territory reachable and useful, which makes them a primary system rather than a convenience feature.

Three things change:

1. **Outposts are miniature cities.** Up to four connected chunks with full protection, ranks, and defense, not a single tile.
2. **Cost scales with distance**, continuously and without a cap, using the formula in 39.3.
3. **A second kind exists**: the Waystation, for the resource worlds, covered in 39.10.

The **Supply Range** point budget proposed during design is dropped. It existed to make distant outposts more expensive than near ones, and the distance-scaled cost formula does that directly and more legibly. Two systems solving one problem is one system too many.

### 39.2 What an outpost is

A detached holding of a city, not adjacent to the city body.

| Property | Value |
|---|---|
| Size | 1 to 4 chunks, edge-connected to each other |
| Count | 2 base, up to 6 via the Outpost Range upgrade (Part I 5.7) |
| World | `world` only. Outposts cannot exist in the Nether, the End, or the resource worlds. |
| Protection | Identical to city claims. Part I 5.5 applies unchanged. |
| Ranks | City ranks apply. There are no outpost-specific ranks. |
| Teleport | One warp point per outpost, settable anywhere within its chunks |
| Defense units | Permitted, capped, see 39.8 |
| War | Valid war targets, part of the war zone, fully rolled back |
| City Hall | Never. One per city, in the core chunk. |
| City spawn | Never. Outposts cannot host the city spawn. |

An outpost is not a second city. It has no treasury, no separate membership, no independent diplomacy, and cannot declare war. It is territory the city owns, somewhere else.

### 39.3 The cost formula

This is the centrepiece of the rework. Every outpost chunk is priced as a normal city chunk plus a distance premium that grows on a square root curve, so a very distant outpost costs a great deal without becoming absurd.

```
outpost_chunk_cost(n, k, d) = base(n) * D(d) * F(k) / member_divisor

  base(n) = 400 * n^1.25
            n = the city's TOTAL chunk count including all outpost chunks,
                exactly as Part I 6.2 computes it for city chunks

  D(d)    = 1 + 0.25 * sqrt(d / 1000)
            d = block distance from the city core chunk centre to this chunk centre,
                measured in the horizontal plane

  F(k)    = 1.50            if k == 1   (founding chunk of a new outpost)
          = 1 + 0.25*(k-1)  if k  > 1   (expansion chunks 2, 3, 4)

  member_divisor = 1 + 0.18 * (active_members - 1)     unchanged from Part I 6.2
```

**Why square root.** Linear distance pricing makes a million-block outpost a hundred times a ten-thousand-block one, which forbids the frontier outright. Logarithmic pricing flattens so hard that distance stops mattering past about fifty thousand blocks. Square root sits between: the premium keeps rising forever, but each additional order of magnitude costs progressively less per block.

| Distance | D(d) multiplier |
|---|---|
| 500 | 1.18x |
| 1,000 | 1.25x |
| 5,000 | 1.56x |
| 15,000 (the `/rtp` frontier) | 1.97x |
| 50,000 | 2.77x |
| 100,000 | 3.50x |
| 500,000 | 6.59x |
| 1,000,000 | 8.91x |
| 5,000,000 | 18.68x |

**Why outpost chunks count toward `n`.** An outpost chunk is a claim, and claiming it makes every future chunk anywhere in the city more expensive. This is deliberate: expansion is expansion, and a city that sprawls should feel that in its next purchase regardless of where it sprawled. It also removes an obvious exploit, since otherwise a city would claim cheap outpost chunks to avoid raising its city chunk index.

**The founding surcharge of 1.50x** exists because establishing a new remote holding is a project, while adding a chunk to one that already exists is not. It is deliberately modest, because the distance multiplier is already doing the heavy work and stacking two large multipliers produced numbers no city could reach.

### 39.4 Cost reference

Founding chunk of a new outpost, single-member city, at various city sizes and distances:

| City chunks | @1k | @15k | @100k | @1M |
|---|---|---|---|---|
| 20 | 31,721 | 49,948 | 88,819 | 225,999 |
| 50 | 99,718 | 157,016 | 279,211 | 710,447 |
| 100 | 237,171 | 373,448 | 664,078 | 1,689,737 |
| 200 | 564,090 | 888,215 | 1,579,453 | 4,018,894 |
| 400 | 1,341,641 | 2,112,543 | 3,756,594 | 9,558,594 |

Complete four-chunk outpost, total of all four purchases:

| City chunks | @1k | @15k | @100k | @1M |
|---|---|---|---|---|
| 20 | 139,625 | 219,853 | 390,950 | 994,766 |
| 50 | 414,755 | 653,072 | 1,161,315 | 2,954,947 |
| 100 | 967,516 | 1,523,447 | 2,709,044 | 6,893,120 |
| 200 | 2,278,724 | 3,588,071 | 6,380,428 | 16,234,896 |
| 400 | 5,393,137 | 8,492,015 | 15,100,782 | 38,423,699 |

**Calibration against income.** Under the Part II daily sell quota, a ten-member city earns roughly 250,000 C per day. A full four-chunk outpost at 100,000 blocks, built by a hundred-chunk city, costs 2.71M, which is about eleven days of that city's entire collective income. That is a serious project, not a casual purchase, and it is achievable.

The same outpost at a million blocks costs 6.89M, roughly twenty-seven days. An empire that reaches that far is straining to hold itself together, which is thematically correct and mechanically self-limiting without any cap being imposed.

Note that the member divisor applies, so a fifteen-member city pays these figures divided by 3.52. Distant expansion is a group project, exactly as city expansion is.

### 39.5 Upkeep and teleport

Both scale with the same distance multiplier, so an outpost far away is a permanent commitment rather than a one-time purchase.

```
outpost_upkeep_per_day = outposts.base-upkeep-per-chunk * D(d) * chunk_count
                       = 1200 * D(d) * chunks

outpost_teleport_cost  = outposts.base-teleport-cost * D(d)
                       = 100 * D(d)
```

| Distance | Upkeep per chunk/day | Full 4-chunk outpost/day | Teleport fee |
|---|---|---|---|
| 1,000 | 1,500 | 6,000 | 125 C |
| 15,000 | 2,362 | 9,448 | 197 C |
| 100,000 | 4,200 | 16,800 | 350 C |
| 1,000,000 | 10,687 | 42,747 | 891 C |

A four-chunk outpost at a million blocks costs 42,747 C per day, which is roughly seventeen percent of a ten-member city's total daily income. Sustainable, and felt.

Outpost upkeep is charged with city upkeep in the same daily cycle (Part I 4.3) and is included in the runway figure shown by `/city upkeep`. If the city becomes delinquent, **outposts are released before city chunks**, furthest first, because a city should lose its frontier before it loses its home.

Teleport rules unchanged from Part I: 8-second warmup, 3-minute cooldown, disabled entirely during a war involving the city, unsafe destinations resolve to the highest safe Y within the outpost.

### 39.6 Placement rules

| Rule | Value | Reason |
|---|---|---|
| Minimum distance from own city body | 32 chunks | Prevents using outposts to bypass the adjacency rule |
| Minimum distance from own other outposts | 24 chunks | Prevents daisy-chaining outposts into a corridor |
| Minimum distance from another city's claims | 8 chunks | Part I buffer, unchanged |
| Internal contiguity | All chunks of one outpost must be edge-connected | An outpost is a place, not scattered tiles |
| Maximum chunks | 4 | |
| Expansion adjacency | Expansion chunks must border an existing chunk of that outpost | |
| World | `world` only | |

Six outposts of four chunks each is twenty-four remote chunks. With a 24-chunk minimum spacing between them, they cannot be arranged into a continuous road, which is the specific abuse this constraint exists to prevent.

### 39.7 Merging

Because outposts are now up to four chunks and cities grow, contact becomes likely rather than a curiosity.

| Situation | Result |
|---|---|
| An outpost chunk becomes edge-adjacent to the city body | **The entire outpost merges into the city.** All its chunks convert to NORMAL, the outpost slot frees, the warp point is deleted, and nothing is refunded. Mayor notified. |
| Two of a city's own outposts become adjacent | **They merge into one outpost**, keeping the older one's name and warp. One slot frees. If the merged result exceeds 4 chunks, the merge is **blocked** and the claim that would trigger it is rejected with a clear message. |
| A merge would exceed the city's outpost cap | Cannot occur; merging only ever frees slots |
| An outpost is reduced to zero chunks by unclaiming | The outpost record is deleted and the slot frees |

Merging never refunds and never charges. The chunks were already paid for.

### 39.8 Defense at outposts

Outposts may host defense units from Part III, drawn from the **city's single Defense Capacity budget**. There is no separate outpost budget.

| Rule | Value |
|---|---|
| Maximum units per outpost | 4, regardless of chunk count |
| Maximum units per chunk | 3, unchanged from Part III 27.8 |
| City Warden | **Never.** The Warden is bound to the core chunk permanently. |
| Materialization | Standard Part III 25.4 rules apply, so a remote outpost with nobody near it costs nothing |

The four-unit cap per outpost exists so a city cannot convert its entire garrison into a remote fortress and leave its actual city undefended, which would make wars unwinnable in the wrong direction.

### 39.9 Outposts in war

Unchanged in principle from Part I, but materially more significant now that outposts are four chunks with defenders.

- Outposts of a warring city are part of the war zone, are griefable, and are fully rolled back
- The one-chunk perimeter from Part I 11.4 applies around each outpost
- Outpost teleports are disabled for both sides during PREP and ACTIVE, so nobody can instantly reinforce
- Capture points are generated from the **main city body only**, never from outposts, so a war is decided at the city rather than at a remote holding
- Block-break score inside an outpost counts at the standard rate and against the same cap

**A distant outpost extends the war zone by up to four chunks plus perimeter, in a location possibly hundreds of thousands of blocks from the main fight.** The war zone chunk set (Part I 11.4) must handle a discontiguous, widely separated set of regions, and the pre-war snapshot in 32.8 must cover all of them. This is the single most important implementation consequence of the rework.

### 39.10 Waystations

The resource world equivalent. A different thing with a different purpose, deliberately kept minimal.

| Property | Value |
|---|---|
| Worlds | `resource` and `resource_nether` only |
| Limit | **1 per city per resource world**, so 2 total. Separate pool from the 2 to 6 outpost limit. |
| Size | 1 to 2 chunks, edge-connected |
| Purpose | A shared teleport anchor and protected mining base for the whole city |
| Cost | `60,000 * W(d)` for chunk 1, `90,000 * W(d)` for chunk 2 |
| Distance | `W(d) = 1 + 0.10 * sqrt(d / 1000)`, measured from that world's spawn, not from the city core |
| Upkeep | `1,500 * W(d)` per chunk per day |
| Teleport | `/city waystation tp <world>`, 8s warmup, 3 min cooldown, 200 C |
| Defense units | **Not permitted.** PvP is disabled in the resource worlds, so defenders would have nothing to defend against. |
| War | **Never** a war target, never in a war zone, never rolled back |
| Protection | Full, identical to city claims |

`W(d)` uses a gentler constant than outposts because the resource worlds exist to be travelled deep into, and penalising that would defeat their purpose. At 15,000 blocks the multiplier is 1.39x, at 100,000 it is 2.00x.

**Waystation and Mining Claim coexist and do not overlap.** A Mining Claim (32.6) is personal, one chunk, available to any player including those with no city. A Waystation is city-owned, up to two chunks, and gives every member a teleport anchor. A player may hold both.

### 39.11 Commands

Replaces Part I 7.3.

| Command | Permission | Description |
|---|---|---|
| `/city outpost create <name>` | OUTPOST_MANAGE | Founds an outpost at the current chunk. Shows the full cost breakdown and requires confirmation. |
| `/city outpost claim <name>` | OUTPOST_MANAGE | Adds the current chunk to an existing outpost |
| `/city outpost unclaim` | OUTPOST_MANAGE | Removes the current chunk. 50% refund to treasury. Blocked if it breaks the outpost's internal contiguity. |
| `/city outpost delete <name>` | OUTPOST_MANAGE | Removes the whole outpost. 50% refund of all chunks. Confirmation required. |
| `/city outpost tp <name>` | OUTPOST_TP | Teleport |
| `/city outpost setwarp <name>` | OUTPOST_MANAGE | Sets the warp point within the outpost |
| `/city outpost rename <old> <new>` | OUTPOST_MANAGE | |
| `/city outpost list` | member | All outposts: name, chunks, distance, upkeep, coordinates |
| `/city outpost info <name>` | member | One outpost in detail, including total invested and current upkeep |
| `/city outpost cost` | member | **Cost of claiming the current chunk**, with the full formula broken out: base, distance multiplier, chunk factor, member divisor, final |
| `/city waystation create` | OUTPOST_MANAGE | Founds a waystation in the current resource world |
| `/city waystation claim` | OUTPOST_MANAGE | Adds the second chunk |
| `/city waystation tp <world>` | OUTPOST_TP | |
| `/city waystation delete <world>` | OUTPOST_MANAGE | |
| `/city waystation list` | member | |

`/city outpost cost` is the important addition. A formula with four terms is opaque unless the game shows its work, and a player about to spend two million coins deserves to see exactly why.

### 39.12 GUI

The Outposts menu (Part I 8.3, slot 32) is expanded.

| Slot | Item | Content |
|---|---|---|
| 4 | Filled Map | Header: outposts used vs available, total remote chunks, combined daily upkeep |
| 10 to 16 | Filled Map per outpost | Name, chunk count, distance, daily upkeep, total invested, unit count. Click to open detail. |
| 20 | Grass Block | **Claim current chunk**, with the full cost breakdown in the lore |
| 22 | Emerald | Found new outpost here, cost breakdown in lore, disabled with a reason if a placement rule fails |
| 24 | Ice | Waystations submenu |
| 30 | Ender Pearl | Teleport menu, one entry per outpost with its fee |
| 32 | Book | Cost explainer: the formula in plain language with the city's current values substituted |

Outpost detail submenu: rename, set warp, expand, unclaim current chunk, delete, view defense units, and a chunk layout diagram showing which of the four chunks are owned.

### 39.13 Messages

Added to the Part II Section 23 catalogue.

| Key | Audience | Channel | Template |
|---|---|---|---|
| `outpost.created` | SELF + CITY | Chat | `{p.land}<body>Founded outpost <subject>{name}</subject> at <subject>({x}, {z})</subject><body>, <subject>{dist}</subject> blocks out, for <neg>-{cost}</neg><body>. <dim>Upkeep: {upkeep}/day.</dim>` |
| `outpost.expanded` | SELF + CITY | Chat | `{p.land}<body>Outpost <subject>{name}</subject> expanded to <subject>{chunks}/4</subject><body> chunks for <neg>-{cost}</neg><body>.` |
| `outpost.cost_preview` | SELF | Chat | Multi-line: base, distance multiplier with the distance shown, chunk factor, member divisor, final |
| `outpost.merged_city` | CITY | Chat + sound | `{p.land}<body>Outpost <subject>{name}</subject> now borders the city and has been absorbed. <dim>Outpost slot freed: {used}/{max}.</dim>` |
| `outpost.merged_outpost` | CITY | Chat | `{p.land}<body>Outposts <subject>{a}</subject><body> and <subject>{b}</subject><body> merged. <dim>Slot freed.</dim>` |
| `outpost.error.merge_too_large` | SELF | Chat | `{p.error}<body>This claim would merge two outposts into <subject>{total}</subject><body> chunks, over the limit of 4.</body>` |
| `outpost.error.too_close_city` | SELF | Chat | `{p.error}<body>Outposts must be at least <subject>{min}</subject><body> chunks from your city. <dim>This is {actual}.</dim>` |
| `outpost.error.too_close_outpost` | SELF | Chat | `{p.error}<body>Too close to outpost <subject>{name}</subject><body>. <dim>Minimum {min} chunks, this is {actual}.</dim>` |
| `outpost.error.cap` | SELF | Chat | `{p.error}<body>Outpost limit reached: <subject>{used}/{max}</subject><body>. <dim>Upgrade Outpost Range.</dim>` |
| `outpost.released_delinquent` | CITY | Chat + sound | `{p.city}<neg>Outpost {name} released</neg><body> to unpaid upkeep. <dim>Furthest holdings go first.</dim>` |
| `waystation.created` | CITY | Chat | `{p.land}<body>Waystation established in <subject>{world}</subject><body> for <neg>-{cost}</neg><body>.` |

### 39.14 Edge cases

Continues from Part IV Section 33.9.

| # | Case | Required behaviour |
|---|---|---|
| 131 | City core is moved by an admin, changing every outpost's distance | Costs already paid are never recomputed. **Upkeep is recomputed** from the new core on the next cycle. The mayor is notified of the change with old and new figures. |
| 132 | An outpost is founded, then the city expands toward it and merges | Full merge per 39.7. Nothing refunded. This is a legitimate strategy and should not be penalised beyond the sunk cost. |
| 133 | Unclaiming an outpost chunk would split the outpost in two | Rejected, same contiguity logic as Part I 6.1 applied within the outpost |
| 134 | Outpost chunk claimed at the exact moment another city claims a bordering chunk | Unique index on `(world, chunk_x, chunk_z)` resolves it; the loser is refunded automatically |
| 135 | An outpost exists at 2 million blocks and the city goes delinquent | Furthest outposts release first, before any city chunk, per 39.5 |
| 136 | War declared while the city holds an outpost 800k blocks away | The war zone includes it. The pre-war snapshot must cover a discontiguous region set. Rollback processes each region independently. |
| 137 | Two wars are active and both zones include outposts near each other | Per Part I case 51, each war logs independently. Test explicitly with remote outposts. |
| 138 | Outpost teleport attempted during a war | Blocked for both sides, Part I 7.4 |
| 139 | Player teleports to an outpost that is unloaded and 1M blocks away | Chunks load on demand. Warmup is 8 seconds specifically to cover the load. If loading fails, the teleport is cancelled and the fee refunded. |
| 140 | A waystation is placed and then that resource world is reset by an admin | The 14-day notice from 32.5 must explicitly list every waystation and mining claim that will be destroyed, by owner. Full refund of the waystation cost is paid on reset. |
| 141 | Outpost chunk count pushes the city over a cost threshold mid-purchase | `n` is read once at the start of the transaction and held for its duration |
| 142 | Distance is computed across a world boundary | Cannot occur; outposts are `world` only and waystations measure from their own world's spawn |
| 143 | An outpost is founded, then the Outpost Range upgrade is downgraded by an admin | Existing outposts persist. New ones are blocked until back under the cap. Nothing is deleted. |
| 144 | A player claims an outpost chunk at the coordinate limit of the vanilla border | Allowed. Distance multiplier applies normally. At 29,999,984 blocks D(d) is roughly 44x, which is correct and intended. |

### 39.15 Configuration

Replaces the `outposts` block in Part I 16.2.

```yaml
outposts:
  base-max: 2
  max-per-upgrade-level: 1        # Outpost Range upgrade, to a maximum of 6
  max-chunks-per-outpost: 4

  cost:
    distance-constant: 0.25       # K in D(d) = 1 + K*sqrt(d/1000)
    distance-reference-blocks: 1000
    founding-surcharge: 1.50      # F(1)
    expansion-escalation: 0.25    # F(k>1) = 1 + 0.25*(k-1)
    counts-toward-city-chunk-index: true
    apply-member-divisor: true

  upkeep:
    base-per-chunk-per-day: 1200
    scales-with-distance: true
    release-order: FURTHEST_FIRST
    release-before-city-chunks: true

  teleport:
    base-cost: 100
    scales-with-distance: true
    warmup-seconds: 8
    cooldown-seconds: 180
    disabled-during-war: true

  placement:
    min-chunks-from-own-city: 32
    min-chunks-from-own-outposts: 24
    min-chunks-from-other-city: 8
    worlds: [world]

  defense:
    max-units-per-outpost: 4
    warden-allowed: false

  unclaim-refund-percent: 50

waystations:
  enabled: true
  worlds: [resource, resource_nether]
  max-per-world-per-city: 1
  max-chunks: 2
  chunk-1-cost: 60000
  chunk-2-cost: 90000
  distance-constant: 0.10         # W(d) = 1 + 0.10*sqrt(d/1000), from world spawn
  base-upkeep-per-chunk-per-day: 1500
  teleport-cost: 200
  defense-units-allowed: false
  war-target: false
```

---

## 40. Consequences of an unbounded world

Three systems in Parts I to IV assumed bounded distances and need adjusting. Each is small, and each would be a real bug if missed.

### 40.1 Contest voting

Part I 13.4 requires players to travel to contest entries to view and score them. An entry four hundred thousand blocks out would receive zero votes, and the city that built it would be structurally excluded from a core system.

**Fix:** submitting a contest entry generates a **temporary public warp** to the entry's viewing platform, available to all players via `/contest visit <n>` for the duration of the voting window only. The warp is deleted when the contest closes.

The warp lands on a viewing platform above the build, grants no build permission, and does not bypass the city's protection. It is a viewing gallery, not access.

### 40.2 Distance is no longer a proxy for anything

Several Part I and Part II rules used distance thresholds that assumed a compact map. Reviewed:

| Rule | Status |
|---|---|
| City minimum distance, 5 chunks (Part I 5.1) | Unchanged. Still correct. |
| Claim buffer, 5 chunks (Part I 6.3) | Unchanged. |
| Claim distance multiplier from core (Part I 6.2) | Unchanged. Applies to city chunks, which remain contiguous. |
| Siege Camp within 12 chunks of the enemy (Part III 29.5) | Unchanged. Correct, and now more meaningful. |
| `/rtp` minimum 200 blocks from another player | Unchanged. |
| War declaration | **No distance requirement of any kind.** Wars arise from rivalry between players, not from proximity. Two cities a million blocks apart may declare on each other freely. |

The last row is a deliberate design statement and should be read as one. Proximity-based aggression is a territorial-conquest-game assumption. On a server of this kind, conflict comes from who players know and dislike, not from who happens to border them.

### 40.3 Performance

| Concern | Handling |
|---|---|
| Region file sprawl | Incremental backups, 32.8 |
| Chunk loading for remote outpost teleports | 8-second warmup, on-demand load, refund on failure |
| War zones spanning enormous distances | The zone is a `LongOpenHashSet` of packed chunk keys and is distance-agnostic. Rollback processes each contiguous region group independently. |
| Defense units at remote outposts | Materialization (Part III 25.4) means they cost nothing when nobody is near |
| `/city map` for a city with distant outposts | The ASCII map shows local chunks only, with a legend line listing outposts and their bearing and distance |

---

## 41. Milestones

Replaces Part I milestone M10 and Part IV milestone 3a.

| M | Milestone | Deliverable | Depends on |
|---|---|---|---|
| 3a | **World setup** | Multi-world config, per-world claim rules, world whitelist enforcement in every protection listener. **No border management of any kind.** | M3 |
| 3b | **Travel** | `/spawn`, `/rtp` with the 15k radius and full safe-location validation, `/warp`, warmups, cooldowns, cancellation | 3a |
| 3c | **Mining claims** | Section 32.6, `/mine` tree, protection reusing M4 listeners, personal-balance upkeep | 3a, M4, M5 |
| 10 | **Outposts** | Full Section 39.1 to 39.9. Cost engine with unit tests against every value in the 39.4 tables. Multi-chunk claims, internal contiguity, merging, placement rules, distance-scaled upkeep and teleport, delinquency release order. | M3, M5, 3a |
| 10a | **Waystations** | Section 39.10, separate pool, resource world placement, own distance constant | M10 |
| 10b | **Outpost GUI and cost transparency** | Section 39.12, `/city outpost cost`, the formula explainer screen | M10, M8 |
| 19d | **Discontiguous war zones** | War zone computation, block logging, pre-war snapshot, and rollback verified against a war where an outpost is over 500,000 blocks from the city. Case 136. | M10, M18, M19 |
| 15a | **Contest visit warps** | Section 40.1, temporary warps generated on submission, deleted on close | M15 |

**M19d is the milestone most likely to be skipped and most likely to cause a serious bug.** Every war test will naturally be run on a compact test map where both cities are a few hundred blocks apart. The discontiguous case, where one region group sits half a million blocks from the others, exercises different code paths in zone computation, snapshotting, and rollback chunk loading. Build a test fixture that places an outpost at extreme distance and run the full war cycle against it before launch.

**M10's cost engine needs unit tests against the published tables**, not just against the formula. The tables in 39.4 are the specification. If the implementation produces different numbers, the implementation is wrong, and catching that in a test is far cheaper than catching it after a player has spent six million coins.
