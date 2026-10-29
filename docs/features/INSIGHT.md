# Braven Insight — Statistics and Recommendations — INSI-01

**Status: PROPOSED**. An optional rules-and-metrics analytical assistant **not a chatbot and not an external AI dependency**.

## 1. Goals

Give players explainable production analytics and useful optimization recommendations using **measured** production/withdrawal/sell rates. Help server owners detect runaway virtual output, overloaded networks and economic imbalance.

## 2. Required signals

| Metric | Scope | Data source | Retention |
| --- | --- | --- | --- |
| produced items by ItemKey | Spawner / Controller | Committed production events | Rolling aggregates |
| rate/hour & utilization | Spawner / Vault | Completed quantity + sampled time | 5m/1h/24h windows |
| storage fill and rejected output | Vault | Stock snapshot and capacity | Aggregated |
| sale value, actual income and fees | Player / Vault | Confirmed settlement receipts | Restricted ledger |
| processing input/output and fuel | Controller | Committed recipe and fuel ops | Aggregated |
| scheduled delay, region work, queue depth | Operator / world | Scheduler and diagnostic counters | Short metrics window |
| uncertainty / sample count | Every estimate | Observation window | With each recommendation |

Use bounded counters and time buckets; do not scan every stored item or all worlds on every UI open. Do not include private player activity in publicly accessible stats by default.

## 3. Recommendation engine (examples)

| Rule | Example message | Required caveat |
| --- | --- | --- |
| Time-to-full estimate | "At the last hour's net fill rate, vault may fill in ~45m." | No estimate if rate unstable or sample insufficient |
| Upgrade ROI | "Capacity may avoid overflow sooner than Speed." | Show price, current assumptions and evidence window |
| Bottleneck | "Export queue is saturated; Distribution upgrade may help." | Display queue lengths and cap |
| Autosell warning | "Most stocked items have no matching sale price." | Verify adapter and unpriced material count |
| Risk warning | "Overdrive fuel will run out soon." | Based on recorded consumption, no guaranteed future |
| Admin diagnostic | "Physical mob mode dominates region work." | Link measured sample, not theory |

Recommended compute is deterministic, bounded and explainable. External LLM or telemetry service must be *explicit opt-in* later, with privacy and cost documentation. No unsupervised actions, auto-purchases or auto-disposal from Insight itself.

## 4. Rate and ROI definitions

```text
NetFillRate = (deposited - withdrawn - sold - consumed) / observedHours
ETAtoFull  = remainingItemCapacity / NetFillRate       when NetFillRate > 0
GrossRate  = confirmedProduced / observedHours
PaybackHours = confirmedUpgradePrice / max(estimatedIncrementalNetIncomePerHour,epsilon)
```

Rate is an empirical estimate. Reject `observedHours <= 0`, negative/overflow values and sparse samples. Payback is displayed as an estimate and should account for operation caps, capacity loss/overflow, fuel and multiplier limits. No prediction if the data quality threshold fails.

## 5. UX mockup

```text
[ Braven Insight ]  Last 60m · Sample quality: Good
Produced: 142,000 items | Withdrawn: 30,000
Vault: 78% full | Network queue: 2
Best opportunity: add Capacity before Speed (estimated)
[ Why? ] [ Upgrade Preview ] [ Change window ] [ Back ]
```

"Why?" shows the source counters, filters, prices, model formula and confidence indicator. Recommendations are advisory only.

## 6. Security and performance

- All queries require current role/claim permission; other players see only allowed public totals.
- Rolling aggregates may be cached and invalidated on revision/time bucket changes; no full-table query per click.
- Async DB reads return DTOs; no Bukkit world/player access from async or global foreign-region context.
- Admin controls metrics retention and per-player visibility.
- Publish measured workload benchmarks for number of vaults/players and dashboard query latency.

## 7. Acceptance

- With no events or unstable rate, no false precise ETA or guaranteed ROI.
- GUI and /stats show consistent totals with authoritative committed ledger.
- Disabling Insight removes recurring metrics jobs and preserves production behavior.
- Folia dashboard for remote network reports snapshots without wrong-thread exceptions.
- Privacy checks prevent one player's private balances or vault contents leaking to non-owners.
