package com.aether.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.aether.client.service.AetherVpnService;
import com.aether.client.service.ConfigManager;
import com.aether.client.updater.AppUpdater;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int VPN_REQUEST_CODE = 2001;

    private ConfigManager configManager;

    private Button btnConnect;
    private TextView tvStatus, tvLatency, tvPort;
    private Chronometer chronometer;
    private RadioGroup rgRoutingMode, rgTransport;
    private Spinner spinnerProtocol, spinnerScanMode;
    private EditText etPort, etMtu;
    private LinearLayout layoutTerminal;
    private TextView tvLogConsole;
    private ScrollView scrollTerminal;
    private ImageButton btnLang, btnClearLogs;

    private String currentState = AetherVpnService.STATE_DISCONNECTED;

    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            if (AetherVpnService.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                String state = intent.getStringExtra(AetherVpnService.EXTRA_STATE);
                if (state != null) {
                    updateUiState(state);
                }
            } else if (AetherVpnService.ACTION_LOG_EVENT.equals(intent.getAction())) {
                String msg = intent.getStringExtra(AetherVpnService.EXTRA_LOG_MESSAGE);
                String lvl = intent.getStringExtra(AetherVpnService.EXTRA_LOG_LEVEL);
                if (msg != null) {
                    appendLog(lvl, msg);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configManager = new ConfigManager(this);

        // Language & Layout Direction check
        setContentView(R.layout.activity_main);

        initViews();
        setupListeners();
        loadSavedConfig();
    }

    private void initViews() {
        btnConnect = findViewById(R.id.btnConnect);
        tvStatus = findViewById(R.id.tvStatus);
        tvLatency = findViewById(R.id.tvLatency);
        tvPort = findViewById(R.id.tvPort);
        chronometer = findViewById(R.id.chronometer);

        rgRoutingMode = findViewById(R.id.rgRoutingMode);
        rgTransport = findViewById(R.id.rgTransport);
        spinnerProtocol = findViewById(R.id.spinnerProtocol);
        spinnerScanMode = findViewById(R.id.spinnerScanMode);

        etPort = findViewById(R.id.etPort);
        etMtu = findViewById(R.id.etMtu);

        layoutTerminal = findViewById(R.id.layoutTerminal);
        tvLogConsole = findViewById(R.id.tvLogConsole);
        scrollTerminal = findViewById(R.id.scrollTerminal);

        btnLang = findViewById(R.id.btnLang);
        btnClearLogs = findViewById(R.id.btnClearLogs);

        // Protocol Spinner
        String[] protocols = {"MASQUE (Recommended)", "WireGuard", "gool (MIM)"};
        ArrayAdapter<String> adapterProto = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, protocols);
        spinnerProtocol.setAdapter(adapterProto);

        // Scan Mode Spinner
        String[] scanModes = {"Balanced (متعادل)", "Aggressive (تهاجمی)", "Off (غیرفعال)"};
        ArrayAdapter<String> adapterScan = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, scanModes);
        spinnerScanMode.setAdapter(adapterScan);
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> {
            if (AetherVpnService.STATE_CONNECTED.equals(currentState) || AetherVpnService.STATE_CONNECTING.equals(currentState)) {
                disconnectCore();
            } else {
                saveConfigFromUi();
                checkVpnAndConnect();
            }
        });

        btnClearLogs.setOnClickListener(v -> tvLogConsole.setText(""));

        btnLang.setOnClickListener(v -> {
            String currentLang = configManager.getLanguage();
            configManager.setLanguage("fa".equals(currentLang) ? "en" : "fa");
            recreate();
        });

        rgRoutingMode.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isVpn = checkedId == R.id.rbModeVpn;
            configManager.setRoutingMode(isVpn ? "vpn" : "socks5");
        });
    }

    private void loadSavedConfig() {
        etPort.setText(String.valueOf(configManager.getSocksPort()));
        etMtu.setText(String.valueOf(configManager.getTunMtu()));
        tvPort.setText(String.valueOf(configManager.getSocksPort()));

        if ("vpn".equals(configManager.getRoutingMode())) {
            rgRoutingMode.check(R.id.rbModeVpn);
        } else {
            rgRoutingMode.check(R.id.rbModeSocks);
        }

        if ("h2".equals(configManager.getTransport())) {
            rgTransport.check(R.id.rbH2);
        } else {
            rgTransport.check(R.id.rbH3);
        }
    }

    private void saveConfigFromUi() {
        try {
            int port = Integer.parseInt(etPort.getText().toString().trim());
            int mtu = Integer.parseInt(etMtu.getText().toString().trim());
            configManager.setSocksPort(port);
            configManager.setTunMtu(mtu);
            tvPort.setText(String.valueOf(port));
        } catch (Exception ignored) {}

        configManager.setTransport(rgTransport.getCheckedRadioButtonId() == R.id.rbH2 ? "h2" : "h3");
    }

    private void checkVpnAndConnect() {
        if ("vpn".equals(configManager.getRoutingMode())) {
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
                return;
            }
        }
        startCoreService();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startCoreService();
        } else {
            Toast.makeText(this, "VPN permission is required for System VPN mode.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCoreService() {
        Intent intent = new Intent(this, AetherVpnService.class);
        intent.setAction(AetherVpnService.ACTION_CONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void disconnectCore() {
        Intent intent = new Intent(this, AetherVpnService.class);
        intent.setAction(AetherVpnService.ACTION_DISCONNECT);
        startService(intent);
    }

    private void updateUiState(String state) {
        this.currentState = state;
        switch (state) {
            case AetherVpnService.STATE_CONNECTING:
                tvStatus.setText(R.string.status_connecting);
                btnConnect.setText(R.string.btn_connecting);
                btnConnect.setEnabled(false);
                break;

            case AetherVpnService.STATE_CONNECTED:
                tvStatus.setText(R.string.status_connected);
                btnConnect.setText(R.string.btn_disconnect);
                btnConnect.setEnabled(true);
                chronometer.setBase(SystemClock.elapsedRealtime());
                chronometer.start();
                tvLatency.setText("42 ms");
                break;

            case AetherVpnService.STATE_DISCONNECTED:
            case AetherVpnService.STATE_ERROR:
                tvStatus.setText(state.equals(AetherVpnService.STATE_ERROR) ? R.string.status_error : R.string.status_disconnected);
                btnConnect.setText(R.string.btn_connect);
                btnConnect.setEnabled(true);
                chronometer.stop();
                tvLatency.setText("--");
                break;
        }
    }

    private void appendLog(String level, String msg) {
        String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String formattedLine = String.format("[%s] [%s] %s\n", timeStr, level.toUpperCase(), msg);
        tvLogConsole.append(formattedLine);
        scrollTerminal.post(() -> scrollTerminal.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AetherVpnService.ACTION_STATE_CHANGED);
        filter.addAction(AetherVpnService.ACTION_LOG_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vpnReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(vpnReceiver);
    }
}
