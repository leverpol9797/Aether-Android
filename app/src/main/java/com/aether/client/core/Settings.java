package com.aether.client.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * Every user-facing setting, with its defaults and the exact core environment variable
 * each one maps to.
 *
 * <p>Stored as discrete preferences rather than one JSON blob: the reference app persists a
 * protocol <em>index</em> into an ordered array, and a discrete key survives an app update
 * that reorders that array, which a positional index does not.
 */
public final class Settings implements ConfigSource {

    // ---- keys -------------------------------------------------------------------------
    public static final String K_LANGUAGE = "language";
    public static final String K_APPEARANCE = "appearance";
    public static final String K_PROTOCOL = "protocol";
    public static final String K_SCAN_MODE = "scanMode";
    public static final String K_LOG_LEVEL = "logLevel";
    public static final String K_IP_MODE = "ipMode";
    public static final String K_OBFUSCATION = "obfuscation";
    public static final String K_MASQUE_TRANSPORT = "masqueTransport";
    public static final String K_GOOL_MODE = "goolMode";
    public static final String K_PSIPHON_TRANSPORT = "psiphonTransport";
    public static final String K_PSIPHON_REGION = "psiphonRegion";
    public static final String K_QUIC_V2 = "quicV2";
    public static final String K_SOCKS_ADDRESS = "socksAddress";
    public static final String K_REMOTE_LISTENER = "allowRemoteListener";
    public static final String K_ROUTING_MODE = "routingMode";
    public static final String K_KILL_SWITCH = "killSwitch";
    public static final String K_TUN_MTU = "tunMtu";
    public static final String K_SPLIT_APPS = "splitApplications";
    public static final String K_ROUTE_EXCLUSIONS = "routeExclusions";
    public static final String K_DNS_LEAK = "dnsLeakProtection";
    public static final String K_IPV6_BEHAVIOR = "ipv6Behavior";
    public static final String K_WG_KEEPALIVE = "wgKeepalive";
    public static final String K_STALL_TIMEOUT = "stallTimeout";
    public static final String K_WATCHDOG = "watchdog";
    public static final String K_QUICK_RECONNECT = "quickReconnect";
    public static final String K_AUTO_CONNECT = "autoConnectAtStart";
    public static final String K_ECH = "ech";
    public static final String K_H2_FRAGMENT = "h2Fragment";
    public static final String K_H2_FRAGMENT_SIZE = "h2FragmentSize";
    public static final String K_H2_FRAGMENT_DELAY = "h2FragmentDelay";
    public static final String K_NO_DATA_CHECK = "noDataCheck";
    public static final String K_VALIDATE_SECS = "validateSecs";
    public static final String K_STARTUP_SECS = "startupSecs";
    public static final String K_RECONNECT_SECS = "reconnectSecs";
    public static final String K_GATEWAY = "gateway";
    public static final String K_UPSTREAM_PROXY = "upstreamProxy";
    public static final String K_WG_NO_PROFILE_RETRY = "wgNoProfileRetry";
    public static final String K_ROUTE_SNIFF = "routeSniff";
    public static final String K_ROUTE_SNIFF_MS = "routeSniffMs";
    public static final String K_PEER = "peer";
    public static final String K_MIM_OUTER_PEER = "mimOuterPeer";
    public static final String K_MIM_INNER_PEER = "mimInnerPeer";
    public static final String K_WIW_OUTER_PEER = "wiwOuterPeer";
    public static final String K_WIW_INNER_PEER = "wiwInnerPeer";
    public static final String K_AUTO_UPDATE = "automaticUpdates";
    public static final String K_OBFUSCATION_ENABLED = "obfuscationEnabled";

    // ---- defaults ---------------------------------------------------------------------
    public static final String D_LANGUAGE = "fa";
    public static final String D_APPEARANCE = "system";
    public static final String D_SCAN_MODE = "balanced";
    public static final String D_LOG_LEVEL = "info";
    public static final String D_IP_MODE = "v4";
    public static final String D_OBFUSCATION = "balanced";
    public static final String D_MASQUE_TRANSPORT = "h2";
    public static final String D_GOOL_MODE = "masque";
    public static final String D_PSIPHON_TRANSPORT = "auto";
    public static final String D_SOCKS_ADDRESS = "127.0.0.1:1819";
    public static final String D_ROUTING_MODE = "bypass-local";
    public static final String D_IPV6_BEHAVIOR = "tunnel";
    public static final String D_H2_FRAGMENT_SIZE = "16-32";
    public static final String D_H2_FRAGMENT_DELAY = "2-10";
    public static final String D_ECH = "";

    public static final int D_TUN_MTU = 1500;
    public static final int D_WG_KEEPALIVE = 5;
    public static final int D_STALL_TIMEOUT = 90;
    public static final int D_VALIDATE_SECS = 10;
    public static final int D_STARTUP_SECS = 30;
    public static final int D_RECONNECT_SECS = 2;
    public static final int D_ROUTE_SNIFF_MS = 400;

    public static final int MIN_MTU = 1280;
    public static final int MAX_MTU = 9000;

    // ---- option sets ------------------------------------------------------------------
    public static final String[] SCAN_MODES = {"turbo", "balanced", "thorough", "stealth", "ironclad"};
    public static final String[] IP_MODES = {"v4", "v6", "both"};
    // The core's own spellings, not invented ones: noize profiles are firewall/gfw.
    public static final String[] OBFUSCATIONS = {"firewall", "gfw", "balanced", "aggressive", "off"};
    public static final String[] MASQUE_TRANSPORTS = {"h2", "h3"};
    // "masque" emits nothing to the core -- it is the core's own default. Only
    // "classic" is sent, because only it changes the core's behaviour.
    public static final String[] GOOL_MODES = {"masque", "classic"};
    public static final String[] PSIPHON_TRANSPORTS = {"auto", "direct"};
    public static final String[] LOG_LEVELS = {"error", "warn", "info", "debug", "trace"};
    public static final String[] ROUTING_MODES = {"full", "bypass-local", "split-include", "split-exclude"};
    public static final String[] IPV6_BEHAVIORS = {"tunnel", "block"};

    /** Psiphon egress countries, in the reference's order. Empty means Automatic. */
    public static final String[] PSIPHON_COUNTRIES = {
            "AT", "BE", "CH", "CZ", "DE", "DK", "ES", "FR", "GB", "IE", "IN", "IT", "JP",
            "MD", "NL", "NO", "PL", "RO", "RS", "SE", "SG", "UA", "US"
    };

    private final SharedPreferences prefs;

    public Settings(Context context) {
        this.prefs = context.getSharedPreferences("aether_settings", Context.MODE_PRIVATE);
    }

    // ---- generic accessors ------------------------------------------------------------

    public String getString(String key, String fallback) {
        return prefs.getString(key, fallback);
    }

    public void putString(String key, @Nullable String value) {
        SharedPreferences.Editor editor = prefs.edit();
        if (value == null) editor.remove(key);
        else editor.putString(key, value);
        editor.apply();
    }

    public boolean getBool(String key, boolean fallback) {
        return prefs.getBoolean(key, fallback);
    }

    public void putBool(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    public int getInt(String key, int fallback) {
        return prefs.getInt(key, fallback);
    }

    public void putInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    // ---- named accessors -------------------------------------------------------------

    public String language() { return getString(K_LANGUAGE, D_LANGUAGE); }
    public String appearance() { return getString(K_APPEARANCE, D_APPEARANCE); }

    /**
     * The stored protocol id, sanitised on read. A value written by a build that knew a
     * protocol this one does not is replaced rather than passed to the core, whose parser
     * would silently fall back to masque.
     */
    public String protocol() {
        String stored = getString(K_PROTOCOL, Protocols.DEFAULT_PROTOCOL);
        return Protocols.isKnown(stored) ? stored : Protocols.DEFAULT_PROTOCOL;
    }

    public String scanMode() { return getString(K_SCAN_MODE, D_SCAN_MODE); }
    public String logLevel() { return getString(K_LOG_LEVEL, D_LOG_LEVEL); }
    public String ipMode() { return getString(K_IP_MODE, D_IP_MODE); }
    public String obfuscation() { return getString(K_OBFUSCATION, D_OBFUSCATION); }
    public String masqueTransport() { return getString(K_MASQUE_TRANSPORT, D_MASQUE_TRANSPORT); }
    public String goolMode() { return getString(K_GOOL_MODE, D_GOOL_MODE); }
    public String psiphonTransport() { return getString(K_PSIPHON_TRANSPORT, D_PSIPHON_TRANSPORT); }
    public String psiphonRegion() { return getString(K_PSIPHON_REGION, ""); }
    public boolean quicV2() { return getBool(K_QUIC_V2, true); }
    public String socksAddress() { return getString(K_SOCKS_ADDRESS, D_SOCKS_ADDRESS); }
    public boolean remoteListener() { return getBool(K_REMOTE_LISTENER, false); }
    public String routingMode() { return getString(K_ROUTING_MODE, D_ROUTING_MODE); }
    public boolean killSwitch() { return getBool(K_KILL_SWITCH, false); }
    public int tunMtu() { return getInt(K_TUN_MTU, D_TUN_MTU); }
    public String splitApplications() { return getString(K_SPLIT_APPS, ""); }
    public String routeExclusions() { return getString(K_ROUTE_EXCLUSIONS, ""); }
    public boolean dnsLeakProtection() { return getBool(K_DNS_LEAK, true); }
    public String ipv6Behavior() { return getString(K_IPV6_BEHAVIOR, D_IPV6_BEHAVIOR); }
    public int wgKeepalive() { return getInt(K_WG_KEEPALIVE, D_WG_KEEPALIVE); }
    public int stallTimeout() { return getInt(K_STALL_TIMEOUT, D_STALL_TIMEOUT); }
    public boolean watchdog() { return getBool(K_WATCHDOG, true); }
    public boolean quickReconnect() { return getBool(K_QUICK_RECONNECT, true); }
    public boolean autoConnect() { return getBool(K_AUTO_CONNECT, false); }
    public String ech() { return getString(K_ECH, ""); }
    public boolean h2Fragment() { return getBool(K_H2_FRAGMENT, false); }
    public String h2FragmentSize() { return getString(K_H2_FRAGMENT_SIZE, D_H2_FRAGMENT_SIZE); }
    public String h2FragmentDelay() { return getString(K_H2_FRAGMENT_DELAY, D_H2_FRAGMENT_DELAY); }
    public boolean noDataCheck() { return getBool(K_NO_DATA_CHECK, false); }
    public int validateSecs() { return getInt(K_VALIDATE_SECS, D_VALIDATE_SECS); }
    public int startupSecs() { return getInt(K_STARTUP_SECS, D_STARTUP_SECS); }
    public int reconnectSecs() { return getInt(K_RECONNECT_SECS, D_RECONNECT_SECS); }
    public boolean gateway() { return getBool(K_GATEWAY, false); }
    public String upstreamProxy() { return getString(K_UPSTREAM_PROXY, ""); }
    public boolean wgNoProfileRetry() { return getBool(K_WG_NO_PROFILE_RETRY, false); }
    public boolean routeSniff() { return getBool(K_ROUTE_SNIFF, true); }
    public int routeSniffMs() { return getInt(K_ROUTE_SNIFF_MS, D_ROUTE_SNIFF_MS); }
    public String peer() { return getString(K_PEER, ""); }
    public String mimOuterPeer() { return getString(K_MIM_OUTER_PEER, ""); }
    public String mimInnerPeer() { return getString(K_MIM_INNER_PEER, ""); }
    public String wiwOuterPeer() { return getString(K_WIW_OUTER_PEER, ""); }
    public String wiwInnerPeer() { return getString(K_WIW_INNER_PEER, ""); }
    public boolean automaticUpdates() { return getBool(K_AUTO_UPDATE, true); }

    /** True when the chain's split tunnelling / DNS knobs change anything at all. */
    public boolean hasAdvanced() {
        return killSwitch() || dnsLeakProtection() || routeSniff() || h2Fragment()
                || watchdog() || noDataCheck() || !upstreamProxy().isEmpty();
    }

    /** Restores every key this class owns to its shipped default. */
    public void resetToDefaults() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.apply();
    }
}