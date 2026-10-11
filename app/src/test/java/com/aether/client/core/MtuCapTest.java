package com.aether.client.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The MTU cap.
 *
 * <p>A configured 1500 is not a usable interface MTU: each transport adds its own
 * encapsulation, so the packets the interface emits are larger than the path can carry and
 * get fragmented or dropped. Getting this wrong produces a tunnel that establishes and
 * then carries nothing.
 */
public class MtuCapTest {

    @Test
    public void nestedWireGuardIsCappedLowest() {
        // gool and smart stack two layers of WireGuard headers.
        assertEquals(1360, CoreEnvironment.capMtu("gool", 1500));
        assertEquals(1360, CoreEnvironment.capMtu("smart", 1500));
    }

    @Test
    public void wireGuardAllowsMoreHeadroomThanMasque() {
        assertEquals(1420, CoreEnvironment.capMtu("wg", 1500));
        assertEquals(1400, CoreEnvironment.capMtu("masque", 1500));
        assertEquals(1400, CoreEnvironment.capMtu("mim", 1500));
    }

    @Test
    public void aConfiguredValueUnderTheCapIsKept() {
        assertEquals(1300, CoreEnvironment.capMtu("wg", 1300));
        assertEquals(1280, CoreEnvironment.capMtu("gool", 1280));
    }

    @Test
    public void neverGoesBelowTheIPv6Minimum() {
        // Below 1280 an IPv6 packet cannot be emitted at all, so a lower configured value
        // yields a tunnel that can never carry v6 rather than a smaller one.
        assertEquals(1280, CoreEnvironment.capMtu("gool", 900));
        assertEquals(1280, CoreEnvironment.capMtu("masque", 1280));
    }

    @Test
    public void everyProtocolHasACap() {
        for (String protocol : Protocols.DISPLAY_ORDER) {
            int capped = CoreEnvironment.capMtu(Protocols.baseProtocol(protocol), 1500);
            assertTrue(protocol + " produced an unusable MTU of " + capped,
                    capped >= Settings.MIN_MTU && capped <= 1500);
        }
    }
}