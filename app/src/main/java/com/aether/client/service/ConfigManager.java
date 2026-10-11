package com.aether.client.service;

import android.content.Context;
import android.content.SharedPreferences;

public class ConfigManager {
    private static final String PREF_NAME = "aether_prefs";

    public static final String KEY_PROTOCOL = "protocol";
    public static final String KEY_TRANSPORT = "masque_transport";
    public static final String KEY_SCAN_MODE = "scan_mode";
    public static final String KEY_SOCKS_PORT = "socks_port";
    public static final String KEY_ROUTING_MODE = "routing_mode"; // "socks5" or "vpn"
    public static final String KEY_TUN_MTU = "tun_mtu";
    public static final String KEY_LANGUAGE = "language"; // "fa" or "en"

    private final SharedPreferences prefs;

    public ConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getProtocol() {
        return prefs.getString(KEY_PROTOCOL, "masque");
    }

    public void setProtocol(String protocol) {
        prefs.edit().putString(KEY_PROTOCOL, protocol).apply();
    }

    public String getTransport() {
        return prefs.getString(KEY_TRANSPORT, "h3");
    }

    public void setTransport(String transport) {
        prefs.edit().putString(KEY_TRANSPORT, transport).apply();
    }

    public String getScanMode() {
        return prefs.getString(KEY_SCAN_MODE, "balanced");
    }

    public void setScanMode(String scanMode) {
        prefs.edit().putString(KEY_SCAN_MODE, scanMode).apply();
    }

    public int getSocksPort() {
        return prefs.getInt(KEY_SOCKS_PORT, 1819);
    }

    public void setSocksPort(int port) {
        prefs.edit().putInt(KEY_SOCKS_PORT, port).apply();
    }

    public String getRoutingMode() {
        return prefs.getString(KEY_ROUTING_MODE, "socks5");
    }

    public void setRoutingMode(String mode) {
        prefs.edit().putString(KEY_ROUTING_MODE, mode).apply();
    }

    public int getTunMtu() {
        return prefs.getInt(KEY_TUN_MTU, 1500);
    }

    public void setTunMtu(int mtu) {
        prefs.edit().putInt(KEY_TUN_MTU, mtu).apply();
    }

    public String getLanguage() {
        return prefs.getString(KEY_LANGUAGE, "fa");
    }

    public void setLanguage(String lang) {
        prefs.edit().putString(KEY_LANGUAGE, lang).apply();
    }
}
