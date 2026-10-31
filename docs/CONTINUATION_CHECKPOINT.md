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
