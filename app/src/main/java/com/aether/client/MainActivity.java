package com.aether.client;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.aether.client.core.ConnectionState;
import com.aether.client.core.CoreProcess;
import com.aether.client.core.Protocols;
import com.aether.client.core.Settings;
import com.aether.client.service.AetherVpnService;
import com.aether.client.ui.SettingsScreen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The single screen: a dashboard, a live log console, a settings screen built from the
 * active protocol's applicable controls, and an about tab.
 *
 * <p>It is a mirror and nothing more. Every state and every log line originates in the
 * service or the core process and arrives by broadcast; the activity holds no connection
 * state of its own, so it cannot drift from what is actually running. That matters
 * because the alternative -- the UI deciding what "connected" looks like - is how an app
 * ends up showing a green shield over a tunnel that carries nothing.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_VPN = 2001;
    private static final int REQUEST_NOTIFICATIONS = 2002;
    private static final int MAX_LOG_LINES = 2000;

    private Settings settings;
    private SettingsScreen settingsScreen;

    private Button btnConnect;
    private TextView tvStatus, tvProtocolName, tvProtocolNote, tvLatency, tvUptime, tvPort;
    private TextView tvUpload, tvDownload, tvErrorDetail, tvTitle, tvLogs;
    private View statusDot, spinner;
    private LinearLayout dashboardPanel, logsPanel, settingsPanel;
    private ScrollView logScroll;
    private Button btnModeVpn, btnModeProxy;
    private LinearLayout navDashboard, navLogs, navSettings, navAbout;
    private TextView navDashboardLabel, navLogsLabel, navSettingsLabel, navAboutLabel;
    private ImageView navDashboardIcon, navLogsIcon, navSettingsIcon, navAboutIcon;

    private ConnectionState state = ConnectionState.DISCONNECTED;
    private final List<String[]> logLines = new ArrayList<>();
    private int logFilter = 0; // 0 all, 1 warnings and errors, 2 errors only
    private long connectedAtMs;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            switch (intent.getAction()) {
                case AetherVpnService.ACTION_STATE:
                    String id = intent.getStringExtra(AetherVpnService.EXTRA_STATE);
                    String detail = intent.getStringExtra(AetherVpnService.EXTRA_DETAIL);
                    if (id != null) applyState(ConnectionState.fromId(id), detail);
                    break;
                case AetherVpnService.ACTION_LOG:
                    String line = intent.getStringExtra(AetherVpnService.EXTRA_LOG_LINE);
                    String level = intent.getStringExtra(AetherVpnService.EXTRA_LOG_LEVEL);
                    if (line != null) appendLog(line, level == null ? "INFO" : level);
                    break;
                case AetherVpnService.ACTION_METRICS:
                    applyMetrics(intent);
                    break;
                case AetherVpnService.ACTION_REQUEST_STATE:
                    // A reconnect or a cold start asks what is actually true rather than
                    // trusting a local field: only the service knows what is running.
                    sendBroadcast(new Intent(AetherVpnService.ACTION_STATE)
                            .setPackage(getPackageName()));
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        setContentView(R.layout.activity_main);

        bindViews();
        settingsScreen = new SettingsScreen(this, findViewById(R.id.settingsContainer),
                settings, this::refreshProtocolLabels);
        wireControls();

        selectTab(Tab.DASHBOARD);
        refreshProtocolLabels();
        requestNotificationPermission();
    }

    private void bindViews() {
        btnConnect = findViewById(R.id.btnConnect);
        tvStatus = findViewById(R.id.tvStatus);
        tvProtocolName = findViewById(R.id.tvProtocolName);
        tvProtocolNote = findViewById(R.id.tvProtocolNote);
        tvLatency = findViewById(R.id.tvLatency);
        tvUptime = findViewById(R.id.tvUptime);
        tvPort = findViewById(R.id.tvPort);
        tvUpload = findViewById(R.id.tvUpload);
        tvDownload = findViewById(R.id.tvDownload);
        tvErrorDetail = findViewById(R.id.tvErrorDetail);
        tvTitle = findViewById(R.id.tvTitle);
        tvLogs = findViewById(R.id.tvLogs);
        statusDot = findViewById(R.id.statusDot);
        spinner = findViewById(R.id.spinner);
        dashboardPanel = findViewById(R.id.dashboard);
        logsPanel = findViewById(R.id.logsPanel);
        settingsPanel = findViewById(R.id.settingsPanel);
        logScroll = findViewById(R.id.logScroll);
        btnModeVpn = findViewById(R.id.btnModeVpn);
        btnModeProxy = findViewById(R.id.btnModeProxy);

        navDashboard = findViewById(R.id.navDashboard);
        navLogs = findViewById(R.id.navLogs);
        navSettings = findViewById(R.id.navSettings);
        navAbout = findViewById(R.id.navAbout);
        navDashboardLabel = findViewById(R.id.navDashboardLabel);
        navLogsLabel = findViewById(R.id.navLogsLabel);
        navSettingsLabel = findViewById(R.id.navSettingsLabel);
        navAboutLabel = findViewById(R.id.navAboutLabel);
        navDashboardIcon = ((LinearLayout) navDashboard).getChildAt(0);
        navLogsIcon = ((LinearLayout) navLogs).getChildAt(0);
        navSettingsIcon = ((LinearLayout) navSettings).getChildAt(0);
        navAboutIcon = ((LinearLayout) navAbout).getChildAt(0);
    }

    private void wireControls() {
        btnConnect.setOnClickListener(v -> {
            if (state.isUp() || state.isBusy()) disconnect();
            else requestConnect();
        });

        btnModeVpn.setOnClickListener(v -> setRoutingMode("vpn"));
        btnModeProxy.setOnClickListener(v -> setRoutingMode("proxy"));

        findViewById(R.id.btnProtocolPrev).setOnClickListener(v -> stepProtocol(-1));
        findViewById(R.id.btnProtocolNext).setOnClickListener(v -> stepProtocol(1));
        findViewById(R.id.btnProtocolList).setOnClickListener(v -> showProtocolPicker());

        findViewById(R.id.btnClearLogs).setOnClickListener(v -> {
            logLines.clear();
            tvLogs.setText("");
        });
        findViewById(R.id.btnCopyLogs).setOnClickListener(v -> copyLogs());

        findViewById(R.id.btnFilterAll).setOnClickListener(v -> { logFilter = 0; renderLogs(); });
        findViewById(R.id.btnFilterWarn).setOnClickListener(v -> { logFilter = 1; renderLogs(); });
        findViewById(R.id.btnFilterError).setOnClickListener(v -> { logFilter = 2; renderLogs(); });

        findViewById(R.id.btnLanguage).setOnClickListener(v -> {
            settings.putString(Settings.K_LANGUAGE, "fa".equals(settings.language()) ? "en" : "fa");
            recreate();
        });

        navDashboard.setOnClickListener(v -> selectTab(Tab.DASHBOARD));
        navLogs.setOnClickListener(v -> selectTab(Tab.LOGS));
        navSettings.setOnClickListener(v -> selectTab(Tab.SETTINGS));
        navAbout.setOnClickListener(v -> selectTab(Tab.ABOUT));
    }

    private enum Tab { DASHBOARD, LOGS, SETTINGS, ABOUT }

    private void selectTab(Tab tab) {
        dashboardPanel.setVisibility(tab == Tab.DASHBOARD ? View.VISIBLE : View.GONE);
        logsPanel.setVisibility(tab == Tab.LOGS ? View.VISIBLE : View.GONE);
        settingsPanel.setVisibility(tab == Tab.SETTINGS ? View.VISIBLE : View.GONE);

        if (tab == Tab.SETTINGS) settingsScreen.rebuild();
        if (tab == Tab.ABOUT) showAbout();

        int cyan = getColor(R.color.cyan);
        int muted = getColor(R.color.text_muted);
        navDashboardLabel.setTextColor(tab == Tab.DASHBOARD ? cyan : muted);
        navDashboardIcon.setColorFilter(tab == Tab.DASHBOARD ? cyan : muted);
        navLogsLabel.setTextColor(tab == Tab.LOGS ? cyan : muted);
        navLogsIcon.setColorFilter(tab == Tab.LOGS ? cyan : muted);
        navSettingsLabel.setTextColor(tab == Tab.SETTINGS ? cyan : muted);
        navSettingsIcon.setColorFilter(tab == Tab.SETTINGS ? cyan : muted);
        navAboutLabel.setTextColor(tab == Tab.ABOUT ? cyan : muted);
        navAboutIcon.setColorFilter(tab == Tab.ABOUT ? cyan : muted);

        tvTitle.setText(tab == Tab.ABOUT ? getString(R.string.tab_about) : getString(R.string.app_name));
        if (tab == Tab.ABOUT) dashboardPanel.setVisibility(View.VISIBLE);
    }

    // ---- connect / disconnect -----------------------------------------------------------

    private void requestConnect() {
        if (!validate()) return;
        if ("vpn".equals(settings.routingMode())) {
            Intent consent = VpnService.prepare(this);
            if (consent != null) {
                startActivityForResult(consent, REQUEST_VPN);
                return;
            }
        }
        sendServiceAction(AetherVpnService.ACTION_CONNECT);
    }

    private void disconnect() {
        sendServiceAction(AetherVpnService.ACTION_DISCONNECT);
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, AetherVpnService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    /**
     * Refuses an impossible combination here rather than letting the service spawn a core
     * that cannot succeed. The checks mirror the service's, because a refusal after a
     * process has been spawned looks like a core failure and sends the user to the wrong
     * place for the cause.
     */
    private boolean validate() {
        String protocol = settings.protocol();
        if (!Protocols.isKnown(protocol)) {
            toast(getString(R.string.state_error) + ": unknown protocol");
            return false;
        }
        boolean vpnMode = "vpn".equals(settings.routingMode());
        if (!vpnMode && !Protocols.visibleInProxyMode(protocol)) {
            toast("This protocol needs Device VPN mode.");
            return false;
        }
        String upstream = settings.upstreamProxy().trim();
        if (!upstream.isEmpty()) {
            if (!upstream.startsWith("socks5://") && !upstream.startsWith("http://")
                    && !upstream.startsWith("https://")) {
                toast(getString(R.string.label_upstream) + ": unsupported scheme");
                return false;
            }
            if (Protocols.chain(protocol) != Protocols.Chain.NONE) {
                toast("An upstream proxy cannot be combined with a privacy chain.");
                return false;
            }
        }
        String socks = settings.socksAddress();
        if (!socks.contains(":")) {
            toast(getString(R.string.label_socks_address) + ": expected IP:port");
            return false;
        }
        if (!socks.startsWith("127.0.0.1") && !settings.remoteListener()) {
            toast(getString(R.string.label_remote_listener_warn));
            return false;
        }
        if (vpnMode && (settings.tunMtu() < Settings.MIN_MTU || settings.tunMtu() > Settings.MAX_MTU)) {
            toast(getString(R.string.label_mtu) + ": " + Settings.MIN_MTU + "–" + Settings.MAX_MTU);
            return false;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                sendServiceAction(AetherVpnService.ACTION_CONNECT);
            } else {
                toast("VPN permission was refused, so the tunnel cannot be built.");
            }
        }
    }

    // ---- protocol picker ---------------------------------------------------------------

    private void setRoutingMode(String mode) {
        settings.putString(Settings.K_ROUTING_MODE, mode);
        refreshModeButtons();
        refreshProtocolLabels();
        settingsScreen.rebuild();
    }

    private void refreshModeButtons() {
        boolean vpn = "vpn".equals(settings.routingMode());
        btnModeVpn.setBackgroundResource(vpn ? R.drawable.bg_segment_selected : android.R.color.transparent);
        btnModeProxy.setBackgroundResource(vpn ? android.R.color.transparent : R.drawable.bg_segment_selected);
        btnModeVpn.setTextColor(getColor(vpn ? R.color.cyan : R.color.text_muted));
        btnModeProxy.setTextColor(getColor(vpn ? R.color.text_muted : R.color.cyan));
    }

    /** Steps through the visible protocols, skipping the ones hidden in the current mode. */
    private void stepProtocol(int delta) {
        boolean vpnMode = "vpn".equals(settings.routingMode());
        List<String> visible = new ArrayList<>();
        for (String protocol : Protocols.DISPLAY_ORDER) {
            if (!vpnMode && !Protocols.visibleInProxyMode(protocol)) continue;
            visible.add(protocol);
        }
        int index = visible.indexOf(settings.protocol());
        if (index < 0) index = 0;
        index = (index + delta + visible.size()) % visible.size();
        settings.putString(Settings.K_PROTOCOL, visible.get(index));
        refreshProtocolLabels();
        settingsScreen.rebuild();
    }

    private void showProtocolPicker() {
        boolean vpnMode = "vpn".equals(settings.routingMode());
        String language = settings.language();
        List<String> visible = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        for (String protocol : Protocols.DISPLAY_ORDER) {
            if (!vpnMode && !Protocols.visibleInProxyMode(protocol)) continue;
            visible.add(protocol);
            labels.add(Protocols.label(protocol, language) + "\n" + Protocols.description(protocol, language));
            notes.add(protocol);
        }

        int checked = Math.max(0, visible.indexOf(settings.protocol()));
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_protocol)
                .setSingleChoiceItems(labels.toArray(new CharSequence[0]), checked, (dialog, which) -> {
                    settings.putString(Settings.K_PROTOCOL, visible.get(which));
                    refreshProtocolLabels();
                    settingsScreen.rebuild();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private void refreshProtocolLabels() {
        String protocol = settings.protocol();
        String language = settings.language();
        tvProtocolName.setText(Protocols.label(protocol, language));
        tvProtocolNote.setText(Protocols.description(protocol, language));
        tvPort.setText(String.valueOf(socksPort()));
        refreshModeButtons();
    }

    private int socksPort() {
        String configured = settings.socksAddress();
        int colon = configured.lastIndexOf(':');
        if (colon > 0 && colon < configured.length() - 1) {
            try {
                return Integer.parseInt(configured.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 1819;
    }

    // ---- state rendering ---------------------------------------------------------------

    private void applyState(ConnectionState next, String detail) {
        this.state = next;
        switch (next) {
            case DISCONNECTED:
                statusDot.setBackgroundResource(R.drawable.dot_idle);
                tvStatus.setText(R.string.state_disconnected);
                btnConnect.setText(R.string.action_connect);
                btnConnect.setEnabled(true);
                btnConnect.setBackgroundResource(R.drawable.bg_connect);
                btnConnect.setTextColor(getColor(R.color.cyan));
                spinner.setVisibility(View.GONE);
                tvUptime.setText("00:00:00");
                tvLatency.setText("--");
                tvUpload.setText("0 KB");
                tvDownload.setText("0 KB");
                hideError();
                break;
            case STARTING:
            case SCANNING:
            case SECURING:
                statusDot.setBackgroundResource(R.drawable.dot_busy);
                tvStatus.setText(next == ConnectionState.STARTING
                        ? R.string.state_starting
                        : next == ConnectionState.SCANNING
                        ? R.string.state_scanning : R.string.state_securing);
                btnConnect.setText(R.string.action_disconnect);
                btnConnect.setEnabled(true);
                btnConnect.setBackgroundResource(R.drawable.bg_connect);
                btnConnect.setTextColor(getColor(R.color.amber));
                spinner.setVisibility(View.VISIBLE);
                hideError();
                break;
            case CONNECTED:
                statusDot.setBackgroundResource(R.drawable.dot_up);
                tvStatus.setText(R.string.state_connected);
                btnConnect.setText(R.string.action_disconnect);
                btnConnect.setEnabled(true);
                btnConnect.setBackgroundResource(R.drawable.bg_connect);
                btnConnect.setTextColor(getColor(R.color.emerald));
                spinner.setVisibility(View.GONE);
                connectedAtMs = System.currentTimeMillis();
                startUptimeTicker();
                hideError();
                break;
            case DISCONNECTING:
                statusDot.setBackgroundResource(R.drawable.dot_idle);
                tvStatus.setText(R.string.state_disconnecting);
                btnConnect.setEnabled(false);
                spinner.setVisibility(View.VISIBLE);
                break;
            case ERROR:
                statusDot.setBackgroundResource(R.drawable.dot_error);
                tvStatus.setText(R.string.state_error);
                btnConnect.setText(R.string.action_connect);
                btnConnect.setEnabled(true);
                spinner.setVisibility(View.GONE);
                tvUptime.setText("00:00:00");
                tvLatency.setText("--");
                if (detail != null) showError(detail);
                break;
            default:
                break;
        }
    }

    private void startUptimeTicker() {
        new Thread(() -> {
            while (state == ConnectionState.CONNECTED && !isFinishing() && !isDestroyed()) {
                long elapsed = (System.currentTimeMillis() - connectedAtMs) / 1000;
                runOnUiThread(() -> tvUptime.setText(formatUptime(elapsed)));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "aether-uptime").start();
    }

    private void applyMetrics(Intent intent) {
        long uptime = intent.getLongExtra(AetherVpnService.EXTRA_UPTIME_MS, 0);
        long up = intent.getLongExtra(AetherVpnService.EXTRA_UPLOADED, 0);
        long down = intent.getLongExtra(AetherVpnService.EXTRA_DOWNLOADED, 0);
        tvUptime.setText(formatUptime(uptime / 1000));
        tvUpload.setText(formatBytes(up));
        tvDownload.setText(formatBytes(down));
        // The service does not measure latency separately: the interval between traffic
        // probes is the honest upper bound available without adding a second probe.
        tvLatency.setText(up > 0 || down > 0 ? "active" : "--");
    }

    private void showError(String detail) {
        tvErrorDetail.setText(detail);
        tvErrorDetail.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        tvErrorDetail.setVisibility(View.GONE);
    }

    // ---- logs --------------------------------------------------------------------------

    private void appendLog(String line, String level) {
        String stamp = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new java.util.Date());
        logLines.add(new String[]{level, stamp, line});
        // Bounded: an upstream that reconnects in a loop can emit faster than the UI can
        // lay out, and an unbounded list is what turns a stuck tunnel into an out-of-memory.
        while (logLines.size() > MAX_LOG_LINES) logLines.remove(0);
        renderLogs();
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void renderLogs() {
        StringBuilder builder = new StringBuilder();
        for (String[] entry : logLines) {
            String level = entry[0];
            if (logFilter == 1 && !level.equals("WARN") && !level.equals("ERROR")) continue;
            if (logFilter == 2 && !level.equals("ERROR")) continue;
            int color = logColor(level);
            builder.append(String.format("%s  %-5s  %s%n",
                    entry[1], level, entry[2]));
        }
        String text = builder.length() == 0 ? getString(R.string.logs_empty) : builder.toString();
        tvLogs.setText(text);
        tvLogs.setTextColor(logLines.isEmpty() ? getColor(R.color.text_tertiary) : getColor(R.color.emerald));
    }

    private int logColor(String level) {
        if (level == null) return getColor(R.color.emerald);
        switch (level) {
            case "ERROR": return getColor(R.color.rose);
            case "WARN": return getColor(R.color.amber);
            case "DEBUG": return getColor(R.color.text_muted);
            default: return getColor(R.color.emerald);
        }
    }

    private void copyLogs() {
        StringBuilder builder = new StringBuilder();
        for (String[] entry : logLines) {
            builder.append(String.format("%s [%s] %s%n", entry[1], entry[0], entry[2]));
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("aether-logs", builder.toString()));
            toast(getString(R.string.logs_copied));
        }
    }

    // ---- about -------------------------------------------------------------------------

    private void showAbout() {
        String coreVersion = "2.3.0";
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        String message = getString(R.string.about_core) + ": " + coreVersion
                + "\n" + getString(R.string.about_version) + ": " + BuildConfig.VERSION_NAME
                + "\nABI: " + abi
                + "\n" + getString(R.string.label_protocol) + ": "
                + Protocols.label(settings.protocol(), settings.language());
        new AlertDialog.Builder(this)
                .setTitle(R.string.tab_about)
                .setMessage(message)
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    // ---- lifecycle ---------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AetherVpnService.ACTION_STATE);
        filter.addAction(AetherVpnService.ACTION_LOG);
        filter.addAction(AetherVpnService.ACTION_METRICS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        refreshProtocolLabels();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }

    private void requestNotificationPermission() {
        // A foreground service whose notification cannot be shown is a foreground service
        // the user cannot stop, and on some OEM builds it is killed instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }

    private static String formatUptime(long seconds) {
        return String.format(Locale.US, "%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}