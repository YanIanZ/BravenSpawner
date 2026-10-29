# Source Audit — 2026-10-30

**Repository:** `YanIanZ/BravenSpawner` · **Branch:** `main` · **Examined commit:** `90b39fce6326e50b45f77121c65b3efc0ae89e7d` · **Code version:** `1.0.1`.

## Scope, severity, and evidence

Static review of bootstrap, manager, spawner model, block/entity listeners, GUI, locking, boosters, corruption, holograms, hoppers, sell/economy, storage codecs, YAML/SQL storage, build files and documentation. No runtime benchmark, exploit reproduction, automated regression suite or full compatibility matrix was executed. Treat findings as **source-derived** until independently reproduced and fixed. Existing CI shows build success on `main`; this is not a security or runtime pass.

- **P0 blocker:** Possible loss/duplication of assets, unauthorized mutation, incorrect payouts or immediate platform incompatibility.
- **P1 high:** Scalability failure, thread ownership risks, inconsistent lifecycle or unrecoverable error handling.
- **P2 medium:** Incorrect user-visible behavior, incomplete feature surface or documentation mismatch.

## Findings and fix/acceptance matrix

| ID | Severity | Current source evidence | Required remediation | Acceptance evidence |
| --- | --- | --- | --- | --- |
| BS-001 | P0 | `BravenSpawner.onDisable` and `reloadAll` flush storage; no installed periodic durable flush in bootstrap. `YamlStorage.upsert/delete` are no-ops. | Dirty-state queue, periodic async durable writes, bounded shutdown drain, recovery policy. | State survives forced termination within stated durability window; data isn't cleared after failed load. |
| BS-002 | P0 | `SpawnerManager.register` schedules; `startAll` schedules again, replacing map handle without cancelling the previous task. | Exactly one cancellable task per spawner; atomic schedule replacement; lifecycle tests. | One production cycle per due interval after boot/reload/reschedule. |
| BS-003 | P0 | `BlockListener.stack` does not verify ownership/trust or item kind, and does not enforce per-chunk cap during merge. | Central authorized stacking service with ownership, product+kind, cap and protected-region checks. | Unauthorized/invalid cross-kind or over-cap actions leave inventory and model unchanged. |
| BS-004 | P0 | `createSpawnerItem` writes virtual count in PDC but `onPlace` creates model count 1; merging reads physical `ItemStack.amount`, not virtual count. | Define a canonical representation and lossless place/merge/break transfer. | All stack counts round-trip exactly through commands, place, merge, break and restart. |
| BS-005 | P0 | `Economy.deposit` ignores `EconomyResponse`; `SellService.pay` assumes no exception means successful transfer. | Verify provider result and adopt a reconciled payout journal/state machine. | Rejected/partial payouts do not consume stock; retry does not pay twice. |
| BS-006 | P0 | `Boosters.start` uses `Bukkit.getScheduler().runTaskTimer` while plugin declares `folia-supported: true`. | Replace legacy scheduling and audit all Bukkit world/entity access against thread ownership. | Folia runtime verification across regions, no wrong-thread exceptions. |
| BS-007 | P1 | `SpawnerManager.tick` loops `stack` times; config default stack limit is 200,000; real-mob path performs proximity scan within each iteration. | Bounded aggregation and per-region work budgets, capped physical entity spawning. | Work grows with active groups, not linearly with virtual stack count. |
| BS-008 | P1 | `SqlStorage.flushAll` deletes entire table before re-inserting; errors are logged, not surfaced. `SqlStorage.loadAll` can return an empty/partial list after exceptions. | Incremental UPSERT, schema migration, strict failure propagation and backup/restore. | SQL failure preserves last valid durable state; no empty-state destructive flush. |
| BS-009 | P1 | `Storage.Codec.encodeItems` silently returns empty string and `decodeItems` silently returns empty list on error. | Fail-closed bounded deserialization; quarantine invalid rows and emit diagnostics. | Malformed inventory records cannot become valid empty inventories via save. |
| BS-010 | P1 | `HopperLink` global pass and `Ninja` global pass consult world/player state; `Holograms.removeAll` and `RestoreMissing.reconcile` may access foreign-region entities/blocks. | Dispatch region/entity operations through owning schedulers; use snapshot DTOs for global/async passes. | Cross-region mobility/unload/shutdown scenarios pass on Folia. |
| BS-011 | P1 | GUI `onClick` identifies sessions by player UUID rather than an inventory holder; `onClose` clears current sessions without verifying the closed view. Drag protection is not explicitly implemented. | Typed holder+session generation, close identity validation, drag guard, atomic inventory services. | Rapid page navigation, close/open, drag, shift-click and disconnect do not change stock incorrectly. |
| BS-012 | P1 | `SellService.clear` replaces a full storage snapshot after the quote while producers/hoppers may mutate storage; service-wide lock is not shared with model mutations. | Single atomic stock reservation/commit protocol; separate economy settlement. | Concurrent production, autosell and withdrawal conserve items and funds. |
| BS-013 | P1 | `BlockListener.onInteract` applies consumable boosters before authorization; invalid `Kind.valueOf` in `onPlace` is not guarded. | Validate actor, hand, PDC, product and input limits before any consumption. | Unauthorized or malformed actions fail closed without item loss or server exception. |
| BS-014 | P2 | `plugin.yml` declares `bmodify` and `bhtoggle`, but `onEnable` only registers executor for `bspawner`; README documents other command names. | Wire all executors, permissions, tab completion and consistent aliases. | Help, plugin.yml and actual commands match. |
| BS-015 | P2 | `Settings.boosterSeconds()` returns hard-coded 600 despite configured duration; other config flags/hook descriptions are not full implementations. | Remove dead/inconsistent configuration; validate parsed values and document active semantics. | Config change takes effect as documented after reload/restart. |
| BS-016 | P2 | `Mending` transfers XP on break but other stored XP may be discarded; `restore-missing-spawners` intentionally modifies blocks. | Explicit XP preservation policy; admin-only restore mode with audit and region guards. | Break/recovery never silently discard owned value or overwrite unrelated blocks. |

## Confirmed build/compatibility detail

- `main` is compiled against `io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT`, Java 21 and Gradle 9.4.1 (current `build.gradle.kts` and wrapper).
- Dependabot's runtime dependency upgrade [PR #5](https://github.com/YanIanZ/BravenSpawner/pull/5) failed in [workflow run 40949237419](https://github.com/YanIanZ/BravenSpawner/actions/runs/40949237419) because `paper-api:26.3.build.224-stable` needs JVM 25+ while the build ran on JVM 21.
- Do **not** merge that group update unchanged. Separate API/JDK migration from database-driver/shadow updates and verify the support matrix.

## Immediate repair order

1. **Contain data loss:** suspend/disable unsafe automated transactions if needed, back up persisted state, preserve failures and add periodic durable persistence.
2. **Correct invariants:** one scheduler per spawner, validated ownership and virtual count conservation on all inventory entry points.
3. **Harden money and UI:** payout validation, stock reservation, inventory-holder session identity, input checks.
4. **Make Folia truthful:** audit scheduling, stop cross-region world accesses and validate on actual server builds.
5. **Optimize safely:** aggregation, chunk indexes, pooled/dirty writes, metrics and overload behavior.
6. **Close user-facing gaps:** command registration, config accuracy, documentation and migration guidance.

## Verification and disclosure

Source findings are **not claims of tested exploitability**. Keep sensitive proof-of-concept details and server-specific vulnerability reports in private advisories per [SECURITY.md](../SECURITY.md). Record test evidence, commits and pass/fail in [TESTER_OWNER.md](TESTER_OWNER.md) before calling any item fixed.
