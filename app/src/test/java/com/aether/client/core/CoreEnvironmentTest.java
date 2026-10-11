package com.aether.client.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The environment handed to the core process.
 *
 * <p>The core reads configuration from the environment and nowhere else, so a variable
 * that is present but wrong is indistinguishable from one that was forgotten: the core
 * starts, applies its own default, and the app reports a setting as applied when it was
 * not. Several of these are <em>negative</em> variables whose absence is the whole point,
 * which is exactly the kind of rule that cannot be checked by running the app.
 */
public class CoreEnvironmentTest {

    /** A config backed by a map, so the builder can be exercised without a device. */
    private static final class FakeConfig implements ConfigSource {
        private final Map<String, String> values = new HashMap<>();

        FakeConfig set(String key, Object value) {
            values.put(key, String.valueOf(value));
            return this;
        }

        private String get(String key, String fallback) {
            String value = values.get(key);
            return value == null ? fallback : value;
        }

        private boolean flag(String key, boolean fallback) {
            return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : fallback;
        }

        private int number(String key, int fallback) {
            String value = values.get(key);
            return value == null ? fallback : Integer.parseInt(value);
        }

        @Override public String protocol() { return get(Settings.K_PROTOCOL, "wg"); }
        @Override public String scanMode() { return get(Settings.K_SCAN_MODE, "balanced"); }
        @Override public String logLevel() { return get(Settings.K_LOG_LEVEL, "info"); }
        @Override public String ipMode() { return get(Settings.K_IP_MODE, "v4"); }
        @Override public String obfuscation() { return get(Settings.K_OBFUSCATION, "balanced"); }
        @Override public String masqueTransport() { return get(Settings.K_MASQUE_TRANSPORT, "h2"); }
        @Override public String goolMode() { return get(Settings.K_GOOL_MODE, "masque"); }
        @Override public String psiphonTransport() { return get(Settings.K_PSIPHON_TRANSPORT, "auto"); }
        @Override public String psiphonRegion() { return get(Settings.K_PSIPHON_REGION, ""); }
        @Override public boolean quicV2() { return flag(Settings.K_QUIC_V2, true); }
        @Override public String peer() { return get(Settings.K_PEER, ""); }
        @Override public String ech() { return get(Settings.K_ECH, ""); }
        @Override public boolean gateway() { return flag(Settings.K_GATEWAY, false); }
        @Override public boolean noDataCheck() { return flag(Settings.K_NO_DATA_CHECK, false); }
        @Override public boolean h2Fragment() { return flag(Settings.K_H2_FRAGMENT, false); }
        @Override public String h2FragmentSize() { return get(Settings.K_H2_FRAGMENT_SIZE, "1-3"); }
        @Override public String h2FragmentDelay() { return get(Settings.K_H2_FRAGMENT_DELAY, "10-20"); }
        @Override public boolean routeSniff() { return flag(Settings.K_ROUTE_SNIFF, true); }
        @Override public int routeSniffMs() { return number(Settings.K_ROUTE_SNIFF_MS, 300); }
        @Override public int tunMtu() { return number(Settings.K_TUN_MTU, 1360); }
        @Override public int validateSecs() { return number(Settings.K_VALIDATE_SECS, 300); }
        @Override public int startupSecs() { return number(Settings.K_STARTUP_SECS, 600); }
        @Override public int reconnectSecs() { return number(Settings.K_RECONNECT_SECS, 30); }
        @Override public int wgKeepalive() { return number(Settings.K_WG_KEEPALIVE, 25); }
        @Override public boolean wgNoProfileRetry() { return flag(Settings.K_WG_NO_PROFILE_RETRY, false); }
        @Override public String mimOuterPeer() { return get(Settings.K_MIM_OUTER_PEER, ""); }
        @Override public String mimInnerPeer() { return get(Settings.K_MIM_INNER_PEER, ""); }
        @Override public String wiwOuterPeer() { return get(Settings.K_WIW_OUTER_PEER, ""); }
        @Override public String wiwInnerPeer() { return get(Settings.K_WIW_INNER_PEER, ""); }
        @Override public String upstreamProxy() { return get(Settings.K_UPSTREAM_PROXY, ""); }
        @Override public boolean quickReconnect() { return flag(Settings.K_QUICK_RECONNECT, true); }
    }

    private static Map<String, String> envOf(ConfigSource config) {
        return new CoreEnvironment(config, new File("/data/aether")).build(1819);
    }

    private static FakeConfig config() {
        return new FakeConfig();
    }

    @Test
    public void theInnerLegGoesInNotTheChainedId() {
        Map<String, String> env = envOf(config().set(Settings.K_PROTOCOL, "wg+psiphon"));
        // The core knows masque, wireguard and gool. Handing it "wg+psiphon" sends its
        // parser to the fallback and the chain silently becomes a plain MASQUE leg.
        assertEquals("wg", env.get("AETHER_PROTOCOL"));
    }

    @Test
    public void goolModeIsOmittedUnlessItIsClassic() {
        // "classic" is the only value the core accepts; anything else, including the
        // MASQUE default, must not appear.
        assertFalse(envOf(config().set(Settings.K_PROTOCOL, "gool")
                .set(Settings.K_GOOL_MODE, "masque")).containsKey("AETHER_GOOL_MODE"));
        assertEquals("classic", envOf(config().set(Settings.K_PROTOCOL, "gool")
                .set(Settings.K_GOOL_MODE, "classic")).get("AETHER_GOOL_MODE"));
    }

    @Test
    public void routeSniffIsOnlySentWhenDisabled() {
        // The core reads the variable's presence, so sending "1" to mean "on" is an
        // override of its own default and sending it at all is the bug.
        assertFalse(envOf(config().set(Settings.K_ROUTE_SNIFF, true))
                .containsKey("AETHER_ROUTE_SNIFF"));
        assertEquals("0", envOf(config().set(Settings.K_ROUTE_SNIFF, false))
                .get("AETHER_ROUTE_SNIFF"));
    }

    @Test
    public void anUpstreamProxyIsNotForwardedAlongsideAChain() {
        Map<String, String> env = envOf(config()
                .set(Settings.K_PROTOCOL, "wg+psiphon")
                .set(Settings.K_UPSTREAM_PROXY, "socks5://127.0.0.1:9050"));
        // Psiphon already occupies the upstream slot; letting the core see a second one
        // picks one of them silently.
        assertFalse(env.containsKey("AETHER_UPSTREAM"));
    }

    @Test
    public void aStandaloneUpstreamIsForwarded() {
        assertEquals("socks5://127.0.0.1:9050",
                envOf(config().set(Settings.K_UPSTREAM_PROXY, "socks5://127.0.0.1:9050"))
                        .get("AETHER_UPSTREAM"));
    }

    @Test
    public void obfuscationUsesTheNameTheCoreActuallyReads() {
        // AETHER_OBFUSCATION is silently ignored by the core; AETHER_NOIZE is what it reads.
        assertEquals("aggressive", envOf(config()
                .set(Settings.K_OBFUSCATION, "aggressive")).get("AETHER_NOIZE"));
    }

    @Test
    public void statsAreAlwaysEnabledSoTheDashboardHasSomethingToShow() {
        assertEquals("1", envOf(config()).get("AETHER_STATS"));
    }

    @Test
    public void aChainMovesTheCoreOffThePublicPortPair() {
        Map<String, String> env = envOf(config().set(Settings.K_PROTOCOL, "wg+psiphon"));
        // 1818/1819 belong to the chain's egress; two things cannot share a port.
        assertEquals(CoreEnvironment.INTERNAL_SOCKS_PREFERRED, env.get("AETHER_SOCKS"));
        assertFalse(env.containsKey("AETHER_HTTP_PROXY"));
    }

    @Test
    public void aPlainSessionKeepsTheCoreOnItsOwnPublicPorts() {
        Map<String, String> env = envOf(config().set(Settings.K_PROTOCOL, "wg"));
        assertEquals(CoreEnvironment.PUBLIC_SOCKS, env.get("AETHER_SOCKS"));
        assertEquals(CoreEnvironment.PUBLIC_HTTP, env.get("AETHER_HTTP_PROXY"));
    }

    @Test
    public void h2FragmentIsOnlySentWhenEnabled() {
        assertFalse("fragmenting off is the default; sending the flag asks for something "
                        + "that was not requested",
                envOf(config().set(Settings.K_PROTOCOL, "masque")).containsKey("AETHER_MASQUE_H2_FRAGMENT"));

        Map<String, String> on = envOf(config().set(Settings.K_PROTOCOL, "masque")
                .set(Settings.K_H2_FRAGMENT, true)
                .set(Settings.K_H2_FRAGMENT_SIZE, "1-3"));
        assertEquals("1", on.get("AETHER_MASQUE_H2_FRAGMENT"));
        assertEquals("1-3", on.get("AETHER_MASQUE_H2_FRAGMENT_SIZE"));
    }

    @Test
    public void masqueOnlySettingsAreAbsentFromAWireGuardSession() {
        // Sending MASQUE variables to a WireGuard leg is not inert: it is how a setting
        // ends up saved, reported as applied, and doing nothing.
        Map<String, String> env = envOf(config().set(Settings.K_PROTOCOL, "wg")
                .set(Settings.K_MASQUE_TRANSPORT, "h3")
                .set(Settings.K_QUIC_V2, true)
                .set(Settings.K_ECH, "cloudflare"));
        assertFalse(env.containsKey("AETHER_MASQUE_HTTP2"));
        assertFalse(env.containsKey("AETHER_QUIC_V2"));
        assertFalse(env.containsKey("AETHER_ECH"));
    }

    @Test
    public void everyProtocolProducesAnEnvironmentTheCoreWillAccept() {
        for (String protocol : Protocols.DISPLAY_ORDER) {
            Map<String, String> env = envOf(config().set(Settings.K_PROTOCOL, protocol));
            String base = env.get("AETHER_PROTOCOL");
            // Anything the core's parser does not recognise becomes masque with no
            // message, so the value sent has to be one of the three it knows.
            assertTrue(protocol + " sent an unrecognised protocol: " + base,
                    "masque".equals(base) || "wg".equals(base) || "gool".equals(base));
        }
    }
}