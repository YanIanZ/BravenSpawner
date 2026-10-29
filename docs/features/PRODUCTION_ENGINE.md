# Adaptive Production Engine & Offline Production — CORE-01

**Status: PROPOSED, NOT IMPLEMENTED.** The runtime foundation for [Virtual Storage](VIRTUAL_STORAGE.md), [Virtual Stacking](VIRTUAL_STACKING.md) and [Upgrade System](UPGRADE_SYSTEM.md).

## 1. Supported modes

| Mode | Proposed behavior | Default |
| --- | --- | --- |
| `VIRTUAL_LOOT` | Compute configured drops + XP in bounded aggregate operations into exact-count vault; no per-unit mob entity | On |
| `PHYSICAL_MOB` | Spawn bounded real entities for combat; use explicit mob-death routing and quotas | Off |
| `HYBRID` | Split accountable virtual versus physical output; no double credit | Off, after dedicated tests |
| `OFFLINE_CATCHUP` | Account for eligible elapsed time up to configured limits | Off |

One placed spawner with 10,000 virtual units must not create 10,000 repeating tasks, entities, or equivalent physical `ItemStack` segments. **Production engine changes must not precede the v1.0.2 storage/anti-dupe/scheduler fixes.**

## 2. Production math

A versioned cycle record includes `producerId`, `groupRevision`, `epoch`, `cycleIndex`, `eligibleUnits`, tier, applicable multiplier snapshot and committed results. Each eligible cycle is credited **at most once**, including restart/reload and retry.

- **Deterministic:** `quantity = checkedMultiply(eligibleUnits, eligibleCycles, dropAmount)`.
- **Probabilistic:** for `n = units × cycles × amount` independent Bernoulli rolls with probability `p`, sample `Binomial(n,p)` via a bounded algorithm. Treat `p=0/1` deterministically; any approximate sampler needs explicit error tolerance and tests.
- **Modifiers:** apply configured bonuses through one shared cap evaluator, not unbounded multiplication of speed, yield, fortune, traits, synergy and overdrive.
- **XP:** bounded exact integer accounting, clear distinction between virtual-cycle XP and physical mob-death XP to prevent double credit.
- **Capacity:** excess yield follows explicit PAUSE/RETRY/SELL policy and durable accounting; do not silently discard virtual stock.

## 3. Paper/Folia scheduling

1. Register exactly **one region-owned scheduled lease per physical spawner group**, with generation token. Stale tasks abort after unregister/reschedule.
2. No world/chunk access from a foreign-region task; do not force-load chunks for periodic production.
3. Physical mob state/loot handled on entity owning scheduler; player inventory/notifications on player entity scheduler.
4. Pure calculations can run on a bounded async executor using immutable DTOs, then commit with revision validation and required region ownership.
5. Use chunk-based indexes, region work quotas and backpressure. Never scan nearby entities once for every virtual producer unit.
6. Track committed output, deferred cycles, rejected stock, physical mobs, scheduler leases, queue depth and work duration.

## 4. Offline production contract

```text
elapsed = clamp(nowEpochMillis - lastDurableCreditAt, 0, maxEligibleOfflineMillis)
cycles  = min(floor(elapsed / intervalMillis), maxCatchupCycles)
output  = boundedAggregate(cycles, virtualUnits, frozenPolicySnapshot)
```

Default `offline.enabled=false`. When enabled, validate owner policy, worlds, max offline seconds, max catch-up cycles, capacity, XP caps and whether boosters/fuel count while offline. Persist checkpoint and cycle epoch atomically with output to prevent repeat credit. Clock reversal, DB failure or invalid config yields **no catch-up**, not an invented reward.

Offline production must **never simulate missed ticks one by one** and never grant unbounded output after downtime. Explain any intentional skipped output to players/admins.

## 5. Error and physical mode behavior

| Situation | Safe behavior |
| --- | --- |
| Local vault full | Pause or bounded queue; no silent loss |
| Missing item/mob definition | Disable affected producer and preserve existing vault |
| Database durability uncertain | Fail closed/degrade read-only; report pending journal |
| Missing controller | Buffer locally, do not pretend transfer succeeded |
| Physical mob spawn rejected | Do not grant mob-kill drops; record bounded retry/no-credit policy |
| Overdrive fuel depleted | Fall back to base configured rate, no extra hidden cycle |
| Region unloaded | Pause; catch up only under opted-in bounded policy |

## 6. Proposed config (illustrative; not supported by v1.0.1)

```yaml
production-engine:
  mode: VIRTUAL_LOOT
  min-interval-ticks: 20
  max-output-per-cycle: 10000000
  max-physical-mobs-per-region: 40
  overflow: PAUSE_PRODUCTION
  offline:
    enabled: false
    max-seconds: 21600
    max-catchup-cycles: 360
    require-owner-online: false
```

## 7. Verification gates

- Compare 100, 1,000 and 10,000 virtual producer units under identical environment; record MSPT distributions, heap, entity counts, queue depth and actual committed output.
- Validate deterministic arithmetic at high values and statistical drop distribution under seeded probability fixtures.
- Restart, reload, unload/reload and pause: no double due-cycle credit.
- Verify disabled/enabled offline catch-up with downtime longer than the configured cap.
- Ensure `HYBRID` cannot grant virtual-cycle and physical-death loot for the same accounted output.
- Folia multi-region runtime verification with physical mobs and owner test sign-off.
