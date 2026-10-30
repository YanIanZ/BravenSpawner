# BravenSpawner

> **Development status (2026-10-30):** `1.2.0-dev` integrates the REA continuation and timed-booster persistence. See the current [feature parity inventory](docs/FEATURE_PARITY.md) for implemented paths and remaining gaps. Paper/Folia and third-party integrations still require live-server qualification; earlier audit/roadmap documents record historical findings.

Virtual spawners for **Paper** and **Folia** — stacking, chest-GUI storage, upgrades,
boosters, corruption and multi-backend persistence.

[![Build](https://github.com/YanIanZ/BravenSpawner/actions/workflows/build.yml/badge.svg)](https://github.com/YanIanZ/BravenSpawner/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Paper](https://img.shields.io/badge/Paper-1.20%E2%80%931.21-blue)](https://papermc.io)
[![Folia](https://img.shields.io/badge/Folia-supported-brightgreen)](https://papermc.io/software/folia)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net)

## What it does

A spawner block becomes a self-contained production unit. It produces virtual mob loot or
generates items on a timer, banks the output in its own storage, and hands it back
through a paged chest GUI. The current implementation keeps generated output in memory and stores it using the selected backend; periodic spawner checkpoints and a separate active-booster ledger are implemented. Full Folia thread-ownership behavior still needs live-server verification.

```
place spawner ──▶ tick (tier interval) ──▶ produce (stack × cycle)
      │                                          │
      │                                          ▼
      └─ right-click ──▶ GUI ──▶ withdraw ◀── stored loot
```

## Features

| Area | What you get |
|---|---|
| **Production** | mob spawners and item generators, per-tier interval, stack multiplier |
| **Storage** | per-spawner inventory with capacity tiers, unlimited mode, paged GUI |
| **Stacking** | shift-right-click to merge up to `stack-limit`, per-chunk cap |
| **Economy** | Vault sell/autosell and upgrades, validated deposits, guarded stock settlement, and typed shop price adapters |
| **Corruption** | random corruption countdown, repair radius, production halt |
| **Protection** | ownership/trust, reconnect cooldown, lock timeout, teleport guard, and WorldGuard access checks |
| **World rules** | blacklist, activation radius, silk-touch, explosion guard and durability settings |
| **Visuals** | TextDisplay holograms with live stored count (no external plugin) |
| **Persistence** | YAML, SQLite, MySQL or MongoDB (Braven schema); startup readiness gates and periodic checkpoints |
| **Scheduling** | Paper region/entity APIs for production and player/entity work; live Folia qualification pending |

## Compatibility

| | |
|---|---|
| Server | Built against Paper API 1.21.1; other versions/Folia require runtime validation |
| Java | 21 for current build; newer Paper API versions may require Java 25 |
| Optional | Vault, PlaceholderAPI, Floodgate, WorldGuard; EssentialsX, ShopGUI+ and EconomyShopGUI pricing |

## Install

```bash
# build
./gradlew build
# → build/libs/BravenSpawner.jar
```

Drop the jar in `plugins/`, start the server, edit `plugins/BravenSpawner/config.yml`,
then `/vspawner give ZOMBIE`.

## Commands

| Command | Permission | Purpose |
|---|---|---|
| `/vspawner give <mob\|item:ID> [player] [stack]` | `bravenspawner.admin` | hand out a spawner item |
| `/vspawner list` | `bravenspawner.admin` | your spawners, stacks and stored totals |
| `/vspawner reload` | `bravenspawner.admin` | reload config, rebuild schedules |
| `/vspawner booster <player> <mult> [sec]` | `bravenspawner.admin` | grant a production multiplier |
| `/vspawner menu` | `bravenspawner.admin` | open the GUI for your nearest spawner |
| `/vspawner corrupt\|repair` | `bravenspawner.admin` | drive the corruption system |
| `/vmodify <player>` | `bravenspawner.use` | toggle trust on the spawner you look at |
| `/shtoggle` | `bravenspawner.use` | toggle holograms on your spawners |

## Configuration

Core settings and definition files are created on first run:

```
config.yml      production, stacking, economy, corruption, holograms, mobs, items
databases.yml   YAML | SQLITE | MYSQL | MONGODB, connection settings
hooks.yml       currency and shop integration selection
mobs/, itemspawners/, gui/, boosters/, other/   product and feature definitions
active-boosters.properties   runtime timed-effect ledger (generated on save)
```

Every switch in `config.yml` is documented inline. The full surface is large by
design — a server that wants only "spawn mobs, store loot" can leave everything
else at defaults.

## Architecture and upgrade documentation

The [architecture guide](docs/ARCHITECTURE.md) distinguishes implemented code from the target design. The [documentation hub](docs/README.md) links the [source audit](docs/AUDIT.md), [PRD](docs/PRD.md), [technical specification](docs/TECHNICAL_SPEC.md), [roadmap](docs/ROADMAP.md), [migration guide](docs/MIGRATION.md), and [owner test plan](docs/TESTER_OWNER.md). These describe **proposed** work, not shipped features.

```
BravenSpawner       boot order: config → storage → manager → subsystems → load
manager/            one tick pass, per-spawner region schedule
storage/            interface + YAML / SQLite / MySQL, Codec for items
gui/                sessions, click-cancel by construction
listener/           block lifecycle, death routing, guards
mechanics/          ninja invisibility, hoppers, mending, restore-missing
corruption/         countdown scheduler
sell/               valuation, autosell, shop hooks
util/               Schedulers (Paper region API), Text/money formatter
```

Target design principles (runtime qualification pending):

1. **Thread ownership.** All world/entity operations must obey Paper/Folia scheduler rules.
2. **Fail closed.** Failed loads, invalid items and rejected transactions cannot mutate authoritative stock.
3. **Single-owner invariants.** Lifecycle, inventory and economy mutation must be coordinated atomically.
4. **GUI/session integrity.** Only an active verified inventory session may issue a mutation.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues and pull requests are welcome;
`main` has a CI build; no automated test sources were found at the audited commit.

## Security

See [SECURITY.md](SECURITY.md) for reporting vulnerabilities.

## License

[MIT](LICENSE) © 2026 YanIanZ

BravenSpawner is an independent implementation. It is not affiliated with,
endorsed by, or derived from the source of any commercial spawner plugin.
