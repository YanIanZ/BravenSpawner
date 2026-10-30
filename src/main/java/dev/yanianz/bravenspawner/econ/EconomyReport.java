package dev.yanianz.bravenspawner.econ;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.util.Messages;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/** Admin-facing summary for {@code /bspawner doctor} and {@code /bspawner economy}. */
public final class EconomyReport {

    private final BravenSpawner plugin;
    private final Messages messages;

    public EconomyReport(BravenSpawner plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /** Extra {@code doctor.line} rows: detected currencies and the selection per action. */
    public void doctorLines(CommandSender sender) {
        CurrencyRegistry registry = plugin.currencies();
        if (registry == null) return;
        List<String> detected = new ArrayList<>();
        for (Currency currency : registry.known()) {
            if (currency.available()) detected.add(currency.id());
        }
        messages.sendPlain(sender, "doctor.line", "key", "currencies",
                "value", detected.isEmpty() ? "none available" : String.join(", ", detected));
        for (CurrencyAction action : CurrencyAction.values()) {
            Currency selected = registry.forAction(action);
            messages.sendPlain(sender, "doctor.line", "key", "currency " + action.key(),
                    "value", selected.id() + (selected.available() ? "" : " — unavailable: " + selected.unavailableReason()));
        }
    }

    /** The full {@code /bspawner economy} output. */
    public void send(CommandSender sender) {
        CurrencyRegistry registry = plugin.currencies();
        messages.send(sender, "economy.header");
        if (registry == null) {
            messages.sendPlain(sender, "economy.master", "value", "registry not started");
            return;
        }
        messages.sendPlain(sender, "economy.master", "value", registry.enabled());
        for (CurrencyAction action : CurrencyAction.values()) {
            Currency selected = registry.forAction(action);
            messages.sendPlain(sender, "economy.action", "action", action.key(), "currency", selected.id(),
                    "state", state(selected));
        }
        for (Currency currency : registry.known()) {
            messages.sendPlain(sender, "economy.currency", "id", currency.id(), "name", currency.displayName(),
                    "state", state(currency));
        }
        String shop = "local sell-prices";
        if (plugin.shops() != null) {
            var active = plugin.shops().active();
            if (active.isPresent()) shop = active.get().name();
        }
        messages.sendPlain(sender, "economy.shop", "value", shop);
    }

    private static String state(Currency currency) {
        return currency.available()
                ? "available, e.g. " + currency.format(1234.5)
                : "unavailable — " + currency.unavailableReason();
    }
}
