package dev.yanianz.bravenspawner.econ;

import java.util.Locale;

/** The places money changes hands; each reads {@code economy.currencies.<key>}. */
public enum CurrencyAction {
    SELL("sell"),
    BUY_STACK("buy-stack"),
    UPGRADE("upgrade"),
    SPAWNER_BUY("spawner-buy");

    private final String key;

    CurrencyAction(String key) {
        this.key = key;
    }

    /** Config key under {@code economy.currencies}. */
    public String key() {
        return key;
    }

    public static CurrencyAction fromKey(String raw) {
        if (raw == null) return null;
        String wanted = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (CurrencyAction action : values()) {
            if (action.key.equals(wanted)) return action;
        }
        return null;
    }
}
