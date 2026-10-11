package com.aether.client.tun;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the Android VPN interface that feeds the hev bridge.
 *
 * <p>Two routing policies are offered. Full-device takes everything including private
 * space, which is the honest reading of "system-wide VPN". Bypass-local keeps RFC1918,
 * CGNAT and link-local off the tunnel so LAN printers and local network shares keep
 * working, and adds exactly the complement of the excluded ranges rather than relying on
 * the platform to subtract them.
 */
public final class TunBuilder {

    /** Ranges that stay on the local network under bypass-local. */
    private static final String[][] PRIVATE_V4 = {
            {"0.0.0.0", "8"},
            {"10.0.0.0", "8"},
            {"100.64.0.0", "10"},
            {"127.0.0.0", "8"},
            {"169.254.0.0", "16"},
            {"172.16.0.0", "12"},
            {"192.0.0.0", "24"},
            {"192.168.0.0", "16"},
            {"198.18.0.0", "15"},
            {"224.0.0.0", "3"},
    };

    private TunBuilder() {}

    /**
     * @param mtu            effective MTU for the active transport
     * @param bypassLocal    true to keep private and local ranges off the tunnel
     * @param dnsProtected   true to declare resolvers that live inside the tunnel
     * @param chain          true when a privacy chain is in play, which changes which
     *                      resolver can be declared and needs the mapdns range routed
     * @param excludedRoutes extra CIDRs to keep off the tunnel, e.g. a LAN the user named
     */
    public static ParcelFileDescriptor establish(VpnService service,
                                                 String sessionName,
                                                 int mtu,
                                                 boolean bypassLocal,
                                                 boolean dnsProtected,
                                                 boolean chain,
                                                 List<String> excludedRoutes,
                                                 List<String> splitPackages) throws Exception {
        VpnService.Builder builder = new VpnService.Builder()
                .setSession(sessionName)
                .setMtu(mtu)
                // Non-blocking, because the bridge owns the other end and the lifecycle
                // thread must never be pinned to a read that will not return.
                .setBlocking(false)
                .addAddress(TunConfig.TUN_ADDRESS, TunConfig.TUN_PREFIX)
                .addAddress(TunConfig.TUN_ADDRESS_V6, TunConfig.TUN_PREFIX_V6);

        // The tunnel's own subnet is routed explicitly, always. addAddress does not imply
        // it, and bypass-local excludes 10.0.0.0/8 as private space -- which is the tunnel
        // itself, including the resolver at 10.0.0.2. Losing that route leaves a device
        // that can reach the internet with no way to resolve anything.
        builder.addRoute(TunConfig.TUN_SUBNET, TunConfig.TUN_PREFIX);
        if (chain) {
            // mapdns hands out answers in 100.64.0.0/10, which bypass-local keeps off the
            // tunnel as CGNAT space. On a chain that is backwards: the lookup returns
            // 100.64.0.x, the device sends to it, and with the range excluded the packet
            // leaves the TUN and the answer is a timeout.
            builder.addRoute(TunConfig.MAPDNS_NETWORK, 10);
        }

        if (bypassLocal) {
            addComplement(builder);
            // 2000::/3 is all currently allocated global unicast, so every routable v6
            // destination is captured while link-local and ULA stay local.
            builder.addRoute("2000::", 3);
        } else {
            builder.addRoute("0.0.0.0", 0);
            builder.addRoute("::", 0);
        }

        for (String cidr : excludedRoutes) {
            applyCidr(builder, cidr);
        }

        if (dnsProtected) {
            if (chain) {
                // Both resolvers must be public addresses covered by the tunnel routes, so
                // every lookup -- including the hostname behind Android Private DNS --
                // travels inside the tunnel and no query reaches the carrier resolver.
                // On a chain it is hev's own mapped resolver instead: pointing the TUN at
                // 1.1.1.1 would send the query straight back out as UDP, and UDP is the
                // one thing Psiphon's SOCKS cannot carry.
                builder.addDnsServer(TunConfig.ROUTER_DNS);
            } else {
                builder.addDnsServer("1.1.1.1").addDnsServer("1.0.0.1");
            }
        }

        for (String packageName : splitPackages) {
            // A split-tunnel entry must be an absolute package name; an app that has been
            // uninstalled throws here, so one bad entry is skipped rather than failing the
            // whole session.
            try {
                if (service != null) service.addDisallowedApplication(packageName);
            } catch (Exception ignored) {
                // package not installed or not visible; skip it
            }
        }

        ParcelFileDescriptor descriptor = builder.establish();
        if (descriptor == null) {
            throw new IllegalStateException("Android could not create the VPN interface");
        }
        return descriptor;
    }

    /**
     * Adds every public v4 range by walking the complement of the excluded list.
     *
     * <p>Adding "0.0.0.0/0" and subtracting is not available on every platform version,
     * so the complement is materialised directly: sort the exclusions, then emit each gap
     * as one or more routes.
     */
    private static void addComplement(VpnService.Builder builder) {
        List<long[]> excluded = new ArrayList<>();
        for (String[] entry : PRIVATE_V4) {
            excluded.add(Cidr.parse(entry[0], Integer.parseInt(entry[1])));
        }
        Collections.sort(excluded, (a, b) -> Long.compare(a[0], b[0]));

        long cursor = 0;
        for (long[] range : excluded) {
            if (cursor < range[0]) addSpan(builder, cursor, range[0] - 1);
            cursor = Math.max(cursor, range[1] + 1);
        }
        if (cursor <= 0xffffffffL) addSpan(builder, cursor, 0xffffffffL);
    }

    /** Emits one address range as the smallest set of CIDR routes that covers it. */
    private static void addSpan(VpnService.Builder builder, long start, long end) {
        long cursor = start;
        while (cursor <= end) {
            // Largest power-of-two block that is both aligned at `cursor` and still fits
            // inside the span. Alignment is why the naive "split the range in half"
            // approach produces routes like 10.0.0.1/32 that Android rejects.
            long size = 1;
            while (true) {
                long bigger = size << 1;
                if (bigger > 0x100000000L) break;          // would run past 255.255.255.255
                if (cursor % bigger != 0) break;           // not aligned to that size
                if (cursor + bigger - 1 > end) break;      // would overshoot the span
                size = bigger;
            }
            addRoute(builder, cursor, 32 - Long.numberOfTrailingZeros(size));
            if (cursor + size - 1 > end) break;
            cursor += size;
        }
    }

    private static void addRoute(VpnService.Builder builder, long address, int prefix) {
        builder.addRoute(ipv4(address), prefix);
    }

    private static void applyCidr(VpnService.Builder builder, String cidr) {
        if (cidr == null || cidr.trim().isEmpty()) return;
        String[] parts = cidr.trim().split("/");
        if (parts.length != 2) return;
        try {
            builder.addRoute(parts[0], Integer.parseInt(parts[1].trim()));
        } catch (Exception ignored) {
            // a malformed entry must not take the session down
        }
    }

    private static String ipv4(long address) {
        return ((address >> 24) & 0xff) + "."
                + ((address >> 16) & 0xff) + "."
                + ((address >> 8) & 0xff) + "."
                + (address & 0xff);
    }

    /** Parses an IPv4 CIDR into {start, end} as unsigned 32-bit values in a long. */
    static final class Cidr {
        static long[] parse(String address, int prefix) {
            long base = 0;
            String[] octets = address.split("\\.");
            for (String octet : octets) {
                base = (base << 8) | (Integer.parseInt(octet) & 0xffL);
            }
            long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
            long start = base & mask;
            long end = start | (~mask & 0xffffffffL);
            return new long[]{start, end};
        }
    }
}