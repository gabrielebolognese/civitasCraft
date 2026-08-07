# Performance and scale

SPEC 17.7, audited case by case. SPEC 17 opens with *"This section is exhaustive by design.
Every case below must have an explicit test."* M20 did that sweep for SPEC 17.4 and M22 for
SPEC 15; this is the same treatment for 17.7.

Four of the six cases already had tests, written for other reasons. Two — the two with the
largest numbers in them — had none at the stated scale.

## The six cases

| # | SPEC 17.7 case | SPEC's claim | Covered by | Measured |
|---|---|---|---|---|
| 81 | 50,000 claims across 200 cities | Lookup is O(1) via a packed-long map | `Spec17ScaleTest.ClaimScale` | **46.6ns over 500 claims, 63.8ns over 50,000 — 1.37x for 100x the data** |
| 82 | 100 players breaking blocks in a war zone | 2,000 writes/sec sustained | `WarBlockLogBenchmarkTest` | **1,765,431/sec record path, 97,646/sec end to end** |
| 83 | A single war produces 2M logged changes | Rollback pages, never holds the log | `RollbackEngineTest` | Paged at 5,000 rows |
| 84 | Plugin disable during an active war | Flush the buffer synchronously, lose nothing | `WarBlockLoggerTest` | Asserted |
| 85 | Database connection lost mid-war | Bounded buffer, then refuse grief | `WarBlockLoggerTest` | Asserted at the 100k bound |
| 86 | 500 players open GUIs simultaneously | Construction cheap, cached per layout | `Spec17ScaleTest.MenuScale` | Same layout instance across 500 opens |

Figures are from one developer machine and will differ on yours. What the tests assert is
**shape**, not speed — see below.

## Why the assertions are ratios, not milliseconds

A wall-clock threshold on a build server is worthless. CI machines vary by more than the
margin being measured, and a benchmark that fails on a busy afternoon teaches people to rerun
it rather than read it.

So case 81 asserts that lookup cost does not grow with the number of claims (a linear scan
over 50,000 entries would be hundreds of times slower than over 500; the measured figure is
1.37x, which is cache behaviour rather than algorithmic), and case 86 asserts that the
five-hundredth menu gets the same layout instance as the first. Those are the properties SPEC
actually claims, and unlike a millisecond count they fail for exactly one reason.

The two throughput numbers in cases 81 and 82 are printed by their tests rather than only
asserted, because a number that exists inside a passing assertion tells an operator nothing.

## Headroom against SPEC's targets

SPEC 17.7 case 82 sets the only hard target: 2,000 block changes per second. The record path —
the half that runs on the server thread, once per block — sustains roughly **880x** that. The
end-to-end path, including the SQLite write, sustains roughly **48x**. SPEC 17.4 case 45's
40,000-block TNT chain reaction is recorded in 68ms.

That headroom is the reason SPEC 11.8.1's ring buffer and batching design is not the
bottleneck anyone will hit first.

## Two things this does not prove

- **MySQL is not covered.** Every storage test runs against a real SQLite file in a temp
  directory, which is deliberate — the point is that the SQL is correct, and a mock cannot
  prove a unique index rejects a duplicate. The MySQL path has been untested locally since M1
  and its throughput is unmeasured.
- **`ClaimRegistry`'s hot map is `Long2ObjectMaps.synchronize(...)`,** so every lookup takes a
  monitor. At the measured 46–64ns this is not currently the cost, and no test here contends
  it across threads at load. If a future profile shows contention with many players in claimed
  land, that wrapper is where to look first — the fix would be a read-mostly structure, since
  claims change rarely and are read on every block event.

## Measuring a live server

`/ca perf` reports the four figures SPEC 9.4.6 names: average claim lookup, block-log write
rate, GUI open time, and DB pool status. Claim lookup is sampled at one call in 64, because
SPEC 17.7 case 81 puts it on every block event and reading the clock costs more than the map
lookup it would measure. Set `performance.timings-enabled: false` in `config.yml` to leave the
path completely untouched, at the price of `/ca perf` reporting `-` for both timings.
