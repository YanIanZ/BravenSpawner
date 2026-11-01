package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class WithdrawalsTest {

    /** A mock stack whose amount is real state and whose clone is another such stack. */
    private static ItemStack stack(int amount, int max) {
        ItemStack item = mock(ItemStack.class);
        AtomicInteger value = new AtomicInteger(amount);
        when(item.getAmount()).thenAnswer(call -> value.get());
        doAnswer(call -> { value.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
        when(item.getMaxStackSize()).thenReturn(max);
        when(item.clone()).thenAnswer(call -> stack(value.get(), max));
        return item;
    }

    @Test void oversizedStacksAreSplitByTheirOwnMaximum() {
        List<ItemStack> overflow = new ArrayList<>();
        List<ItemStack> parts = Withdrawals.split(List.of(stack(500, 16)), Withdrawals.MAX_PARTS, overflow);
        assertTrue(parts.stream().allMatch(part -> part.getAmount() <= 16));
        assertEquals(32, parts.size());
        assertEquals(500, parts.stream().mapToInt(ItemStack::getAmount).sum());
        assertTrue(overflow.isEmpty());
    }

    @Test void stacksBeyondAnInventoryAreKeptAsOneOverflowEntry() {
        List<ItemStack> overflow = new ArrayList<>();
        List<ItemStack> parts = Withdrawals.split(List.of(stack(100_000, 64)), 3, overflow);
        assertEquals(3, parts.size());
        assertEquals(1, overflow.size());
        assertEquals(100_000 - 192, overflow.getFirst().getAmount());
    }

    @Test void leftoversAndOverflowReturnToStorage() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack rejected = stack(10, 64);
        when(inventory.addItem(any(ItemStack[].class))).thenAnswer(call -> {
            Object[] offered = call.getArguments();
            for (Object part : offered) assertTrue(((ItemStack) part).getAmount() <= 64);
            HashMap<Integer, ItemStack> left = new HashMap<>();
            left.put(0, rejected);
            return left;
        });
        Spawner spawner = mock(Spawner.class);
        Withdrawals.Result result = Withdrawals.deliver(player, spawner, List.of(stack(200, 64)));
        assertEquals(190, result.moved());
        assertEquals(10, result.returned());
        verify(spawner).restore(rejected, 10L);
    }

    /** A mock item of one identity (its Material), with real amount state. */
    private static ItemStack identity(org.bukkit.Material type, int amount) {
        ItemStack item = mock(ItemStack.class);
        AtomicInteger value = new AtomicInteger(amount);
        when(item.getType()).thenReturn(type);
        when(item.getAmount()).thenAnswer(call -> value.get());
        doAnswer(call -> { value.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.clone()).thenAnswer(call -> identity(type, value.get()));
        when(item.isSimilar(any())).thenAnswer(call -> call.getArgument(0) instanceof ItemStack other && other.getType() == type);
        return item;
    }

    private static Spawner model() {
        return new Spawner(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "world", 0, 0, 0,
                Spawner.Kind.MOB, "ZOMBIE", 1, 0, true);
    }

    @Test void takeAllMovesOneInventoryAndReportsWhatStaysStored() {
        org.bukkit.Material rod = mock(org.bukkit.Material.class);
        when(rod.isAir()).thenReturn(false);
        Spawner spawner = model();
        spawner.deposit(identity(rod, 1), 10_000_000L, dev.yanianz.bravenspawner.model.StockLimits.UNLIMITED);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());

        Withdrawals.Result result = Withdrawals.takeAll(player, spawner);
        long batch = (long) Withdrawals.MAX_PARTS * 64;
        assertEquals(batch, result.moved());
        assertEquals(10_000_000L - batch, result.returned());
        assertEquals(10_000_000L - batch, spawner.storedCount());
    }

    @Test void takeAllRestoresWhatTheInventoryRejects() {
        org.bukkit.Material rod = mock(org.bukkit.Material.class);
        when(rod.isAir()).thenReturn(false);
        Spawner spawner = model();
        spawner.deposit(identity(rod, 1), 100L, dev.yanianz.bravenspawner.model.StockLimits.UNLIMITED);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        HashMap<Integer, ItemStack> rejected = new HashMap<>();
        rejected.put(0, identity(rod, 30));
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(rejected);

        Withdrawals.Result result = Withdrawals.takeAll(player, spawner);
        assertEquals(70, result.moved());
        assertEquals(30, result.returned());
        assertEquals(30, spawner.storedCount(), "conservation: rejected units are back in storage");
    }
}
