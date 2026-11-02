# Custom-item drops

A product drop can be an item from a custom-item plugin instead of a vanilla
material. Write `provider:id` in the drop's `material` (or `item`) field in
`mobs/*.yml` or `itemspawners/*.yml`:

```yaml
drops:
  1:
    material: ROTTEN_FLESH
    chance: 100.0
  2:
    material: "mmoitems:SWORD:CUTLASS"
    amount: {min: 1, max: 1}
    chance: 2.5
    sell-price: 150.0
```

| Plugin | Prefix (aliases) | Id format | Example |
| --- | --- | --- | --- |
| MMOItems | `mmoitems` (`mmoitem`) | `TYPE:ID` | `mmoitems:SWORD:CUTLASS` |
| EcoItems | `ecoitems` (`ecoitem`) | item id | `ecoitems:enchanted_diamond` |
| ItemsAdder | `itemsadder` (`ia`) | `namespace:id` | `itemsadder:myitems:ruby` |
| Oraxen | `oraxen` | item id | `oraxen:amethyst_sword` |
| Nexo | `nexo` | item id | `nexo:onyx` |
| MythicMobs / Crucible | `mythicmobs` (`mythic`, `crucible`) | item name | `mythicmobs:KingsCrown` |

## Behaviour

- Each referenced item is created once and reused, so every unit is identical and
  stacks in storage. MMOItems random stats are rolled once per server start or
  `/bspawner reload`, not per unit.
- Selling uses the drop's `sell-price`. A custom drop is recognised by its plugin
  id, never by its base material: without a `sell-price` it is not sold by
  BravenSpawner (a shop plugin quote can still price it), and it never inherits a
  vanilla `sell-prices` entry for the same material.
- `display-name` and `custom-model-data` only decorate vanilla drops; custom items
  keep their own look.
- Fail closed: if the plugin is missing, disabled, incompatible or does not know
  the id, that drop is skipped and a warning is logged once. Other drops still
  produce. ItemsAdder, Oraxen and Nexo load items after startup; their drops
  start once the item exists.
- `/bspawner doctor` shows each installed item plugin as `ready` or
  `incompatible`.
- A malformed reference (for example `mmoitems:SWORD` without an id) is reported
  when products load and skipped.

## How it is built

The adapters call each plugin's public API directly; there is no reflection. The
APIs are `compileOnly` dependencies (never shaded) compiled against MMOItems 6.10.1
(with MythicLib 1.7.1), eco 2026.44.1, ItemsAdder API 3.6.3, Oraxen 1.220.1, Nexo
1.29.1 and MythicMobs 5.14.0. Each adapter is its own class and is only loaded
after its plugin is confirmed enabled, so servers without these plugins never
touch their classes. If an installed version removed one of the methods used, the
`LinkageError` is caught once, the plugin is reported `incompatible` and its drops
are skipped. EcoItems items are looked up through eco's registry (`ecoitems:id`).

Live servers with these plugins are not qualified yet.
