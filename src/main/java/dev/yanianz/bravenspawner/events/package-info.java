/**
 * Timed global spawner events reconstructed from VirtualSpawner 7.2.0-rc-12.
 *
 * <p>Evidence: bundled {@code other/events.yml}; javap of
 * {@code me.serbob.commons.events.EventManager} (weighted higherEntry selection,
 * eligibility methods c/s/F, multiplicative getXPMultiplier and F, disable H), and
 * {@code me.serbob.commons.objectholders.B} (event identity, multipliers, lifetime).
 * Calendar checks use the server's local zone, inclusive same-day time bounds,
 * and inclusive month ranges that may cross December. Selection occurs before
 * calendar validation; an invalid or already-active selection is not rerolled.
 * Reload/disable clears active state silently, like the reference.
 *
 * <p>Two configuration promises are enforced explicitly: always-active can
 * replace an event as soon as it expires, and max-concurrent-events is respected
 * even with always-active enabled. The reference's scheduler/check implementation
 * does not consistently enforce those promises.
 *
 * <p>XP belongs at collection/display, not loot generation:
 * VirtualSpawner.getXp bytecode 0-18 multiplies stored base XP by the event
 * multiplier, then sellXP 0-17 multiplies that rounded value by personal XP.
 * SpawnerPlugin.getItemPriceForPlayerBigDecimal 17-80 multiplies the base value
 * by event cash and personal cash before HALF_UP rounding to whole currency.
 */
package dev.yanianz.bravenspawner.events;
