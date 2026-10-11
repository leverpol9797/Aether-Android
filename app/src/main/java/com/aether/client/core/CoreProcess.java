package com.aether.client.core;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the Aether core binary: where it lives, how it is launched, and how it is stopped.
 *
 * <p>The core ships as one ELF executable per ABI, placed in {@code lib/} under the
 * name {@code libaether.so}. The name is only a packaging convention: nothing loads it as
 * a shared library, it is copied out and executed as a program.
 */
public final class CoreProcess {

    private static final String TAG = "CoreProcess";

    /** How long to wait for the SOCKS port to accept after a spawn. */
    private static final int PORT_READY_TIMEOUT_MS = 30_000;
    private static final int PORT_POLL_MS = 250;

    public interface LogSink {
        void onLine(String line, LogLevel level);
    }

    public interface ExitListener {
        void onExit(int code);
    }

    /**
     * A phase the core's own output reports.
     *
     * <p>Read from the core's words rather than inferred from elapsed time: a tunnel that
     * says it validated a gateway has done the handshake, and one that has not has not,
     * however long it has been trying. The literals are the core's own.
     */
    public interface PhaseListener {
        void onPhase(Phase phase);
    }

    public enum Phase {
        /** Identity resolved; the core is now hunting for a usable gateway. */
        SCANNING,
        /** A gateway validated and the handshake passed. */
        GATEWAY_VERIFIED,
        /** This transport found nothing usable on this network. */
        NO_GATEWAY
    }

    public enum LogLevel {
        DEBUG, INFO, WARN, ERROR
    }

    private final Context context;
    private final CopyOnWriteArrayList<LogSink> sinks = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private volatile Process process;
    private volatile long startedAtMs;
    private volatile int generation;

    public CoreProcess(Context context) {
        this.context = context.getApplicationContext();
    }

    public void addSink(LogSink sink) {
        sinks.add(sink);
    }

    public void removeSink(LogSink sink) {
        sinks.remove(sink);
    }

    public boolean isRunning() {
        Process current = process;
        if (current == null) return false;
        try {
            // try_wait semantics from Java: exitValue throws while alive.
            current.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    public int pid() {
        // Java does not expose the pid. The core's own log line carries it, and the
        // reaper matches on the executable path, so this stays null rather than guessing.
        return -1;
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    /** Where the core's state files live: identity, config, and the working directory. */
    public File workingDir() {
        File dir = new File(context.getFilesDir(), "aether");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create working directory " + dir);
        }
        return dir;
    }

    /** The extracted, executable core for this device's ABI. */
    public File executable() {
        File nativeDir = context.getApplicationInfo().nativeLibraryDir;
        return new File(nativeDir, "libaether.so");
    }

    public boolean isAvailable() {
        File core = executable();
        return core.isFile() && core.canExecute();
    }

    /**
     * Waits for the port to be free.
     *
     * <p>A core that outlived its session still owns the SOCKS port, so the next connect
     * races it and loses in a way that looks like a bind failure inside the core. Nothing
     * else frees it: the socket dies with the process, and the process is only killed on
     * the paths that know about it.
     *
     * @return true if the port is free, false if it is still held after the timeout
     */
    public boolean awaitPortFree(int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!portAccepts(port)) return true;
            sleep(PORT_POLL_MS);
        }
        return !portAccepts(port);
    }

    /** Waits for the core to start serving SOCKS5, which is the proof it actually came up. */
    public boolean awaitPortListening(int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) return false;
            if (socksHandshakeSucceeds(port)) return true;
            sleep(PORT_POLL_MS);
        }
        return false;
    }

    /**
     * A real SOCKS5 method negotiation, not a bare TCP connect.
     *
     * <p>Something else on the device can hold the port -- an earlier core that outlived
     * its session, another app. A TCP connect succeeds against any of them, so it answers
     * "is something there", not "is the core up". This sends {@code {5,1,0}} and requires
     * the core's own {@code {5,0}} reply, which only a SOCKS5 server produces.
     */
    public static boolean socksHandshakeSucceeds(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            socket.setSoTimeout(400);
            socket.getOutputStream().write(new byte[]{5, 1, 0});
            socket.getOutputStream().flush();
            byte[] greeting = new byte[2];
            int read = socket.getInputStream().read(greeting);
            return read == 2 && greeting[0] == 5 && greeting[1] == 0;
        } catch (Exception notReady) {
            return false;
        }
    }

    private boolean portAccepts(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (Exception refused) {
            return false;
        }
    }

    /**
     * Starts the core and streams its output.
     *
     * @param socksPort the port it should publish on
     * @param onExit    called once when the process ends, on a background thread
     */
    public void start(int socksPort, ExitListener onExit, PhaseListener onPhase) throws IOException {
        synchronized (lock) {
            if (isRunning()) throw new IllegalStateException("Aether core is already running");

            File core = executable();
            if (!core.isFile()) {
                throw new IOException("The Aether core binary is missing for this device architecture");
            }
            if (!core.canExecute() && !core.setExecutable(true, false)) {
                throw new IOException("The Aether core binary is not executable: " + core);
            }

            awaitPortFree(socksPort, 10_000);

            CoreEnvironment environment = new CoreEnvironment(
                    new Settings(context), workingDir());
            Map<String, String> env = environment.build(socksPort);

            ProcessBuilder builder = new ProcessBuilder(core.getAbsolutePath());
            builder.directory(workingDir());
            // One merged stream: the core writes progress to stdout and failures to
            // stderr interleaved, and reading them as one line stream preserves the order
            // a user would see on a terminal.
            builder.redirectErrorStream(true);
            builder.environment().putAll(env);

            Process started = builder.start();
            process = started;
            generation++;
            startedAtMs = System.currentTimeMillis();

            emit(LogLevel.INFO, "Aether core started for " + android.os.Build.SUPPORTED_ABIS[0]);

            Thread reader = new Thread(() -> pumpLogs(started, socksPort, onExit, onPhase),
                    "aether-core-log-reader");
            reader.setDaemon(true);
            reader.start();
        }
    }

    /**
     * Reads the core's merged output until it ends.
     *
     * <p>Each line is classified and published. The state transitions are read from the
     * core's own words rather than inferred from elapsed time: a tunnel that says it
     * validated a gateway has done the handshake, and one that has not has not, however
     * long it has been trying.
     */
    private void pumpLogs(Process started, int socksPort, ExitListener onExit,
                           PhaseListener onPhase) {
        int session = generation;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(java.util.Locale.US);
                LogLevel level = classify(line, lower);
                emit(level, "[Aether] " + line);

                // The core announcing its listener is the strongest available proof it
                // came up: it has bound the port and is serving.
                if (lower.contains("socks5 server listening") || lower.contains("socks5 listening")) {
                    emit(LogLevel.INFO, "[Aether] SOCKS5 is live on 127.0.0.1:" + socksPort);
                }

                // These four literals are the core's own phrasing, not an invented
                // protocol. They are the only signals that separate "still hunting" from
                // "gateway secured", and a UI that cannot tell them apart shows
                // "connecting" for a tunnel that is already up.
                if (session == generation && onPhase != null) {
                    if (lower.contains("no usable masque gateway found")) {
                        onPhase.onPhase(Phase.NO_GATEWAY);
                    } else if (lower.contains("identity ready") || lower.contains("hunting for")) {
                        onPhase.onPhase(Phase.SCANNING);
                    } else if (lower.contains("validated") || lower.contains("passed handshake")) {
                        onPhase.onPhase(Phase.GATEWAY_VERIFIED);
                    }
                }
            }
        } catch (IOException closed) {
            if (process != null) {
                emit(LogLevel.WARN, "[Aether] log stream closed: " + closed.getMessage());
            }
        }

        int code;
        try {
            code = started.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        if (session == generation && onExit != null) {
            onExit.onExit(code);
        }
    }

    private LogLevel classify(String line, String lower) {
        if (lower.contains("error") || lower.contains("failed") || lower.contains("[err]")
                || lower.contains("fatal")) {
            return LogLevel.ERROR;
        }
        if (lower.contains("warn") || lower.contains("[wrn]") || lower.contains("retry")) {
            return LogLevel.WARN;
        }
        if (lower.contains("debug") || lower.contains("trace")) {
            return LogLevel.DEBUG;
        }
        return LogLevel.INFO;
    }

    private void emit(LogLevel level, String line) {
        for (LogSink sink : sinks) {
            sink.onLine(line, level);
        }
    }

    /**
     * Stops the core and waits for the port to be released.
     *
     * <p>{@code destroy()} sends SIGTERM; a core that ignores it during a tunnel teardown
     * is killed after a grace period, because leaving it alive would leave 1819 bound and
     * the next connect would fail for a reason the user cannot see.
     *
     * @return true if the port was free when this returned
     */
    public boolean stop(int socksPort, int graceMs) {
        Process current;
        synchronized (lock) {
            current = process;
            process = null;
            generation++;
        }
        if (current != null) {
            current.destroy();
            try {
                if (!waitFor(current, graceMs)) {
                    emit(LogLevel.WARN, "[Aether] core ignored the shutdown request; killing it");
                    current.destroyForcibly();
                    waitFor(current, 3000);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        boolean free = awaitPortFree(socksPort, graceMs);
        if (!free) {
            emit(LogLevel.ERROR, "[Aether] SOCKS port " + socksPort
                    + " did not free up; the next connect may fail to bind");
        }
        return free;
    }

    private static boolean waitFor(Process process, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException alive) {
                sleep(100);
            }
        }
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException alive) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}