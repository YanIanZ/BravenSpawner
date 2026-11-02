# BravenSpawner documentation

Updated 2026-11-01. Published **1.4.1**. Implementation, historical audit evidence and future designs are separated below.

| Document | Purpose |
| --- | --- |
| [Changelog](../CHANGELOG.md) | Actual release scope and unreleased changes |
| [Verification](VERIFICATION.md) | Current build/tests and runtime limitations |
| [Code review](CODE_REVIEW.md) | Confirmed findings, changes and remaining risks |
| [Continuation checkpoint](CONTINUATION_CHECKPOINT.md) | Handoff and prior smoke-test record |
| [Architecture](ARCHITECTURE.md) | Current source map and future invariants |
| [Roadmap](ROADMAP.md) | Proposed order and acceptance gates |
| [Feature specifications](features/README.md) | Vault, ledger, production, UX and public API designs |
| [Parked proposals](features/proposals/README.md) | Unscheduled advanced gameplay modules |
| [Migration](MIGRATION.md) | Current persistence format and staging/rollback requirements |
| [Owner testing](TESTER_OWNER.md) | Manual acceptance record; unexecuted cases remain NOT RUN |
| [Shop integrations](SHOP_INTEGRATIONS.md) | Current optional pricing adapters |

## Historical evidence

[AUDIT](AUDIT.md) describes v1.0.1; [GAP_AUDIT](GAP_AUDIT.md) describes v1.2.0; [FEATURE_PARITY](FEATURE_PARITY.md) records the 1.2.0-dev reference audit. Their original findings/test counts are not the current status. [PRD](PRD.md) and [TECHNICAL_SPEC](TECHNICAL_SPEC.md) retain proposed requirements.

## Reading rules

- **Implemented:** identifiable source path, not automatic runtime certification.
- **Verified:** named test/run and explicit limits in the verification record.
- **Historical:** valid only for the recorded source baseline.
- **Proposed:** future behavior, not available in the published JAR.

Use the wrapper for builds. Preserve fail-closed persistence, session/authorization checks and stock/payment conservation. Record schema changes, migration and owner runtime evidence before release.
