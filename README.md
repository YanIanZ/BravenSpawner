# BravenSpawner

> **Status (2026-11-01):** Latest release: **1.4.1** (1.4.0 plus fixes from live testing): exact-count virtual storage with crash-safe escrowed sales, SuperiorSkyblock2 and island-world (SourbyCraft AWF, AdvancedSlimePaper) support, per-action permissions and production/scan performance work. See the [changelog](CHANGELOG.md), [code review](docs/CODE_REVIEW.md), [verification](docs/VERIFICATION.md), [island integrations](docs/ISLAND_INTEGRATIONS.md) and [roadmap](docs/ROADMAP.md). 1.4.0 is verified by 305 automated tests and CI; live player, Folia, provider and database qualification is still pending ([owner tests](docs/TESTER_OWNER.md)). The stacking ledger and public API remain proposals.

Virtual spawners for **Paper** and **Folia** — stacking, chest-GUI storage, upgrades,
boosters, corruption and multi-backend persistence.

[![Build](https://github.com/YanIanZ/BravenSpawner/actions/workflows/build.yml/badge.svg)](https://github.com/YanIanZ/BravenSpawner/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Paper](https://img.shields.io/badge/Paper-1.21.1_API-blue)](https://papermc.io)
[![Folia](https://img.shields.io/badge/Folia-qualification_pending-yellow)](https://papermc.io/software/folia)
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
| **Economy** | Per-action currencies (Vault, PlayerPoints, CoinsEngine, XP levels/points, items), validated deposits with refunds, guarded stock settlement, and shop price adapters |
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
| Optional | Vault, PlayerPoints, CoinsEngine/ExcellentEconomy, PlaceholderAPI, Floodgate, WorldGuard; ShopGUI+, EconomyShopGUI, EssentialsX, ZShop, ExcellentShop, Nascraft, DonutWorth pricing |

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
| `/vspawner doctor` | `bravenspawner.admin` | storage, catalogue and currency health |
| `/vspawner economy` | `bravenspawner.admin` | currency per action, every detected currency, active shop |
| `/vspawner booster <player> <mult> [sec]` | `bravenspawner.admin` | grant a production multiplier |
| `/vspawner menu` | `bravenspawner.admin` | open the GUI for the targeted managed spawner |
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

## Economy

`economy.enabled` is the master switch. Each action picks its own currency in
`config.yml`:

```yaml
economy:
  enabled: true
  currencies:
    sell: VAULT              # payouts (manual sell, sell wand, autosell)
    buy-stack: PLAYERPOINTS  # buying extra stacks in the GUI
    upgrade: coinsengine:gems
    spawner-buy: VAULT       # reserved: no spawner shop flow exists yet
  format:
    VAULT: { symbol: "$" }
    PLAYERPOINTS: { suffix: " points", decimals: 0 }
  item-currency: { material: EMERALD, name: "", custom-model-data: 0 }
```

| Id | Provider | Notes |
|---|---|---|
| `VAULT` | any Vault economy | default; late-registering providers are picked up |
| `PLAYERPOINTS` | PlayerPoints | whole points, works for offline players |
| `coinsengine:<id>` | CoinsEngine 2.x or ExcellentEconomy | ExcellentEconomy needs the player online |
| `EXP_LEVELS`, `EXP_POINTS` | built in | online players only |
| `ITEM` | built in | items in the inventory; overflow is dropped at the player's feet |

Whole-unit currencies round payouts down and prices up. An unknown, missing or
disabled currency refuses the action with a message (logged once) — nothing is
ever free. `hooks.yml: currency` is the default for any action left unset.
Autosell quotes and pays online owners on their entity scheduler. Queued work is
rechecked for disconnects, spawner removal/ownership changes, disabled selling and
storage readiness. Folia keeps an offline owner's stock until they return; Paper
still pays offline-capable currencies while the owner is offline. Use Folia-compatible
provider plugins: entity dispatch does not establish third-party API thread safety.

SuperMobCoins is not supported: it has no public API. Messages can use
`%currency%` and `%currency_symbol%`; formatted amounts already carry the
configured symbol/suffix. Shop pricing is described in
[docs/SHOP_INTEGRATIONS.md](docs/SHOP_INTEGRATIONS.md).

## Colours & Unicode

Every name, lore line, menu title, message and hologram line accepts, freely mixed:

| Format | Example |
| --- | --- |
| Legacy codes | `&a`, `&l`, `&r` (and `§a`) |
| Hex | `&#ff8800`, `{#ff8800}`, `<#ff8800>`, `&x&f&f&8&8&0&0` |
| MiniMessage | `<red>`, `<bold>`, `<color:#ff8800>`, `<gradient:#ff0000:#0000ff>Text</gradient>`, `<rainbow>Text</rainbow>` |
| Unicode | raw UTF-8 (`✦ ❤ 🔥`) or escapes `\u2764`, `\U0001F525` |

A backslash makes the next character literal (`\<`, `\&`, `\{`, `\\`). Write backslash
forms in single-quoted or unquoted YAML values; double quotes apply YAML's own escapes. Text that
only uses legacy codes renders exactly as before; an unknown or broken tag is shown
as typed instead of failing. Item names and lore are no longer italic by default —
add `&o` or `<italic>` to get it back. Player names and other player-controlled
values are escaped before formatting, so they cannot inject colours or tags.

## Architecture and upgrade documentation

The [architecture guide](docs/ARCHITECTURE.md) distinguishes implemented code from the target design. The [documentation hub](docs/README.md) links the [source audit](docs/AUDIT.md), [PRD](docs/PRD.md), [technical specification](docs/TECHNICAL_SPEC.md), [roadmap](docs/ROADMAP.md), [migration guide](docs/MIGRATION.md), and [owner test plan](docs/TESTER_OWNER.md). Each document labels current implementation, historical evidence, and **proposed** work. The [feature specifications](docs/features/README.md) and [parked proposals](docs/features/proposals/README.md) are not shipped capabilities.

```
BravenSpawner       boot order: config → storage → manager → subsystems → load
manager/            one tick pass, per-spawner region schedule
storage/            YAML / SQLite / MySQL / Mongo, BSC2 item codec v2
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

See [CONTRIBUTING.md](CONTRIBUTING.md). CI builds and runs the automated suite, including a SQLite probe against the shaded JAR. See [verification](docs/VERIFICATION.md) for the current test count and runtime limits.

## Security

See [SECURITY.md](SECURITY.md) for reporting vulnerabilities.

## License

[MIT](LICENSE) © 2026 YanIanZ

BravenSpawner is an independent implementation. It is not affiliated with,
endorsed by, or derived from the source of any commercial spawner plugin.
