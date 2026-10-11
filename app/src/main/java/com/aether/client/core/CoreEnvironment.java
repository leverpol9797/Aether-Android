package com.aether.client.core;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates stored settings into the environment the core binary is started with.
 *
 * <p>Two rules here are easy to get wrong and both fail silently at the core:
 *
 * <ul>
 *   <li>Several variables are <em>negative</em>: they are emitted only when they differ
 *       from the core's own default. Emitting {@code AETHER_ROUTE_SNIFF=1} or
 *       {@code AETHER_GOOL_MODE=masque} is not harmless tidying -- the core treats those
 *       as explicit overrides and the reference builds assert their <em>absence</em>.</li>
 *   <li>{@code AETHER_HTTP_PROXY} is deliberately omitted for a chained protocol: on a
 *       chain this process owns 1818 and relays it, and asking the core to bind the same
 *       port starts a fight neither side wins.</li>
 * </ul>
 */
public final class CoreEnvironment {

    /** The core's own listener contract. */
    public static final String PUBLIC_HTTP = "127.0.0.1:1818";
    public static final String PUBLIC_SOCKS = "127.0.0.1:1819";
    public static final String INTERNAL_SOCKS_PREFERRED = "127.0.0.1:18193";
    public static final String PSIPHON_SOCKS = "127.0.0.1:1822";
    public static final String PSIPHON_HTTP = "127.0.0.1:1824";
    public static final String TOR_SOCKS = "127.0.0.1:1821";
    public static final String TOR_HTTP = "127.0.0.1:1825";

    private final ConfigSource settings;
    private final File dataDir;
    private final File cacheDir;

    /**
     * @param dataDir  where the core keeps its identity and config files
     * @param cacheDir where the core keeps scratch files -- must already exist. Derived
     *                 from dataDir rather than guessed: {@code dataDir.getParentFile()}
     *                 is the files dir, which has no "cache" child, and a TMPDIR that
     *                 does not exist makes the core exit on its first scratch file.
     */
    public CoreEnvironment(ConfigSource settings, File dataDir, File cacheDir) {
        this.settings = settings;
        this.dataDir = dataDir;
        this.cacheDir = cacheDir;
    }

    /**
     * Builds the environment for one connect attempt.
     *
     * @param socksPort the port this process will relay to the user; on a chain the core
     *                  is moved onto the internal underlay instead and this is where the
     *                  chain's final egress will be published
     */
    public Map<String, String> build(int socksPort) {
        String protocol = settings.protocol();
        String base = Protocols.baseProtocol(protocol);
        Protocols.Chain chain = Protocols.chain(protocol);
        boolean masqueBase = "masque".equals(base) || "mim".equals(base);

        Map<String, String> env = new LinkedHashMap<>();

        // The core always runs the BASE transport. On a chained entry the outer half is
        // a helper this process runs, and the core's parser maps anything it does not
        // recognise to masque without saying so -- which looks like a tunnel that came
        // up on the wrong leg with nothing visibly wrong until traffic never flows.
        env.put("AETHER_PROTOCOL", base);
        env.put("AETHER_SCAN", settings.scanMode());
        env.put("AETHER_LOG_LEVEL", settings.logLevel());
        env.put("AETHER_IP", settings.ipMode());
        // The core's own spelling. AETHER_OBFUSCATION is silently ignored.
        env.put("AETHER_NOIZE", settings.obfuscation());
        env.put("AETHER_QUICK_RECONNECT", settings.quickReconnect() ? "1" : "0");

        // A plain session gets the core's own 1818/1819 pair. A chain moves the core onto
        // the internal underlay, because 1819 is where the chain's egress listener has to
        // live and two things cannot share a port.
        if (chain == Protocols.Chain.NONE) {
            env.put("AETHER_SOCKS", "127.0.0.1:" + socksPort);
            env.put("AETHER_HTTP_PROXY", PUBLIC_HTTP);
        } else {
            env.put("AETHER_SOCKS", INTERNAL_SOCKS_PREFERRED);
        }

        // One identity file per protocol. Without the per-protocol override every
        // transport resolves to the same base file, and gool -- which stacks an outer and
        // an inner tunnel -- ends up sharing a registered device with plain WireGuard: two
        // tunnels on one identity, with the peer's cached endpoint read back for the wrong
        // one.
        env.put("AETHER_CONFIG", new File(dataDir, "aether.toml").getAbsolutePath());
        env.put("AETHER_WG_CONFIG", new File(dataDir, base + "-wg.toml").getAbsolutePath());
        env.put("AETHER_MASQUE_CONFIG", new File(dataDir, base + "-masque.toml").getAbsolutePath());

        // A custom endpoint is named differently per family: the masque core reads
        // AETHER_PEER, everything else reads AETHER_WG_PEER.
        String peer = settings.peer().trim();
        if (!peer.isEmpty()) {
            env.put(masqueBase ? "AETHER_PEER" : "AETHER_WG_PEER", peer);
        }

        if (masqueBase) {
            env.put("AETHER_MASQUE_HTTP2", "h2".equals(settings.masqueTransport()) ? "1" : "0");
            env.put("AETHER_QUIC_V2", settings.quicV2() ? "1" : "0");
            putIfSet(env, "AETHER_ECH", settings.ech());
            if (settings.h2Fragment()) {
                env.put("AETHER_MASQUE_H2_FRAGMENT", "1");
                env.put("AETHER_MASQUE_H2_FRAGMENT_SIZE", settings.h2FragmentSize());
                env.put("AETHER_MASQUE_H2_FRAGMENT_DELAY", settings.h2FragmentDelay());
            }
            if (settings.noDataCheck()) env.put("AETHER_MASQUE_NO_DATA_CHECK", "1");
        } else {
            // Not a "both families" list: this is the else branch of the masque check, so
            // a WireGuard or gool session carries the keepalive and a masque one does not.
            env.put("AETHER_WG_KEEPALIVE", Integer.toString(settings.wgKeepalive()));
            if (settings.noDataCheck()) env.put("AETHER_WG_NO_DATA_CHECK", "1");
        }

        if ("mim".equals(base)) {
            putIfSet(env, "AETHER_MIM_OUTER_PEER", settings.mimOuterPeer());
            putIfSet(env, "AETHER_MIM_INNER_PEER", settings.mimInnerPeer());
        }

        if ("gool".equals(base)) {
            // "masque" is the core's own default and emits nothing; only "classic"
            // changes its behaviour, and naming the default as an override would pin it
            // against a future core that changes the default.
            if ("classic".equals(settings.goolMode())) {
                env.put("AETHER_GOOL_MODE", "classic");
            }
            putIfSet(env, "AETHER_WIW_OUTER_PEER", settings.wiwOuterPeer());
            putIfSet(env, "AETHER_WIW_INNER_PEER", settings.wiwInnerPeer());
        }

        // Always present in the reference build; the core reads its own defaults for
        // these when they are absent, but sending them keeps both sides in step.
        env.put("AETHER_MASQUE_VALIDATE_SECS", Integer.toString(settings.validateSecs()));
        env.put("AETHER_WG_VALIDATE_SECS", Integer.toString(settings.validateSecs()));
        env.put("AETHER_MASQUE_STARTUP_SECS", Integer.toString(settings.startupSecs()));
        env.put("AETHER_RECONNECT_SECS", Integer.toString(settings.reconnectSecs()));
        env.put("AETHER_WG_RECONNECT_SECS", Integer.toString(settings.reconnectSecs()));
        env.put("AETHER_ROUTE_SNIFF_MS", Integer.toString(settings.routeSniffMs()));

        // Negative form: only sent when sniffing is OFF, because on is the core default.
        if (!settings.routeSniff()) env.put("AETHER_ROUTE_SNIFF", "0");

        if (settings.wgNoProfileRetry()) env.put("AETHER_WG_NO_PROFILE_RETRY", "1");
        if (settings.gateway()) env.put("AETHER_GATEWAY", "1");

        switch (chain) {
            case PSIPHON:
                env.put("AETHER_PSIPHON", "chain");
                env.put("AETHER_PSIPHON_BIND", PSIPHON_SOCKS);
                env.put("AETHER_PSIPHON_HTTP", PSIPHON_HTTP);
                env.put("AETHER_PSIPHON_MODE", settings.psiphonTransport());
                String region = settings.psiphonRegion().trim();
                // The core compares against an uppercased code.
                if (!region.isEmpty()) env.put("AETHER_PSIPHON_REGION", region.toUpperCase(java.util.Locale.US));
                env.put("AETHER_PSIPHON_DIR", new File(dataDir, "psiphon").getAbsolutePath());
                break;
            case TOR:
                env.put("AETHER_TOR", "chain");
                env.put("AETHER_TOR_BIND", TOR_SOCKS);
                env.put("AETHER_TOR_HTTP", TOR_HTTP);
                // Bridges are pinned off: the Android core build has no bridge line to
                // parse, and a setting that cannot be honoured must not be offered.
                env.put("AETHER_TOR_BRIDGES", "off");
                env.put("AETHER_TOR_DIR", new File(dataDir, "tor").getAbsolutePath());
                break;
            default:
                break;
        }

        // Cumulative byte counters on the core's own listeners, emitted every 5s. This
        // is the authoritative traffic source and it works in proxy mode too, where the
        // TUN counters would read zero.
        env.put("AETHER_STATS", "1");
        env.put("AETHER_STATS_SECS", "5");

        String upstream = settings.upstreamProxy().trim();
        if (!upstream.isEmpty() && chain == Protocols.Chain.NONE) {
            // Deliberately dropped for a chain: Psiphon or Tor already occupies the
            // upstream slot, and the core silently picks one of the two. The UI refuses
            // the combination too; this is the same rule enforced where it matters --
            // a setting that is only refused in the UI is refused by whichever layer the
            // user happened to reach it from.
            env.put("AETHER_UPSTREAM", upstream);
        }

        // The core writes scratch files relative to TMPDIR, and it does so early -- during
        // startup, before anything the app can observe. A TMPDIR that does not exist is
        // therefore not a degraded session but a core that exits immediately. The
        // platform cache dir always exists; the parent of dataDir does not contain a
        // "cache" child, which is why it is passed in rather than derived.
        env.put("TMPDIR", cacheDir.getAbsolutePath());

        return env;
    }

    private static void putIfSet(Map<String, String> env, String key, String value) {
        if (value != null && !value.trim().isEmpty()) env.put(key, value.trim());
    }

    /**
     * The MTU the interface is actually built with.
     *
     * <p>A configured 1500 is not usable: each transport wraps its own encapsulation, so
     * the interface would emit packets larger than the path can carry and they would
     * fragment or be dropped. Capping per base is what makes the tunnel usable at all.
     */
    public int effectiveMtu() {
        return capMtu(Protocols.baseProtocol(settings.protocol()), settings.tunMtu());
    }

    /** Caps a configured MTU to what the base transport can carry. Floor 1280. */
    public static int capMtu(String baseProtocol, int configured) {
        int cap;
        switch (baseProtocol == null ? "" : baseProtocol) {
            case "gool":
            case "smart":
                // Nested WireGuard: two layers of headers.
                cap = 1360;
                break;
            case "wg":
                cap = 1420;
                break;
            default:
                // masque and mim.
                cap = 1400;
                break;
        }
        // Below 1280 an IPv6 packet cannot be emitted at all, so a lower configured value
        // produces a tunnel that can never carry v6 rather than a smaller one.
        return Math.max(Settings.MIN_MTU, Math.min(configured, cap));
    }
}