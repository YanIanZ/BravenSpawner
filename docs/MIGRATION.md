# Migration, Compatibility and Recovery Guide

**State:** Preparatory plan for future releases; **not** an executable migration guide for unreleased features.

## 1. Current deployment baseline

- Current source compiles for Java 21 against `paper-api:1.21.1-R0.1-SNAPSHOT`; this does **not** prove every 1.20+/Folia version works.
- Current storage choices: YAML, SQLite, MySQL in `databases.yml`. The database schema is created in `SqlStorage.init()`.
- Current bootstrap loads database asynchronously and may proceed with an empty list following a read failure. `SqlStorage.flushAll()` replaces the table contents. Do not test upgrade paths on your only copy of production data.

## 2. Pre-upgrade checklist

1. Stop gameplay writes; schedule a maintenance window and notify players.
2. Record plugin JAR hash, server build, Java version, `config.yml`, `databases.yml`, generated `hooks.yml`, plugin integrations and installed economy.
3. Stop the server cleanly. Verify the latest persisted data is consistent; **do not assume the in-memory records were saved after a crash**.
4. Back up all BravenSpawner files and the SQL database using engine-appropriate consistent backup. Include SQLite `.db` plus required WAL/shm state or use SQLite's backup API.
5. Restore the backup to a staging server. Measure owner/spawner/item/XP totals and check for malformed records.
6. Test the proposed JAR against identical data in staging before production.
7. Confirm that all dependent plugins and server variants support the new Java/API target.

## 3. Proposed schema transition

**Stage A:** Add schema version and new columns/tables without destroying old rows; preserve UUID and world/coordinates.

**Stage B:** Read old records with compatibility adapter; validate and quarantine records with unknown materials or corrupt inventories instead of quietly zeroing them.

**Stage C:** Run transactional migration and reconciliation on staging; record row totals, virtual stack totals, stored XP and material quantities before/after.

**Stage D:** Enable new dirty writes and journal only after post-migration validation; retain a read-only backup and document forward-only migration cutover.

If validation fails, remain offline/read-only, restore backup and investigate. Do not auto-restore blocks or overwrite unrelated world changes.

## 4. Java/server compatibility strategy

The Dependabot API upgrade from Paper 1.21.1 to `26.3.build.224-stable` cannot compile using the existing Java 21 toolchain (see [CI failure](https://github.com/YanIanZ/BravenSpawner/actions/runs/40949237419)). Before shipping v1.1.0, choose **one**:
- Publish a Java 25 + selected newer Paper/Folia artifact, with an explicitly supported build matrix.
- Maintain a separate compatible `1.21.x` line if legacy users are still supported.

Do not publish a single universal-compatibility claim without runtime evidence, and never silently raise minimum Java in a patch release.

## 5. Rollback and recovery

- Take application offline; do not mix old and new plugin versions with concurrent writes to the same database.
- Restore both database **and associated world snapshot** from consistent points where possible.
- Restore config and plugin version together; a newer schema may not be backward-readable without an explicit down-migration.
- Check UUID duplicates, block collisions, item totals, XP totals, player balances and payout journal status.
- Preserve pending or uncertain economy transactions for manual reconciliation; do not blindly retry payouts.
- Collect logs/DB status and annotate the failed release in the owner test report.

## 6. Multi-server note

MySQL shared across servers is **not** proof of multi-server correctness. Until explicit leasing/fencing and idempotent operations are designed, one authoritative writer must own each spawner. Redis, when introduced, is a cache/event bus, not the source of truth.

See [TECHNICAL_SPEC.md](TECHNICAL_SPEC.md) and [TESTER_OWNER.md](TESTER_OWNER.md).
