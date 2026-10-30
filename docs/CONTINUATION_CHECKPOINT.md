# Continuation checkpoint — 2026-10-30 (1.2.1)

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
