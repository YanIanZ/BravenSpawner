package dev.yanianz.bravenspawner.listener;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/**
 * Death routing: a mob spawned by a spawner credits that spawner instead of the
 * ground. Boosters scale the stored amount, and XP is banked on the spawner for
 * later collection.
 */
public final class EntityListener implements Listener {

    private final BravenSpawner plugin;

    public EntityListener(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaSpawn(org.bukkit.event.entity.SpawnerSpawnEvent event) {
        if (plugin.manager().at(event.getSpawner().getLocation()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        String raw = event.getEntity().getPersistentDataContainer()
                .get(plugin.spawnerIdKey(), PersistentDataType.STRING);
        if (raw == null) return;

        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return;
        }
        Spawner spawner = plugin.manager().byId(id);
        if (spawner == null) return;

        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        double multiplier = plugin.boosters().multiplier(spawner.owner(), spawner.id());
        boolean storedAny = false;
        int capacity = plugin.settings().unlimitedStorage
                ? 0
                : plugin.settings().tiers.get(Math.min(spawner.tier(), plugin.settings().tiers.size() - 1)).capacity;

        List<ItemStack> drops = event.getDrops();
        for (ItemStack drop : drops) {
            ItemStack scaled = drop.clone();
            scaled.setAmount(Math.max(1, (int) Math.round(drop.getAmount() * multiplier)));
            ItemStack leftover = spawner.store(scaled, capacity);
            if (leftover == null || leftover.getAmount() < scaled.getAmount()) storedAny = true;
            if (leftover != null && leftover.getAmount() > 0) {
                drop.setAmount(leftover.getAmount());
            } else {
                drop.setAmount(0);
            }
        }
        drops.removeIf(item -> item == null || item.getAmount() <= 0 || item.getType().isAir());

        var product = plugin.settings().mobs.get(spawner.product());
        if (product != null && product.xp > 0) spawner.addXp(product.xp);

        if (storedAny) {
            plugin.holograms().update(spawner);
            if (!plugin.settings().disableAutosellNotifications && plugin.economy().enabled()) {
                killer.sendActionBar(Text.of("&7→ spawner &f" + spawner.storedCount()));
            }
        }
    }
}
