# BravenSpawner Roadmap

**Status:** Active · **Updated:** 2026-10-30 · **Shipped:** 1.2.1 (see [CHANGELOG](../CHANGELOG.md)) · **Source of the plan:** [GAP_AUDIT §4.4](GAP_AUDIT.md).

These are release gates, not delivery promises. Nothing is "done" until it is merged, covered by tests and owner-verified in [TESTER_OWNER.md](TESTER_OWNER.md).

## Shipped

| Release | Theme |
| --- | --- |
| 1.0.2 | Folia-safe booster sweep, shading fix |
| 1.1.0–1.1.4 | YAML menu engine, sell wand, per-product files, shop price adapters, audit log, platform report, `/bspawner doctor`, SQLite fixes |
| 1.2.0 | Menus and holograms compatible with VirtualSpawner 7.2.0 |
| 1.2.1 | Shipped config works: fail-closed storage, exploit fixes EX-01..09, SQL schema v2 + dirty-only checkpoints, every menu token/action wired, `messages.yml`, confirmations, sounds, hex/MiniMessage text |

The v1.0.1 P0/P1 findings in [AUDIT.md](AUDIT.md) are addressed in code by these releases. Live qualification is still open: only a Paper 1.21.1 smoke test has been run. Player interaction, Folia, live MySQL/Mongo and third-party providers have not been exercised.

## Plan

| Release | Theme | Gate |
| --- | --- | --- |
| **1.2.x** | Remaining P2s, no schema change | GAP_AUDIT P2 rows closed or explicitly deferred; tests per fix |
| **1.3.0** | Virtual Vault MVP — [STOR-01](features/VIRTUAL_STORAGE.md) | VSS-01/03/04/08/09; one-way migration with backup |
| **1.4.0** | Stacking ledger + UX/API base — [STAC-01](features/VIRTUAL_STACKING.md), [UX-01](features/GUI_UX.md), [API-01](features/API_SPEC.md) | Ledger in all four backends; replay test; API compile test |
| Later | Upgrades, then parked modules ([proposals](features/proposals/)) | After 1.4.0 and live Paper + Folia qualification |

### 1.2.x patch

- Threading/perf: TH-02 (hopper/Ninja O(N) passes), TH-03 (open inventories on next tick), TH-04 (`runDelayed` chain instead of reschedule).
- Policy and correctness: EX-05 (`pay-owner` option or documented trusted-sell policy), EX-10 (`canonicalCount` clamp/doc), `RestoreMissing` audit + unregister path, booster sounds.
- Any other open P2 row in [GAP_AUDIT](GAP_AUDIT.md) not listed here; re-check each against `main` before starting, since many were closed in 1.2.1.
- Plan the replacement for the deprecated `BukkitObjectOutputStream` item codec; the format change itself ships with 1.3.0.

### 1.3.0 Virtual Vault

- `VirtualVault` (`ItemKey → long`, checked arithmetic, `max_total`/`max_distinct`) behind the `Spawner` storage API.
- Codec v2 with one-way migration from `List<ItemStack>` and a backup.
- Key-based reservations shared by `SellService`, `HopperLink`, sell wand and GUI; bounded withdrawals per action; explicit overflow policy (no silent discard).

### 1.4.0 Stacking ledger and UX/API base

- Issued-bundle ledger (one table/collection per backend), idempotent op-ids for give/place/merge/break/split, migration policy for legacy PDC items.
- Per-action permission nodes (PM-01), owner-visible log polish, remaining `sounds.yml` cues.
- Read-only `SpawnerSnapshot` service, cancellable sell/stack events, `%bravenspawner_*%` PlaceholderAPI expansion.

## PR checklist (all releases)

- [ ] Linked audit/spec IDs and a scoped objective
- [ ] Unit + integration tests, including failure injection
- [ ] Folia thread context verified where relevant
- [ ] Schema/config compatibility and backup procedure
- [ ] Authorization, input validation, item/economy conservation
- [ ] Changelog and docs updated
- [ ] Owner test record

## Explicit deferrals

Multi-server write sharing, Redis federation, offline production and physical-mob mode stay design-only until consistency and budgets are proven.
