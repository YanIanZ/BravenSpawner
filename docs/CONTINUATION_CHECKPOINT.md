# Release 1.4.0 — 2026-11-01

- PRs #15 to #18 reviewed (independent review, 10 findings, 9 fixed, 1 accepted) and merged; `main` equals the tested #18 tree.
- Released 1.4.0 from `release/1.4.0`: version bump, changelog section, docs status updates. 305 tests pass; CI green.
- Next: owner live qualification OWN-13, OWN-17 to OWN-26; 1.4.x fixes from that; 1.5.0 per roadmap.
- Leftover remote branches from older merged/closed PRs: chore/keep-agent-guidance-local, fix/1.3.1-maintenance-sync, fix/autosell-entity-ownership, docs/*.

# Vault core — 2026-11-01 (1.3.1-dev)

- PR #16 `feat/vault-exact-long`, stacked on PR #15. Exact `long` StockEntry storage, three capacity limits, identity take/restore, exact-count codec decode. 265 tests pass.
- Merge order: PR #15, then PR #16 (retarget to main after #15 merges).
- Open STOR-01 items: versioned ItemKey, durable reservations (VSS-05), overflow policies, GUI search/sort.
- Live qualification owned by user: OWN-13, OWN-17 to OWN-22.

# P2 follow-up — 2026-11-01 (1.3.1-dev)

- Branch `fix/p2-scans-scheduling-permissions` from `main` f32c41f. Addresses REV-06 (hopper back-off, Ninja view-distance index), REV-07 (delayed-task production chain), REV-08 (per-action nodes `bravenspawner.action.*`, default true) and REV-10 (missing-block pause, RESTORE audit). EX-10 doc fixed; PM-05 already fixed in source.
- 256 tests pass; CI-equivalent build passes. See VERIFICATION.md.
- Not done by agent: merging PR14 (dependabot) and closing superseded doc PRs #7-#10 were blocked by the permission classifier; owner to action.
- Live testing is owned by the user: OWN-13 and OWN-17 to OWN-20.
- Remaining: REV-09 exact-long vault (1.4.0 design work), EX-05 pay-owner policy, autosell/sell-wand not gated by action nodes.

## Prior autosell record

# Autosell continuation — 2026-11-01 (1.3.1-dev)

- Baseline main: 2cfdbb0, after merged PR11 modernization and PR12 local-only AGENTS correction.
- Implements REV-05 dispatch fix: global enumeration, entity-owned online pricing/payout, one outstanding ticket per spawner, fresh readiness/identity/eligibility/currency checks, cleanup on rejected/retired/failed tasks.
- BravenSpawner.onDisable stops the existing SellService without installing one. Queued work is invalidated.
- Offline Folia retains stock without provider calls; offline-capable Paper behavior remains. Existing autosell pricing/personal-boost semantics preserved.
- Clean build with configuration cache and warning-mode=fail:236tests pass,0fail/error/skip; artifact SHA in VERIFICATION.md.
- AGENTS.md remains ignored and local-only. User authorized PR creation and merge after checks.
- Next: live Paper/Folia disconnect/region-transfer/XP/item/provider tests; then P2 performance/lifecycle/permission findings. Third-party Folia API compatibility remains unqualified.

## Prior preparation record

# Continuation checkpoint — 2026-11-01 (1.3.1-dev, local)

Goal: synchronize code/docs/PR designs and modernize the maintenance baseline.

- Published baseline remains 1.3.0 at e620f4e. Working tree now identifies as 1.3.1-dev; no commit, push, merge, release or deployment performed.
- GUI menu/data/puzzle actions defer to the player scheduler, reject stale windows and recheck live authorization. Unused instant_page_navigation field/default removed.
- Gradle resource expansion no longer uses Task.project during execution; clean configuration-cache storage and reuse verified. CI updated to reject Gradle deprecations and preserve failed test reports.
- 213 tests passed, zero failures/errors/skips; see VERIFICATION for commands and artifact hash.
- Core specifications from docs/v2-core-specs and parked proposals from docs/v2-proposals incorporated locally. Roadmap assigns proposed vault1.4.0 and ledger/API1.5.0, replacing obsolete targets. PR7-10 remain unchanged remotely.
- Review: CODE_REVIEW.md. High-priority follow-up: autosell global scheduler can reach player-owned currencies/providers. Also pending: hopper/Ninja scans, task churn, per-action permissions, exact-long storage and live qualification.
- Local AGENTS.md initialized in prior turn and updated to reference synchronized docs.
- Next: fix autosell ownership with regression evidence; run disposable Paper/Folia player/provider qualification; integrate this local change and reconcile overlapping documentation PRs.

## Historical checkpoint — 2026-10-30 (1.3.0)


1.3.0 released: item codec v2 (amount >99 on Paper 26.x), head textures, storage amount lore,
90 mobs up to MC 26.2 (loot tables cached at /Volumes/Ext/DevCache/agent-skills/cache/bravenspawner/mc26.2),
multi-currency (Vault/PlayerPoints/CoinsEngine/EXP/ITEM), ZShop/ExcellentShop/Nascraft/DonutWorth pricing,
particles. 209 tests; live boot on Paper 1.21.1-133 and 26.2-135; codec probes verified legacy→v2.
Smoke servers: /Volumes/Ext/DevCache/agent-tmp/{bspawner-smoke,bs262}. Open: SuperMobCoins (no API),
per-product currency, sulfur_cube head texture, in-game/Folia/live-economy qualification.

## Previous (1.2.1)

Goal: ship 1.2.1 "shipped config works", then the next update per `docs/GAP_AUDIT.md` §4.4.

Done in 1.2.1: sqlite relocation guard (`ShadedSqliteTest`) and removal of the broken
committed jars; build modernisation (version catalog, dependency refresh, protobuf
relocated); hex/MiniMessage/unicode text; fail-closed storage; exploit fixes EX-01..09;
dirty-only batched SQL (schema v2); holograms/menus/placeholders/actions wired;
`messages.yml`; confirmations and sounds. See CHANGELOG 1.2.1.

Validation: `./gradlew clean build` — 148 tests, 0 failures. Live smoke on Paper
1.21.1-133 (Java 21): plugin enables with SQLite (Hikari connection established),
`/bspawner doctor` and `reload` work; with MySQL unreachable the plugin enters
storage-unavailable mode and `doctor` shows the cause. No player-interaction,
Folia, MySQL/Mongo-live or third-party-provider exercise yet.

Open: P2 items in GAP_AUDIT (TH-02/03/04, EX-05/10, PM-01, RestoreMissing, booster
sounds), `BukkitObjectOutputStream` deprecation (format change), PR #7 rebase/split.
Next: 1.3.0 virtual vault (exact `long` counts) after owner qualifies 1.2.1 in game.
