package com.aether.client.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.aether.client.MainActivity;
import com.aether.client.core.ConnectionState;
import com.aether.client.core.CoreProcess;
import com.aether.client.core.Protocols;
import com.aether.client.core.Settings;
import com.aether.client.tun.HevBridge;
import com.aether.client.tun.TunBridge;
import com.aether.client.tun.TunBuilder;
import com.aether.client.tun.TunConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The VPN service: owns the core process, the TUN, and the bridge between them.
 *
 * <p>Connect is a sequence, and each step exists because the one before it can leave a
 * mess behind if it fails:
 *
 * <ol>
 *   <li>validate the request against the settings, so an impossible combination is refused
 *       before a process is spawned rather than after;</li>
 *   <li>wait for the SOCKS port to be free -- a core that outlived its session still holds
 *       it, and the new one would race it;</li>
 *   <li>spawn the core and wait for it to publish its listener;</li>
 *   <li>establish the TUN and hand the descriptor to the bridge;</li>
 *   <li>prove traffic actually flows before telling the UI "connected".</li>
 * </ol>
 *
 * <p>Only the last of those is a user-visible "connected". A tunnel that came up and
 * carries nothing is the failure this ordering exists to prevent, and it is invisible from
 * every other angle.
 */
public class AetherVpnService extends VpnService {

    private static final String TAG = "AetherVpnService";

    public static final String ACTION_CONNECT = "com.aether.client.CONNECT";
    public static final String ACTION_DISCONNECT = "com.aether.client.DISCONNECT";

    /** Broadcast when the state changes. State comes from here, never from the tap. */
    public static final String ACTION_STATE = "com.aether.client.STATE";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_DETAIL = "detail";

    /** Broadcast for each core log line. */
    public static final String ACTION_LOG = "com.aether.client.LOG";
    public static final String EXTRA_LOG_LINE = "line";
    public static final String EXTRA_LOG_LEVEL = "level";

    /** Broadcast for live metrics: ping, uptime, traffic. */
    public static final String ACTION_METRICS = "com.aether.client.METRICS";
    /** The activity asks for the current state; the service answers with ACTION_STATE. */
    public static final String ACTION_REQUEST_STATE = "com.aether.client.REQUEST_STATE";
    public static final String EXTRA_PING_MS = "pingMs";
    public static final String EXTRA_UPTIME_MS = "uptimeMs";
    public static final String EXTRA_UPLOADED = "uploaded";
    public static final String EXTRA_DOWNLOADED = "downloaded";

    private static final String CHANNEL_ID = "aether_vpn";
    private static final int NOTIFICATION_ID = 1001;

    private static final int PORT_READY_TIMEOUT_MS = 45_000;
    private static final int SHUTDOWN_GRACE_MS = 5_000;
    private static final int METRICS_INTERVAL_MS = 2_000;
    /** How long a chain's helper may take to bootstrap before the stall watchdog applies. */
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    private final Object lifecycleLock = new Object();
    private final AtomicInteger generation = new AtomicInteger();

    private static final String STATE_STORE = "service_state";
    private static final String KEY_PERSISTED_STATE = "state";
    private static final String KEY_PERSISTED_DETAIL = "detail";
    /** Set only by a user-initiated connect, so a crash never becomes an auto-reconnect. */
    private static final String KEY_DESIRED_CONNECTED = "desiredConnected";

    private Settings settings;
    private android.content.SharedPreferences stateStore;
    private CoreProcess core;

    /**
     * One stable sink instance for the service's lifetime.
     *
     * <p>{@code removeSink} matches by identity, so registering a fresh lambda and
     * removing {@code this::publishLog} removes nothing. The old sink then keeps
     * broadcasting log lines into a destroyed service.
     */
    private final CoreProcess.LogSink logSink = this::publishLog;
    private ParcelFileDescriptor tunDescriptor;
    private volatile boolean stopping;
    private volatile int sessionGeneration;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;

    private Thread metricsThread;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        stateStore = getSharedPreferences(STATE_STORE, MODE_PRIVATE);
        core = new CoreProcess(this);
        // Named as a field rather than a method reference: removeSink compares by
        // identity, and a fresh lambda each time would never match what was added.
        core.addSink(logSink);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }
        switch (intent.getAction()) {
            case ACTION_CONNECT:
                // A second tap while a session is already up must not spawn a second
                // core: the first one owns the port and the second would fail to bind for
                // a reason the user cannot see.
                if (state.isBusy() || state.isUp()) return START_STICKY;
                stateStore.edit().putBoolean(KEY_DESIRED_CONNECTED, true).commit();
                startForeground(NOTIFICATION_ID, notification("Connecting…"));
                Thread worker = new Thread(this::runConnect, "aether-connect");
                worker.setDaemon(true);
                worker.start();
                return START_STICKY;
            case ACTION_DISCONNECT:
                disconnect();
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_REQUEST_STATE:
                // Answer from the persisted value, not the in-memory one: this may be a
                // broadcast to a freshly started process whose fields are all defaults.
                String persisted = stateStore.getString(KEY_PERSISTED_STATE,
                        ConnectionState.DISCONNECTED.id);
                Intent reply = new Intent(ACTION_STATE);
                reply.putExtra(EXTRA_STATE, persisted);
                reply.putExtra(EXTRA_DETAIL,
                        stateStore.getString(KEY_PERSISTED_DETAIL, ""));
                sendBroadcast(reply);
                return START_STICKY;
            default:
                return START_NOT_STICKY;
        }
    }

    // ---- connect sequence ---------------------------------------------------------------

    private void runConnect() {
        int session = generation.incrementAndGet();
        stopping = false;

        try {
            String problem = validateRequest();
            if (problem != null) {
                fail(problem);
                return;
            }

            String protocol = settings.protocol();
            boolean vpnMode = "vpn".equals(settings.routingMode());
            int socksPort = socksPort();

            publishState(ConnectionState.STARTING, null);

            if (!core.isAvailable()) {
                fail("The Aether core binary is missing for this device architecture.");
                return;
            }

            // A core from a previous session that outlived its teardown still owns the
            // port and its upstream sockets. Nothing else will free them.
            reapOrphanedCores();
            if (!core.awaitPortFree(socksPort, 10_000)) {
                publishLog(CoreProcess.LogLevel.WARN,
                        "SOCKS port " + socksPort + " is still in use; waiting for it to release");
            }

            core.start(socksPort, exitCode -> onCoreExited(session, exitCode),
                    phase -> onCorePhase(phase));
            publishLog(CoreProcess.LogLevel.INFO, "Core spawned; waiting for the SOCKS listener");

            if (!core.awaitPortListening(socksPort, PORT_READY_TIMEOUT_MS)) {
                fail("The core did not open 127.0.0.1:" + socksPort
                        + " within " + (PORT_READY_TIMEOUT_MS / 1000) + "s. See the log for why.");
                return;
            }
            if (!isCurrent(session)) return;

            publishState(ConnectionState.SCANNING, null);
            publishLog(CoreProcess.LogLevel.INFO,
                    "SOCKS5 live on 127.0.0.1:" + socksPort + "; the core is now finding a working path");

            if (!vpnMode) {
                // Proxy mode: the listener is the deliverable. There is no TUN to prove,
                // and a SOCKS listener that accepts is the whole contract.
                publishState(ConnectionState.CONNECTED, null);
                publishLog(CoreProcess.LogLevel.INFO,
                        "SOCKS5 proxy is ready. Configure your app to use 127.0.0.1:" + socksPort);
                startMetrics(session);
                return;
            }

            establishTunnel(session, socksPort);
        } catch (Exception error) {
            Log.e(TAG, "connect failed", error);
            fail(describe(error));
        }
    }

    /** Establishes the TUN and hands it to the bridge, then proves traffic flows. */
    private void establishTunnel(int session, int socksPort) throws Exception {
        String protocol = settings.protocol();
        boolean chain = Protocols.chain(protocol) != Protocols.Chain.NONE;
        int mtu = new com.aether.client.core.CoreEnvironment(
                settings, core.workingDir(), core.cacheDir()).effectiveMtu();

        if (!HevBridge.ensureLoaded()) {
            fail("The packet tunnel library is not available for this device architecture.");
            return;
        }

        File config = TunConfig.write(getCacheDir(), "127.0.0.1", socksPort, mtu, chain);

        // The bridge start is a native call of unbounded duration, so it must not run
        // while the lifecycle lock is held: disconnect needs that same lock, and holding
        // it across a wedged start is what leaves the UI stuck in "connecting".
        ParcelFileDescriptor descriptor = TunBuilder.establish(
                this,
                getString(com.aether.client.R.string.app_name),
                mtu,
                "bypass-local".equals(settings.routingMode()),
                settings.dnsLeakProtection(),
                chain,
                parseList(settings.routeExclusions()),
                parseList(settings.splitApplications()));

        if (!isCurrent(session)) {
            closeQuietly(descriptor);
            return;
        }

        synchronized (lifecycleLock) {
            tunDescriptor = descriptor;
        }

        if (!TunBridge.start(config, descriptor.getFd())) {
            fail(TunBridge.awaitHandover()
                    ? "The packet tunnel library could not be started."
                    : "The previous tunnel is still shutting down; try connecting again in a moment.");
            return;
        }
        if (!isCurrent(session)) return;

        publishLog(CoreProcess.LogLevel.INFO, "Tunnel bridge attached to the TUN interface");

        publishState(ConnectionState.SECURING, null);
        publishLog(CoreProcess.LogLevel.INFO, "Tunnel interface up; verifying that traffic really flows");

        // The last and only real proof. This request is deliberately NOT protected: every
        // other probe in this service protects its socket to exempt it from the VPN, and
        // here that is the opposite of what is wanted. The packet has to land on the TUN,
        // be read by the bridge and come back out through the core. If any of the three is
        // wrong this times out, and the session is never published.
        if (!proveTunnelCarriesTraffic(session)) {
            fail("The tunnel came up but did not carry traffic. See the log for the failing step.");
            return;
        }
        if (!isCurrent(session)) return;

        publishState(ConnectionState.CONNECTED, null);
        updateNotification("Connected and protected");
        startMetrics(session);
    }

    /**
     * An HTTPS GET whose only route out is the tunnel itself.
     *
     * <p>A long timeout, because a chain's bootstrap -- especially Tor's first consensus --
     * can legitimately take minutes, and killing it here would restart the very thing it
     * is waiting for.
     */
    private boolean proveTunnelCarriesTraffic(int session) {
        int allowance = Protocols.startupAllowanceSeconds(settings.protocol());
        int timeoutMs = Math.max(settings.startupSecs(), 30 + allowance) * 1000;
        long deadline = System.currentTimeMillis() + timeoutMs;
        int attempt = 0;

        while (System.currentTimeMillis() < deadline && isCurrent(session)) {
            attempt++;
            long started = System.nanoTime();
            try {
                java.net.HttpURLConnection connection = openThroughTunnel();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                int code = connection.getResponseCode();
                connection.disconnect();
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                if (code > 0 && code < 500) {
                    publishLog(CoreProcess.LogLevel.INFO,
                            "Traffic confirmed through the tunnel in " + elapsed + "ms (HTTP " + code + ")");
                    return true;
                }
            } catch (Exception notYet) {
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                publishLog(CoreProcess.LogLevel.DEBUG,
                        "Tunnel probe attempt " + attempt + " did not complete in " + elapsed
                                + "ms: " + describe(notYet));
            }
            if (!sleep(1000) || !isCurrent(session)) return false;
        }
        return false;
    }

    private java.net.HttpURLConnection openThroughTunnel() throws Exception {
        java.net.URL url = new java.net.URL("https://www.cloudflare.com/cdn-cgi/trace");
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
        connection.setRequestProperty("User-Agent", "Aether-Android");
        return connection;
    }

    // ---- lifecycle ----------------------------------------------------------------------

    /**
     * Reacts to what the core reports about itself.
 *
     * <p>These come from the core's own output, so they are the only states that can be
 * trusted to mean something. A UI that shows "connecting" until its own timer expires is
 * describing its own impatience, not the tunnel.
     */
    private void onCorePhase(CoreProcess.Phase phase) {
        switch (phase) {
            case SCANNING:
                publishLog(CoreProcess.LogLevel.INFO, "The core is hunting for a usable gateway");
                break;
            case GATEWAY_VERIFIED:
                publishLog(CoreProcess.LogLevel.INFO, "A gateway validated and the handshake passed");
                break;
            case NO_GATEWAY:
                publishLog(CoreProcess.LogLevel.WARN,
                        "MASQUE over HTTP/3 found no usable gateway on this network. "
                                + "HTTP/2 reaches the same edges; use the transport setting in Settings.");
                break;
            default:
                break;
        }
    }

    private void onCoreExited(int session, int exitCode) {
        if (stopping || session != sessionGeneration) return;

        // Say what was lost, not just that something ended. A core that exits during
        // startup failed for a reason; the lines above it carry the reason, and this
        // message is what a user sees when there were no lines.
        long uptimeMs = System.currentTimeMillis() - core.startedAtMs();
        publishLog(CoreProcess.LogLevel.ERROR,
                "Aether core exited with code " + exitCode + " after "
                        + (uptimeMs / 1000) + "s");
        if (uptimeMs < 5_000) {
            publishLog(CoreProcess.LogLevel.WARN,
                    "The core exited almost immediately, so this is a startup failure rather "
                            + "than a dropped session. The lines above carry the reason; "
                            + "if there are none, the binary cannot run on this device.");
        }

        if (!settings.quickReconnect()) {
            fail("The connection dropped unexpectedly (core exit " + exitCode + ").");
            return;
        }

        // Reconnect with exponential backoff, capped. An unbounded retry against a core
        // that exits immediately -- a revoked account, a banned endpoint -- turns one
        // failure into an endless spawn loop that also keeps re-binding the port.
        Thread reconnect = new Thread(() -> {
            int attempts = 0;
            while (!stopping && attempts < MAX_RECONNECT_ATTEMPTS && isCurrent(session)) {
                attempts++;
                long backoff = Math.min(20_000L, 1500L * (1L << Math.min(attempts - 1, 4)));
                publishLog(CoreProcess.LogLevel.WARN,
                        "Reconnecting in " + (backoff / 1000) + "s (attempt " + attempts
                                + " of " + MAX_RECONNECT_ATTEMPTS + ")");
                if (!sleep(backoff)) return;
                if (stopping || !isCurrent(session)) return;
                try {
                    int socksPort = socksPort();
                    core.start(socksPort, code -> onCoreExited(session, code), phase -> { });
                    if (core.awaitPortListening(socksPort, PORT_READY_TIMEOUT_MS)) {
                        publishLog(CoreProcess.LogLevel.INFO, "Reconnected");
                        return;
                    }
                } catch (Exception error) {
                    publishLog(CoreProcess.LogLevel.WARN, "Reconnect attempt failed: " + describe(error));
                }
            }
            if (isCurrent(session)) {
                fail("Could not stay connected after " + MAX_RECONNECT_ATTEMPTS + " attempts.");
            }
        }, "aether-reconnect");
        reconnect.setDaemon(true);
        reconnect.start();
    }

    private void disconnect() {
        stopping = true;
        generation.incrementAndGet();
        // Cleared first and synchronously, so a process death during teardown cannot
        // resurrect a session the user just ended.
        stateStore.edit().putBoolean(KEY_DESIRED_CONNECTED, false).commit();
        publishState(ConnectionState.DISCONNECTING, null);
        stopMetrics();

        synchronized (lifecycleLock) {
            ParcelFileDescriptor descriptor = tunDescriptor;
            tunDescriptor = null;
            if (descriptor != null) {
                // Stop the bridge before closing the descriptor. Closing underneath a
                // running native reader leaves it reading from a recycled fd, which shows
                // up as another session's traffic on this one.
                TunBridge.stop();
                closeQuietly(descriptor);
            }
        }

        reapOrphanedCores();
        core.stop(socksPort(), SHUTDOWN_GRACE_MS);
        publishState(ConnectionState.DISCONNECTED, null);
        updateNotification("Disconnected");
    }

    @Override
    public void onDestroy() {
        disconnect();
        if (core != null) core.removeSink(logSink);
        if (stateStore != null) {
            // commit, not apply: this write races the process death it is trying to record.
            stateStore.edit().putBoolean(KEY_DESIRED_CONNECTED, false).commit();
        }
        super.onDestroy();
    }

    // ---- metrics ------------------------------------------------------------------------

    /**
     * Samples the bridge's byte counters.
     *
     * <p>Read on the bridge's own index convention -- transmitted at 1, received at 3 --
     * and the deltas are what get published, so a counter that resets on reconnect does
     * not produce a negative spike on the dashboard.
     */
    private void startMetrics(int session) {
        stopMetrics();
        long connectedAt = System.currentTimeMillis();
        Thread thread = new Thread(() -> {
            long[] previous = new long[]{0, 0};
            while (!stopping && isCurrent(session)) {
                long[] stats = TunBridge.stats();
                if (stats != null) {
                    publishMetrics(0, System.currentTimeMillis() - connectedAt,
                            Math.max(0, stats[0] - previous[0]),
                            Math.max(0, stats[1] - previous[1]));
                    previous = stats;
                }
                if (!sleep(METRICS_INTERVAL_MS)) return;
            }
        }, "aether-metrics");
        thread.setDaemon(true);
        thread.start();
        metricsThread = thread;
    }

    private void stopMetrics() {
        Thread thread = metricsThread;
        metricsThread = null;
        if (thread != null) thread.interrupt();
    }

    // ---- validation ---------------------------------------------------------------------

    /**
     * Refuses a combination the core cannot honour, before anything is spawned.
     *
     * @return a message to show, or null when the request is fine
     */
    private String validateRequest() {
        String protocol = settings.protocol();
        if (!Protocols.isKnown(protocol)) {
            return "Unsupported protocol: " + protocol;
        }

        boolean vpnMode = "vpn".equals(settings.routingMode());
        if (!vpnMode && !Protocols.visibleInProxyMode(protocol)) {
            // A chain has no meaning without a TUN: the outer half's egress is what the
            // TUN would point at, and in proxy mode there is no TUN.
            return "This protocol needs System VPN mode. Switch the connection mode and try again.";
        }

        if (!vpnMode) {
            try {
                if (!HevBridge.ensureLoaded()) {
                    // Not fatal in proxy mode -- the bridge is never started. Kept quiet.
                    publishLog(CoreProcess.LogLevel.DEBUG, "packet tunnel library not loaded; proxy mode does not need it");
                }
            } catch (Throwable ignored) {
                // handled above
            }
        }

        String socks = settings.socksAddress();
        int port = socksPort();
        if (port < 1024 || port > 65535) {
            return "The SOCKS port must be between 1024 and 65535.";
        }
        if (!socks.startsWith("127.0.0.1") && !settings.remoteListener()) {
            return "A non-local SOCKS listener needs the explicit risk acknowledgement first.";
        }

        if (!settings.upstreamProxy().isEmpty()) {
            String upstream = settings.upstreamProxy();
            if (!upstream.startsWith("socks5://") && !upstream.startsWith("http://")
                    && !upstream.startsWith("https://")) {
                return "The upstream proxy must start with socks5://, http://, or https://";
            }
            Protocols.Chain chain = Protocols.chain(protocol);
            if (chain == Protocols.Chain.PSIPHON) {
                return "An upstream proxy cannot be combined with a Psiphon chain.";
            }
            if (chain == Protocols.Chain.TOR) {
                return "An upstream proxy cannot be combined with a Tor chain.";
            }
        }

        if (settings.tunMtu() < Settings.MIN_MTU || settings.tunMtu() > Settings.MAX_MTU) {
            return "The tunnel MTU must be between " + Settings.MIN_MTU + " and " + Settings.MAX_MTU + ".";
        }

        String mimOuter = settings.mimOuterPeer().trim();
        String mimInner = settings.mimInnerPeer().trim();
        if ("mim".equals(Protocols.baseProtocol(protocol))
                && !mimOuter.isEmpty() && mimOuter.equals(mimInner)) {
            return "The two MIM endpoints must be different.";
        }

        String wiwOuter = settings.wiwOuterPeer().trim();
        String wiwInner = settings.wiwInnerPeer().trim();
        if ("gool".equals(Protocols.baseProtocol(protocol))
                && !wiwOuter.isEmpty() && wiwOuter.equals(wiwInner)) {
            return "The two WARP-in-WARP endpoints must be different.";
        }

        if (settings.stallTimeout() < 10 || settings.stallTimeout() > 3600) {
            return "The stall timeout must be between 10 and 3600 seconds.";
        }
        if (settings.routeSniffMs() < 10 || settings.routeSniffMs() > 5000) {
            return "The route sniff timeout must be between 10 and 5000 ms.";
        }
        String region = settings.psiphonRegion().trim();
        if (!region.isEmpty() && !region.matches("^[A-Za-z]{2}$")) {
            return "The Psiphon region must be a two-letter country code, or left on Automatic.";
        }

        return null;
    }

    // ---- publishing ---------------------------------------------------------------------

    /**
     * Publishes a state, and remembers it.
     *
     * <p>Persisted with {@code commit()} rather than {@code apply()}: a disconnect writes
     * and then stops the service, and an asynchronous write can still be in flight when
     * the process dies. The app would come back claiming to be connected with nothing
     * running, which is the one thing a VPN client must never do.
     */
    private void publishState(ConnectionState next, String detail) {
        state = next;
        stateStore.edit()
                .putString(KEY_PERSISTED_STATE, next.id)
                .putString(KEY_PERSISTED_DETAIL, detail == null ? "" : detail)
                .commit();
        Intent intent = new Intent(ACTION_STATE);
        intent.putExtra(EXTRA_STATE, next.id);
        if (detail != null) intent.putExtra(EXTRA_DETAIL, detail);
        sendBroadcast(intent);

        if (next.isUp()) {
            startForeground(NOTIFICATION_ID, notification("Connected and protected"));
        }
    }

    private void fail(String message) {
        Log.w(TAG, "connect failed: " + message);
        publishLog(CoreProcess.LogLevel.ERROR, message);
        // The session did not come up, so nothing should try to bring it back. A crash
        // during connect is different: onCoreExited handles that, and only if the user
        // actually asked for a connection in the first place.
        stateStore.edit().putBoolean(KEY_DESIRED_CONNECTED, false).commit();
        teardownQuietly();
        publishState(ConnectionState.ERROR, message);
        updateNotification(message);
        stopSelf();
    }

    private void teardownQuietly() {
        stopping = true;
        stopMetrics();
        synchronized (lifecycleLock) {
            ParcelFileDescriptor descriptor = tunDescriptor;
            tunDescriptor = null;
            if (descriptor != null) {
                TunBridge.stop();
                closeQuietly(descriptor);
            }
        }
        reapOrphanedCores();
        core.stop(socksPort(), SHUTDOWN_GRACE_MS);
    }

    private void publishLog(CoreProcess.LogLevel level, String line) {
        Intent intent = new Intent(ACTION_LOG);
        intent.putExtra(EXTRA_LOG_LEVEL, level.name());
        intent.putExtra(EXTRA_LOG_LINE, line);
        sendBroadcast(intent);
    }

    private void publishMetrics(int pingMs, long uptimeMs, long uploaded, long downloaded) {
        Intent intent = new Intent(ACTION_METRICS);
        intent.putExtra(EXTRA_PING_MS, pingMs);
        intent.putExtra(EXTRA_UPTIME_MS, uptimeMs);
        intent.putExtra(EXTRA_UPLOADED, uploaded);
        intent.putExtra(EXTRA_DOWNLOADED, downloaded);
        sendBroadcast(intent);
    }

    // ---- helpers ------------------------------------------------------------------------

    private int socksPort() {
        String configured = settings.socksAddress();
        int colon = configured.lastIndexOf(':');
        if (colon > 0 && colon < configured.length() - 1) {
            try {
                return Integer.parseInt(configured.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return Integer.parseInt(CoreEnvironmentPort.DEFAULT_SOCKS_PORT);
    }

    /** The core's own default port, kept here so one place decides it. */
    private static final class CoreEnvironmentPort {
        static final String DEFAULT_SOCKS_PORT = "1819";
    }

    /**
     * Kills a core left behind by a session that was torn down without killing it.
     *
     * <p>Android mounts /proc with hidepid, so the only processes visible here are this
     * app's own children -- which is exactly the set that can still be holding the SOCKS
     * port. A live tracked session is never reaped: the check is against the tracked
     * process first, not against the executable name alone.
     *
     * @return how many were killed
     */
    private int reapOrphanedCores() {
        if (core != null && core.isRunning()) return 0;

        File[] entries = new File("/proc").listFiles();
        if (entries == null) return 0;

        int self = android.os.Process.myPid();
        int killed = 0;
        for (File entry : entries) {
            int pid;
            try {
                pid = Integer.parseInt(entry.getName());
            } catch (NumberFormatException notAProcess) {
                continue;
            }
            if (pid == self) continue;
            // The core ships as libaether.so, so that is what its cmdline carries.
            if (!readProcCmdline(new File(entry, "cmdline")).contains("libaether.so")) continue;
            try {
                android.os.Process.killProcess(pid);
                killed++;
                publishLog(CoreProcess.LogLevel.WARN, "Cleared a leftover Aether core, pid " + pid);
            } catch (RuntimeException refused) {
                publishLog(CoreProcess.LogLevel.WARN,
                        "Could not clear leftover Aether core pid " + pid + ": " + describe(refused));
            }
        }
        return killed;
    }

    private static String readProcCmdline(File file) {
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[512];
            int read = input.read(buffer);
            if (read <= 0) return "";
            // NUL-separated on Linux.
            return new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8)
                    .replace('\0', ' ');
        } catch (Exception unreadable) {
            return "";
        }
    }

    private boolean isCurrent(int session) {
        return !stopping && session == sessionGeneration;
    }

    private static List<String> parseList(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return out;
        for (String part : value.split("[,\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (Exception ignored) {
            // already closed
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) message = error.getClass().getSimpleName();
        return message;
    }

    // ---- notification -------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(com.aether.client.R.string.channel_status),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(com.aether.client.R.string.app_name))
                .setContentText(text)
                .setSmallIcon(com.aether.client.R.drawable.ic_stat_aether)
                .setContentIntent(pending)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }
}