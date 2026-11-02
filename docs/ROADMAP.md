# BravenSpawner Roadmap

Updated 2026-11-01. Latest published release: **1.4.1**.
Targets below are proposed milestones, not delivery promises. Source, tests and owner qualification determine completion.

## Shipped and in progress

| Version | Actual scope |
| --- | --- |
| 1.1.x–1.2.0 | Product definitions, YAML menus, virtual loot, stacking, wands, boosters, events, protection and persistence integrations |
| 1.2.1 | Fail-closed storage, exploit fixes, dirty SQL batches/schema v2, menu coverage, messages, confirmations, text formatting and shaded SQLite regression |
| 1.3.0 | BSC2 item codec v2, amount lore/head textures, 90 mob definitions, particles, per-action currencies and additional shop pricing adapters |
| 1.4.0 | Exact-count vault (STOR-01 local: `long` stock, capacity limits, escrowed crash-safe sales, `/bspawner escrow`), SuperiorSkyblock2 and island worlds (AWF, ASP, Aurora MultiProfile), per-action permissions, production delay chain, hopper/Ninja scan bounds, deferred GUI callbacks, entity-owned autosell |

1.4.0 ships the local STOR-01 vault ([status](features/VIRTUAL_STORAGE.md)): exact `long` quantities, total/distinct/per-item limits and escrowed sales. The GUI search/sort and withdraw picker move to 1.5.0; the network vault stays a proposal.

## Proposed implementation order

| Milestone | Scope | Acceptance gate |
| --- | --- | --- |
| 1.3.x maintenance | Remaining audit findings and runtime qualification | Evidence-backed fixes; Paper/Folia player flows, live providers and databases, restart/failure scenarios |
| 1.4.x maintenance | Live qualification (phase 1 on Paper 1.21.1 done in 1.4.1; remaining OWN-13, OWN-17..26) and any fixes it finds | Recorded runtime evidence on Paper, Folia/SourbyCraft, SSB2 and each storage backend |
| 1.5.0 stacking and API | [STAC-01](features/VIRTUAL_STACKING.md), [UX-01](features/GUI_UX.md), [API-01](features/API_SPEC.md) | Durable issuance/idempotency across backends, replay tests, API compile tests and per-action authorization |
| Later, unscheduled | [Upgrade, evolution, network, automation, progression, insight and balancing proposals](features/proposals/README.md) | Stable vault/ledger and measured runtime budgets |

Earlier documents assigned the vault to 1.3.0 and ledger to 1.4.0. Those assignments are superseded by this roadmap; they do not describe what 1.3.0 shipped.

## Open review and qualification work

- Recheck historical P2s against current code before implementation: hopper/Ninja scans, production task churn, trusted-sell payout policy, per-action permissions, restoration and booster feedback.
- Qualify all cross-region interactions and optional provider thread requirements. GUI and online autosell now use entity scheduling; offline Folia autosell retains stock. This does not establish full Folia or third-party API safety.
- Exercise player interaction on Paper and Folia; SQLite restart, live MySQL/Mongo, unavailable providers and transaction failure paths.
- Measure production at 100/1,000/10,000 virtual units before making throughput or latency claims.
- Offline catch-up, physical-mob mode and multi-server federation remain design-only.

## Documentation PR reconciliation

PR [#7](https://github.com/YanIanZ/BravenSpawner/pull/7) is the original combined design. This working tree incorporates and updates the split core specifications from [#8](https://github.com/YanIanZ/BravenSpawner/pull/8), parked proposals from [#9](https://github.com/YanIanZ/BravenSpawner/pull/9), and the roadmap intent of [#10](https://github.com/YanIanZ/BravenSpawner/pull/10). None of those PRs was merged or closed by this local synchronization. After this work is integrated, reconcile the older PRs rather than merging duplicate/outdated files.

## Release gates

- Scoped review, meaningful regression tests and reproducible build.
- Authorization, item/XP/money conservation and failure behavior verified.
- Storage/config migration and rollback documented.
- Runtime matrix and owner results recorded in [TESTER_OWNER.md](TESTER_OWNER.md).
- Changelog, verification, roadmap and artifact version agree.
