# Advanced Upgrade System — UPGR-01

**Status: PROPOSED**. No upgrade tree described here is shipped in v1.0.1.

## 1. Design goals

A multi-path, data-driven upgrade framework with independent upgrade levels, skill unlocks, costs, preview, confirmation and respec. It must handle a **group of N virtual producer units** without letting one cheap per-block upgrade accidentally boost millions of virtual units.

## 2. Upgrade catalogue

| Upgrade ID | Effect | Initial suggested max | Required unlock |
| --- | --- | ---: | --- |
| `speed` | Reduce interval, bounded minimum | 25 | Base |
| `capacity` | Increase total vault quantity cap | 20 | Base |
| `yield` | Increase expected output per production cycle | 20 | Base |
| `fortune` | Additional **bounded** bonus-drop trials; never rewrite all drop chances blindly | 15 | Research |
| `experience` | Increase virtual XP accumulation | 15 | Base |
| `economy` | Improve sell proceeds within server multiplier cap | 10 | Vault adapter |
| `automation` | Unlock specific rule slots/actions | 10 | Research |
| `stability` | Reduce corruption/overdrive penalties **only if those modules are enabled** | 10 | Base |
| `compression` | Increase virtual stock capacity or distinct-key allowance; **never multiply actual items** | Configurable | Vault |
| `distribution` | Raise network transfer quotas | Configurable | Braven Core |
| `fuel_efficiency` | Lower consumable cost with floor | Configurable | Overdrive |
| `recovery` | Decrease optional downtime with hard minimum | Configurable | Overdrive |
| `refinement` | Unlock recipes/processing quotas | Configurable | Processing |
| `offline_efficiency` | Increase bounded catch-up window/efficiency | Configurable | Offline module |
| `synergy` | Unlock specific controller recipes/boosts | Configurable | Network/research |
| `specialization` | Choose Industrial, Prospector or Automator path | Configurable | Research |

Maximum levels and coefficients above are *balance placeholders*, not default technical limits. A server may disable any track.

## 3. Scaling rules and worked calculations

Let `L` be current upgrade level, `N` the exact positive virtual producer units in a group, `b` base price and `g > 1` the level cost growth.

```text
NextLevelBaseCost(L) = round_money(b × g^L)
EffectiveUnits(N)    = N                         [default fair policy]
GroupCost(L,N)       = round_money(NextLevelBaseCost(L) × EffectiveUnits(N))
SpeedInterval(L)     = max(minTicks, baseTicks / (1 + speedRate × L))
YieldMultiplier(L)   = min(yieldCap, 1 + yieldRate × L)
TotalMultiplier      = min(globalMax, product(all enabled bounded bonuses))
```

An optional discounted economy curve, `EffectiveUnits(N)=N^alpha` with `0<alpha<1`, **must** be explicitly advertised as a large-stack discount; by default use linear unit-equivalent cost to eliminate split/remerge arbitrage. Monetary computation should use validated `BigDecimal` or integer minor units, not unchecked floating point; input finite/positive and max charge validated. Missing balance or failed withdrawal must not commit an upgrade.

Example (illustrative): `baseCost=250`, `growth=1.25`, `L=2`, `N=10` → unit price `390.625`, group price `3,906.25` after the configured currency rounding. The **real config** must state whether upgrade progress is per virtual unit, per identical unit-group or fixed controller-wide. Costs and upgrade inheritance on split must preserve invested value.

## 4. Upgrade compatibility with stacking

**Recommended MVP:** all producer units in a group share one `UpgradeProfile`. Merge only groups/bundles with identical kind/product, profile, trait state, specialization and compatible other modifiers. Splitting copies profile to each unit but **must not mint free entitlements, levels or refunds**. Group-level upgrades with fixed costs are separate “controller improvements” and must not be multiplied through splitting.

Profiles are immutable/versioned; each upgrade write changes `profile_revision`. Price quotes use the currently committed group size and profile revision. Reject stale GUI purchases.

## 5. Upgrade action state machine

```text
REQUESTED → VALIDATED → PRICE_QUOTED → PAYMENT_PENDING
    → PAYMENT_CONFIRMED → PROFILE_COMMITTED → COMPLETE
                                  ↘ RECONCILIATION_REQUIRED
REQUESTED/VALIDATED/PRICE_QUOTED → REJECTED (no payment)
```

Currency withdrawals through external Vault cannot be atomic with local DB. Use a persisted operation ID and compensated/held state for unknown outcomes; never report success from “no exception” alone. Persist upgraded level and payment reference together where possible and expose admin reconciliation.

**Respec:** disabled by default. When enabled, define refundable percentage, locked masteries/traits, existing module handling, and idempotent payout. Disallow during pending transfer/sale.

## 6. Upgrade menu proposal

```text
[ Blaze Spawner — Upgrade Centre ]
Stack 1,250   |   Tier: Diamond   |   Profile rev: 108
Speed        Lv 12/25  Next: -? ticks  Cost: ...
Capacity     Lv 08/20  Next: +? items  Cost: ...
Yield        Lv 05/20  Next: +? expected output
[ Preview effects ] [ Confirm purchase ] [ Back ]
```

All computed text must derive from the same server-side quote and show the **total group cost**, not only single-unit cost. If funds change or revision is stale, close/refresh the quote.

## 7. Proposed configuration shape

```yaml
advanced-upgrades:
  enabled: false
  max-total-production-multiplier: 20.0
  group-cost-policy: LINEAR_PER_UNIT
  speed:
    enabled: true
    max-level: 25
    base-price: 250.00
    price-growth: 1.25
    rate-per-level: 0.035
    minimum-interval-ticks: 20
  yield:
    enabled: true
    max-level: 20
    max-multiplier: 4.0
  compression:
    enabled: false
    unlock-research: VAULT_TECH_I
```

This is a **schema proposal**, not a config that current code understands.

## 8. Acceptance criteria

- Authorize upgrade, verify economy debit outcome and committed profile exactly once under rapid double clicks.
- Change stack units during pending upgrade -> stale quote rejected and exact group cost recomputed.
- Split/merge upgraded bundles -> units and profile privileges conserved, no free levels or refunds.
- Maximum level, finite/overflow values, invalid coefficients and empty levels fail closed.
- All effects obey hard multiplier/minimum-tick caps; region tick budget and offline production cannot bypass these.
- Upgrade menu supports text-safe representations and always shows current actual vs projected performance.
