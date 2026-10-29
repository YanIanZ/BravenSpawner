# Product Requirements Document — BravenSpawner 2.0

**Status:** Proposed · **Baseline:** v1.0.1 source (2026-10-30) · **Owner:** repository maintainers · **Deployment:** Paper/Folia Minecraft servers.

## 1. Product vision

BravenSpawner is a modular **virtual spawner and resource-production system** for SkyBlock, OneBlock and Survival networks. Players place, stack, upgrade and manage producers without requiring one physical entity per unit. Administrators retain economy balance, world protection and capacity controls. Operations must be safe under multithreaded Folia region ownership.

## 2. Primary outcomes

1. **Asset correctness first:** no lost/duplicated virtual stacks, loot, XP or money after ordinary operations and supported failure recovery.
2. **Performance:** virtual aggregation and work budgets prevent production cost from exploding with large stack counts.
3. **Usability:** predictable GUI, storage filters, clear inventory capacity, actionable feedback and accessible text/controls.
4. **Operability:** safe schema migration, backups, audit trails, diagnostics and rollback for server owners.
5. **Extension:** stable documented Java API and optional shop/custom-mob integrations.

### Non-goals

- No unrestricted cross-server shared-world mutation or distributed inventory writes in early releases.
- No guarantee of offline production until anti-abuse limits and catch-up semantics are implemented.
- No hard TPS/MSPT promise without an agreed test hardware, platform and workload.
- No direct NMS dependency in core or mandatory third-party economy plugin.

## 3. Personas and jobs-to-be-done

| Persona | Need | Key action |
| --- | --- | --- |
| Player | Understand and collect yield | Open spawner, view rates/capacity, withdraw/sell |
| Island member | Collaborate without theft | Permissioned trust roles, limited access |
| Server admin | Balance progression | Per-world caps, tier costs, drop tables, logging |
| Operator | Diagnose bottlenecks | /bspawner stats, profile snapshots, database health |
| Developer | Build add-ons | Events and immutable API interfaces |

## 4. Functional requirements

Each requirement is **PROPOSED** unless explicitly linked as existing behavior.

### FR-01 Identity, ownership and lifecycle — P0

- One persisted UUID per placed spawner; canonical world ID, block coordinate, owner, product, kind and virtual stack quantity.
- Authenticated item provenance and validated PDC metadata; no action trusts display name alone.
- Authorized place/stack/break/trust paths; configurable owner/trusted/administrator capabilities.
- On deletion, preserve or explicitly transfer stored items and XP; prevent restoration from overwriting unrelated blocks.
- A single active production schedule per registered spawner.

### FR-02 Virtual production — P1

- Two modes: `VIRTUAL_LOOT` (default recommended) and `PHYSICAL_MOB` (opt-in).
- Configurable products, drop probability, XP, tier interval, capacity, batch size and region budget.
- Production scales independently from stack count as far as practical; maintain statistically correct configured probabilities.
- Physics/mob caps, no despawn suppression by default without a valid use case; avoid unbounded entity retention.
- Deterministic storage overflow policy: pause, drop (region-owned), sell, or reject, chosen by admin.

### FR-03 Storage and transactions — P0/P1

- Read-only GUI views with explicit commands; atomic take, deposit/replace, sell reservation and hopper transfer.
- Paged storage with type filters, search, totals and bulk collect; never transfer what will not fit without a rollback or escrow path.
- Configurable slot/volume caps; explicit semantics for unlimited; no integer overflow.
- Dirty record tracking, asynchronous durable checkpoints and safe shutdown/backup.

### FR-03A Virtual storage and producer stacking — P1/P2

- Introduce virtual item-count vaults with `ItemKey → long quantity`, bounded key and total-item limits, exact quantity presentation, filtering, bulk withdraw and configurable overflow.
- Maintain separate **virtual producer units per placed block** and **virtual item stock per vault**. Physical `ItemStack.amount` never directly represents either quantity.
- Require authorized merge/split/place/break with durable issued-bundle entitlement ledger to prevent replay of copied PDC-bearing items.
- Support local vaults first, with Braven Core network vaults after atomic source/destination transfer, fencing and recovery rules are verified.
- Preserve custom-item metadata through strict canonical keys or a separately persisted non-fungible item path.
- Full design: [Virtual Storage](features/VIRTUAL_STORAGE.md) and [Virtual Stacking](features/VIRTUAL_STACKING.md).

### FR-04 Economy — P0/P1

- Existing Vault support is the baseline; validate transaction responses.
- Manual sell, autosell, per-material prices, multiplier policy, tax, per-player quotas, failed-payment recovery and ledger.
- True shop price adapters are **future work**; current hook only detects presence of named shop plugins.
- Payout IDs are idempotent, logged and reconcilable; no successful sale with unresolved economy response.

### FR-05 Upgrades and boosters — P1/P2

- Independent upgrade dimensions: speed, capacity, yield efficiency, automation and optional drop quality.
- Tier transitions charge exactly once; cap upgrade price/config; rollback if economy charge or save fails.
- Multipliers are bounded and follow an explicit stacking policy.
- Booster durations persist only if the release intentionally specifies restart behavior; never imply persistence by default.

### FR-06 Access, protection and compatibility — P0/P1

- Enforce allow/deny integration with installed WorldGuard, Lands or Towny via supported public APIs, with configurable fallback and explicit message on unavailable hooks.
- Use region-owned scheduler for block/world, entity scheduler for players/entities, async executor for database and global scheduler for pure orchestration.
- Supported versions must be listed per release, built and tested; do not equate declared Folia support with runtime validation.
- Optional Bedrock UX handled with compatible GUI/input semantics; no mandatory Geyser dependency.

### FR-07 Administrators and analytics — P2

- Admin inspect, per-player/world spawner reports, rate/uptime counters, overflow and corruption diagnostics.
- Audit history for stack, collect, upgrade, pay, break, restore and permission changes; data retention configurable.
- Diagnostic counters: active tasks, due cycles, physical mobs, queued writes, last-successful checkpoint, transaction failures.
- Privacy-conscious event logging and bounded log retention.

### FR-08 Integration and network — P2/P3

- Public API: query spawner, inspect snapshot, request authorized mutation, subscribe to cancellable events.
- Optional adapters: PlaceholderAPI, MythicMobs, supported economy/shop plugins.
- Redis **optional** as cache/notification bus; authoritative SQL and single-writer fencing needed before multi-server writes.
- Schema and API versioning support backward-compatible deployments.

## 5. Non-functional requirements

| Category | Release target / invariant |
| --- | --- |
| Integrity | Exact item/virtual-count conservation across supported operations; no accepted payout without provider confirmation |
| Durability | Configurable checkpoint interval, observable last successful flush, recoverable write failures |
| Performance | Budgeted region work, no per-virtual-stack physical entity loop, incremental database writes |
| Concurrency | World/entity access only from owning context; immutable snapshots at async boundaries |
| Security | Strict actor validation and input bounds, no untrusted deserialization failures interpreted as empty data |
| Compatibility | Version matrix for every release; no undocumented minimum JVM change |
| Operability | Backup before migrations, atomic schema version upgrades, admin troubleshooting commands |
| Extensibility | Stable public interfaces, namespaced identifiers and explicit deprecation policy |

## 6. Player experience

Proposed menu sections: **Overview** (type, owner, stack, tier, current state), **Storage** (filters, pages, withdraw), **Upgrades** (cost and next benefit), **Automation** (sell/overflow/hopper policies), **Members** (roles), and **Statistics** (actual rolling yield, not theoretical-only values). Every irreversible action shows a server-side price/count check and clear result.

## 7. Delivery principles and acceptance

- Every FR maps to technical spec, implementation PR, regression coverage and owner sign-off.
- No broad feature launch until `1.0.2` critical integrity fixes and `1.1.0` Folia tests pass.
- Human-readable permissions and config migrations are mandatory.
- Benchmark reports state server build, Java, CPU, RAM, worlds/chunks, spawner composition, player count, measured sample period, percentiles, and limitations.
- Owner tests are documented in [TESTER_OWNER.md](TESTER_OWNER.md); engineering gates in [ROADMAP.md](ROADMAP.md).
