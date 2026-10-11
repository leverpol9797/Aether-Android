package com.aether.client.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The protocol catalog rules, which are pure functions of the stored id.
 *
 * <p>These carry the failures that are invisible until a tunnel silently runs on the
 * wrong leg: a chained entry passed whole to the core is mapped to masque without any
 * message, so nothing looks wrong until traffic never flows.
 */
public class ProtocolsTest {

    @Test
    public void baseOfChainedEntryIsItsInnerLeg() {
        assertEquals("wg", Protocols.baseProtocol("wg+psiphon"));
        assertEquals("wg", Protocols.baseProtocol("wg+tor"));
        assertEquals("gool", Protocols.baseProtocol("gool+psiphon"));
        assertEquals("gool", Protocols.baseProtocol("gool+tor"));
        assertEquals("masque", Protocols.baseProtocol("masque+psiphon"));
        assertEquals("masque", Protocols.baseProtocol("masque+tor"));
    }

    @Test
    public void plainEntriesAreTheirOwnBase() {
        for (String protocol : new String[]{"smart", "masque", "wg", "gool", "mim"}) {
            assertEquals(protocol, Protocols.baseProtocol(protocol));
        }
    }

    @Test
    public void chainIsDerivedFromTheProtocolId() {
        assertEquals(Protocols.Chain.PSIPHON, Protocols.chain("wg+psiphon"));
        assertEquals(Protocols.Chain.PSIPHON, Protocols.chain("gool+psiphon"));
        assertEquals(Protocols.Chain.TOR, Protocols.chain("masque+tor"));
        assertEquals(Protocols.Chain.NONE, Protocols.chain("wg"));
        assertEquals(Protocols.Chain.NONE, Protocols.chain("smart"));
    }

    @Test
    public void masqueTransportOnlyAppliesToMasqueAndMim() {
        assertTrue(Protocols.masqueTransportApplicable("masque"));
        assertTrue(Protocols.masqueTransportApplicable("mim"));
        // A chained entry inherits the control from its base.
        assertTrue(Protocols.masqueTransportApplicable("masque+psiphon"));
        assertFalse(Protocols.masqueTransportApplicable("wg"));
        assertFalse(Protocols.masqueTransportApplicable("gool"));
        assertFalse(Protocols.masqueTransportApplicable("smart"));
    }

    @Test
    public void goolModeOnlyAppliesToGool() {
        assertTrue(Protocols.goolModeApplicable("gool"));
        assertTrue(Protocols.goolModeApplicable("gool+psiphon"));
        assertFalse(Protocols.goolModeApplicable("masque"));
        assertFalse(Protocols.goolModeApplicable("wg"));
    }

    @Test
    public void chainedEntriesAreHiddenInProxyMode() {
        assertTrue(Protocols.visibleInProxyMode("wg"));
        assertTrue(Protocols.visibleInProxyMode("masque"));
        assertFalse(Protocols.visibleInProxyMode("wg+psiphon"));
        assertFalse(Protocols.visibleInProxyMode("masque+tor"));
    }

    @Test
    public void everyDisplayEntryIsKnown() {
        assertEquals(11, Protocols.DISPLAY_ORDER.length);
        for (String protocol : Protocols.DISPLAY_ORDER) {
            assertTrue("not in the storage set: " + protocol, Protocols.isKnown(protocol));
        }
    }

    @Test
    public void unknownValuesAreRejectedRatherThanPassedToTheCore() {
        // The core's parser maps anything unrecognised to masque without saying so.
        assertFalse(Protocols.isKnown("wireguard"));
        assertFalse(Protocols.isKnown("siphon"));
        assertFalse(Protocols.isKnown(""));
        assertFalse(Protocols.isKnown(null));
    }

    @Test
    public void stealthStoresButRendersAsVerified() {
        // v2.3.0 renamed the mode; the stored spelling has to stay loadable.
        assertEquals("Verified", Protocols.scanModeLabel("stealth"));
        assertEquals("Balanced", Protocols.scanModeLabel("balanced"));
        assertEquals("Ironclad", Protocols.scanModeLabel("ironclad"));
        assertEquals("Balanced", Protocols.scanModeLabel(null));
    }

    @Test
    public void chainsGetExtraStartupAllowance() {
        assertEquals(0, Protocols.startupAllowanceSeconds("wg"));
        assertEquals(120, Protocols.startupAllowanceSeconds("wg+psiphon"));
        assertEquals(300, Protocols.startupAllowanceSeconds("masque+tor"));
    }

    @Test
    public void everyEntryHasBothLanguageLabels() {
        for (String protocol : Protocols.DISPLAY_ORDER) {
            assertTrue(protocol + " missing English label", Protocols.label(protocol, "en") != null);
            assertTrue(protocol + " missing Persian label", Protocols.label(protocol, "fa") != null);
        }
    }
}