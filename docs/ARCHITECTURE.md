# Architecture

BravenSpawner is organised around four invariants. Everything else is detail.

## 1. No reflection

Paper 1.20+ ships the region scheduler API in `paper-api`. `util/Schedulers` uses
it directly:

```java
Bukkit.getRegionScheduler().execute(plugin, location, task);
entity.getScheduler().execute(plugin, task, null, 1L);
Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task, ticks);
Bukkit.getAsyncScheduler().runNow(plugin, task);
```

On Paper these run on the main thread; on Folia they run on the region that owns
the target. One jar, two platforms, zero string lookups. A reflective call would
compile but fail at runtime on someone else's server — that is the worst failure
mode a plugin can have, so the rule is absolute.

## 2. Fail closed

Every tick begins with the same guards:

```
world exists → chunk loaded → not blacklisted → owner online (if required)
```

Failing any guard skips that spawner for that cycle. There is no partial spawn,
no half-written record, no "best effort" that leaves state inconsistent. The same
applies to storage: `SqlStorage.flushAll` runs inside one transaction and rolls
back on any error.

## 3. One owner per invariant

| Invariant | Owner | Never touched by |
|---|---|---|
| persistence | `storage/` | manager, gui |
| scheduling | `manager/` | gui, listeners |
| presentation | `gui/` | storage, manager |
| trust and ownership | `model/Spawner` | listeners |

A listener may *ask* the manager to change state; it never writes storage and
never schedules a task. This is why adding a button cannot introduce a dupe bug.

## 4. Cancelled by construction

`SpawnerGui` cancels the click event before dispatching to its own handler, then
routes only three known slots. Drag, shift-click and number keys therefore cannot
move a stack out of a slot the model still owns. New buttons inherit the
protection for free.

## Boot order

```
config.yml + databases.yml + hooks.yml
        │
        ▼
Settings snapshot ──▶ Storage.init ──▶ SpawnerManager
        │                                   │
        ▼                                   ▼
 economy · boosters · holograms      async loadAll()
        │                                   │
        ▼                                   ▼
 listeners · command ◀──────────── register + reconcile + startAll
```

Spawners are registered only after the load returns, so a slow database can never
produce a duplicate tick. `RestoreMissing.reconcile` runs immediately after
registration: a record whose block vanished (world rollback, plugin migration) gets
its block back instead of being deleted.

## Persistence

One interface, three backends:

```
Storage
 ├── YamlStorage   single file, atomic replace via rename
 └── SqlStorage    Hikari pool; SQLite (WAL) or MySQL, same statements
```

Item stacks travel through `BukkitObjectOutputStream` and base64 inside the codec,
so a backend never needs to know what is being stored.

## Scheduling model

```
manager pass ──▶ per-spawner repeating task (region of its block)
                        │
                        ▼
                 tick(spawner)
                        │
        ┌───────────────┴───────────────┐
        ▼                               ▼
   produceMob                     produceItem
   region scheduler               direct store
```

Mob spawning is deferred into the region that owns the spawn location; item
production mutates the model directly because it never touches the world.

## What is deliberately absent

- No economy abstraction beyond Vault. Adding a second currency means a new
  interface, not a reflection call.
- No packet-level rendering. Ninja invisibility uses `sendBlockChange`, which is
  the highest layer that works on both Paper and Folia without NMS.
