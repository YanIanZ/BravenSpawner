# BravenSpawner Upgrade Roadmap

**Status:** Proposed · **Prepared:** 2026-10-30 · **Baseline:** v1.0.1 / `main` at `90b39fce`.

These are **release gates, not delivery promises**. No feature below should be marked implemented unless merged, covered and owner-verified. Each milestone should have independent PRs with changelog and migration notes.

## Release plan

| Release | Theme | Dependency | Ship gate |
| --- | --- | --- | --- |
| **v1.0.2** | Integrity & security hotfix | Existing Java 21/Paper 1.21.1 line | P0 item, storage, payment and schedule paths closed; regression evidence |
| **v1.1.0** | Compatibility and lifecycle | v1.0.2 stable | Documented tested server/JDK matrix, correct Folia ownership, upgrade-safe migration |
| **v1.2.0** | Adaptive performance engine | v1.1.0 | Correct virtual aggregation, workload budgets and benchmark report |
| **v2.0.0** | Advanced gameplay/platform | v1.2.0 | GUI, upgrades, analytics, extension APIs and hooks pass functional/rollback tests |
| **v2.x optional** | Multi-server ownership | Explicit fencing design | Single-writer consistency + failover and split-brain tests |

## v1.0.2 — P0 hotfix work packages

| Work package | Implementation scope | Verification |
| --- | --- | --- |
| HOTFIX-01 | Strict loading state, no destructive flush after load failure; add auto checkpoint and failure metrics | Restart, kill -9, DB unavailable, corrupt payload |
| HOTFIX-02 | Single schedule per spawner; safe register/disable/reschedule | Start/reload/unregister counters & yield conservation |
| HOTFIX-03 | Authorize place/stack/booster, validate PDC kind/product/count, apply per-chunk limits | Owner, trusted, non-owner, malformed item |
| HOTFIX-04 | Atomic storage reservation/withdrawal, safe GUI holder/session close, inventory drag guard | Rapid click/close/navigation with concurrent producers |
| HOTFIX-05 | Vault result handling and transaction state for manual/autosell | Deposit rejected, partial, exception, uncertain completion |
| HOTFIX-06 | Commands, config switches and documented behavior synchronized | Permission/command smoke suite |

**Exit gate:** All P0 findings in [AUDIT.md](AUDIT.md) have code fixes, regression tests, release notes and explicit owner pass. Do not accept a successful compile as the exit gate.

## v1.1.0 — Platform and architecture

- Split upgrades: Java/toolchain, Paper API, Gradle, Shadow and JDBC libraries must be validated independently. Dependabot PR #5 is not mergeable unchanged on Java 21.
- Produce support matrix for every targeted build (minimum version versus latest tested), including `1.21.x` baseline and selected `26.x` builds as appropriate. If Java 25 is required, document minimum Java **per artifact** and choose whether to maintain a legacy line.
- Replace `BukkitScheduler` use; migrate all world/entity calls into owning region/entity context. Add static checks and runtime warnings.
- Implement chunk-indexed lifecycle registry and immutable settings/config validation.
- SQL additive migrations with backup, async dirty flush and observable retry behavior.
- Update installation and `plugin.yml` version requirements after actual build tests.

**Exit gate:** Reproducible CI + owner runtime pass on each claimed Paper/Folia version, successful database upgrade and rollback.

## v1.2.0 — Performance

- Aggregated stochastic yield calculation with bounded precision/error.
- Region time budget, cap real entities, avoid per-stack entity-nearby scan.
- Spatial/chunk indexes; no full-world scans on every small interval.
- Overflow/catch-up policy and workload backpressure.
- Benchmark against v1.1.0 at 100 / 1,000 / 10,000 **virtual units**, report p50/p95/p99 MSPT, tick work, heap and GC without claiming unmeasured gains.

**Exit gate:** Item/XP probability checks and sustained workload benchmark; no regression in restart/rollback tests.

## Virtual storage + stacking dependency path

1. **v1.0.2:** fix existing metadata count-on-placement, stacking permissions, scheduler duplication, GUI handoffs and persistence safety before any format migration.
2. **v1.1.0:** establish stable revisioned aggregate, provenance/redeem ledger, testable region/entity/DB ownership, additive storage schema and rollback plan.
3. **v1.2.0:** introduce aggregation-friendly producer units and material-counter storage MVP for supported commodity outputs; production batching must not instantiate one physical item/entity per virtual unit.
4. **v2.0.0:** ship comprehensive virtual-vault GUI, storage/transfer/compression upgrade paths, safe custom-item support, split/merge UX and opt-in Braven Core vaults when network accounting passes recovery tests.

See [Virtual Storage](features/VIRTUAL_STORAGE.md) and [Virtual Stacking](features/VIRTUAL_STACKING.md) for detailed semantics and owner acceptance cases.

## v2.0.0 — Product upgrade

- Overview and storage UX (filters, paging, withdraw, status), advanced upgrade tree and automation policies.
- Real shop integration via feature-gated adapters; permissioned claims and MythicMobs adapters.
- Player and admin analytics (per-world counts, loot rates, cost, queue state).
- Public read-only API and cancellable controlled mutation requests.
- Optional offline production with quotas; disabled until accounting and anti-abuse tests pass.

**Exit gate:** API compatibility tests, full player flows, admin smoke, UX accessibility checks and manual owner acceptance.

## PR checklist (all releases)

- [ ] Linked audit/PRD IDs and clearly scoped objective
- [ ] Unit + integration regression, including failure injections
- [ ] Folia thread context verified where relevant
- [ ] Schema/config compatibility and backup procedure
- [ ] Security (authorization, input validation, item/economy conservation)
- [ ] Metrics & rollback notes
- [ ] Changelog, README and docs changed where applicable
- [ ] Owner test record and sign-off

## Explicit deferrals

Multi-server write sharing, Redis federation, online/offline economy reconciliation with unknown provider outcomes and aggressive entity virtualization must remain opt-in/design-only until consistency is proved.

## Detailed Advanced 2.0 workstreams (PROPOSED)

The complete [feature catalogue](features/FEATURE_CATALOG.md) binds each workstream to its spec. **No feature is marked implemented merely because the design is merged.**

| Module | Dependency | Release gate |
| --- | --- | --- |
| [Adaptive Production](features/PRODUCTION_ENGINE.md) | Task-lifecycle, Folia and stock correctness | Bounded aggregate work and no duplicate offline cycles |
| [Virtual Storage](features/VIRTUAL_STORAGE.md) + [Stacking](features/VIRTUAL_STACKING.md) | Ledger, safe migration, atomic operations | Exact item/unit conservation including crash/retry |
| [Upgrades](features/UPGRADE_SYSTEM.md) | Economy response validation and versioned profiles | Cost applied exactly once and split/merge remains value-neutral |
| [Evolution / Genome](features/EVOLUTION.md) | Trusted bundle/trait lineage + research | No replayable fusion or unexpected prestige wipe |
| [Braven Core](features/BRAVEN_CORE.md) | Local vaults + single-writer coordination | Idempotent network transfers across Folia regions |
| [Automation](features/AUTOMATION.md) | Transactional vault + confirmed sales | Bounded rules and no item loss under provider outage |
| [Progression](features/PROGRESSION.md) | Committed counters + entitlement IDs | No repeat contract rewards from split/remerge |
| [Insight](features/INSIGHT.md) | Accurate telemetry | Explainable estimates, privacy and bounded queries |
| [GUI](features/GUI_UX.md) / [Balance](features/BALANCING.md) / [API](features/API_SPEC.md) | Stable service boundaries | Safe sessions, bonus caps and versioned extensions |

Recommended sequence: v1.0.2 integrity fixes → v1.1 Folia/persistence → v1.2 core production and virtual commodity vault → v2 phased upgrades/controller/automation → evolution/research/insight and full UX. All values and target version names remain proposals until tested.
