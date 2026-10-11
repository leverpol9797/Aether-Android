package com.aether.client.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

public class AetherVpnService extends VpnService {
    private static final String TAG = "AetherVpnService";
    private static final String CHANNEL_ID = "AetherVpnChannel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_CONNECT = "com.aether.client.CONNECT";
    public static final String ACTION_DISCONNECT = "com.aether.client.DISCONNECT";
    public static final String ACTION_STATE_CHANGED = "com.aether.client.STATE_CHANGED";
    public static final String ACTION_LOG_EVENT = "com.aether.client.LOG_EVENT";

    public static final String EXTRA_STATE = "extra_state";
    public static final String EXTRA_LOG_MESSAGE = "extra_log_msg";
    public static final String EXTRA_LOG_LEVEL = "extra_log_level";

    public static final String STATE_DISCONNECTED = "disconnected";
    public static final String STATE_CONNECTING = "connecting";
    public static final String STATE_CONNECTED = "connected";
    public static final String STATE_ERROR = "error";

    private Process aetherProcess;
    private ParcelFileDescriptor vpnInterface;
    private String currentState = STATE_DISCONNECTED;
    private ConfigManager configManager;

    @Override
    public void onCreate() {
        super.onCreate();
        configManager = new ConfigManager(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_CONNECT:
                    startVpnAndCore();
                    break;
                case ACTION_DISCONNECT:
                    stopVpnAndCore();
                    stopSelf();
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    private void startVpnAndCore() {
        updateState(STATE_CONNECTING);
        startForeground(NOTIF_ID, createNotification("Aether - Connecting..."));

        new Thread(() -> {
            try {
                // 1. Extract binary
                File binaryFile = extractNativeBinary();
                if (binaryFile == null || !binaryFile.exists()) {
                    broadcastLog("error", "Failed to resolve Aether core binary");
                    updateState(STATE_ERROR);
                    return;
                }

                // Make executable
                binaryFile.setExecutable(true, false);

                // 2. Setup environment
                ProcessBuilder pb = new ProcessBuilder(binaryFile.getAbsolutePath());
                Map<String, String> env = pb.environment();
                
                int socksPort = configManager.getSocksPort();
                String socksAddr = "127.0.0.1:" + socksPort;
                env.put("AETHER_SOCKS", socksAddr);
                env.put("AETHER_PROTOCOL", configManager.getProtocol());
                env.put("AETHER_TRANSPORT", configManager.getTransport());
                env.put("AETHER_SCAN_MODE", configManager.getScanMode());
                env.put("AETHER_STATS", "1");
                env.put("AETHER_STATS_SECS", "2");

                pb.redirectErrorStream(true);

                broadcastLog("info", "Spawning Aether Core binary on SOCKS " + socksAddr);
                aetherProcess = pb.start();

                // 3. Read stdout log stream asynchronously
                startLogReader(aetherProcess.getInputStream());

                // 4. Setup TUN if VPN mode selected
                if ("vpn".equals(configManager.getRoutingMode())) {
                    setupTunAdapter(socksPort);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error starting Aether process", e);
                broadcastLog("error", "Process Execution Error: " + e.getMessage());
                updateState(STATE_ERROR);
            }
        }).start();
    }

    private File extractNativeBinary() {
        try {
            // Find embedded libaether.so in nativeLibraryDir
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            File nativeBinary = new File(nativeDir, "libaether.so");
            if (nativeBinary.exists()) {
                return nativeBinary;
            }

            // Fallback: Copy from assets or app private dir
            File targetFile = new File(getFilesDir(), "aether_core");
            if (!targetFile.exists()) {
                try (InputStream in = getAssets().open("aether");
                     FileOutputStream out = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
            return targetFile;
        } catch (Exception e) {
            Log.e(TAG, "Binary extraction failed", e);
            return null;
        }
    }

    private void setupTunAdapter(int socksPort) {
        try {
            Builder builder = new Builder();
            builder.setMtu(configManager.getTunMtu());
            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("1.1.1.1");
            builder.setSession("Aether VPN");

            vpnInterface = builder.establish();
            broadcastLog("info", "TUN Adapter established successfully.");
        } catch (Exception e) {
            broadcastLog("error", "Failed to establish TUN Interface: " + e.getMessage());
        }
    }

    private void startLogReader(InputStream inputStream) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String level = "info";
                    if (line.contains("ERR") || line.contains("error")) {
                        level = "error";
                    } else if (line.contains("WARN") || line.contains("warning")) {
                        level = "warn";
                    }

                    broadcastLog(level, line);

                    // Detect successful connection
                    if (line.contains("socks5 server listening") || line.contains("connected successfully")) {
                        updateState(STATE_CONNECTED);
                        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        nm.notify(NOTIF_ID, createNotification("Aether - Connected & Protected"));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Log reader closed", e);
            }
        }).start();
    }

    private void stopVpnAndCore() {
        updateState(STATE_DISCONNECTED);
        if (aetherProcess != null) {
            aetherProcess.destroy();
            aetherProcess = null;
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception ignored) {}
            vpnInterface = null;
        }
        broadcastLog("info", "Aether Core and VPN service stopped.");
    }

    private void updateState(String state) {
        this.currentState = state;
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.putExtra(EXTRA_STATE, state);
        sendBroadcast(intent);
    }

    private void broadcastLog(String level, String message) {
        Intent intent = new Intent(ACTION_LOG_EVENT);
        intent.putExtra(EXTRA_LOG_LEVEL, level);
        intent.putExtra(EXTRA_LOG_MESSAGE, message);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Aether VPN Status",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String title) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("Tap to open Aether Dashboard")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        stopVpnAndCore();
        super.onDestroy();
    }
}
