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
