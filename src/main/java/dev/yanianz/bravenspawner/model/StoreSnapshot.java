package dev.yanianz.bravenspawner.model;

import java.util.List;

/** Stock and escrows of one spawner, captured together under the spawner monitor. */
public record StoreSnapshot(List<StockEntry> stock, List<Escrow> escrows) {
    public StoreSnapshot {
        stock = stock == null ? List.of() : List.copyOf(stock);
        escrows = escrows == null ? List.of() : List.copyOf(escrows);
    }
}
