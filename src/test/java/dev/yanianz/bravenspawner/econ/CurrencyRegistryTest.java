package dev.yanianz.bravenspawner.econ;

import dev.yanianz.bravenspawner.econ.currency.UnavailableCurrency;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CurrencyRegistryTest {

    YamlConfiguration config;
    CurrencyRegistry registry;
    FakeCurrency vault;
    FakeCurrency points;
    final List<String> warnings = new ArrayList<>();

    @BeforeEach void setup() {
        config = new YamlConfiguration();
        config.set("economy.enabled", true);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        registry = new CurrencyRegistry(logger, new CurrencyFormats(() -> config), null);
        vault = new FakeCurrency("VAULT");
        points = new FakeCurrency("PLAYERPOINTS");
        registry.register(vault).register(points);
        registry.registerPrefix("coinsengine", id -> new FakeCurrency("coinsengine:" + id));
    }

    @Test void lookupIsCaseInsensitiveAndKnowsAliases() {
        assertSame(vault, registry.lookup("vault").orElseThrow());
        assertSame(vault, registry.lookup(" Vault ").orElseThrow());
        assertSame(vault, registry.lookup("money").orElseThrow());
        assertSame(points, registry.lookup("player-points").orElseThrow());
        assertTrue(registry.lookup("nope").isEmpty());
        assertTrue(registry.lookup("").isEmpty());
    }

    @Test void prefixedIdsAreCreatedOnceAndShared() {
        Currency gems = registry.lookup("CoinsEngine:Gems").orElseThrow();
        assertEquals("coinsengine:gems", gems.id());
        assertSame(gems, registry.lookup("coinsengine:gems").orElseThrow());
        assertSame(gems, registry.lookup("excellenteconomy:gems").orElseThrow());
        assertTrue(registry.lookup("coinsengine:").isEmpty());
        assertTrue(registry.lookup("unknownprefix:gems").isEmpty());
        assertTrue(registry.known().contains(gems));
    }

    @Test void eachActionReadsItsOwnKeyAndDefaultsToVault() {
        config.set("economy.currencies.sell", "playerpoints");
        assertSame(points, registry.forAction(CurrencyAction.SELL));
        assertSame(vault, registry.forAction(CurrencyAction.UPGRADE));
        assertEquals("VAULT", registry.configuredId(CurrencyAction.BUY_STACK));
    }

    @Test void hooksDefaultAppliesWhenActionIsUnset() {
        CurrencyRegistry withDefault = new CurrencyRegistry(Logger.getAnonymousLogger(),
                new CurrencyFormats(() -> config), () -> "PlayerPoints");
        withDefault.register(points);
        assertSame(points, withDefault.forAction(CurrencyAction.UPGRADE));
    }

    @Test void unknownCurrencyIsNeverFreeAndIsLoggedOnce() {
        config.set("economy.currencies.buy-stack", "Doubloons");
        Currency selected = registry.forAction(CurrencyAction.BUY_STACK);
        registry.forAction(CurrencyAction.BUY_STACK);
        assertInstanceOf(UnavailableCurrency.class, selected);
        assertFalse(selected.available());
        OfflinePlayer player = mock(OfflinePlayer.class);
        assertFalse(selected.withdraw(player, 10).success());
        assertFalse(selected.deposit(player, 10).success());
        assertEquals(1, warnings.stream().filter(w -> w.contains("Doubloons")).count());
    }

    @Test void superMobCoinsExplainsWhyItIsUnsupported() {
        config.set("economy.currencies.sell", "SuperMobCoins");
        Currency selected = registry.forAction(CurrencyAction.SELL);
        assertFalse(selected.available());
        assertTrue(selected.unavailableReason().contains("no public API"));
    }

    @Test void masterSwitchOffMakesEveryActionUnavailable() {
        config.set("economy.enabled", false);
        Currency selected = registry.forAction(CurrencyAction.SELL);
        assertFalse(selected.available());
        assertTrue(selected.unavailableReason().contains("economy.enabled"));
    }

    @Test void unavailableProviderIsReturnedButReportedOnceUntilItRecovers() {
        vault.available = false;
        Currency first = registry.forAction(CurrencyAction.SELL);
        registry.forAction(CurrencyAction.SELL);
        assertSame(vault, first);
        assertFalse(first.available());
        assertEquals(1, warnings.stream().filter(w -> w.contains("unavailable")).count());
        vault.available = true;
        registry.refresh();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("now available")));
    }

    @Test void formatsAreReadPerCurrencyCaseInsensitively() {
        config.set("economy.format.vault.symbol", "$");
        CurrencyFormats formats = new CurrencyFormats(() -> config);
        CurrencyFormat format = formats.resolve("VAULT", CurrencyFormat.of("", "", -1));
        assertEquals("$", format.symbol());
        assertEquals("$1.50K", format.format(1500, formats.money()));
    }
}
