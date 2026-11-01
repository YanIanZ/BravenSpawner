# Code review and maintenance — 2026-11-01

Baseline: published 1.3.0 / `e620f4e`. Result: local **1.3.1-dev**, not published. This is a targeted source review of GUI dispatch, build configuration, persistence representation, scheduler boundaries and documentation drift; it is not a complete security audit or runtime certification.

## Findings addressed

| ID | Severity | Root cause | Change and regression evidence |
| --- | --- | --- | --- |
| REV-01 / TH-03 | P2 | `MenuListener.onClick` and `SpawnerGui.onClick` invoke callbacks that open/close inventories inside the click transaction | Defer callbacks to the player's entity scheduler. Snapshot slot/click type; check online/window/session identity and resolve live authorization before mutation. Five regression cases failed before the fix and passed after it. Storage cases also cover close, disconnect, revoked access and removed spawner. |
| REV-02 | P2 | `processResources.filesMatching` accesses `project.version` during task execution | Capture a string during task configuration; clean build with configuration cache succeeds without the prior Gradle 10 deprecation. |
| REV-03 | P2 | README/audits/roadmap use incompatible snapshots; PR specifications assigned the unimplemented vault to already-released 1.3.0 | Current docs identify shipped 1.3.0 and unreleased maintenance; historical audits retain their dated evidence. Core specs/proposals are integrated locally with proposed 1.4.0/1.5.0 targets. |
| REV-04 | P2 | `instant_page_navigation` is parsed but never consumed | Remove the dead field/default and document unconditional next-tick inventory dispatch. Existing configuration entries are ignored. |
| REV-06 / TH-02 | P2 | `HopperLink.tick` dispatched a region task per stocked spawner every 8 ticks; `Ninja.refresh` visited every spawner per player and re-sent AIR to every distant one. | Hopper probes that find no hopper (or an unloaded chunk) back off 16 passes; `BlockPlaceEvent` for a hopper below wakes the spawner. Ninja uses `SpawnerManager.nearChunk` (per-world + per-chunk index) limited to send view distance + 1. `HopperLinkTest` (5), `NinjaTest` (2) and a `nearChunk` case in `ChunkLimitTest`. No live workload measurement. |
| REV-07 / TH-04 | P2 | Repeating production task was cancelled and reinstalled after every cycle to resample the interval. | One delayed-task chain per spawner (`TaskLease`); each link re-arms only while it is the live, current lease of a registered spawner. `ProductionChainTest` (8): all 8 failed against the previous implementation and pass now. Folia behaviour of delayed region tasks in unloading chunks remains a live-qualification item (OWN-13). |
| REV-08 / PM-01 | P2 | Sell, XP, upgrade and buy-stack relied only on general access. | `ActionPermissions` nodes, default `true`, checked in `perform` and again in each action method. Collect-all applies each half separately. `ActionPermissionsTest` (4) covers mapping, `plugin.yml` defaults and a denied/granted buy click; `FullCollectionTest` fixtures now grant the default nodes. |
| REV-10 | P2 | A record whose block vanished without a break event kept producing; opt-in restore overwrote blocks without an audit trail. | Production checks the block type on its owning region and pauses with one warning. Restore logs the replaced material and records `RESTORE`. Covered by `ProductionChainTest.missingBlockPausesProductionWithoutEndingTheChain`. |
| REV-05 / TH-01 | P1 | Global autosell performed online currency and shop work without owning the player thread; built-in XP/item currencies refused it. | Global pass now only dispatches online work. Quotes and settlement run on the owner entity scheduler, with one ticket per spawner and fresh identity/readiness/config checks. Shutdown invalidates queued tickets. Folia offline sales preserve stock; Paper offline-capable settlement remains. `AutosellSchedulingTest` covers real XP/item currency ownership plus scheduler/lifecycle/failure cases. |

## Remaining findings and limits

| ID | Priority | Current evidence | Required follow-up |
| --- | --- | --- | --- |
| EX-05 | Policy | Trusted players may sell the owner's storage and the payout goes to the clicker. | Unchanged by design. `bravenspawner.action.sell` can now withhold selling from a group. A pay-owner option would need offline-capable settlement and is not implemented. |
| Autosell / sell wand | Scope | Autosell and the sell wand are not gated by `bravenspawner.action.sell`; the owner can be offline and the wand is an explicitly issued item. | Revisit only with an owner-permission cache or provider support for offline permission checks. |
| REV-09 | Partly addressed (branch `feat/vault-exact-long`) | Storage is now one exact `long` `StockEntry` per identity; production decode uses `decodeStock` without clamping (`toIntAmount` remains only for the legacy `decodeItems` API). | Escrowed settlement added on `feat/vault-escrow` (VSS-05). `ItemKey` and overflow policies resolved as design decisions in STOR-01. Player-inventory withdrawals remain non-crash-proof. Downgrade to 1.3.x clamps identities above `Integer.MAX_VALUE` and refuses v3 records. |

Entity dispatch establishes the plugin-owned execution context, not a third-party API guarantee. All selected economy/shop plugins must support Folia and be qualified on the owner entity thread. There is no provider allowlist or claim of universal adapter thread safety. Offline Folia settlement remains deliberately unavailable.

No performance, complete reference parity, universal Folia safety, live provider compatibility or full database recovery claim is made. Existing mocked driver/provider tests cannot substitute for live qualification.

## Regression invariant and bug record

**GUI-01:** Every inventory-click-driven action executes after the event on the player's scheduler; it may mutate state only while its original session and top inventory remain current, the player is online, and live authorization permits the action. A rejected or retired task must not reopen the window or consume stock.

**B1 — 2026-11-01:** Synchronous click callbacks crossed the inventory transaction boundary; captured callbacks also require fresh session checks after deferral. Covered by `MenuSessionRoutingTest` and `StorageWithdrawalTest`, with source review of the fallback GUI and puzzle dispatch sharing the same guard. No live GUI test was run.

**AUTOSELL-01:** Online autosell resolves currency readiness, shop quotes and payout on the owner entity scheduler, never in the global dispatcher. One ticket per spawner; rejected/retired/failed callbacks release only their own ticket. Execution revalidates player session, owner, registered model, ready state and eligibility. Queued shutdown work cannot deposit or drain. Offline Folia stock is retained without querying providers.

**B2 — 2026-11-01:** Global autosell bypassed entity ownership; the built-in currencies correctly refused writes, while optional providers had no established global-thread contract. Initial regression run after fixture correction: 16/17 cases failed on old behavior. `AutosellSchedulingTest` now verifies dispatch, actual XP/item currency paths, offline Paper behavior, scheduler failure and lifecycle invalidation. Live Folia/provider qualification remains NOT RUN.

## Sources and validation

- [Paper InventoryClickEvent contract](https://jd.papermc.io/paper/1.21.1/org/bukkit/event/inventory/InventoryClickEvent.html): view transitions must be scheduled after the event.
- [EntityScheduler contract](https://jd.papermc.io/paper/1.21.1/io/papermc/paper/threadedregions/scheduler/EntityScheduler.html): execute follows entity ownership; rejected scheduling invokes neither callback.
- [Paper/Folia scheduler ownership](https://docs.papermc.io/paper/dev/folia-support/): entity work uses the entity scheduler; a plugin declaration alone does not prove Folia support.
- [Verification](VERIFICATION.md) contains commands, counts and artifact identity. [Roadmap](ROADMAP.md) records local reconciliation of PRs #7–#10; remote PRs were not changed.
