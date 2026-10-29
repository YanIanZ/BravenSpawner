# BravemSpawner

A virtual spawner plugin for Paper 1.20+, written from scratch after reading
`VirtualSpawner-7.2.0-rc-12.jar`. Same surface — plugin name, commands,
permission nodes, YAML-driven mob definitions — with a much smaller footprint.

## Build

```bash
mvn clean package
# → target/BravemSpawner.jar
```

Java 17, Paper API 1.20.4 (`provided`), no shaded dependencies.

## Install

```
plugins/BravemSpawner.jar
plugins/BravemSpawner/config.yml
```

## Commands

| command | permission | what |
|---|---|---|
| `/vspawner give <mob> [player]` | `bravemspawner.admin` | hand out a spawner item |
| `/vspawner list` | `bravemspawner.admin` | your spawners and stored totals |
| `/vspawner reload` | `bravemspawner.admin` | reload config.yml |
| `/vmodify <player>` | `bravemspawner.use` | toggle trust on the spawner you look at |
| `/shtoggle` | `bravemspawner.use` | toggle holograms on your spawners |

Aliases: `/vspawner` → `/bravemspawner`, `/bs`.

## How it works

1. `/vspawner give ZOMBIE` → spawner item with a PDC tag.
2. Place it → the block stays in the world as the anchor; a `SpawnerData` row is
   created and registered.
3. Every `spawner.interval-ticks`, each spawner spawns up to
   `spawner.per-cycle` mobs within `spawner.radius`, capped by `spawner.max-alive`.
4. Mobs carry metadata naming their spawner. When a player kills one, the drops
   are moved into that spawner's storage instead of the ground.
5. Right-click the block → GUI. Slot 49 withdraws everything, slot 48 cycles the
   mob, slot 50 closes. Items are display-only; drag and shift-click are cancelled.

## Storage

`plugins/BravemSpawner/spawners.yml`, written on disable:

```yaml
spawners:
  <uuid>:
    owner: <uuid>
    world: world
    x: 0
    y: 64
    z: 0
    mob: ZOMBIE
    hologram: true
    trusted: []
    storage: []
```

Broken spawners drop their contents and their item — nothing is lost or
duplicated, because the row is removed before the block is cleared.

## Design notes

- **Fail closed.** A spawner whose world is unloaded, whose chunk is not loaded,
  or whose owner is offline (when `owner-online-only` is set) simply skips its
  tick. No exceptions, no partial spawns.
- **One task.** A single scheduler pass iterates every spawner; a failure inside
  one is logged and does not stop the others.
- **Capacity enforced at write time.** `SpawnerData.addStored` merges into
  existing stacks first and never exceeds `storage.capacity`.
- **GUI is read-only by construction.** The click handler cancels everything,
  then dispatches only slots 48/49/50.

## Differences from VirtualSpawner

BravemSpawner implements the core loop — place, tick, kill, store, withdraw,
trust — and stops there. VirtualSpawner's extra systems (multi-database storage,
Vault economy, 30+ protection-plugin hooks, boosters, item spawners, hologram
providers) are not reproduced; the softdepend list is kept so the plugin still
loads cleanly alongside them.
