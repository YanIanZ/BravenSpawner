package dev.yanianz.bravenspawner.boosters;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class BoosterStateTest {
    final AtomicLong clock = new AtomicLong(1_000L);
    final BoosterState state = new BoosterState(clock::get);
    final UUID owner = UUID.randomUUID(), spawner = UUID.randomUUID(), other = UUID.randomUUID();

    @Test void typesAndSpawnerScopesRemainIndependent() {
        state.grantSpawner(Boosters.Type.XP, spawner, 2.0, 10);
        state.grantSpawner(Boosters.Type.SPEED, spawner, 3.0, 20);
        assertEquals(2.0, state.multiplier(Boosters.Type.XP, owner, spawner));
        assertEquals(3.0, state.multiplier(Boosters.Type.SPEED, owner, spawner));
        assertEquals(1.0, state.multiplier(Boosters.Type.LOOT, owner, spawner));
        assertEquals(1.0, state.multiplier(Boosters.Type.XP, owner, other));
    }

    @Test void timersExpireAtExactBoundaryWithoutExpiringAnotherType() {
        state.grantSpawner(Boosters.Type.XP, spawner, 2.0, 2);
        state.grantSpawner(Boosters.Type.LOOT, spawner, 4.0, 5);
        clock.set(2_999L);
        assertEquals(2.0, state.multiplier(Boosters.Type.XP, owner, spawner));
        clock.set(3_000L);
        assertEquals(1.0, state.multiplier(Boosters.Type.XP, owner, spawner));
        assertEquals(4.0, state.multiplier(Boosters.Type.LOOT, owner, spawner));
    }

    @Test void sameTypeUsesHighestApplicablePlayerOrSpawnerValue() {
        state.grantPlayer(Boosters.Type.LOOT, owner, 2.0, 10);
        state.grantSpawner(Boosters.Type.LOOT, spawner, 3.0, 10);
        assertEquals(3.0, state.multiplier(Boosters.Type.LOOT, owner, spawner));
        assertEquals(2.0, state.multiplier(Boosters.Type.LOOT, owner, other));
        assertEquals(1.0, state.multiplier(Boosters.Type.LOOT, other, other));
    }

    @Test void autosellIsTimedFlagAndAcceptsReferenceZeroMultiplier() {
        state.grantSpawner(Boosters.Type.AUTOSELL, spawner, 0.0, 2);
        assertTrue(state.active(Boosters.Type.AUTOSELL, owner, spawner));
        assertEquals(1.0, state.multiplier(Boosters.Type.LOOT, owner, spawner));
        clock.set(3_000L);
        assertFalse(state.active(Boosters.Type.AUTOSELL, owner, spawner));
    }

    @Test void legacyGrantsAffectLootAndXpButNeverSpeedOrCash() {
        state.grantLegacyPlayer(owner, 2.0, 10);
        assertEquals(2.0, state.multiplier(Boosters.Type.LOOT, owner, spawner));
        assertEquals(2.0, state.multiplier(Boosters.Type.XP, owner, spawner));
        assertEquals(1.0, state.multiplier(Boosters.Type.SPEED, owner, spawner));
        assertEquals(1.0, state.multiplier(Boosters.Type.CASH, owner, spawner));
    }

    @Test void carriedPersonalEffectsAreSeparateFromProductionAndDisappearWhenRemoved() {
        state.replacePersonal(owner, Map.of(Boosters.Type.XP, 1.1, Boosters.Type.CASH, 1.5));
        assertEquals(1.1, state.personalMultiplier(Boosters.Type.XP, owner));
        assertEquals(1.5, state.personalMultiplier(Boosters.Type.CASH, owner));
        assertEquals(1.0, state.multiplier(Boosters.Type.XP, owner, spawner));
        state.replacePersonal(owner, Map.of());
        assertEquals(1.0, state.personalMultiplier(Boosters.Type.XP, owner));
    }

    @Test void refusesNonfiniteNegativeAndOverflowingGrants() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 0}) {
            assertThrows(IllegalArgumentException.class, () -> state.grantSpawner(Boosters.Type.XP, spawner, value, 10));
        }
        assertThrows(IllegalArgumentException.class, () -> state.grantSpawner(Boosters.Type.XP, spawner, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> state.grantSpawner(Boosters.Type.XP, spawner, 2, Long.MAX_VALUE));
    }
}
