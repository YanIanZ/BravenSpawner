# Economy, Balance, Progression Curves & Server Resource Budgets — ECON-01

> **Proposal — not scheduled; depends on 1.3.0/1.4.0.** Moved from PR #7; see the [feature catalogue](../FEATURE_CATALOG.md) and [roadmap](../../ROADMAP.md).

**Status: PROPOSED**. Values below are examples, not tuned or performance-tested defaults.

## 1. Why balance is architectural

Speed, Yield, Fortune, Genome, specialization, Mastery, Synergy, Boosters, Overdrive, offline catch-up and network processing can overlap. Without bounded policy they multiply into runaway virtual output, uncontrolled wealth and region/database pressure.

Balance must constrain both gameplay **and** technical cost.

## 2. Rate model

```text
RawCycles = elapsedTicks / max(minIntervalTicks, configuredTierInterval / speedMultiplier)
ExpectedOutput = eligibleUnits × RawCycles × expectedPerUnitDrop
EffectiveOutput = min(checkedRawOutput × boundedYieldFactors, perVaultLimit, perRegionBudget)
PhysicalMobCount = min(configuredPhysicalQuota, eligibleSpawnRequests)
```

Use checked integer quantities and explicit rounded probabilities. “Per-region budget” means defer/queue work and preserve/reconcile due cycles according to bounded catch-up policy, **not** silently fabricate outputs when over quota. Separate soft balancing caps from hard runtime caps.

## 3. Sample multiplier ceilings (illustrative)

| Source | Example cap | Notes |
| --- | ---: | --- |
| Speed | x3.0 interval reduction | Cannot cross minimum interval |
| Yield + Fortune combined | x4.0 expected drop | Separate deterministic and chance contributions |
| Trait + Specialization | x1.5 | Conditional compatibility |
| Booster + Overdrive | x2.0 | Finite duration/fuel |
| Network Synergy | x1.2 | Requires verifiable active recipe/conditions |
| **Global effective yield** | **x12.0** | Hard minimum of product and global cap |

Numbers deliberately do not assume all caps compose multiplicatively to their maximum simultaneously. A single combined-effect evaluator enforces the global cap **after** local modifiers. Config validation rejects NaN/infinity, negative rates and overflow.

## 4. Money sinks and income controls

- **Upgrade spending:** level-curve price and group-unit scaling, no split/merge discount exploit.
- **Catalysts/fuel:** consumed as inventory resources and entered into sink ledger.
- **Research/blueprints:** require earned objectives and material sinks, not cash-only gating.
- **Optional tax:** transparent net sale quote before confirming, with finite min/max and audit.
- **Autosell policy:** configurable per-material caps, throughput and market price adapter. Static prices require admin review for balance; **never claim live market prices from a detection-only shop hook**.
- **Prestige:** optional sink with clearly disclosed keep/reset rules, not irreversible stealth wipe.
- **Anti-abuse:** no reward for round-tripping item through sell/withdraw/network; mastered/contract progress derives from provenance of newly produced committed stock.

## 5. Large-stack cost policy

Default group upgrade cost: `unit_upgrade_cost(level) × virtual_units`, with explicit rounding and overflow bound. Alternative discounted curves (for casual servers) are opt-in, displayed and stress-tested. The economic model must ensure choosing to split vs merge identical producer units does not create extra net benefit when the same upgrades apply.

## 6. Storage / network economics

- Compression increases **capacity or eligible number of distinct keys**, never current item count or external sale price.
- Distribution upgrades increase token-bucket transfer rate, not free item supply.
- Network synergy consumes inputs or grants small capped conditional production effects; no hidden duplicates.
- Overflow is PAUSE by default; voiding stock requires owner/admin opt-in and clear material-loss warning.
- Offline production caps by time/units/output; no infinite catch-up after server downtime.

## 7. Config design and migration

Maintain an explicit `balance_schema_version`; changes to formulas/trait effects require migration notes and possibly rebalancing compensation for invested upgrade profiles. Config validate before activation and expose dry-run simulation of 100, 1k, 10k virtual units. Record settings fingerprint in performance comparisons.

## 8. Server performance budgets (not benchmark claims)

- Define max active physical entities/region, max logical stock-update batches/second, max pending transactions, max display entities/chunk, max controller nodes, max scheduled work/region.
- Bound expensive rarity sampling and do not spawn one physical mob or `ItemStack` per virtual unit.
- Backpressure policy: prioritize durable completion and player withdrawals; defer optional analytics, cosmetic updates and low-priority production first.
- Every throttling policy reports deferred counts and is visible to admin diagnostics.

## 9. Validation matrix

- Small group vs same units split into multiple groups show consistent rate and upgrade economics.
- All compatible upgrade combinations remain below technical and configured multipliers.
- Economy prices, offline windows and upgraded capacity cannot exceed bounds.
- Endgame bonuses do not bypass physical entity caps or region budgets.
- No measurable runaway growth in heap/queued work when stress-tested over stated observation windows.
