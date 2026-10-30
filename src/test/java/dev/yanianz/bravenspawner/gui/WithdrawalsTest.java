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
        verify(spawner).storeVirtual(rejected, 0);
    }
}
