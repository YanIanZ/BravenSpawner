# Feature specifications

Design specs for the post-1.2.1 work. Each one opens with a **Current implementation (1.2.1)** section that names the code it replaces. A spec is not evidence that a feature has shipped; check [CHANGELOG](../../CHANGELOG.md) for that.

| Spec | ID | Target |
| --- | --- | --- |
| [Feature catalogue](FEATURE_CATALOG.md) | all | Overview, dependencies, invariants |
| [Virtual Storage](VIRTUAL_STORAGE.md) | STOR-01 | 1.3.0 local vault (`ItemKey → long`) |
| [Virtual Stacking](VIRTUAL_STACKING.md) | STAC-01 | 1.4.0 issued-bundle ledger |
| [GUI and accessibility](GUI_UX.md) | UX-01 | 1.4.0 base (partly shipped in 1.2.1) |
| [Public API](API_SPEC.md) | API-01 | 1.4.0 read-only base |
| [Production engine](PRODUCTION_ENGINE.md) | CORE-01 | Aggregation shipped; offline and physical modes later |

Proposals for later modules (upgrades, evolution, Braven Core, automation, progression, insight, balancing) live in [`proposals/`](proposals/) and are not scheduled.

Plan and ordering: [ROADMAP](../ROADMAP.md) and [GAP_AUDIT §4.4](../GAP_AUDIT.md).
