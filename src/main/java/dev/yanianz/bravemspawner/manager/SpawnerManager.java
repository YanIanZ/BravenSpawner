package dev.yanianz.bravemspawner.manager;

import dev.yanianz.bravemspawner.BravemSpawner;
import dev.yanianz.bravemspawner.config.Settings;
import dev.yanianz.bravemspawner.model.Spawner;
import dev.yanianz.bravemspawner.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns every spawner and the tick pass.
 *
 * <p>Folia-safe: each spawner is scheduled against its own region, and entity
 * work is routed through the entity's scheduler. A failure inside one spawner is
 * logged and does not stop the pass.
 */
public final class SpawnerManager {

    private final BravemSpawner plugin;
    private final Map<String, Spawner> byBlock = new ConcurrentHashMap<>();
    private final Map<UUID, Spawner> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Schedulers.Cancelable> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGuiOpen = new ConcurrentHashMap<>();

    private volatile Settings settings;

    public SpawnerManager(BravemSpawner plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public Settings settings() { return settings; }

    public void settings(Settings value) {
        this.settings = value;
        all().forEach(this::reschedule);
    }

    public Collection<Spawner> all() { return byId.values(); }
    public int size() { return byId.size(); }
    public Spawner byId(UUID id) { return byId.get(id); }

    public static String key(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }

    public Spawner at(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return byBlock.get(key(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    public List<Spawner> ownedBy(UUID owner) {
        List<Spawner> out = new ArrayList<>();
        for (Spawner s : byId.values()) if (s.isOwner(owner)) out.add(s);
        return out;
    }

    public int stackedInChunk(World world, int chunkX, int chunkZ) {
        int total = 0;
        for (Spawner s : byId.values()) {
            if (!s.world().equals(world.getName())) continue;
            if ((s.x() >> 4) != chunkX || (s.z() >> 4) != chunkZ) continue;
            total += s.stack();
        }
        return total;
    }

    public boolean register(Spawner spawner) {
        String key = key(spawner.world(), spawner.x(), spawner.y(), spawner.z());
        if (byBlock.putIfAbsent(key, spawner) != null) return false;
        byId.put(spawner.id(), spawner);
        schedule(spawner);
        return true;
    }

    public void unregister(Spawner spawner) {
        byBlock.remove(key(spawner.world(), spawner.x(), spawner.y(), spawner.z()));
        byId.remove(spawner.id());
        Schedulers.Cancelable task = tasks.remove(spawner.id());
        if (task != null) task.cancel();
    }

    public void startAll() { all().forEach(this::schedule); }

    public void stopAll() {
        tasks.values().forEach(t -> {
            try {
                t.cancel();
            } catch (RuntimeException ignored) {
                // already gone
            }
        });
        tasks.clear();
    }

    public void reschedule(Spawner spawner) {
        Schedulers.Cancelable old = tasks.remove(spawner.id());
        if (old != null) old.cancel();
        schedule(spawner);
    }

    public boolean guiThrottled(UUID player, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long last = lastGuiOpen.get(player);
        if (last != null && now - last < cooldownMs) return true;
        lastGuiOpen.put(player, now);
        return false;
    }

    private void schedule(Spawner spawner) {
        Location location = spawner.location();
        if (location == null) return;
        Settings s = settings;
        long period = Math.max(20L, s.tiers.get(Math.min(spawner.tier(), s.tiers.size() - 1)).intervalTicks);
        tasks.put(spawner.id(), Schedulers.repeating(plugin, location, period, () -> tick(spawner)));
    }

    private void tick(Spawner spawner) {
        Settings s = settings;
        if (spawner.corrupted()) return;
        Location location = spawner.location();
        if (location == null) return;
        World world = location.getWorld();
        if (world == null) return;
        if (s.isBlacklisted(world.getName())) return;
        if (!world.isChunkLoaded(spawner.x() >> 4, spawner.z() >> 4)) return;

        if (s.noActivationRange) {
            if (world.getPlayers().stream().noneMatch(p ->
                    p.getLocation().distanceSquared(location) <= (double) s.activationRadius * s.activationRadius
                            && p.getWorld().equals(world))) {
                return;
            }
        }

        int batches = Math.max(1, spawner.stack());
        for (int i = 0; i < batches; i++) {
            if (spawner.kind() == Spawner.Kind.ITEM) produceItem(spawner, s);
            else produceMob(spawner, s, location, world);
        }
        spawner.produced(batches);
        plugin.holograms().update(spawner);
    }

    private void produceItem(Spawner spawner, Settings s) {
        Settings.Product product = s.items.get(spawner.product());
        if (product == null) return;
        Random random = ThreadLocalRandom.current();
        int capacity = s.unlimitedStorage ? 0 : s.tiers.get(Math.min(spawner.tier(), s.tiers.size() - 1)).capacity;
        for (Settings.Drop drop : product.drops) {
            int amount = drop.roll(random);
            if (amount <= 0) continue;
            spawner.store(new ItemStack(drop.material, amount), capacity);
        }
        spawner.addXp(product.xp);
    }

    private void produceMob(Spawner spawner, Settings s, Location location, World world) {
        Settings.Product product = s.mobs.get(spawner.product());
        if (product == null) return;
        EntityType type;
        try {
            type = EntityType.valueOf(product.id);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!type.isSpawnable()) return;

        long alive = world.getNearbyEntities(location, 16, 16, 16).stream()
                .filter(e -> {
                    String tag = e.getPersistentDataContainer().get(plugin.spawnerIdKey(), PersistentDataType.STRING);
                    return spawner.id().toString().equals(tag);
                })
                .count();
        if (alive >= Math.max(1, spawner.stack() * 8)) return;

        Location spawnAt = scatter(location, world);
        if (spawnAt == null) return;

        Schedulers.at(plugin, spawnAt, () -> {
            try {
                Entity entity = world.spawnEntity(spawnAt, type);
                entity.getPersistentDataContainer().set(
                        plugin.spawnerIdKey(), PersistentDataType.STRING, spawner.id().toString());
                if (entity instanceof LivingEntity living) {
                    living.setRemoveWhenFarAway(false);
                    living.setPersistent(true);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("spawn failed at " + spawnAt + ": " + e.getMessage());
            }
        });
    }

    private Location scatter(Location base, World world) {
        int radius = 4;
        Random random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 8; attempt++) {
            int dx = random.nextInt(-radius, radius + 1);
            int dz = random.nextInt(-radius, radius + 1);
            Location candidate = base.clone().add(dx, 0, dz);
            candidate.setY(world.getHighestBlockYAt(candidate) + 1.0);
            if (candidate.getBlock().isPassable()) return candidate;
        }
        return base;
    }
}
