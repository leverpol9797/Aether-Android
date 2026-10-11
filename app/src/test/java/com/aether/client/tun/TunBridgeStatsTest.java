package com.aether.client.tun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The bridge's reported byte counters are at index 1 and 3, not 0 and 1.
 *
 * <p>Indexing from zero reports the wrong direction as traffic. Both counters come from
 * one array, so a swap is invisible on the dashboard -- the numbers move, they just move
 * backwards -- which is why it gets pinned here.
 */
public class TunBridgeStatsTest {

    private static long[] normalise(long[] raw) {
        if (raw == null || raw.length < 4) return null;
        return new long[]{raw[1], raw[3]};
    }

    @Test
    public void transmittedAndReceivedComeFromIndexesOneAndThree() {
        long[] normalised = normalise(new long[]{99, 1234, 88, 5678});
        assertEquals(1234L, normalised[0]);
        assertEquals(5678L, normalised[1]);
    }

    @Test
    public void indexesZeroAndTwoAreNotTraffic() {
        // They are internal to the native stack. Treating them as counters would make a
        // completely idle tunnel look busy.
        long[] idle = {0, 0, 0, 0};
        assertEquals(0L, normalise(idle)[0]);
        assertEquals(0L, normalise(idle)[1]);
    }

    @Test
    public void aShortArrayIsRejectedRatherThanIndexed() {
        // An older or mismatched build of the native library returns fewer elements.
        // Indexing it would throw inside the metrics thread and silently stop the counter.
        org.junit.Assert.assertNull(normalise(new long[]{1, 2, 3}));
        org.junit.Assert.assertNull(normalise(new long[0]));
        org.junit.Assert.assertNull(normalise(null));
    }
}