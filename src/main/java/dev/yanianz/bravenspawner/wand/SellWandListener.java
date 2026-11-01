package dev.yanianz.bravenspawner.wand;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Turns a wand click into a sale and a cursor modifier into an applied modifier.
 *
 * <p>The interact handler runs at HIGHEST so protection plugins have already had
 * their chance to veto the click, and the spawner GUI listener now ignores a wand
 * item outright (it checks {@code isWand} before cancelling), so a spawner click
 * reaches this handler as a normal, uncancelled event.
 *
 * <p>A finite-use wand spends one use only after the sale reported at least one
 * sold spawner, and the held stack is re-read before writing so a stale copy
 * cannot overwrite a wand the player swapped mid-sale.
 */
public final class SellWandListener implements Listener {

    private final BravenSpawner plugin;
    private final SellWandService service;

    public SellWandListener(BravenSpawner plugin, SellWandService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;
        // Air use is pre-cancelled by Bukkit when vanilla predicts no action.
        // For a block, a cancelled interact is exactly useInteractedBlock=DENY;
        // checking the result avoids the deprecated Event#isCancelled override.
        if (action == Action.RIGHT_CLICK_BLOCK
                && event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
        if (!service.enabled()) return;
        // Main hand only. Paper can deliver a second event for the off-hand; a
        // wand there would double-fire, consume the cooldown and report a sale
        // the player never saw. The reference wand is a main-hand tool.
        if (event.getHand() != null && event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        ItemStack item = event.getItem();
        Wand wand = service.read(item);
        if (wand == null) return;

        // Cancel before doing any work: the wand owns this click, and the spawner
        // GUI listener must not also open a window for the same event.
        event.setCancelled(true);
        Player player = event.getPlayer();

        Block clicked = event.getClickedBlock();
        if (clicked != null && plugin.manager() != null) {
            Spawner spawner = plugin.manager().at(clicked.getLocation());
            if (spawner != null) {
                if (service.sell(player, wand, spawner)) consumeUse(player, wand);
                return;
            }
        }

        if (wand.definition().withinRadius()) {
            if (service.sellArea(player, wand)) consumeUse(player, wand);
        }
        // within-radius: false and no spawner under the cursor is the reference
        // "clicked only" mode: the wand reports nothing and stays silent.
    }

    /** Charged in the click event itself, while the clicked wand is still in hand. */
    private void consumeUse(Player player, Wand used) {
        if (used.lifetime().uses() < 0) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        Wand current = service.read(held);
        if (current == null || !current.id().equals(used.id())) return;
        Wand consumed = current.consumeUse();
        ItemStack updated = service.write(consumed);
        if (updated == null) return;
        updated.setAmount(held.getAmount());
        player.getInventory().setItemInMainHand(updated);
        player.sendMessage(dev.yanianz.bravenspawner.util.Text.of(service.definition()
                .lifetimeMessage("uses-remaining", "&aUsed sell wand. %uses% uses remaining.")
                .replace("%uses%", Integer.toString(consumed.lifetime().uses()))));
    }

    /** Applies a cursor modifier to a single wand in the owner's inventory. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onModifierApply(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!service.enabled() || !(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != player.getInventory()) return;
        if (event.getClick() != org.bukkit.event.inventory.ClickType.LEFT
                && event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) return;
        ItemStack item = event.getCurrentItem();
        Wand wand = service.read(item);
        Modifier modifier = service.readModifier(event.getCursor());
        if (wand == null || modifier == null) return;
        event.setCancelled(true);
        // Never turn a stack of cloned wand identities into one modified item.
        if (item.getAmount() != 1) return;
        Wand applied = service.applyModifier(player, wand, modifier);
        if (applied == null) return;
        ItemStack updated = service.write(applied);
        if (updated == null) return;
        event.setCurrentItem(updated);
        ItemStack cursor = event.getCursor();
        if (cursor.getAmount() <= 1) event.getView().setCursor(null);
        else {
            cursor.setAmount(cursor.getAmount() - 1);
            event.getView().setCursor(cursor);
        }
    }

}
