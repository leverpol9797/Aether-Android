package com.aether.client.tun;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Writes the hev-socks5-tunnel YAML configuration and owns the addresses the tunnel is
 * built on.
 *
 * <p>The address plan is load-bearing and not interchangeable:
 * <ul>
 *   <li>{@code 10.0.0.1} is the TUN interface itself.</li>
 *   <li>{@code 10.0.0.2} is the router side, used as the DNS resolver the device is
 *       pointed at.</li>
 *   <li>{@code 100.64.0.0/10} is the synthetic range mapdns answers from when it
 *       resolves locally.</li>
 * </ul>
 * The subnet route must be added explicitly next to the interface address: a route is not
 * implied by the address, and dropping it makes the resolver unreachable, so a device that
 * can reach the internet has no way to find an address worth reaching.
 */
public final class TunConfig {

    private static final String TAG = "TunConfig";

    public static final String TUN_ADDRESS = "10.0.0.1";
    // The /8 is load-bearing. A /30 cannot hold both ends of the link plus the resolver,
    // and lwIP silently drops every packet whose own address falls outside its subnet --
    // which presents as a tunnel that accepts a connection and carries nothing.
    public static final int TUN_PREFIX = 8;
    public static final String TUN_SUBNET = "10.0.0.0";

    public static final String TUN_ADDRESS_V6 = "fc00::1";
    public static final int TUN_PREFIX_V6 = 126;

    public static final String ROUTER_DNS = "10.0.0.2";
    public static final String MAPDNS_NETWORK = "100.64.0.0";
    public static final String MAPDNS_NETMASK = "255.192.0.0";

    private TunConfig() {}

    /**
     * Writes the config for one connect attempt.
     *
     * @param socksHost  host of the core's SOCKS listener
     * @param socksPort  its port
     * @param mtu        the effective MTU, already capped for the transport
     * @param mapDns     true when a privacy chain is in play, where UDP cannot be
     *                   carried as-is and DNS has to be answered inside the bridge
     */
    public static File write(File cacheDir,
                             String socksHost,
                             int socksPort,
                             int mtu,
                             boolean mapDns) throws IOException {
        File config = new File(cacheDir, "hev.yml");
        try (FileWriter out = new FileWriter(config, false)) {
            out.write("misc:\n");
            out.write("  task-stack-size: 32768\n");
            out.write("  connect-timeout: 15000\n");
            out.write("  log-level: warn\n");

            out.write("tunnel:\n");
            out.write("  mtu: " + mtu + "\n");
            out.write("  ipv4: " + TUN_ADDRESS + "\n");
            // Always given the v6 address. The TUN always carries ::/0, so without it
            // those packets are silently dropped; with it every v6 flow becomes a SOCKS5
            // CONNECT that either reaches the core's v6 exit or fails fast enough for
            // the client to fall back to v4.
            out.write("  ipv6: '" + TUN_ADDRESS_V6 + "'\n");
            out.write("  icmp: 'reply'\n");

            out.write("socks5:\n");
            out.write("  address: '" + escape(socksHost) + "'\n");
            out.write("  port: " + socksPort + "\n");
            out.write("  udp: '" + udpMode(mapDns) + "'\n");

            if (mapDns) {
                // hev cannot be told to carry CONNECT only: UDP is on unconditionally
                // in lwIP, and the one key that picks the SOCKS5 command has no third
                // value (udp -> ASSOCIATE, tcp -> BIND, omitted -> BIND). Both are
                // refused by Psiphon's SOCKS, so the flood cannot be configured away and
                // has to be taken off the wire instead. mapdns answers DNS from a local
                // cache and only resolves through the tunnel on a miss -- and DNS is
                // effectively all the UDP a phone generates.
                out.write("mapdns:\n");
                out.write("  address: " + ROUTER_DNS + "\n");
                out.write("  port: 53\n");
                out.write("  network: " + MAPDNS_NETWORK + "\n");
                out.write("  netmask: " + MAPDNS_NETMASK + "\n");
                out.write("  cache-size: 4096\n");
            }
        }
        Log.i(TAG, "wrote hev config for socks " + socksHost + ":" + socksPort
                + " mtu=" + mtu + " mapdns=" + mapDns);
        return config;
    }

    private static String udpMode(boolean chain) {
        // A direct core session handles UDP, so hand it straight through. Behind
        // Psiphon it cannot, and mapdns covers DNS; the rest is left to fail fast.
        return chain ? "tcp" : "udp";
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }
}