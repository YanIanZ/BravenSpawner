package dev.yanianz.bravenspawner.convert;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.audit.AuditEvent;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.manager.StackService;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Messages;
import dev.yanianz.bravenspawner.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Live conversion service: chunk passes, join inventory scans and the admin scan.
 *
 * <p>Every path funnels through the same guards ({@link ConvertGuards}), the
 * same server-wide throttle ({@link ConvertThrottle}) and the same product
 * lookup, so chunk-load conversion, join conversion and the scan command can
 * never disagree about what is convertible. World reads happen on the owning
 * region thread, inventory writes on the owning entity thread, and the scan
 * fan-out is coordinated from the global thread.
 *
 * <p>Conversions found in the world are owned by {@link ConvertService#SYSTEM_OWNER}
 * (see {@link ConvertService} for why); the placer is only stamped when an
 * actual player placed the block, which is the existing place path.
 */
public final class ConvertManager {

    /** Scan receipt timeout: region tasks for unloaded regions may never run. */
    static final long SCAN_TIMEOUT_TICKS = 300L;

    private final BravenSpawner plugin;
    private volatile ConvertThrottle throttle;

    public ConvertManager(BravenSpawner plugin) {
        this.plugin = plugin;
        this.throttle = throttleFor(plugin == null ? null : plugin.settings());
    }

    /** Rebuilds the throttle after a reload; in-flight budget is discarded. */
    public void reload() {
        throttle = throttleFor(plugin.settings());
    }

    /** The live server-wide throttle, for tests and diagnostics. */
    public ConvertThrottle throttle() {
        return throttle;
    }

    private static ConvertThrottle throttleFor(Settings settings) {
        int max = settings == null ? 4 : settings.convertMaxPerTick;
        return new ConvertThrottle(max, ConvertThrottle::wallTick);
    }

    // ------------------------------------------------------------ chunk pass

    /**
     * Converts every convertible vanilla spawner in one chunk.
     *
     * <p>Must run on the chunk's region thread. Skips while the plugin is not
     * ready (fail closed; the chunk will be seen again on its next load or by
     * the admin scan) and never queues unbounded work: at most one bounded
     * retry chain per throttled chunk, see {@link #convertChunkWithRetry}.
     *
     * @param dryRun count what would convert without registering anything
     */
    public ScanReport convertChunkNow(Chunk chunk, boolean dryRun) {
        ScanReport partial = new ScanReport();
        if (chunk == null || !plugin.ready()) {
            return partial;
        }
        Settings settings = plugin.settings();
        if (settings == null || plugin.manager() == null) {
            return partial;
        }
        if (!dryRun && !settings.convertEnabled) {
            return partial;
        }
        World world = chunk.getWorld();
        if (world == null || settings.isBlacklisted(world.getName())) {
            return partial;
        }
        BlockState[] tiles;
        try {
            tiles = chunk.getTileEntities();
        } catch (RuntimeException unreadable) {
            plugin.debug("convert: could not read tile entities of " + world.getName()
                    + " " + chunk.getX() + "," + chunk.getZ());
            return partial;
        }
        if (tiles == null) {
            return partial;
        }
        int pending = 0;
        for (BlockState state : tiles) {
            if (!(state instanceof CreatureSpawner spawner)) {
                continue;
            }
            ConvertCheck verdict = convertOne(world, spawner, settings, dryRun, pending);
            if (verdict == ConvertCheck.CONVERTIBLE) {
                pending++;
                partial.converted();
            } else if (verdict == ConvertCheck.ALREADY_MANAGED) {
                partial.alreadyManaged();
            } else {
                partial.skipped(verdict);
            }
        }
        return partial;
    }

    /**
     * Chunk-load entry point: converts now and, only when the pass was
     * throttled, schedules one bounded delayed retry on the same region.
     */
    public void convertChunkWithRetry(Chunk chunk, int attempt) {
        if (chunk == null) {
            return;
        }
        World world = chunk.getWorld();
        ScanReport partial = convertChunkNow(chunk, false);
        if (!ConvertService.shouldRetry(partial.skippedCount(ConvertCheck.THROTTLED), attempt) || world == null) {
            return;
        }
        String worldName = world.getName();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        Location anchor = anchor(world, chunkX, chunkZ);
        try {
            Schedulers.atLater(plugin, anchor, ConvertService.RETRY_DELAY_TICKS,
                    () -> retryChunk(worldName, chunkX, chunkZ, attempt + 1));
        } catch (RuntimeException rejected) {
            plugin.debug("convert: retry for " + worldName + " " + chunkX + "," + chunkZ + " rejected");
        }
    }

    private void retryChunk(String worldName, int chunkX, int chunkZ, int attempt) {
        if (!plugin.ready() || !plugin.isEnabled()) {
            return;
        }
        World world;
        try {
            world = Bukkit.getWorld(worldName);
        } catch (RuntimeException gone) {
            return;
        }
        if (world == null) {
            return;
        }
        Chunk chunk;
        try {
            // Never force-load: an unloaded chunk is simply seen on its next load.
            chunk = world.getChunkAt(chunkX, chunkZ, false);
        } catch (RuntimeException gone) {
            return;
        }
        if (chunk == null || !chunk.isLoaded()) {
            return;
        }
        convertChunkWithRetry(chunk, attempt);
    }

    private ConvertCheck convertOne(World world, CreatureSpawner spawner, Settings settings,
                                    boolean dryRun, int pending) {
        Block block;
        try {
            block = spawner.getBlock();
        } catch (RuntimeException unreadable) {
            return ConvertCheck.NOT_READY;
        }
        boolean alreadyManaged;
        try {
            alreadyManaged = plugin.manager().at(block.getLocation()) != null;
        } catch (RuntimeException unreadable) {
            return ConvertCheck.NOT_READY;
        }
        EntityType type;
        try {
            type = spawner.getSpawnedType();
        } catch (RuntimeException unreadable) {
            return ConvertCheck.NO_PRODUCT;
        }
        String product = type == null ? null : productFor(type.name());
        boolean chunkAllows = ConvertService.chunkAllowsWithPending(
                plugin.manager(), world, block.getX() >> 4, block.getZ() >> 4, pending);
        // The throttle is taken last so a skipped spawner never consumes budget.
        boolean granted = dryRun || throttle.tryAcquire();
        ConvertCheck verdict = ConvertGuards.checkBlock(true, true, false,
                alreadyManaged, product != null, chunkAllows, granted);
        if (verdict != ConvertCheck.CONVERTIBLE || dryRun) {
            return verdict;
        }
        Spawner managed = new Spawner(UUID.randomUUID(), ConvertService.ownerFor(null),
                world.getName(), block.getX(), block.getY(), block.getZ(),
                Spawner.Kind.MOB, product, 1, 0, true);
        if (!plugin.manager().register(managed)) {
            // Lost a race with a concurrent pass; the block is managed now.
            return ConvertCheck.ALREADY_MANAGED;
        }
        if (plugin.holograms() != null) {
            plugin.holograms().update(managed);
        }
        plugin.record(managed, AuditEvent.Action.CONVERT, null, 1, "auto-convert chunk-load");
        return ConvertCheck.CONVERTIBLE;
    }

    // ------------------------------------------------------- join inventory

    /** Outcome of one join scan, for the receipt and the log line. */
    public record JoinOutcome(int converted, int skipped) {
    }

    /**
     * Swaps vanilla and SilkSpawners spawner items in the player's inventory and
     * ender chest for Braven spawner items of the same mob.
     *
     * <p>Must run on the player's entity thread. Items are world-independent, so
     * the world blacklist does not apply here; placement still enforces the
     * world rules. Mobs without a product stay untouched and are logged. One
     * pass per join; the caller schedules it.
     */
    public JoinOutcome joinScan(Player player) {
        if (player == null || !plugin.ready()) {
            return new JoinOutcome(0, 0);
        }
        Settings settings = plugin.settings();
        if (settings == null || !settings.convertEnabled || !settings.convertJoinScan) {
            return new JoinOutcome(0, 0);
        }
        int converted = 0;
        int skipped = 0;
        for (Inventory inventory : new Inventory[] {player.getInventory(), player.getEnderChest()}) {
            if (inventory == null) {
                continue;
            }
            int size;
            try {
                size = inventory.getSize();
            } catch (RuntimeException gone) {
                continue;
            }
            for (int slot = 0; slot < size; slot++) {
                ItemStack item;
                try {
                    item = inventory.getItem(slot);
                } catch (RuntimeException gone) {
                    break;
                }
                if (item == null || item.getType() != Material.SPAWNER || item.getAmount() <= 0) {
                    continue;
                }
                // Already a Braven item: never touch it.
                if (StackService.kindOf(plugin, item) != null) {
                    continue;
                }
                String mob;
                try {
                    mob = ConvertService.mobForItem(item).orElse(null);
                } catch (RuntimeException unreadable) {
                    skipped++;
                    continue;
                }
                String product = mob == null ? null : productFor(mob);
                if (product == null) {
                    skipped++;
                    plugin.debug("convert: join scan left a spawner item of "
                            + (mob == null ? "unknown mob" : mob) + " for " + player.getName());
                    continue;
                }
                ItemStack replacement;
                try {
                    replacement = plugin.createSpawnerItem(Spawner.Kind.MOB, product, item.getAmount());
                    inventory.setItem(slot, replacement);
                } catch (RuntimeException failed) {
                    plugin.getLogger().warning("convert: could not swap a spawner item for "
                            + player.getName() + ": " + failed.getMessage());
                    skipped++;
                    continue;
                }
                converted++;
                plugin.record(null, AuditEvent.Action.CONVERT, player, item.getAmount(),
                        "join-scan " + product);
            }
        }
        if (converted > 0) {
            Messages.of(plugin).send(player, "convert.join", "count", converted);
        }
        return new JoinOutcome(converted, skipped);
    }

    // --------------------------------------------------------- admin scan

    /**
     * Starts a throttled region-by-region scan of loaded chunks.
     *
     * <p>Loaded chunks only: nothing is ever force-loaded, and coverage
     * ({@code scanned/total}) says how many dispatched chunks a region task
     * actually read. A dry run counts without registering; a live run needs
     * {@code convert.enabled}. The receipt goes to {@code sender} exactly once,
     * from whichever finishes last — the final chunk task or the timeout.
     */
    public void scan(CommandSender sender, String worldName, boolean dryRun) {
        Messages messages = Messages.of(plugin);
        if (!plugin.ready()) {
            messages.send(sender, plugin.isStorageAvailable() ? "general.not-ready" : "general.storage-down");
            return;
        }
        if (!dryRun && !plugin.settings().convertEnabled) {
            messages.send(sender, "convert.disabled");
            return;
        }
        List<World> worlds = new ArrayList<>();
        if (worldName != null) {
            World world;
            try {
                world = Bukkit.getWorld(worldName);
            } catch (RuntimeException gone) {
                world = null;
            }
            if (world == null) {
                messages.send(sender, "convert.unknown-world", "world", worldName);
                return;
            }
            worlds.add(world);
        } else {
            try {
                worlds.addAll(Bukkit.getWorlds());
            } catch (RuntimeException gone) {
                messages.send(sender, "general.not-ready");
                return;
            }
        }
        try {
            Schedulers.later(plugin, 1L, () -> beginScan(sender, worlds, dryRun));
        } catch (RuntimeException rejected) {
            messages.send(sender, "general.not-ready");
        }
    }

    /** Runs on the global thread: collects loaded chunks, then fans out per region. */
    private void beginScan(CommandSender sender, List<World> worlds, boolean dryRun) {
        Messages messages = Messages.of(plugin);
        ScanReport report = new ScanReport();
        List<ChunkRef> targets = new ArrayList<>();
        for (World world : worlds) {
            if (world == null) {
                continue;
            }
            if (plugin.settings().isBlacklisted(world.getName())) {
                report.skippedWorld(world.getName());
                continue;
            }
            Chunk[] loaded;
            try {
                loaded = world.getLoadedChunks();
            } catch (RuntimeException gone) {
                report.skippedWorld(world.getName());
                continue;
            }
            if (loaded == null) {
                continue;
            }
            report.worldScanned();
            for (Chunk chunk : loaded) {
                if (chunk != null) {
                    targets.add(new ChunkRef(world.getName(), chunk.getX(), chunk.getZ()));
                }
            }
        }
        report.setChunksTotal(targets.size());
        if (targets.isEmpty()) {
            sendReceipt(sender, report, dryRun);
            return;
        }
        messages.send(sender, "convert.scan-started", "chunks", targets.size());
        for (ChunkRef target : targets) {
            dispatchChunk(sender, report, target, dryRun);
        }
        try {
            Schedulers.later(plugin, SCAN_TIMEOUT_TICKS, () -> {
                if (report.claimReceipt()) {
                    sendReceipt(sender, report, dryRun);
                }
            });
        } catch (RuntimeException rejected) {
            // No timeout: the last chunk task still sends the receipt.
        }
    }

    private void dispatchChunk(CommandSender sender, ScanReport report, ChunkRef target, boolean dryRun) {
        World resolved;
        try {
            resolved = Bukkit.getWorld(target.world);
        } catch (RuntimeException gone) {
            resolved = null;
        }
        final World world = resolved;
        if (world == null) {
            finishChunk(sender, report, dryRun);
            return;
        }
        Location anchor = anchor(world, target.chunkX, target.chunkZ);
        try {
            Schedulers.at(plugin, anchor, () -> {
                try {
                    Chunk live = world.getChunkAt(target.chunkX, target.chunkZ, false);
                    if (live != null && live.isLoaded()) {
                        report.merge(convertChunkNow(live, dryRun));
                        report.chunkScanned();
                    }
                } catch (RuntimeException failed) {
                    plugin.debug("convert: scan of " + target.world + " "
                            + target.chunkX + "," + target.chunkZ + " failed");
                } finally {
                    finishChunk(sender, report, dryRun);
                }
            });
        } catch (RuntimeException rejected) {
            finishChunk(sender, report, dryRun);
        }
    }

    private void finishChunk(CommandSender sender, ScanReport report, boolean dryRun) {
        if (report.chunkDone() && report.claimReceipt()) {
            sendReceipt(sender, report, dryRun);
        }
    }

    private void sendReceipt(CommandSender sender, ScanReport report, boolean dryRun) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (sender instanceof Player player && !player.isOnline()) {
            return;
        }
        Messages messages = Messages.of(plugin);
        messages.send(sender, "convert.receipt-header", "mode", dryRun ? "dry run" : "live");
        sendLine(sender, "Worlds", String.valueOf(report.worldsScanned()));
        for (String world : report.skippedWorlds()) {
            sendLine(sender, "World skipped", world);
        }
        sendLine(sender, "Chunks", report.coverage());
        sendLine(sender, dryRun ? "Convertible" : "Converted", String.format(Locale.ROOT, "%,d", report.convertedCount()));
        sendLine(sender, "Already managed", String.format(Locale.ROOT, "%,d", report.alreadyManagedCount()));
        for (Map.Entry<ConvertCheck, Integer> entry : report.skippedByReason().entrySet()) {
            sendLine(sender, "Skipped (" + entry.getKey().label() + ")",
                    String.format(Locale.ROOT, "%,d", entry.getValue()));
        }
        if (!dryRun && report.skippedCount(ConvertCheck.THROTTLED) > 0) {
            messages.sendPlain(sender, "convert.rerun-hint");
        }
        // Console senders over remote panels never see their own output; mirror one
        // audit line to the log so every scan (dry or live) leaves evidence.
        StringBuilder skips = new StringBuilder();
        for (Map.Entry<ConvertCheck, Integer> entry : report.skippedByReason().entrySet()) {
            if (skips.length() > 0) {
                skips.append(',');
            }
            skips.append(entry.getKey().label()).append('=').append(entry.getValue());
        }
        plugin.getLogger().info(String.format(Locale.ROOT,
                "Convert scan (%s): worlds=%d chunks=%s %s=%d managed=%d skipped=[%s]",
                dryRun ? "dry run" : "live", report.worldsScanned(), report.coverage(),
                dryRun ? "convertible" : "converted", report.convertedCount(),
                report.alreadyManagedCount(), skips));
    }

    private void sendLine(CommandSender sender, String label, String value) {
        Messages.of(plugin).sendPlain(sender, "convert.receipt-line", "label", label, "value", value);
    }

    /** Product id for an EntityType name, or null when the mob has no product. */
    String productFor(String mob) {
        if (mob == null) {
            return null;
        }
        if (plugin.products() != null && plugin.products().mob(mob) != null) {
            return mob;
        }
        Settings settings = plugin.settings();
        return settings != null && settings.mob(mob) != null ? mob : null;
    }

    private static Location anchor(World world, int chunkX, int chunkZ) {
        // Region ownership is by chunk column; y is arbitrary but must be valid.
        int y;
        try {
            y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, 64));
        } catch (RuntimeException unusual) {
            y = 64;
        }
        return new Location(world, (chunkX << 4) + 8, y, (chunkZ << 4) + 8);
    }

    private record ChunkRef(String world, int chunkX, int chunkZ) {
    }
}
