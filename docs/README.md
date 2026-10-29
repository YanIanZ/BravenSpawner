# BravenSpawner Documentation Hub

> **Status (2026-10-30):** Planning and source-audit documents, based on `main` commit [`90b39fce`](https://github.com/YanIanZ/BravenSpawner/commit/90b39fce6326e50b45f77121c65b3efc0ae89e7d). **This documentation does not implement the described fixes.** Production compatibility, concurrency safety and crash durability remain unverified until the relevant acceptance gates pass.

## Start here

| Document | Purpose | Intended reader |
| --- | --- | --- |
| [AUDIT.md](AUDIT.md) | Evidence-backed weaknesses, impact, priority, and fix criteria | Maintainers / security reviewers |
| [PRD.md](PRD.md) | Product goals, user stories, in/out-of-scope capabilities and release requirements | Product / developers |
| [TECHNICAL_SPEC.md](TECHNICAL_SPEC.md) | Target modules, scheduling ownership, persistence, atomic operations and APIs | Implementers |
| [ARCHITECTURE.md](ARCHITECTURE.md) | **Current** architecture versus **proposed** target; invariants and data flows | Implementers |
| [ROADMAP.md](ROADMAP.md) | Ordered releases, dependencies, work packages and definition of done | Maintainers |
| [MIGRATION.md](MIGRATION.md) | Backups, schema transitions, version support and rollback plan | Server administrators |
| [TESTER_OWNER.md](TESTER_OWNER.md) | Manual verification matrix, performance protocol and release sign-off | Owner / QA |

## Reading rules

- **CURRENT:** Confirmed from source inspection; not necessarily tested or safe.
- **DEFECT:** A concrete code path with a known failure mode or insufficient guard. Severity reflects impact if exercised.
- **PROPOSED:** Desired behavior; no implication it exists in the current JAR.
- **TARGET:** A release acceptance criterion, **not** a measured result.
- **VERIFIED:** Reserved for cases with recorded CI/manual evidence and version information.

This project currently has a successful build on `main`, but compiling is not the same as passing a test suite or being Folia-safe.

## Development policy

1. Stabilize `1.0.x` before invasive changes.
2. Prefer additive schema migrations; never silently destroy data on failed load or partial decode.
3. Do not merge features without evidence for authorization, item/economy integrity, region ownership and restart behavior.
4. Maintain one pull request per coherent work package, update corresponding docs and changelog, and publish migration notes for behavior changes.
5. Do not disclose exploitable security reproduction steps in public documentation. Use [private GitHub security advisories](../SECURITY.md) for confidential reports.

## Release sequence

`1.0.2` data/safety hotfix → `1.1.0` compatibility and scheduling → `1.2.0` performance engine → `2.0.0` product experience and integrations.

See [ROADMAP.md](ROADMAP.md) for exact gates.
