package dev.yanianz.bravenspawner.boosters;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Item activation is validated against the server's current definition and ownership policy. */
final class BoosterListener implements Listener {
    private final BravenSpawner plugin;
    private final Boosters boosters;
    BoosterListener(BravenSpawner plugin, Boosters boosters) { this.plugin = plugin; this.boosters = boosters; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.useInteractedBlock() == Event.Result.DENY) return;
        BoosterDefinition definition = boosters.readItem(event.getItem());
        if (definition == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (definition.scope() == Boosters.Scope.PERSONAL) {
            boosters.refreshPersonal(player);
            player.sendMessage(Text.of("&aCarry this personal booster in your inventory to receive its effect."));
            return;
        }
        if (!plugin.ready()) return;
        var block = event.getClickedBlock();
        Spawner spawner = block == null || plugin.manager() == null ? null : plugin.manager().at(block.getLocation());
        if (spawner == null) {
            player.sendMessage(Text.of("&eRight-click one of your spawners to activate this booster."));
            return;
        }
        if (!plugin.mayUse(player, spawner)) {
            player.sendMessage(Text.of("&cYou cannot activate a booster on this spawner."));
            return;
        }
        var restrictions = plugin.restrictions();
        if (restrictions != null && !restrictions.canOpen(player, spawner)) return;
        var protection = plugin.protection();
        if (protection != null && !protection.canUse(player, spawner.blockLocation())) {
            player.sendMessage(Text.of("&cProtection prevents booster activation here."));
            return;
        }
        var hand = player.getInventory().getItemInMainHand();
        if (!definition.equals(boosters.readItem(hand)) || hand.getAmount() < 1) return;
        if (!boosters.grantSpawner(definition.type(), spawner.id(), definition.multiplier(), definition.seconds())) {
            player.sendMessage(Text.of("&cAn equal or stronger booster of this type is already active on this spawner."));
            return;
        }
        // The event and inventory are on this entity's thread; consume exactly one.
        hand.setAmount(hand.getAmount() - 1);
        player.getInventory().setItemInMainHand(hand);
        plugin.markDirty(spawner);
        player.sendMessage(Text.of("&aActivated " + definition.type().name().toLowerCase(java.util.Locale.ROOT)
                + " booster for " + definition.duration() + "."));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) { if (event.getWhoClicked() instanceof Player player) boosters.refreshLater(player); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) { if (event.getWhoClicked() instanceof Player player) boosters.refreshLater(player); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) { if (event.getEntity() instanceof Player player) boosters.refreshLater(player); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) { boosters.refreshLater(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { boosters.refreshLater(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { boosters.forgetPersonal(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) { boosters.forgetPersonal(event.getEntity().getUniqueId()); }
}
