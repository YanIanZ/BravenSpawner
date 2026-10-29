# BravenSpawner

Virtual spawners for **Paper** and **Folia** — stacking, chest-GUI storage, upgrades,
boosters, corruption and multi-backend persistence.

[![Build](https://github.com/YanIanZ/BravenSpawner/actions/workflows/build.yml/badge.svg)](https://github.com/YanIanZ/BravenSpawner/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Paper](https://img.shields.io/badge/Paper-1.20%E2%80%931.21-blue)](https://papermc.io)
[![Folia](https://img.shields.io/badge/Folia-supported-brightgreen)](https://papermc.io/software/folia)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net)

## What it does

A spawner block becomes a self-contained production unit. It spawns mobs or
generates items on a timer, banks the output in its own storage, and hands it back
through a paged chest GUI. Everything is persisted, restart-safe, and scheduled on
the thread that owns the chunk.

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
| **Economy** | Vault sell/autosell, purchase price, tier upgrades, booster multipliers |
| **Corruption** | random corruption countdown, repair radius, production halt |
| **Protection** | ownership, per-spawner trust, reconnect cooldown, lock timeout, teleport guard |
| **World rules** | blacklist, activation radius, silk-touch, explosion guard, durability |
| **Visuals** | TextDisplay holograms with live stored count (no external plugin) |
| **Persistence** | YAML, SQLite (WAL) or MySQL through one interface, transactional flush |
| **Scheduling** | Paper region API — same jar on Paper and Folia, zero reflection |

## Compatibility

| | |
|---|---|
| Server | Paper 1.20+ · Folia 1.20+ |
| Java | 21 |
| Optional | Vault (economy), PlaceholderAPI, WorldGuard, Lands, Towny (claimed-land checks) |

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

Three files, created on first run:

```
config.yml      production, stacking, economy, corruption, holograms, mobs, items
databases.yml   YAML | SQLITE | MYSQL, pool size, credentials
hooks.yml       currency and shop integration selection
```

Every switch in `config.yml` is documented inline. The full surface is large by
design — a server that wants only "spawn mobs, store loot" can leave everything
else at defaults.

## Architecture

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

Design rules the code holds to:

1. **No reflection.** Paper's region scheduler API is used directly.
2. **Fail closed.** Unloaded chunk, missing world, offline owner → skip, never partial.
3. **One owner per invariant.** Storage owns persistence, manager owns scheduling,
   the GUI owns only presentation.
4. **Cancelled by construction.** Inventory clicks are cancelled before dispatch, so
   a dupe path cannot be introduced by adding a button.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues and pull requests are welcome;
`main` is protected by CI (build + tests on every push).

## Security

See [SECURITY.md](SECURITY.md) for reporting vulnerabilities.

## License

[MIT](LICENSE) © 2026 YanIanZ

BravenSpawner is an independent implementation. It is not affiliated with,
endorsed by, or derived from the source of any commercial spawner plugin.
