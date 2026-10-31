# BravenSpawner Advanced — Complete Feature Catalogue

**Status: PROPOSED DESIGN** · Draft: 2026-10-30, synchronized against shipped **1.3.0** on 2026-11-01 · Companion to [PRD](../PRD.md), [Roadmap](../ROADMAP.md), [Audit](../AUDIT.md) and [Gap audit](../GAP_AUDIT.md). Index: [features/README](README.md).

The goal is to turn BravenSpawner into an extensible, performance-conscious production ecosystem while preserving a small, safe base installation. **Feature catalogue describes intent, never evidence of a shipped feature.**

## 1. Pillars and release assignment

| ID | System | Responsibility | Target |
| --- | --- | --- | --- |
| FOUND-01 | Persistence, auth, transaction safety | P0 fixes, anti-dupe, safe payout, scheduler lifecycle | **Shipped** (1.1–1.2.1); owner live verification pending |
| FOUND-02 | Folia runtime contract | Region/entity ownership, Java/Paper version matrix | Code uses region/entity schedulers; live Folia qualification pending |
| CORE-01 | [Adaptive Production Engine](PRODUCTION_ENGINE.md) | Aggregated virtual output; optional budgeted real mobs, offline catch-up | Aggregation **shipped**; other modes later (after 1.5.0) |
| STOR-01 | [Virtual Storage](VIRTUAL_STORAGE.md) | Exact `ItemKey → long` count, vaults, capacity, withdraw, overflow | **1.4.0** local vault; network vault = proposal |
| STAC-01 | [Virtual Stacking](VIRTUAL_STACKING.md) | Virtual producer units, merge/split, bundle issuance and caps | **1.5.0** ledger; split/merge UX later |
| UX-01 | [GUI and Accessibility](GUI_UX.md) | In-game menu/navigation, confirmation, Bedrock-friendly fallbacks | Partly shipped in 1.2.1; **1.5.0** base |
| API-01 | [Public API](API_SPEC.md) | Stable snapshots, mutation requests, events, provider contracts | **1.5.0** read-only base; mutations later |
| UPGR-01 | [Upgrade System](proposals/UPGRADE_SYSTEM.md) | Speed, Yield, Capacity, Fortune, XP, Automation, Stability and specialist upgrades | Proposal — not scheduled |
| EVOL-01 | [Evolution / Genome](proposals/EVOLUTION.md) | Traits, fusion, specialization, evolution, prestige and rebirth | Proposal — not scheduled |
| NET-01 | [Braven Core](proposals/BRAVEN_CORE.md) | Controller, virtual network vault, distribution and roles | Proposal — not scheduled |
| AUTO-01 | [Automation & Processing](proposals/AUTOMATION.md) | Rule engine, filters, auto-sell, refining and safe recipes | Proposal — not scheduled |
| PROG-01 | [Research & Progression](proposals/PROGRESSION.md) | Mastery, research, blueprints, achievements and contracts | Proposal — not scheduled |
| INSI-01 | [Braven Insight](proposals/INSIGHT.md) | Production analytics, bottleneck detection and upgrade recommendations | Proposal — not scheduled |
| ECON-01 | [Economy and Balancing](proposals/BALANCING.md) | Prices, sinks, inflation caps, drop/fuel budgets, diminishing returns | Proposal — not scheduled |

Proposal documents live in [`proposals/`](proposals/) and are not on the release plan until 1.4.0 and 1.5.0 have shipped and been qualified on Paper and Folia.

**Dependencies:** FOUND → STOR (1.4.0) → STAC + UX/API base (1.5.0) → UPGR → NET/AUTO → EVOL/PROG/INSI. A user-facing feature is not production-ready unless its durable accounting, permission model, migrations and owner verification pass first.

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

**Shipped baseline (1.2.1):** virtual spawners with one region-owned task each and aggregated production (`ProductRoller`), per-identity `List<ItemStack>` storage with `int` counts, authorized stacking (`StackService`, PDC count without ledger), tier upgrades, PDC-tagged boosters, timed events, Vault economy with checked responses and reservation-based selling, shop price adapters, corruption, hopper bridge, holograms, YAML/SQLite/MySQL (schema v2)/MongoDB storage with dirty-only checkpoints and fail-closed init, YAML menus with confirmations, `messages.yml`. Open P2 items are tracked in [GAP_AUDIT](../GAP_AUDIT.md); live Paper/Folia qualification is still pending, so do not equate existence with verified correctness.

**Design only (not in 1.2.1):** feature IDs above, multi-path upgrades, resource compression, controller, research, fusion, mastery, insight, reliable offline production, item-quantity ledger and true shop pricing. See the linked detailed documents for data model, UX, config examples, risk handling and acceptance criteria.

## 6. Release acceptance

Each feature PR must link the related doc requirement, tests and migration impact. Mark implementation and verified date at the document level only after code lands. [Owner test plan](../TESTER_OWNER.md) is the source of manual sign-off.
