package com.aether.client.tun;

/**
 * JNI bridge to libhev-socks5-tunnel, the userspace TCP/IP stack that carries the TUN.
 *
 * <p>The Android VpnService hands back a file descriptor, not a socket API, so something
 * has to speak IP to it. hev-socks5-tunnel does that and speaks SOCKS5 on the other side,
 * which is exactly what the Aether core already publishes.
 *
 * <p>Loaded reflectively-friendly and guarded: an APK for one ABI can reach this class on
 * another, and the failure there must be a clear message rather than an
 * {@link UnsatisfiedLinkError} at a random later line.
 */
public final class HevBridge {

    private static boolean loaded;
    private static UnsatisfiedLinkError loadFailure;

    private HevBridge() {}

    /** Loads the native library once, remembering a failure instead of throwing twice. */
    public static synchronized boolean ensureLoaded() {
        if (loaded) return true;
        if (loadFailure != null) return false;
        try {
            System.loadLibrary("hev-socks5-tunnel");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loadFailure = error;
        }
        return loaded;
    }

    /**
     * Starts the tunnel on an already-established TUN descriptor.
     *
     * @param configPath absolute path of the written hev.yml
     * @param fd         the descriptor from {@code Builder.establish()}
     *
     * <p>A no-op while a previous worker still exists, so a start on top of a stale one
     * publishes a tunnel that silently carries nothing. Callers must wait for handover.
     */
    public static native void TProxyStartService(String configPath, int fd);

    public static native void TProxyStopService();

    /**
     * @return a four-element array of cumulative counters -- {?, tx, ?, rx} -- or null.
     *         The transmitted byte count is at index 1 and received at index 3, not 0
     *         and 1: indexing from zero silently reports the wrong direction as traffic.
     *
     * <p>A snapshot, not a subscription. {@code stop()} joins its worker thread, so
     * callers that stop the bridge must not read stats afterwards.
     */
    public static native long[] TProxyGetStats();

    /** How long to wait for a stop before giving up on it. */
    public static final long STOP_TIMEOUT_MS = 4_000L;

    /**
     * How long a new start waits for a previous stop to finish.
     *
     * <p>{@link #TProxyStartService} is a no-op while a worker thread from a previous
     * start still exists. Starting on top of one publishes a tunnel that accepts
     * connections and carries nothing -- the failure this whole guard exists to prevent.
     */
    public static final long HANDOVER_TIMEOUT_MS = 6_000L;
}