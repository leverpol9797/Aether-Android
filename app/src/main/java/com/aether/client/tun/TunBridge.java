package com.aether.client.tun;

import android.util.Log;

import java.io.File;

/**
 * Serialises the bridge's lifecycle.
 *
 * <p>Two upstream behaviours make this necessary and neither is visible from the JNI
 * signature:
 *
 * <ul>
 *   <li>{@code TProxyStartService} is a <em>no-op</em> while a worker thread from a
 *       previous start still exists. Starting on top of one does not fail -- it returns
 *       normally and leaves a tunnel that accepts a connection and carries nothing.</li>
 *   <li>{@code TProxyStopService} joins that worker thread, so it blocks for as long as
 *       the teardown takes. Calling it on the main thread is an ANR.</li>
 * </ul>
 *
 * <p>So stop runs on a bounded background thread and the thread is retained afterwards;
 * a start must wait for it to finish, and refuses the session if it does not.
 */
public final class TunBridge {

    private static final String TAG = "TunBridge";

    private static volatile Thread pendingStop;

    private TunBridge() {}

    /**
     * Waits for any in-flight stop to finish.
     *
     * @return true when no previous worker remains and a start is safe
     */
    public static boolean awaitHandover() {
        Thread stop = pendingStop;
        if (stop == null) return true;
        if (!stop.isAlive()) {
            pendingStop = null;
            return true;
        }
        try {
            stop.join(HevBridge.HANDOVER_TIMEOUT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!stop.isAlive()) {
            pendingStop = null;
            return true;
        }
        return false;
    }

    /**
     * Starts the bridge on an established TUN descriptor.
     *
     * @return true if it started, false if a previous worker was still winding down
     */
    public static boolean start(File config, int fd) {
        if (!awaitHandover()) return false;
        try {
            HevBridge.TProxyStartService(config.getAbsolutePath(), fd);
            return true;
        } catch (UnsatisfiedLinkError error) {
            Log.e(TAG, "the hev bridge library could not be loaded", error);
            return false;
        }
    }

    /**
     * Stops the bridge, bounded.
     *
     * <p>An overrun is not treated as success: the thread stays tracked so the next start
     * refuses rather than layering on a live worker.
     *
     * @return true if the stop finished within the timeout
     */
    public static boolean stop() {
        Thread previous = pendingStop;
        if (previous != null && !previous.isAlive()) pendingStop = null;

        Thread stopper = new Thread(() -> {
            try {
                HevBridge.TProxyStopService();
            } catch (Throwable error) {
                Log.w(TAG, "could not stop the bridge", error);
            }
        }, "aether-bridge-stop");
        stopper.setDaemon(true);
        pendingStop = stopper;
        stopper.start();

        try {
            stopper.join(HevBridge.STOP_TIMEOUT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!stopper.isAlive()) {
            pendingStop = null;
            return true;
        }
        return false;
    }

    /** Cumulative bytes: transmitted at index 1, received at index 3. */
    public static long[] stats() {
        try {
            long[] raw = HevBridge.TProxyGetStats();
            if (raw == null || raw.length < 4) return null;
            return new long[]{raw[1], raw[3]};
        } catch (Throwable error) {
            // Stats are decoration; a failure here must not take the session down.
            return null;
        }
    }
}