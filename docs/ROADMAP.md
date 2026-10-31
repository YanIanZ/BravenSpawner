# BravenSpawner Roadmap

Updated 2026-11-01. Latest published release: **1.3.0**. Working tree: **1.3.1-dev**.
Targets below are proposed milestones, not delivery promises. Source, tests and owner qualification determine completion.

## Shipped and in progress

| Version | Actual scope |
| --- | --- |
| 1.1.x–1.2.0 | Product definitions, YAML menus, virtual loot, stacking, wands, boosters, events, protection and persistence integrations |
| 1.2.1 | Fail-closed storage, exploit fixes, dirty SQL batches/schema v2, menu coverage, messages, confirmations, text formatting and shaded SQLite regression |
| 1.3.0 | BSC2 item codec v2, amount lore/head textures, 90 mob definitions, particles, per-action currencies and additional shop pricing adapters |
| 1.3.1-dev (unreleased) | Deferred GUI callbacks, entity-owned autosell with lifecycle checks and offline Folia retention, Gradle execution-time API cleanup, synchronized docs/specifications and regression tests |

The BSC2 codec carries logical amounts separately from item templates. **It is not the proposed `ItemKey → long` vault**: the current model still uses logical `ItemStack` entries with int counts.

## Proposed implementation order

| Milestone | Scope | Acceptance gate |
| --- | --- | --- |
| 1.3.x maintenance | Remaining audit findings and runtime qualification | Evidence-backed fixes; Paper/Folia player flows, live providers and databases, restart/failure scenarios |
| 1.4.0 Virtual Vault | [STOR-01](features/VIRTUAL_STORAGE.md): exact counts, checked arithmetic, explicit overflow, shared reservations | VSS-01/03/04/08/09; separate versioned vault migration, backup and quantity reconciliation |
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
