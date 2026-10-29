package dev.yanianz.bravenspawner.wand;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.sell.SellService;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Behaviour of the sell wand: who sells, what the modifiers do and how often.
 *
 * <p>The service is a per-plugin singleton. {@link #install(BravenSpawner)} owns
 * construction, config loading and listener registration, so onEnable needs one
 * line and cannot accidentally register the listener twice. A disabled wand still
 * returns a service: every operation then degrades to a no-op, which keeps the
 * parent's wiring unconditional during a config-driven outage.
 *
 * <p>Sales deliberately go through the existing {@link SellService}; this class
 * never touches storage or the economy. The per-wand multiplier is included in
 * the authoritative quote before the sale service deposits any money.
 *
 * <p>The cooldown is a per-player marker removed by a delayed global-region task.
 * The atomic map insertion prevents concurrent clicks from starting two uses;
 * the scheduled task only releases that marker after the configured delay.
 *
 * <p>Every method that accepts a {@link Player} must run on that player's region
 * thread: it reads the player's location, inventory or connection. The listener
 * calls them from {@code PlayerInteractEvent} and {@code InventoryClickEvent},
 * where that ownership already holds.
 */
public final class SellWandService {

    /** Totals for one wand use. {@code value} is what the player was credited. */
    public record Report(int targeted, int sold, int items, double value, int failed) {
        public static final Report NONE = new Report(0, 0, 0, 0.0, 0);

        public boolean soldAnything() {
            return sold > 0;
        }
    }

    private static volatile SellWandService instance;

    private final BravenSpawner plugin;
    private final SellService sellService;
    private final SoundFeedback sounds;
    private final Map<UUID, Boolean> cooling = new ConcurrentHashMap<>();
    private final AtomicBoolean cooldownWarning = new AtomicBoolean();

    private volatile WandDefinition definition;
    private volatile Map<ModifierType.Effect, ModifierType> types;
    private volatile Wand template;

    private SellWandService(BravenSpawner plugin) {
        this.plugin = plugin;
        this.sellService = SellService.install(plugin);
        this.sounds = new SoundFeedback(plugin);
        reload();
    }

    /**
     * Returns the singleton for this plugin, creating it and registering the
     * listener on first call.
     *
     * <p>Call it from onEnable after the spawner manager exists. The listener is
     * registered here rather than by the parent so a reload or a second install
     * cannot attach a duplicate handler that would sell twice per click.
     */
    public static SellWandService install(BravenSpawner plugin) {
        if (plugin == null) throw new IllegalArgumentException("plugin");
        SellWandService local = instance;
        if (local != null && local.plugin == plugin) return local;
        synchronized (SellWandService.class) {
            if (instance == null || instance.plugin != plugin) {
                instance = new SellWandService(plugin);
                Bukkit.getPluginManager().registerEvents(new SellWandListener(plugin, instance), plugin);
            }
            return instance;
        }
    }

    /**
     * Re-reads the three YAML files and rebuilds the rendering template.
     *
     * <p>Not called per interaction: file I/O belongs on the install/reload path.
     * Existing wands keep their ids and modifier values; only the layout and the
     * limits in effect change.
     */
    public void reload() {
        WandDefinition loadedDefinition = WandDefinition.load(plugin);
        Map<ModifierType.Effect, ModifierType> loadedTypes = ModifierType.loadAll(plugin);
        this.definition = loadedDefinition;
        this.types = loadedTypes;
        this.template = Wand.template(plugin, loadedDefinition, loadedTypes);
        this.sounds.reload();
        plugin.getLogger().info(loadedDefinition.enabled()
                ? "Sell wand enabled — material " + loadedDefinition.material().name()
                + ", radius " + loadedDefinition.radius()
                + ", cooldown " + loadedDefinition.cooldownMillis() + "ms"
                : "Sell wand disabled by sellwand.yml; wand interactions are a no-op.");
    }

    /** The loaded definition; useful for an admin command that reports the current look. */
    public WandDefinition definition() {
        return definition;
    }

    /** False when {@code settings.enabled} is false; every public operation is then a no-op. */
    public boolean enabled() {
        WandDefinition current = definition;
        return current != null && current.enabled();
    }

    /** True when the stack carries this plugin's wand id and material. */
    public boolean isWand(ItemStack item) {
        Wand current = template;
        return enabled() && current != null && current.isWand(item);
    }

    /** Reads a wand, or null when the stack is not one or the feature is disabled. */
    public Wand read(ItemStack item) {
        Wand current = template;
        return enabled() && current != null ? current.read(item) : null;
    }

    /** Renders a wand; null when the feature is disabled or the value is null. */
    public ItemStack write(Wand wand) {
        Wand current = template;
        return enabled() && current != null && wand != null ? current.write(wand) : null;
    }

    /** Creates a fresh wand value with no modifiers, or null when disabled. */
    public Wand newWand() {
        if (!enabled()) return null;
        return Wand.create(plugin, definition, types, List.of());
    }

    /** Creates the item for a fresh wand, or null when disabled. */
    public ItemStack createWand() {
        Wand wand = newWand();
        return wand == null ? null : write(wand);
    }

    /**
     * Creates a wand with a lifetime parsed from the command's duration argument.
     *
     * <p>Parsing is left to {@link WandLifetime#parse(String, long)}; an invalid
     * duration throws before any item is built, so an admin typo cannot grant a
     * wand with a half-written lifetime. Infinite is represented by an empty
     * duration at the command layer.
     */
    public ItemStack createWand(String duration) {
        Wand wand = newWand();
        return wand == null ? null : write(wand.withLifetime(WandLifetime.parse(duration, System.currentTimeMillis())));
    }

    /** Reject expired and exhausted wands before any economy call or cooldown. */
    /**
     * True while the wand still has a use or has not expired.
     *
     * <p>The check runs before the cooldown and before any sale, so an exhausted
     * wand never charges the player a cooldown for a click that could not sell.
     * The message distinguishes "no uses" from "expired" because the two need
     * different fixes from an admin.
     */
    public boolean usable(Player player, Wand wand) {
        if (wand == null) return false;
        if (wand.lifetime().usable(System.currentTimeMillis())) return true;
        String message = wand.lifetime().uses() == 0
                ? definition.lifetimeMessage("no-uses-left", "&cYour sell wand has no uses left.")
                : definition.lifetimeMessage("wand-expired", "&cYour sell wand has expired.");
        if (player != null) {
            player.sendMessage(Text.of(message));
            sounds.play(player, SoundFeedback.Cue.FAILURE);
        }
        return false;
    }

    /** No recurring wand task is owned by this service. */
    /**
     * Clears cooldown markers on plugin shutdown.
     *
     * <p>The service owns no recurring task; the delayed global task that removes
     * each marker is cancelled by the plugin's own shutdown. Clearing the map
     * here only makes a hot reload start from a clean slate instead of locking a
     * player out for the remainder of a wall-clock second.
     */
    public void shutdown() { cooling.clear(); }

    /**
     * Sells every spawner around the player.
     *
     * <p>The centre is the player, and the radius is the configured value plus the
     * wand's range modifier. When {@code within-radius} is false the reach is zero,
     * matching the reference behaviour where a wand then only works on the spawner
     * that was clicked (the listener's single-spawner path).
     */
    public Report sellArea(Player player, Wand wand) {
        if (!enabled() || player == null || wand == null || !usable(player, wand)) return Report.NONE;
        if (!beginCooldown(player)) {
            player.sendMessage(Text.of(definition.messageCooldown()));
            sounds.play(player, SoundFeedback.Cue.COOLDOWN);
            return Report.NONE;
        }
        return sellAll(player, wand, nearby(player, wand));
    }

    /**
     * Sells one spawner, used when the wand was clicked directly on it.
     *
     * <p>This path works even with {@code within-radius: false}: the reference
     * wand's "clicked only" mode is exactly this call.
     */
    public Report sell(Player player, Wand wand, Spawner spawner) {
        if (!enabled() || player == null || wand == null || spawner == null || !usable(player, wand)) return Report.NONE;
        if (!beginCooldown(player)) {
            player.sendMessage(Text.of(definition.messageCooldown()));
            sounds.play(player, SoundFeedback.Cue.COOLDOWN);
            return Report.NONE;
        }
        return sellAll(player, wand, List.of(spawner));
    }

    /**
     * Applies a modifier and returns the updated wand, or null when refused.
     *
     * <p>Refusal covers the three contract rules: overrides disabled with a modifier
     * of the same effect already present, a full modifier-slot budget, and an effect
     * that no longer exists in {@code modifiers.yml}. The value itself is clamped by
     * {@link Modifier#of(ModifierType, double)} to the type's {@code max_value}.
     *
     * <p>The caller owns the item, so this method does not mutate the stack; write
     * the returned value with {@link #write(Wand)} to update the wand in hand.
     */
    public Wand applyModifier(Player player, Wand wand, Modifier modifier) {
        if (!enabled() || wand == null || modifier == null) return null;

        ModifierType current = types.get(modifier.type().effect());
        if (current == null) return refuse(player);

        boolean present = wand.hasModifier(current.effect());
        WandDefinition currentDefinition = definition;
        if (present && !currentDefinition.allowOverride()) return refuse(player);
        if (!present && currentDefinition.modifierSlots() > 0
                && wand.modifierCount() >= currentDefinition.modifierSlots()) {
            return refuse(player);
        }

        Modifier applied = Modifier.of(current, modifier.value());
        if (player != null) {
            player.sendMessage(Text.of(currentDefinition.messageModifierApplied()));
            sounds.play(player, SoundFeedback.Cue.SUCCESS);
        }
        return wand.with(applied);
    }

    private Wand refuse(Player player) {
        if (player != null) {
            player.sendMessage(Text.of(definition.messageModifierFailed()));
            sounds.play(player, SoundFeedback.Cue.FAILURE);
        }
        return null;
    }

    /**
     * Resolves a configured modifier by config id or stable effect key.
     *
     * <p>Exposed so a command can grant a modifier without parsing YAML itself;
     * the returned value is already clamped to the current maximum.
     */
    public Modifier modifier(String id, double value) {
        if (!enabled() || id == null) return null;
        for (ModifierType type : types.values()) {
            if (type.id().equalsIgnoreCase(id) || type.effect().storageKey().equalsIgnoreCase(id)) {
                return Modifier.of(type, value);
            }
        }
        return null;
    }

    /**
     * Builds the physical modifier item a command gives to a player.
     *
     * <p>Validity is proven by PDC rather than by the material or display name, so
     * a renamed item cannot be applied as a modifier and a modifier stack can be
     * recognized after a restart. The value is stored as a double, not reparsed
     * from lore.
     */
    public ItemStack createModifier(Modifier modifier) {
        if (!enabled() || modifier == null) return null;
        ItemStack item = new ItemStack(modifier.type().material());
        var meta = item.getItemMeta();
        if (meta == null) return null;
        meta.displayName(Text.of(modifier.displayName()));
        meta.lore(Text.of(modifier.itemLore()));
        if (modifier.type().enchanted()) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        var data = meta.getPersistentDataContainer();
        data.set(new org.bukkit.NamespacedKey(plugin, "modifier_effect"),
                org.bukkit.persistence.PersistentDataType.STRING, modifier.storageKey());
        data.set(new org.bukkit.NamespacedKey(plugin, "modifier_value"),
                org.bukkit.persistence.PersistentDataType.DOUBLE, modifier.value());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Reads a modifier item, or null when the stack is not one.
     *
     * <p>Both PDC fields and the configured material must agree. A modifier kind
     * removed from {@code modifiers.yml} therefore stops applying instead of
     * silently landing on a wand with a stale maximum.
     */
    public Modifier readModifier(ItemStack item) {
        if (!enabled() || item == null || !item.hasItemMeta()) return null;
        var data = item.getItemMeta().getPersistentDataContainer();
        String effect = data.get(new org.bukkit.NamespacedKey(plugin, "modifier_effect"),
                org.bukkit.persistence.PersistentDataType.STRING);
        Double value = data.get(new org.bukkit.NamespacedKey(plugin, "modifier_value"),
                org.bukkit.persistence.PersistentDataType.DOUBLE);
        if (effect == null || value == null || !Double.isFinite(value)) return null;
        Modifier modifier = modifier(effect, value);
        return modifier != null && item.getType() == modifier.type().material() ? modifier : null;
    }

    /** The modifier types loaded from {@code modifiers.yml}. */
    public List<ModifierType> modifierTypes() {
        return List.copyOf(types.values());
    }

    /** One loaded type by effect, or null when that effect is not configured. */
    public ModifierType modifierType(ModifierType.Effect effect) {
        return effect == null ? null : types.get(effect);
    }

    /** Same money formatting as the GUI and autosell messages. */
    public String format(double amount) {
        return sellService.format(amount);
    }

    // -------------------------------------------------------------- internals

    private List<Spawner> nearby(Player player, Wand wand) {
        SpawnerManager manager = plugin.manager();
        if (manager == null) return List.of();

        Location center = player.getLocation();
        World world = center.getWorld();
        if (world == null) return List.of();

        double radius = definition.withinRadius() ? wand.radius() : 0.0;
        double limit = radius * radius;
        String worldName = world.getName();

        List<Spawner> targets = new ArrayList<>();
        for (Spawner spawner : manager.all()) {
            if (spawner == null || !worldName.equals(spawner.world())) continue;
            double dx = (spawner.x() + 0.5) - center.getX();
            double dy = (spawner.y() + 0.5) - center.getY();
            double dz = (spawner.z() + 0.5) - center.getZ();
            if (dx * dx + dy * dy + dz * dz <= limit) targets.add(spawner);
        }
        targets.sort(Comparator.comparingDouble(spawner -> distanceSquared(center, spawner)));
        return targets;
    }

    private static double distanceSquared(Location center, Spawner spawner) {
        double dx = (spawner.x() + 0.5) - center.getX();
        double dy = (spawner.y() + 0.5) - center.getY();
        double dz = (spawner.z() + 0.5) - center.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Sells the supplied spawners and reports one aggregate line.
     *
     * <p>Spawners with nothing priced are skipped rather than counted as failures:
     * an empty spawner in range is a normal neighbour, not an error. A sale refused
     * by the sell service keeps its own status so the player sees "disabled" or
     * "no economy" instead of a generic "nothing to sell".
     */
    private Report sellAll(Player player, Wand wand, List<Spawner> spawners) {
        if (spawners.isEmpty()) {
            player.sendMessage(Text.of(definition.messageNothing()));
            sounds.play(player, SoundFeedback.Cue.FAILURE);
            return new Report(0, 0, 0, 0.0, 0);
        }

        int sold = 0;
        int items = 0;
        int failed = 0;
        double base = 0.0;
        SellService.Status blocked = null;

        for (Spawner spawner : spawners) {
            try {
                if (!canSell(player, spawner)) {
                    failed++;
                    continue;
                }
                SellService.Sale sale = sellService.sell(player, spawner, wand.priceMultiplier());
                switch (sale.status()) {
                    case SOLD -> {
                        sold++;
                        items += sale.items();
                        base += sale.value();
                    }
                    case NOTHING_TO_SELL -> { }
                    case DISABLED, NO_ECONOMY, FAILED, PENDING -> {
                        failed++;
                        if (blocked == null) blocked = sale.status();
                    }
                }
            } catch (RuntimeException e) {
                failed++;
                plugin.getLogger().warning("sell wand sale failed for " + spawner.id()
                        + ": " + e.getMessage());
            }
        }

        if (sold == 0) {
            player.sendMessage(Text.of(blockedMessage(blocked)));
            sounds.play(player, SoundFeedback.Cue.FAILURE);
            return new Report(spawners.size(), 0, 0, 0.0, failed);
        }

        double paid = base;
        String message = definition.messageSold()
                .replace("%spawners%", Integer.toString(sold))
                .replace("%items%", Integer.toString(items))
                .replace("%value%", sellService.format(paid));
        player.sendMessage(Text.of(message));
        sounds.play(player, SoundFeedback.Cue.SUCCESS);
        return new Report(spawners.size(), sold, items, paid, failed);
    }

    private String blockedMessage(SellService.Status status) {
        if (status == null) return definition.messageNothing();
        return switch (status) {
            case DISABLED -> "&cSelling is disabled on this server.";
            case NO_ECONOMY -> "&cEconomy not available — nothing was sold.";
            case FAILED -> "&cThe sale failed — nothing was removed.";
            case PENDING -> "&eA previous payout is still being settled; no new sale was made.";
            default -> definition.messageNothing();
        };
    }

    /** Owner/trust policy matches the spawner GUI; radius never bypasses it. */
    public static boolean canSell(Player player, Spawner spawner) {
        return player != null && spawner != null
                && (spawner.canUse(player.getUniqueId()) || player.hasPermission("bravenspawner.admin"));
    }

    /**
     * Starts the per-player cooldown, or reports that one is still running.
     *
     * <p>The marker is inserted atomically with {@code putIfAbsent} from the
     * clicking region thread and removed by a delayed global-region task. The
     * atomic insert is the gate; the global queue supplies one server-wide clock
     * for expiry, so two regions cannot keep a player on different countdowns.
     */
    private boolean beginCooldown(Player player) {
        int millis = definition.cooldownMillis();
        if (millis <= 0) return true;

        UUID id = player.getUniqueId();
        if (cooling.putIfAbsent(id, Boolean.TRUE) != null) return false;

        long ticks = Math.max(1L, (millis + 49L) / 50L);
        try {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> cooling.remove(id), ticks);
        } catch (RuntimeException e) {
            // A server that is shutting down cannot schedule; leaving the player
            // locked out of a disabled task would be worse than allowing the click.
            cooling.remove(id);
            if (cooldownWarning.compareAndSet(false, true)) {
                plugin.getLogger().warning("sell wand cooldown could not be scheduled ("
                        + e.getMessage() + "); the click was allowed.");
            }
        }
        return true;
    }
}
