# Automatic spawner conversion (1.6.0)

Every remaining vanilla spawner becomes a managed Braven spawner through three
paths that share one guard chain, one throttle and one product lookup, so they
can never disagree about what is convertible. The pre-1.6 place path
(`BlockListener.convertVanilla`, `convert-spawners`) is unchanged.

## What converts when

| Path | Trigger | Thread | What happens |
|---|---|---|---|
| Chunk load | `ChunkLoadEvent`, incl. freshly generated dungeon spawners | Chunk's region thread (dispatched when the event thread does not own it) | Each vanilla spawner block in the chunk becomes a managed spawner, stack 1 |
| Join scan | `PlayerJoinEvent`, once per join | Player's entity thread | Spawner items in the inventory + ender chest are swapped for Braven items of the same mob, preserving the item count |
| Admin scan | `/bspawner convert scan` | One region task per loaded chunk, coordinated from the global thread | Same as chunk load, across loaded chunks, with a receipt |

Join (not first movement) is the inventory trigger: the inventory is fully
loaded by join time, it fires exactly once per session, and a movement trigger
would need extra once-per-session bookkeeping for no benefit.

## Guards (evaluated in this order)

1. **Ready** — storage loaded. While not ready everything is skipped fail-closed;
   a chunk is seen again on its next load, a join scan waits for the next join.
   No unbounded work is ever queued.
2. **`convert.enabled`** — master switch (default true). Dry-run scans always work.
3. **World blacklist** — `blacklisted-worlds` (blocks only; items are
   world-independent, and placing a converted item still enforces world rules).
4. **Already managed** — a record for the block (or Braven PDC on the item) wins;
   re-scans never double-register and never consume budget.
5. **Product exists** — the mob must resolve to a product (`products.mob`, else
   the legacy `settings.mob` map). Product-less mobs stay vanilla and are never
   silently remapped. Plain spawner *items* carry no mob and stay untouched.
6. **Chunk cap** — `spawner-limit-in-chunk` in both modes, counting conversions
   already done in the same chunk pass.
7. **Throttle** — `convert.max-per-tick` (default 4, clamped 1..64) conversions
   per server tick, counted in 50 ms wall-clock windows. Taken last so a
   skipped spawner never consumes budget.

Item mob resolution: SilkSpawners NBT first (any PDC key in the `silkspawners`
namespace with a string entity name — no SilkSpawners dependency, every read
guarded, malformed values skipped), then the vanilla BlockState
(`BlockEntityTag`). SilkSpawners is not installed; only PDC-based items are
detected.

Each conversion is audit-logged as `CONVERT` (chunk path: system actor with
`auto-convert chunk-load` detail; join path: the player with
`join-scan <PRODUCT>` detail).

## Owner attribution

Conversions with a known placer keep that placer (the place path). Conversions
found in the world (chunk load, admin scan) have no trustworthy placer, so they
are owned by the nil system UUID (`00000000-0000-0000-0000-000000000000`, the
same value as the audit system actor). The owner is never null (storage columns
are `NOT NULL`) and never a random player. While `ownership.enabled` is true,
only administrators can use or break system-owned spawners; with ownership
disabled anyone can. System-owned spawners do not appear in any player's
`/bspawner list`; the admin total counts them.

## Commands

`/bspawner convert scan [--dry-run] [world]` — permission
`bravenspawner.admin`, console-usable.

- Scans **loaded chunks only**; nothing is ever force-loaded.
- A dry run counts without registering and needs no switch; a live run needs
  `convert.enabled`.
- The receipt reports worlds scanned (blacklisted/unreadable worlds listed),
  coverage as `chunks scanned/total` (a chunk that unloads mid-scan lowers
  coverage instead of vanishing), converted/convertible, already-managed, and
  skipped-by-reason counts. Throttled leftovers print a re-run hint.
- The receipt is sent exactly once, by the last chunk task or a 15 s timeout
  (region tasks for unloaded regions may never run).

## Config

```yaml
convert:
  enabled: true      # master switch for chunk-load, join-scan and live scans
  max-per-tick: 4    # server-wide conversions per tick (1..64)
  chunk-load: true   # convert vanilla spawners when a chunk loads
  join-scan: true    # swap spawner items on player join
```

Plus the unchanged top-level `convert-spawners` (place-time conversion) and
the `convert.*` keys in messages.yml (English, Bedrock-safe: no exotic glyphs,
colour roles only). All switches are read from the reloaded `Settings`
snapshot; `/bspawner reload` applies them without a restart.

## Rollout order

1. **Deploy** 1.6.0 with `convert.enabled: true` (default) or `false` if you
   want the dry run first — dry runs work either way.
2. **Dry-run scan**: `/bspawner convert scan --dry-run` (optionally per world).
   Review convertible vs skipped-by-reason counts and coverage.
3. **Enable**: set `convert.enabled: true` + `/bspawner reload` if it was off.
4. **Live scan** the worlds you care about, re-running until throttled counts
   reach zero; chunk loads and joins convert the rest over time.
5. **Monitor**: `CONVERT` lines in the audit log, `doctor` spawner counts, and
   tick health (the default budget of 4/tick is conservative; raise
   `max-per-tick` only if conversion visibly lags behind chunk loads).

## Rollback

Set `convert.enabled: false` (or `chunk-load` / `join-scan` individually) and
reload: no further conversions happen. **Conversions are one-way by design** —
already-converted spawners and items stay Braven; there is no automatic path
back to vanilla. To undo a conversion, break the spawner (or uncraft the item
path manually) and place a vanilla spawner.

## Limits

- Chunk-load conversion only sees loaded chunks; dormant vanilla spawners in
  never-loaded chunks convert when their chunk first loads or when a scan
  covers it while loaded.
- A live scan converts at most ~4 spawners per tick across the server; on a
  server with thousands of vanilla spawners the first sweep takes minutes and
  several re-runs. This is intentional (registration, scheduling and saving per
  spawner are not free).
- Only PDC-based SilkSpawners items are detected; pre-PDC SilkSpawners NBT and
  display-name guessing are deliberately unsupported.
- System-owned spawners are admin-managed; there is no `/bspawner` flow that
  transfers them to a player yet.
