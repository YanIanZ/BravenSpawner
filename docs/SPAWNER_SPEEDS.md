# Spawner speeds

Since 1.5.0 every product has its own production delay. Each cycle picks a delay between `min` and `max` seconds (`spawn-delays` in `mobs/*.yml` and `itemspawners/*.yml`). Upgrade tiers divide the delay by their `speed` (bundled: base 1.0, iron 1.33, gold 2.0, diamond 4.0), and speed boosters divide it further. The minimum period is one second.

Rule of thumb: cheap or passive products are fast, and products with valuable drops are slow, so hourly value stays balanced across spawners.

On upgrade, files that still have exactly their old shipped timing are switched to these values once (marker `.speeds-v2`). Files you edited keep your values.

## Mobs

| Mob | Delay (s) | Avg per hour (base tier) |
| --- | --- | --- |
| allay | 60–90 | 48 cycles |
| armadillo | 24–38 | 116 cycles |
| axolotl | 25–40 | 111 cycles |
| bat | 14–24 | 189 cycles |
| bee | 20–32 | 138 cycles |
| blaze | 40–60 | 72 cycles |
| bogged | 20–35 | 131 cycles |
| breeze | 60–90 | 48 cycles |
| camel | 22–36 | 124 cycles |
| camel_husk | 22–38 | 120 cycles |
| cat | 18–30 | 150 cycles |
| cave_spider | 15–28 | 167 cycles |
| chicken | 12–22 | 212 cycles |
| cod | 10–18 | 257 cycles |
| copper_golem | 45–70 | 63 cycles |
| cow | 15–25 | 180 cycles |
| creaking | 40–60 | 72 cycles |
| creeper | 25–40 | 111 cycles |
| dolphin | 24–40 | 112 cycles |
| donkey | 22–36 | 124 cycles |
| drowned | 22–38 | 120 cycles |
| elder_guardian | 150–240 | 18 cycles |
| ender_dragon | disabled by default | — |
| enderman | 35–55 | 80 cycles |
| endermite | 30–50 | 90 cycles |
| evoker | 150–240 | 18 cycles |
| fox | 18–30 | 150 cycles |
| frog | 16–28 | 164 cycles |
| ghast | 50–80 | 55 cycles |
| giant | disabled by default | — |
| glow_squid | 16–28 | 164 cycles |
| goat | 20–32 | 138 cycles |
| guardian | 35–55 | 80 cycles |
| happy_ghast | 45–70 | 63 cycles |
| hoglin | 35–55 | 80 cycles |
| horse | 22–36 | 124 cycles |
| husk | 20–35 | 131 cycles |
| illusioner | disabled by default | — |
| iron_golem | 60–90 | 48 cycles |
| llama | 22–36 | 124 cycles |
| magma_cube | 25–40 | 111 cycles |
| mule | 22–36 | 124 cycles |
| mushroom_cow | 18–30 | 150 cycles |
| nautilus | 40–60 | 72 cycles |
| ocelot | 18–30 | 150 cycles |
| panda | 20–32 | 138 cycles |
| parched | 20–35 | 131 cycles |
| parrot | 18–30 | 150 cycles |
| phantom | 30–45 | 96 cycles |
| pig | 14–24 | 189 cycles |
| piglin | 40–60 | 72 cycles |
| piglin_brute | 45–70 | 63 cycles |
| pillager | 35–55 | 80 cycles |
| polar_bear | 22–36 | 124 cycles |
| pufferfish | 14–24 | 189 cycles |
| rabbit | 16–28 | 164 cycles |
| ravager | 120–180 | 24 cycles |
| salmon | 10–18 | 257 cycles |
| sheep | 15–25 | 180 cycles |
| shulker | 90–150 | 30 cycles |
| silverfish | 10–18 | 257 cycles |
| skeleton | 18–32 | 144 cycles |
| skeleton_horse | 25–40 | 111 cycles |
| slime | 20–35 | 131 cycles |
| sniffer | 30–50 | 90 cycles |
| snow_golem | 8–14 | 327 cycles |
| spider | 18–32 | 144 cycles |
| squid | 12–20 | 225 cycles |
| stray | 20–35 | 131 cycles |
| strider | 20–32 | 138 cycles |
| sulfur_cube | 25–40 | 111 cycles |
| tadpole | 10–18 | 257 cycles |
| trader_llama | 24–38 | 116 cycles |
| tropical_fish | 12–20 | 225 cycles |
| turtle | 30–50 | 90 cycles |
| vex | 30–50 | 90 cycles |
| villager | 50–80 | 55 cycles |
| vindicator | 40–60 | 72 cycles |
| wandering_trader | 60–90 | 48 cycles |
| warden | 240–360 | 12 cycles |
| witch | 30–50 | 90 cycles |
| wither | disabled by default | — |
| wither_skeleton | 45–70 | 63 cycles |
| wolf | 45–75 | 60 cycles |
| zoglin | 25–40 | 111 cycles |
| zombie | 20–35 | 131 cycles |
| zombie_horse | 25–40 | 111 cycles |
| zombie_nautilus | 25–40 | 111 cycles |
| zombie_villager | 22–38 | 120 cycles |
| zombified_piglin | 25–40 | 111 cycles |

## Item spawners

| Item | Delay (s) | Avg per hour (base tier) |
| --- | --- | --- |
| cobble | 10–20 | 240 cycles |
| diamond | 180–300 | 15 cycles |
| iron | 60–90 | 48 cycles |
| wheat | 15–25 | 180 cycles |
