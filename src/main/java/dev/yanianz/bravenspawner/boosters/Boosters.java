package dev.yanianz.bravenspawner.boosters;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.util.Schedulers;
import dev.yanianz.bravenspawner.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Typed timed spawner effects and carried personal items; inventory reads stay on entity threads. */
public final class Boosters {
    public enum Type { SPEED, XP, LOOT, AUTOSELL, CASH, LEGACY }
    public enum Scope { NORMAL, PERSONAL }

    private final Plugin plugin;
    private final BoosterState state = new BoosterState(System::currentTimeMillis);
    private volatile Map<String, BoosterDefinition> normal = Map.of(), personal = Map.of();
    private volatile boolean enabled = true;
    private volatile boolean running;
    private ScheduledTask sweep;
    private ScheduledTask checkpoint;
    private final Object persistenceLock = new Object();
    private BoosterStore store;
    private boolean saveFailed;
    private BoosterListener listener;

    public Boosters(Plugin plugin) { this.plugin = plugin; }

    /** Existing bootstrap hook: loads resources, installs handlers, and starts one sweep. */
    public void start(Settings settings) {
        enabled = settings.enableBoosters;
        if (!enabled) { shutdown(); return; }
        if (!(plugin instanceof BravenSpawner host)) return;
        synchronized (persistenceLock) {
            if (!running) {
                store = new BoosterStore(plugin.getDataFolder().toPath().resolve("active-boosters.properties"));
                try { state.restore(store.load()); }
                catch (java.io.IOException exception) {
                    enabled = false;
                    plugin.getLogger().severe("Boosters disabled: cannot load active-boosters.properties; file preserved: " + exception.getMessage());
                    return;
                }
            }
        }
        normal = BoosterDefinition.load(host, Scope.NORMAL);
        personal = BoosterDefinition.load(host, Scope.PERSONAL);
        if (listener == null) {
            listener = new BoosterListener(host, this);
            Bukkit.getPluginManager().registerEvents(listener, plugin);
        }
        running = true;
        if (sweep == null || sweep.isCancelled()) {
            sweep = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
                state.sweep();
                // Other plugins can change inventories without inventory events. A
                // bounded periodic snapshot catches those changes without unsafe reads.
                for (Player player : Bukkit.getOnlinePlayers()) refreshLater(player);
            }, 20L, 20L);
        }
        if (checkpoint == null || checkpoint.isCancelled()) {
            checkpoint = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
                synchronized (persistenceLock) { if (running) saveActive(); }
            }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        }
        for (Player player : Bukkit.getOnlinePlayers()) refreshLater(player);
    }

    public void reload(Settings settings) { start(settings); }

    public void shutdown() {
        synchronized (persistenceLock) {
            boolean wasRunning = running;
            running = false;
            enabled = false;
            if (checkpoint != null) { checkpoint.cancel(); checkpoint = null; }
            if (wasRunning) saveActive();
            state.clear();
        }
        if (sweep != null) { sweep.cancel(); sweep = null; }
        if (listener != null) { HandlerList.unregisterAll(listener); listener = null; }
    }

    /** Caller holds persistenceLock, so an old async checkpoint cannot overwrite shutdown. */
    private void saveActive() {
        if (store == null) return;
        try {
            store.save(state.snapshot());
            saveFailed = false;
        } catch (java.io.IOException exception) {
            if (!saveFailed) plugin.getLogger().severe("Cannot save active boosters; previous ledger retained: " + exception.getMessage());
            saveFailed = true;
        }
    }

    /** Legacy grants preserve the old XP/loot effect rather than speeding production. */
    public void grantPlayer(UUID player, double multiplier, long seconds) {
        if (enabled) state.grantLegacyPlayer(player, multiplier, seconds);
    }

    public void grantSpawner(UUID spawner, double multiplier, long seconds) {
        grantSpawner(Type.LEGACY, spawner, multiplier, seconds);
    }

    public void grantPlayer(Type type, UUID player, double multiplier, long seconds) {
        if (!enabled) return;
        state.grantPlayer(type, player, multiplier, seconds);
        if (type == Type.SPEED && plugin instanceof BravenSpawner host && host.manager() != null) {
            host.manager().ownedBy(player).forEach(host.manager()::reschedule);
        }
    }

    public void grantSpawner(Type type, UUID spawner, double multiplier, long seconds) {
        if (!enabled) return;
        state.grantSpawner(type, spawner, multiplier, seconds);
        if (type == Type.SPEED && plugin instanceof BravenSpawner host && host.manager() != null) {
            // Install against the already-updated multiplier instead of leaving
            // the old, potentially much longer interval queued until its next tick.
            var target = host.manager().byId(spawner);
            if (target != null) host.manager().reschedule(target);
        }
    }

    public double multiplier(UUID owner, UUID spawner) { return multiplier(Type.LEGACY, owner, spawner); }

    /** Production callbacks do not include carried personal XP/CASH effects. */
    public double multiplier(Type type, UUID owner, UUID spawner) {
        return enabled ? state.multiplier(type, owner, spawner) : 1.0;
    }

    public boolean autosell(UUID owner, UUID spawner) {
        return enabled && state.active(Type.AUTOSELL, owner, spawner);
    }

    /** Invoke using the actual XP collector or seller, which may differ from the owner. */
    public double personalMultiplier(Type type, UUID player) {
        return enabled ? state.personalMultiplier(type, player) : 1.0;
    }

    public long remainingSeconds(UUID player) { return enabled ? state.remainingSeconds(player) : 0; }
    public boolean enabled() { return enabled; }

    public List<String> itemIds(Scope scope) { return definitions(scope).keySet().stream().sorted().toList(); }
    private Map<String, BoosterDefinition> definitions(Scope scope) { return scope == Scope.NORMAL ? normal : personal; }

    public ItemStack createItem(Scope scope, String id) {
        if (!enabled || scope == null || id == null) return null;
        BoosterDefinition definition = definitions(scope).get(id.toLowerCase(Locale.ROOT));
        if (definition == null) return null;
        ItemStack item = new ItemStack(definition.material());
        var meta = item.getItemMeta();
        if (meta == null) return null;
        meta.displayName(Text.of(render(definition.name(), definition)));
        meta.lore(Text.of(definition.lore().stream().map(line -> render(line, definition)).toList()));
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "booster_scope"), PersistentDataType.STRING, scope.name());
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "booster_id"), PersistentDataType.STRING, definition.id());
        item.setItemMeta(meta);
        return item;
    }

    private static String render(String text, BoosterDefinition definition) {
        return text.replace("%expiration_time%", definition.duration()).replace("%multiplier%", Double.toString(definition.multiplier()));
    }

    public BoosterDefinition readItem(ItemStack item) {
        if (!enabled || item == null || !item.hasItemMeta()) return null;
        var meta = item.getItemMeta();
        if (meta == null) return null;
        var data = meta.getPersistentDataContainer();
        String scope = data.get(new NamespacedKey(plugin, "booster_scope"), PersistentDataType.STRING);
        String id = data.get(new NamespacedKey(plugin, "booster_id"), PersistentDataType.STRING);
        if (scope == null || id == null) return null;
        try {
            BoosterDefinition definition = definitions(Scope.valueOf(scope)).get(id);
            return definition != null && item.getType() == definition.material() ? definition : null;
        } catch (IllegalArgumentException exception) { return null; }
    }

    public boolean isBooster(ItemStack item) { return readItem(item) != null; }

    /** Schedules the entire inventory mutation on the recipient's entity scheduler. */
    public boolean giveItem(Player player, Scope scope, String id, int amount) {
        if (player == null || scope == null || id == null || amount < 1 || amount > 64
                || !enabled || !definitions(scope).containsKey(id.toLowerCase(Locale.ROOT))) return false;
        Schedulers.onEntity(plugin, player, () -> {
            ItemStack item = createItem(scope, id);
            if (item == null) return;
            item.setAmount(amount);
            player.getInventory().addItem(item).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            refreshPersonal(player);
            player.sendMessage(Text.of("&aReceived " + amount + " booster item(s)."));
        }, null);
        return true;
    }

    public void refreshLater(Player player) {
        if (!running || player == null) return;
        Schedulers.onEntity(plugin, player, () -> { if (running && player.isOnline()) refreshPersonal(player); }, null);
    }

    /** Must be called on this player's entity thread, never from a producer region. */
    public void refreshPersonal(Player player) {
        if (!enabled) return;
        Map<Type, Double> values = new EnumMap<>(Type.class);
        for (ItemStack item : player.getInventory().getContents()) {
            BoosterDefinition definition = readItem(item);
            if (definition != null && definition.scope() == Scope.PERSONAL) {
                values.merge(definition.type(), definition.multiplier(), Math::max);
            }
        }
        state.replacePersonal(player.getUniqueId(), values);
    }

    public void forgetPersonal(UUID player) { state.replacePersonal(player, Map.of()); }
}
