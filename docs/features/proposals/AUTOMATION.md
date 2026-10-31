# Automation, Refinement, Fuel & Overdrive — AUTO-01

> **Proposal — not scheduled; depends on 1.4.0/1.5.0.** Moved from PR #7; see the [feature catalogue](../FEATURE_CATALOG.md) and [roadmap](../../ROADMAP.md).

**Status: PROPOSED**. Automation is a rule engine over durable virtual quantities, **not** arbitrary server-side scripts or commands typed by players.

## 1. Rule system

Each vault/controller owns an ordered, versioned list of rules evaluated on production insert, threshold crossing or a bounded interval.

```text
WHEN material = ROTTEN_FLESH AND quantity >= 100000
THEN SELL amount = EXCESS_ABOVE(50000) IF economy is confirmed

WHEN material = BLAZE_ROD AND quantity >= 10000
THEN SEND_TO_CORE amount = 5000 IF network capacity exists

WHEN total_capacity >= 95%
THEN PAUSE_PRODUCTION and notify owner
```

DSL is **declarative, finite and whitelisted**. No arbitrary JavaScript, reflection, shell commands or unsafe PlaceholderAPI execution. Parse/validate on save, cap rules per controller and actions/tick, reject cycles and enforce owner permissions.

## 2. Built-in actions

| Action | Semantics |
| --- | --- |
| `KEEP` | Reserve a minimum quantity from sale/export |
| `PAUSE` | Suspend production under explicit predicate |
| `SELL` | Reserve, price and settle item stock through confirmed economy adapter |
| `EXPORT` | Send a bounded amount to authorized Core/hopper |
| `FILTER` | Route only approved canonical item keys |
| `PROCESS` | Apply versioned virtual transformation recipe |
| `ALERT` | Rate-limited owner notification |
| `VOID` | Admin-only explicit loss, disabled by default, logged |
| `PRIORITIZE` | Assign deterministic queue order; never starve other nodes indefinitely |

Every rule execution yields an `operationId`, audit record and status `PENDING/COMPLETE/RETRY/FAILED`. Rules must operate on immutable snapshots, then validate revision and permissions before committing.

## 3. Processing and refinement

A virtual recipe consumes explicit inputs and creates explicit outputs in one local transaction:

```text
Recipe SMELTING_EXAMPLE
Input: 3 IRON_ORE + 1 configured heat resource
Output: 3 IRON_INGOT
Capacity check: destination can accept full output
Rollback: no input removed on failed commit
```

Examples are illustrative; vanilla recipe equivalence is not guaranteed. All output counts, probability, fuel consumption, whitelist and XP are configured. Custom items require canonical item metadata support. The processor cannot implicitly create unlimited resources, bypass a max item cap, or ignore economy restrictions.

## 4. Fuel, heat and Overdrive

- Normal virtual production remains available without fuel unless the admin explicitly selects a fuel-dependent mode.
- **Overdrive** is a timed multiplier consuming actual tracked catalyst/fuel stock, with finite level/time limits and a global production cap.
- Optional heat and stability model: `heat = clamp(heat + heatPerCycle * effectiveLoad - cooling, 0, heatMax)`. Above warning threshold throttle/notify; at max **pause safely**, don't delete items/spawner.
- Stability/Recovery upgrades affect validated chance/cooldown only if risk modules are enabled. If disabled, those upgrade tracks are hidden or refunded according to migration policy.
- Corruption and overheating states are separate; repair cost/procedure defined by server config.
- Time sources and reload semantics are explicit. Avoid repeated grants after system clock jumps and avoid running giant catch-up loops.

## 5. Security, economy and scheduling

- Producers never directly call Vault inside a region-wide unbounded loop.
- Autosell must reserve exact stock, validate pricing and payout result, and reconcile ambiguous outcomes.
- Use finite non-negative prices/catalyst costs, hard action-rate limits and bounded queue memory.
- Global orchestration handles pure rule scheduling only; physical world/entity work uses region/entity owners.
- A rule referencing a missing material/recipe/provider is disabled with diagnostic status, not evaluated as a successful zero-cost operation.
- Alert deduplication and log retention keep high-frequency automation from spamming players or storage.
- Auto-delivery to external plugins is an opt-in adapter with handoff receipts, not an unverified fire-and-forget method.

## 6. User experience

```text
[ Automation — Braven Core ]
Rules enabled: 4 / 8 | Queue: 2 | Paused: NO
[ + New Rule ] [ Filters ] [ Auto Sell ] [ Auto Process ]
Rule 1: SELL Rotten Flesh over 50k   [Enabled] [Edit]
Rule 2: EXPORT Blaze Rod over 10k    [Enabled] [Edit]
Rule 3: PAUSE at 95% capacity        [Enabled] [Edit]
[ Test Rule against Snapshot ] [ Activity Log ] [ Back ]
```

Dry-run preview shows projected items/economy effects without mutation; confirm activation with authoritative revalidation.

## 7. Acceptance

- Full target/unknown shop -> no stock loss or false-paid status.
- Cyclic rules and unbounded volumes are rejected before installation.
- Rapid production events coalesce instead of launching unbounded tasks.
- Overdrive and specialization multipliers never bypass ceiling/tick budget.
- Crash/retry at every reserved operation state recovers conservatively; no material duplication.
- Disabled risk/fuel module cannot leave hidden mechanics active or make base spawners unusable.
