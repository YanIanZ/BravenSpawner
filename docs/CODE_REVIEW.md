# Code review and maintenance — 2026-11-01

Baseline: published 1.3.0 / `e620f4e`. Result: local **1.3.1-dev**, not published. This is a targeted source review of GUI dispatch, build configuration, persistence representation, scheduler boundaries and documentation drift; it is not a complete security audit or runtime certification.

## Findings addressed

| ID | Severity | Root cause | Change and regression evidence |
| --- | --- | --- | --- |
| REV-01 / TH-03 | P2 | `MenuListener.onClick` and `SpawnerGui.onClick` invoke callbacks that open/close inventories inside the click transaction | Defer callbacks to the player's entity scheduler. Snapshot slot/click type; check online/window/session identity and resolve live authorization before mutation. Five regression cases failed before the fix and passed after it. Storage cases also cover close, disconnect, revoked access and removed spawner. |
| REV-02 | P2 | `processResources.filesMatching` accesses `project.version` during task execution | Capture a string during task configuration; clean build with configuration cache succeeds without the prior Gradle 10 deprecation. |
| REV-03 | P2 | README/audits/roadmap use incompatible snapshots; PR specifications assigned the unimplemented vault to already-released 1.3.0 | Current docs identify shipped 1.3.0 and unreleased maintenance; historical audits retain their dated evidence. Core specs/proposals are integrated locally with proposed 1.4.0/1.5.0 targets. |
| REV-04 | P2 | `instant_page_navigation` is parsed but never consumed | Remove the dead field/default and document unconditional next-tick inventory dispatch. Existing configuration entries are ignored. |

## Remaining findings and limits

| ID | Priority | Current evidence | Required follow-up |
| --- | --- | --- | --- |
| REV-05 / TH-01 | P1 | `SellService.startAutosell` schedules on the global region; `autosellPass → autosellSpawner → attemptSale` can reach currency deposits. Online-only currencies access player XP/inventory, while shop/provider thread requirements are not established. | Separate entity-owned online settlement and provider-specific execution contracts; test owner disconnect/region transfer and all selected currencies on Folia. GUI dispatch changes do not fix this path. |
| REV-06 / TH-02 | P2 | `HopperLink.tick` iterates all spawners; `Ninja.refresh` scans spawners for each player. | Measure workloads, add appropriate indexing/eligibility filters, preserve region ownership. |
| REV-07 / TH-04 | P2 | `SpawnerManager.install` creates a repeating task; successful production with a product calls `reschedule`. | Replace only with a lifecycle-tested delayed-task chain; protect against unregister/reload races and lost scheduling. |
| REV-08 / PM-01 | P2 | GUI extraction has a permission node; sell/buy/upgrade rely on general access rather than separate action permissions. | Define backwards-compatible permission defaults and test owner/trusted/admin policies. |
| REV-09 | Design boundary | `Spawner.storage` is `List<ItemStack>` with int logical counts. BSC2 wire amounts are long, but `ItemCodec.toIntAmount` clamps decoded amounts to int. | Future vault migration must reconcile quantities explicitly and must not reuse clamping as exact-long storage support. |

No performance, complete reference parity, universal Folia safety, live provider compatibility or full database recovery claim is made. Existing mocked driver/provider tests cannot substitute for live qualification.

## Regression invariant and bug record

**GUI-01:** Every inventory-click-driven action executes after the event on the player's scheduler; it may mutate state only while its original session and top inventory remain current, the player is online, and live authorization permits the action. A rejected or retired task must not reopen the window or consume stock.

**B1 — 2026-11-01:** Synchronous click callbacks crossed the inventory transaction boundary; captured callbacks also require fresh session checks after deferral. Covered by `MenuSessionRoutingTest` and `StorageWithdrawalTest`, with source review of the fallback GUI and puzzle dispatch sharing the same guard. No live GUI test was run.

## Sources and validation

- [Paper InventoryClickEvent contract](https://jd.papermc.io/paper/1.21.1/org/bukkit/event/inventory/InventoryClickEvent.html): view transitions must be scheduled after the event.
- [Paper/Folia scheduler ownership](https://docs.papermc.io/paper/dev/folia-support/): entity work uses the entity scheduler; a plugin declaration alone does not prove Folia support.
- [Verification](VERIFICATION.md) contains commands, counts and artifact identity. [Roadmap](ROADMAP.md) records local reconciliation of PRs #7–#10; remote PRs were not changed.
