# Continuation checkpoint — 2026-10-30

Goal: recreate the VirtualSpawner 7.2.0-rc-12 feature surface in BravenSpawner using shipped-artifact REA and leanREA evidence.

Current workspace: `/Volumes/Ext/REA/BravemSpawner`, version `1.2.0-dev`. Earlier verified source has been integrated after checking a clean tracked tree at `761cee2c10729ca728b8c05bb03b84deef2d34c1`. The existing single-registration sell-wand fix remains intact. No commit or push was made.

Integration backup and manifest: `/Volumes/Ext/DevCache/agent-skills/cache/bravenspawner/integration-20261030/`. Historical isolated source and evidence remain under `artifacts/verified-20261030/`.

This pass adds active timed-booster persistence to the integrated continuation: player/spawner scope, typed effects, absolute deadlines, permanent effects and autosell zero multiplier. The separate local `active-boosters.properties` format does not depend on the selected spawner backend. Async checkpoints run every five seconds and shutdown saves before clearing memory. Repeated shutdown cannot replace the saved ledger with empty state. Checkpoint and grant mutations serialize against shutdown. Invalid/inaccessible ledgers disable booster activation and are preserved. Personal carried-item effects are rebuilt from inventory snapshots. A hard crash may lose mutations since the last checkpoint. No reference DB migration is claimed.

Validation: initial integrated suite 77 tests passed; final suite 82 tests passed, zero failures/errors/skips. `./gradlew clean build --console=plain` passed before the final reader-error hardening; `./gradlew build --console=plain` passed after that change and the fifth regression. JAR descriptor is `1.2.0-dev`; BoosterStore is packaged; no `me/serbob/` or `net/codava/` classes are bundled. A focused independent review found the filesystem access-error case, now corrected by attempting a direct read and treating only NoSuchFileException as missing.

No game server, original plugin, live database or third-party provider was launched. Unit/driver-boundary tests do not certify Paper/Folia deployment. See FEATURE_PARITY.md for remaining providers, public API/events, legacy versions, reference-schema migration and richer item metadata gaps. Next work should address one evidence-backed gap at a time and qualify the integrated stack on disposable servers.
