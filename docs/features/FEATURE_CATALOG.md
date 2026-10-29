# BravenSpawner Advanced — Complete Feature Catalogue

**Status: PROPOSED DESIGN, NOT IMPLEMENTED** · Draft: 2026-10-30 · Companion to [PRD](../PRD.md), [Roadmap](../ROADMAP.md) and [Audit](../AUDIT.md).

The goal is to turn BravenSpawner into an extensible, performance-conscious production ecosystem while preserving a small, safe base installation. **Feature catalogue describes intent, never evidence of a shipped feature.**

## 1. Pillars and release assignment

| ID | System | Responsibility | Target |
| --- | --- | --- | --- |
| FOUND-01 | Persistence, auth, transaction safety | P0 fixes, anti-dupe, safe payout, scheduler lifecycle | v1.0.2 |
| FOUND-02 | Folia runtime contract | Region/entity ownership, Java/Paper version matrix | v1.1 |
| CORE-01 | Adaptive Production Engine | Aggregated virtual output and optional budgeted real mobs | v1.2 |
| STOR-01 | [Virtual Storage](VIRTUAL_STORAGE.md) | Exact `ItemKey → long` count, vaults, capacity, withdraw, overflow | v1.2 MVP; v2 UX |
| STAC-01 | [Virtual Stacking](VIRTUAL_STACKING.md) | Virtual producer units, merge/split, bundle issuance and caps | v1.2 MVP; v2 UX |
| UPGR-01 | [Upgrade System](UPGRADE_SYSTEM.md) | Speed, Yield, Capacity, Fortune, XP, Automation, Stability and specialist upgrades | v2 |
| EVOL-01 | [Evolution / Genome](EVOLUTION.md) | Traits, fusion, specialization, evolution, prestige and rebirth | v2 gated |
| NET-01 | [Braven Core](BRAVEN_CORE.md) | Controller, virtual network vault, distribution and roles | v2 gated |
| AUTO-01 | [Automation & Processing](AUTOMATION.md) | Rule engine, filters, auto-sell, refining and safe recipes | v2 gated |
| PROG-01 | [Research & Progression](PROGRESSION.md) | Mastery, research, blueprints, achievements and contracts | v2 gated |
| INSI-01 | [Braven Insight](INSIGHT.md) | Production analytics, bottleneck detection and upgrade recommendations | v2 |
| UX-01 | [GUI and Accessibility](GUI_UX.md) | In-game menu/navigation, confirmation, Bedrock-friendly fallbacks | v2 |
| ECON-01 | [Economy and Balancing](BALANCING.md) | Prices, sinks, inflation caps, drop/fuel budgets, diminishing returns | v2 |
| API-01 | [Public API](API_SPEC.md) | Stable snapshots, mutation requests, events, provider contracts | v2 |

**Dependencies:** FOUND → CORE/STOR/STAC → UPGR/NET/AUTO → EVOL/PROG/INSI/UX. A user-facing feature is not production-ready unless its durable accounting, permission model, migrations and owner verification pass first.

## 2. Play modes and modularity

| Module group | Minimal survival server | SkyBlock / OneBlock | Large Folia network |
| --- | --- | --- | --- |
| Core virtual production + local vault | Enabled | Enabled | Enabled |
| Advanced upgrade profiles | Optional | Enabled | Enabled |
| Physical mob mode | Optional limited | Optional | Opt-in and capped |
| Braven Core network | Off | Optional | Optional with per-region semantics |
| Economy / auto-sell | Optional Vault | Common | Optional adapter |
| Research, evolution, prestige | Off | Optional progression | Optional progression |
| Offline production | Off by default | Capped/opt-in | Capped/opt-in |
| Redis / cross-server | Not needed | Not needed | Not before single-writer fencing and recovery tests |

Every optional feature has an independent enable flag and absence-safe dependency. No server should have to deploy Redis, MySQL, MythicMobs or a custom mob plugin just to use local virtual production.

## 3. Player journey

- **Early:** acquire spawner, inspect local vault, upgrade Speed/Capacity, safe withdraw, basic rules.
- **Mid:** specialize, craft modules, connect to Braven Core, unlock research and optional synergy.
- **Late:** build automated virtual production network, tune bottlenecks and optimize sale/processing strategies.
- **Endgame:** optional evolution, genome traits, controlled prestige and repeatable challenges (without trivial split/merge exploits).

## 4. Non-negotiable invariants

1. Virtual producer units, virtual item quantities, XP and money are separate currencies/quantities with exact defined conversion rules.
2. All state-changing actions are server-authorized, revisioned and recoverable; a GUI is never authoritative.
3. No component may silently discard stock, duplicate bundle entitlements, overwrite unknown custom item metadata or consume upgrade payments without a confirmed result.
4. Cross-region Folia operations are asynchronous messages/operations with explicit commit or compensation, not direct foreign-region world access.
5. Optional modules must have clear disable/rollback behavior and deterministic config validation.
6. Large logical quantities do not cause equally large loops, physical entities, holograms or Bukkit `ItemStack` allocations.
7. Claims such as “restart-safe”, “exactly once” or “Folia supported” require corresponding tests and documented limitations.

## 5. Implementation boundaries

**Shipped baseline (v1.0.1 audit only):** basic virtual spawner, storage list, upgrades by tier, boosters, Vault, corruption, hopper bridge, holograms and database adapters. These contain audit defects; do not conflate existence with correctness.

**Design only:** feature IDs above, multi-path upgrades, resource compression, controller, research, fusion, mastery, insight, reliable offline production, item-quantity ledger and true shop pricing. See the linked detailed documents for data model, UX, config examples, risk handling and acceptance criteria.

## 6. Release acceptance

Each feature PR must link the related doc requirement, tests and migration impact. Mark implementation and verified date at the document level only after code lands. [Owner test plan](../TESTER_OWNER.md) is the source of manual sign-off.
