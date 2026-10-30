package dev.yanianz.bravenspawner.wand;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WandParticlesTest {

    @Test void gradualRingGrowsFromStartToMax() {
        double[] radii = WandParticles.radii(0.1, 0.1, 1.0, true);
        assertEquals(10, radii.length);
        assertEquals(0.1, radii[0], 1e-9);
        assertEquals(1.0, radii[radii.length - 1], 1e-9);
    }

    @Test void nonGradualRingIsOneStepAtMaxRadius() {
        assertArrayEquals(new double[] {1.5}, WandParticles.radii(0.1, 0.1, 1.5, false), 1e-9);
    }

    @Test void stepsAndPointsAreBounded() {
        double[] capped = WandParticles.radii(0.0, 0.0, 8.0, true);
        assertEquals(WandParticles.MAX_STEPS, capped.length);
        assertEquals(8.0, capped[capped.length - 1], 1e-9, "a capped animation still reaches max-radius");
        assertEquals(6, WandParticles.points(0.1, 2));
        assertEquals(13, WandParticles.points(1.0, 2));
        assertEquals(WandParticles.MAX_POINTS, WandParticles.points(8.0, 16));
    }
}
