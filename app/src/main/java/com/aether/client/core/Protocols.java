package com.aether.client.core;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * The 11-entry protocol catalog and the rules that decide which controls a given
 * entry enables.
 *
 * <p>The ids and ordering mirror the core's own catalog exactly. That matters more than
 * it looks: the stored preference is the protocol <em>id</em>, and the core is handed
 * the <em>base</em> leg of that id. A chained entry like {@code wg+psiphon} runs WireGuard
 * in the core and Psiphon in this process, so the core must never be told "wg+psiphon" --
 * its parser quietly maps anything it does not know to masque, and the tunnel then comes
 * up on the wrong leg with nothing visibly wrong until traffic never flows.
 */
public final class Protocols {

    private Protocols() {}

    /** Display order: Smart Connect first, then the plain bases, then the chained entries. */
    public static final String[] DISPLAY_ORDER = {
            "smart", "masque", "wg", "gool", "mim",
            "masque+psiphon", "wg+psiphon", "gool+psiphon",
            "masque+tor", "wg+tor", "gool+tor"
    };

    /** Storage order. The persisted value is an index into this array on the core side. */
    public static final String[] STORAGE_ORDER = {
            "masque", "wg", "gool", "smart", "mim",
            "wg+psiphon", "gool+psiphon", "masque+psiphon",
            "wg+tor", "gool+tor", "masque+tor"
    };

    public static final String DEFAULT_PROTOCOL = "wg+psiphon";

    public static boolean isKnown(String protocol) {
        for (String candidate : STORAGE_ORDER) {
            if (candidate.equals(protocol)) return true;
        }
        return false;
    }

    /**
     * The transport the core actually runs for a stored entry. A chained entry names its
     * inner leg here because the outer half lives in this process, not in the core.
     */
    public static String baseProtocol(String protocol) {
        if (protocol == null) return "masque";
        switch (protocol) {
            case "wg+psiphon":
            case "wg+tor":
                return "wg";
            case "gool+psiphon":
            case "gool+tor":
                return "gool";
            case "masque+psiphon":
            case "masque+tor":
                return "masque";
            // "smart" is a UI concept: it means "let the scan pick", and the core
            // resolves it itself, so it passes through unchanged.
            default:
                return protocol;
        }
    }

    /** The privacy chain this process runs alongside the core, if any. */
    public static Chain chain(String protocol) {
        if (protocol == null) return Chain.NONE;
        switch (protocol) {
            case "wg+psiphon":
            case "gool+psiphon":
            case "masque+psiphon":
                return Chain.PSIPHON;
            case "wg+tor":
            case "gool+tor":
            case "masque+tor":
                return Chain.TOR;
            default:
                return Chain.NONE;
        }
    }

    public enum Chain {
        NONE("none"), PSIPHON("psiphon"), TOR("tor");

        public final String id;

        Chain(String id) {
            this.id = id;
        }
    }

    /**
     * Extra startup budget per chain, seconds. Psiphon must fetch a server list through
     * the underlay before it can serve; Tor needs a consensus on first run, which is
     * measured in minutes. The stall watchdog is suppressed while these are outstanding
     * so a bootstrapping helper is never killed mid-bootstrap.
     */
    public static int startupAllowanceSeconds(String protocol) {
        switch (chain(protocol)) {
            case PSIPHON:
                return 120;
            case TOR:
                return 300;
            default:
                return 0;
        }
    }

    /** The MASQUE connection-method control applies to the masque and mim bases only. */
    public static boolean masqueTransportApplicable(String protocol) {
        String base = baseProtocol(protocol);
        return "masque".equals(base) || "mim".equals(base);
    }

    /** The gool-mode control applies to gool bases only. */
    public static boolean goolModeApplicable(String protocol) {
        return "gool".equals(baseProtocol(protocol));
    }

    /**
     * Chained entries are hidden in manual/proxy mode: the chain only runs when the whole
     * device is tunnelled. A saved chained entry is preserved rather than reset, so
     * switching back to full-device mode restores what the user had.
     */
    public static boolean visibleInProxyMode(String protocol) {
        return chain(protocol) == Chain.NONE;
    }

    /**
     * The core renamed {@code stealth} to {@code verified} in v2.3.0 but still parses the
     * old spelling as an alias. The stored value stays {@code stealth} so an existing
     * preference keeps working; this renders the current user-facing label.
     */
    public static String scanModeLabel(String stored) {
        if (stored == null) return "Balanced";
        switch (stored) {
            case "turbo": return "Turbo";
            case "balanced": return "Balanced";
            case "thorough": return "Thorough";
            case "stealth": return "Verified";
            case "ironclad": return "Ironclad";
            default: return "Balanced";
        }
    }

    @Nullable
    public static String label(String protocol, String language) {
        boolean fa = "fa".equals(language);
        if (fa) {
            switch (protocol == null ? "" : protocol) {
                case "smart": return "اتصال هوشمند";
                case "masque": return "MASQUE";
                case "wg": return "WireGuard";
                case "gool": return "gool";
                case "mim": return "MIM";
                case "masque+psiphon": return "MASQUE + سایفون";
                case "wg+psiphon": return "WireGuard + سایفون";
                case "gool+psiphon": return "gool + سایفون";
                case "masque+tor": return "MASQUE + تور";
                case "wg+tor": return "WireGuard + تور";
                case "gool+tor": return "gool + تور";
                default: return protocol;
            }
        }
        switch (protocol == null ? "" : protocol) {
            case "smart": return "Smart Connect";
            case "masque": return "MASQUE";
            case "wg": return "WireGuard";
            case "gool": return "gool";
            case "mim": return "MIM";
            case "masque+psiphon": return "MASQUE + Psiphon";
            case "wg+psiphon": return "WireGuard + Psiphon";
            case "gool+psiphon": return "gool + Psiphon";
            case "masque+tor": return "MASQUE + Tor";
            case "wg+tor": return "WireGuard + Tor";
            case "gool+tor": return "gool + Tor";
            default: return protocol;
        }
    }

    /** A short note explaining what an entry actually does, shown under the picker. */
    public static String description(String protocol, String language) {
        boolean fa = "fa".equals(language);
        String base = baseProtocol(protocol);
        Chain chain = chain(protocol);
        String transport = transportName(base, language);
        if (chain == Chain.PSIPHON) {
            return fa
                    ? transport + " از طریق سایفون — دو لایه، ورودی IP ثابت"
                    : transport + " through Psiphon — two layers, fixed entry IP";
        }
        if (chain == Chain.TOR) {
            return fa
                    ? transport + " از طریق تور — سه لایه، ورود متغیر"
                    : transport + " through Tor — three layers, rotating entry";
        }
        return fa ? transport + " — یک لایه، سریع‌ترین" : transport + " — single layer, fastest";
    }

    private static String transportName(String base, String language) {
        boolean fa = "fa".equals(language);
        switch (base) {
            case "masque": return "MASQUE";
            case "wg": return "WireGuard";
            case "gool": return "gool";
            case "mim": return "MIM";
            case "smart": return fa ? "انتخاب خودکار" : "auto-selected transport";
            default: return base == null ? "" : base.toUpperCase(Locale.US);
        }
    }
}