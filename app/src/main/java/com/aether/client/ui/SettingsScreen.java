package com.aether.client.ui;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aether.client.R;
import com.google.android.material.button.MaterialButtonToggleGroup;

/**
 * Builds the settings screen from code rather than a layout file.
 *
 * <p>The alternative -- a static XML with fifty rows -- would mean every row is visible
 * regardless of the protocol, so the MASQUE transport control would sit there for a
 * WireGuard session where it means nothing. Controls that do not apply are not built at
 * all, which is the only way a user can tell which settings actually do something.
 *
 * <p>Every control writes through to {@link com.aether.client.core.Settings} on change.
 * There is no separate Save button on purpose: these are preferences, and a value the
 * user has just set and cannot see stored until they navigate away reads as broken.
 */
public final class SettingsScreen {

    private final Context context;
    private final LinearLayout container;
    private final com.aether.client.core.Settings settings;
    private final Runnable onChanged;

    public SettingsScreen(Context context,
                          LinearLayout container,
                          com.aether.client.core.Settings settings,
                          Runnable onChanged) {
        this.context = context;
        this.container = container;
        this.settings = settings;
        this.onChanged = onChanged;
    }

    /** Rebuilds the whole screen. Cheap enough to do whenever the protocol changes. */
    public void rebuild() {
        container.removeAllViews();
        String protocol = settings.protocol();
        String language = settings.language();
        boolean vpnMode = "vpn".equals(settings.routingMode());

        section(R.string.section_protocol);

        spinner(R.string.label_protocol, protocolsForMode(vpnMode), currentIndex(vpnMode),
                index -> settings.putString(com.aether.client.core.Settings.K_PROTOCOL,
                        com.aether.client.core.Protocols.DISPLAY_ORDER[index]));

        hint(com.aether.client.core.Protocols.description(protocol, language));

        spinner(R.string.label_scan_mode, scanLabels(language), valueIndex(
                        com.aether.client.core.Settings.SCAN_MODES, settings.scanMode()),
                index -> settings.putString(com.aether.client.core.Settings.K_SCAN_MODE,
                        com.aether.client.core.Settings.SCAN_MODES[index]));

        // The MASQUE connection method applies to the masque and mim bases only. A
        // combined entry inherits it from its base.
        if (com.aether.client.core.Protocols.masqueTransportApplicable(protocol)) {
            spinner(R.string.label_masque_transport, transportLabels(language),
                    valueIndex(com.aether.client.core.Settings.MASQUE_TRANSPORTS, settings.masqueTransport()),
                    index -> settings.putString(com.aether.client.core.Settings.K_MASQUE_TRANSPORT,
                            com.aether.client.core.Settings.MASQUE_TRANSPORTS[index]));
            toggle(R.string.label_quic_v2, settings.quicV2(),
                    value -> settings.putBool(com.aether.client.core.Settings.K_QUIC_V2, value));
        }

        if (com.aether.client.core.Protocols.goolModeApplicable(protocol)) {
            spinner(R.string.label_gool_mode, goolLabels(language),
                    valueIndex(com.aether.client.core.Settings.GOOL_MODES, settings.goolMode()),
                    index -> settings.putString(com.aether.client.core.Settings.K_GOOL_MODES,
                            com.aether.client.core.Settings.GOOL_MODES[index]));
        }

        spacer();

        // ---- privacy chain -------------------------------------------------------------
        com.aether.client.core.Protocols.Chain chain = com.aether.client.core.Protocols.chain(protocol);
        if (chain != com.aether.client.core.Protocols.Chain.NONE) {
            section(R.string.section_privacy);
        }
        if (chain == com.aether.client.core.Protocols.Chain.PSIPHON) {
            spinner(R.string.label_psiphon_transport, psiphonTransportLabels(language),
                    valueIndex(com.aether.client.core.Settings.PSIPHON_TRANSPORTS, settings.psiphonTransport()),
                    index -> settings.putString(com.aether.client.core.Settings.K_PSIPHON_TRANSPORT,
                            com.aether.client.core.Settings.PSIPHON_TRANSPORTS[index]));
            spinner(R.string.label_psiphon_region, regionLabels(language), regionIndex(),
                    index -> settings.putString(com.aether.client.core.Settings.K_PSIPHON_REGION,
                            index < 0 ? "" : com.aether.client.core.Settings.PSIPHON_COUNTRIES[index - 1]));
        }

        // ---- transport and network -----------------------------------------------------
        section(R.string.section_transport);
        spinner(R.string.label_obfuscation, obfuscationLabels(language),
                valueIndex(com.aether.client.core.Settings.OBFUSCATIONS, settings.obfuscation()),
                index -> settings.putString(com.aether.client.core.Settings.K_OBFUSCATION,
                        com.aether.client.core.Settings.OBFUSCATIONS[index]));
        spinner(R.string.label_ip_mode, ipModeLabels(language),
                valueIndex(com.aether.client.core.Settings.IP_MODES, settings.ipMode()),
                index -> settings.putString(com.aether.client.core.Settings.K_IP_MODE,
                        com.aether.client.core.Settings.IP_MODES[index]));
        text(R.string.label_log_level, settings.logLevel(), InputType.TYPE_CLASS_TEXT,
                value -> settings.putString(com.aether.client.core.Settings.K_LOG_LEVEL, value));
        text(R.string.label_socks_address, settings.socksAddress(), InputType.TYPE_CLASS_TEXT,
                value -> settings.putString(com.aether.client.core.Settings.K_SOCKS_ADDRESS, value));

        toggle(R.string.label_remote_listener, settings.remoteListener(), value -> {
            settings.putBool(com.aether.client.core.Settings.K_REMOTE_LISTENER, value);
            // The warning is only meaningful while the setting is on; rebuilding is the
            // simplest way to show and hide it with the toggle.
            rebuild();
        });
        if (settings.remoteListener()) {
            warning(R.string.label_remote_listener_warn);
        }

        // ---- routing -------------------------------------------------------------------
        section(R.string.section_routing);
        if (vpnMode) {
            spinner(R.string.label_routing_mode, routingLabels(language),
                    valueIndex(com.aether.client.core.Settings.ROUTING_MODES, settings.routingMode()),
                    index -> {
                        settings.putString(com.aether.client.core.Settings.K_ROUTING_MODE,
                                com.aether.client.core.Settings.ROUTING_MODES[index]);
                        rebuild();
                    });
            number(R.string.label_mtu, settings.tunMtu(),
                    value -> settings.putInt(com.aether.client.core.Settings.K_TUN_MTU, value));
            toggle(R.string.label_dns_leak, settings.dnsLeakProtection(),
                    value -> settings.putBool(com.aether.client.core.Settings.K_DNS_LEAK, value));
            if (!settings.dnsLeakProtection()) {
                warning(R.string.label_dns_leak_off);
            }
            toggle(R.string.label_ipv6 + " — " + ipv6ModeLabel(language),
                    "tunnel".equals(settings.ipv6Behavior()),
                    value -> settings.putString(com.aether.client.core.Settings.K_IPV6_BEHAVIOR,
                            value ? "tunnel" : "block"));
            toggle(R.string.label_route_sniff, settings.routeSniff(),
                    value -> settings.putBool(com.aether.client.core.Settings.K_ROUTE_SNIFF, value));
            number(R.string.label_route_sniff_ms, settings.routeSniffMs(),
                    value -> settings.putInt(com.aether.client.core.Settings.K_ROUTE_SNIFF_MS, value));
            text(R.string.label_route_exclusions, settings.routeExclusions(),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                    value -> settings.putString(com.aether.client.core.Settings.K_ROUTE_EXCLUSIONS, value));
            text(R.string.label_split_apps, settings.splitApplications(),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                    value -> settings.putString(com.aether.client.core.Settings.K_SPLIT_APPS, value));
            toggle(R.string.label_kill_switch, settings.killSwitch(),
                    value -> settings.putBool(com.aether.client.core.Settings.K_KILL_SWITCH, value));
        } else {
            hint("Proxy mode exposes the SOCKS5 listener only. Routing and DNS settings apply to the VPN interface.");
        }

        // ---- custom endpoints ----------------------------------------------------------
        String base = com.aether.client.core.Protocols.baseProtocol(protocol);
        if ("mim".equals(base)) {
            section(R.string.section_endpoints);
            text(R.string.label_mim_outer, settings.mimOuterPeer(), InputType.TYPE_CLASS_TEXT,
                    value -> settings.putString(com.aether.client.core.Settings.K_MIM_OUTER_PEER, value));
            text(R.string.label_mim_inner, settings.mimInnerPeer(), InputType.TYPE_CLASS_TEXT,
                    value -> settings.putString(com.aether.client.core.Settings.K_MIM_INNER_PEER, value));
        } else if ("gool".equals(base)) {
            section(R.string.section_endpoints);
            text(R.string.label_wiw_outer, settings.wiwOuterPeer(), InputType.TYPE_CLASS_TEXT,
                    value -> settings.putString(com.aether.client.core.Settings.K_WIW_OUTER_PEER, value));
            text(R.string.label_wiw_inner, settings.wiwInnerPeer(), InputType.TYPE_CLASS_TEXT,
                    value -> settings.putString(com.aether.client.core.Settings.K_WIW_INNER_PEER, value));
        } else {
            text(R.string.label_peer, settings.peer(), InputType.TYPE_CLASS_TEXT,
                    value -> settings.putString(com.aether.client.core.Settings.K_PEER, value));
        }

        // ---- tuning --------------------------------------------------------------------
        section(R.string.section_tuning);
        if (!"masque".equals(base) && !"mim".equals(base)) {
            number(R.string.label_wg_keepalive, settings.wgKeepalive(),
                    value -> settings.putInt(com.aether.client.core.Settings.K_WG_KEEPALIVE, value));
        }
        text(R.string.label_ech, settings.ech(), InputType.TYPE_CLASS_TEXT,
                value -> settings.putString(com.aether.client.core.Settings.K_ECH, value));
        toggle(R.string.label_h2_fragment, settings.h2Fragment(),
                value -> settings.putBool(com.aether.client.core.Settings.K_H2_FRAGMENT, value));
        if (settings.h2Fragment()) {
            text(R.string.label_h2_fragment_size, settings.h2FragmentSize(), InputType.TYPE_CLASS_TEXT,
                    value -> settings.putString(com.aether.client.core.Settings.K_H2_FRAGMENT_SIZE, value));
            text(R.string.label_h2_fragment_delay, settings.h2FragmentDelay(), InputType.TYPE_CLASS_TEXT,
                    value -> settings.putString(com.aether.client.core.Settings.K_H2_FRAGMENT_DELAY, value));
        }
        toggle(R.string.label_no_data_check, settings.noDataCheck(),
                value -> settings.putBool(com.aether.client.core.Settings.K_NO_DATA_CHECK, value));
        number(R.string.label_validate_secs, settings.validateSecs(),
                value -> settings.putInt(com.aether.client.core.Settings.K_VALIDATE_SECS, value));
        number(R.string.label_startup_secs, settings.startupSecs(),
                value -> settings.putInt(com.aether.client.core.Settings.K_STARTUP_SECS, value));
        number(R.string.label_reconnect_secs, settings.reconnectSecs(),
                value -> settings.putInt(com.aether.client.core.Settings.K_RECONNECT_SECS, value));
        number(R.string.label_stall_timeout, settings.stallTimeout(),
                value -> settings.putInt(com.aether.client.core.Settings.K_STALL_TIMEOUT, value));
        toggle(R.string.label_watchdog, settings.watchdog(),
                value -> settings.putBool(com.aether.client.core.Settings.K_WATCHDOG, value));
        toggle(R.string.label_quick_reconnect, settings.quickReconnect(),
                value -> settings.putBool(com.aether.client.core.Settings.K_QUICK_RECONNECT, value));
        text(R.string.label_upstream, settings.upstreamProxy(), InputType.TYPE_CLASS_TEXT,
                value -> settings.putString(com.aether.client.core.Settings.K_UPSTREAM_PROXY, value));

        // ---- app -----------------------------------------------------------------------
        section(R.string.section_app);
        spinner(R.string.label_language, languageLabels(), "fa".equals(language) ? 0 : 1,
                index -> settings.putString(com.aether.client.core.Settings.K_LANGUAGE,
                        index == 0 ? "fa" : "en"));
        spinner(R.string.label_appearance, appearanceLabels(language),
                valueIndex(new String[]{"system", "light", "dark"}, settings.appearance()),
                index -> settings.putString(com.aether.client.core.Settings.K_APPEARANCE,
                        new String[]{"system", "light", "dark"}[index]));
        toggle(R.string.label_auto_connect, settings.autoConnect(),
                value -> settings.putBool(com.aether.client.core.Settings.K_AUTO_CONNECT, value));
        toggle(R.string.label_auto_update, settings.automaticUpdates(),
                value -> settings.putBool(com.aether.client.core.Settings.K_AUTO_UPDATE, value));

        spacer();
        resetButton();
    }

    // ---- builders ----------------------------------------------------------------------

    private void section(int titleRes) {
        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setTextColor(context.getColor(R.color.cyan));
        title.setTextSize(12f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(22);
        params.bottomMargin = dp(8);
        container.addView(title, params);
    }

    private void spinner(int labelRes, String[] options, int selected, IndexListener listener) {
        LinearLayout row = row();
        TextView label = new TextView(context);
        label.setText(labelRes);
        label.setTextColor(context.getColor(R.color.text_secondary));
        label.setTextSize(13f);
        row.addView(label);

        MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(context);
        group.setSingleSelection(true);
        group.setSelectionPadding(0);
        group.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < options.length; i++) {
            com.google.android.material.button.MaterialButton button =
                    new com.google.android.material.button.MaterialButton(context, null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setText(options[i]);
            button.setTextSize(12f);
            button.setAllCaps(false);
            button.setCheckable(true);
            button.setPadding(dp(8), 0, dp(8), 0);
            group.addView(button, 0, new LinearLayout.LayoutParams(
                    0, dp(42), 1f));
            if (i == selected) button.setChecked(true);
        }
        if (selected >= 0 && selected < options.length) group.check(group.getChildAt(selected).getId());

        group.addOnButtonCheckedListener((toggleGroup, checkedId, isChecked) -> {
            if (!isChecked) return;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i).getId() == checkedId) {
                    listener.onIndex(i);
                    if (onChanged != null) onChanged.run();
                    return;
                }
            }
        });
        row.addView(group);
        container.addView(row, rowParams());
    }

    private void text(int labelRes, String value, int inputType, StringListener listener) {
        LinearLayout row = row();
        TextView label = new TextView(context);
        label.setText(labelRes);
        label.setTextColor(context.getColor(R.color.text_secondary));
        label.setTextSize(13f);
        row.addView(label);

        EditText input = new EditText(context);
        input.setText(value);
        input.setInputType(inputType);
        input.setTextColor(context.getColor(R.color.text_primary));
        input.setHintTextColor(context.getColor(R.color.text_tertiary));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setTextSize(13f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        params.topMargin = dp(6);
        row.addView(input, params);

        // Written on change rather than on focus loss, so switching tabs does not silently
        // drop an edit the user already finished typing.
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(CharSequence text) {
                listener.onValue(text.toString());
            }
        });
        container.addView(row, rowParams());
    }

    private void number(int labelRes, int value, IntListener listener) {
        LinearLayout row = row();
        TextView label = new TextView(context);
        label.setText(labelRes);
        label.setTextColor(context.getColor(R.color.text_secondary));
        label.setTextSize(13f);
        row.addView(label);

        EditText input = new EditText(context);
        input.setText(String.valueOf(value));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(context.getColor(R.color.text_primary));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setTextSize(13f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        params.topMargin = dp(6);
        row.addView(input, params);

        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(CharSequence text) {
                try {
                    listener.onValue(Integer.parseInt(text.toString().trim()));
                } catch (NumberFormatException ignored) {
                    // mid-edit, or cleared: the previous value stands until it parses
                }
            }
        });
        container.addView(row, rowParams());
    }

    private void toggle(int labelRes, boolean checked, BoolListener listener) {
        CheckBox box = new CheckBox(context);
        box.setText(labelRes);
        box.setTextColor(context.getColor(R.color.text_secondary));
        box.setTextSize(13f);
        box.setChecked(checked);
        box.setMinHeight(dp(44));
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setOnCheckedChangeListener((view, value) -> {
            listener.onValue(value);
            if (onChanged != null) onChanged.run();
        });
        container.addView(box, rowParams());
    }

    private void hint(String value) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextColor(context.getColor(R.color.text_tertiary));
        text.setTextSize(11f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        container.addView(text, params);
    }

    private void warning(int labelRes) {
        TextView text = new TextView(context);
        text.setText(labelRes);
        text.setTextColor(context.getColor(R.color.amber));
        text.setTextSize(11f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        params.bottomMargin = dp(4);
        container.addView(text, params);
    }

    private void resetButton() {
        com.google.android.material.button.MaterialButton button =
                new com.google.android.material.button.MaterialButton(context, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(R.string.action_reset);
        button.setTextSize(13f);
        button.setAllCaps(false);
        button.setOnClickListener(v -> {
            settings.resetToDefaults();
            rebuild();
            if (onChanged != null) onChanged.run();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        params.topMargin = dp(20);
        container.addView(button, params);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        return params;
    }

    private void spacer() {
        View view = new View(context);
        container.addView(view, new LinearLayout.LayoutParams(1, dp(1)));
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // ---- label tables -----------------------------------------------------------------

    private String[] protocolsForMode(boolean vpnMode) {
        String language = settings.language();
        int count = 0;
        for (String protocol : com.aether.client.core.Protocols.DISPLAY_ORDER) {
            if (!vpnMode && !com.aether.client.core.Protocols.visibleInProxyMode(protocol)) continue;
            count++;
        }
        String[] out = new String[count];
        int index = 0;
        for (String protocol : com.aether.client.core.Protocols.DISPLAY_ORDER) {
            // Chained entries are hidden in proxy mode, never deleted: a saved chained
            // preference has to survive the switch so full-device mode restores it.
            if (!vpnMode && !com.aether.client.core.Protocols.visibleInProxyMode(protocol)) continue;
            out[index++] = com.aether.client.core.Protocols.label(protocol, language);
        }
        return out;
    }

    private int currentIndex(boolean vpnMode) {
        String current = settings.protocol();
        int index = 0;
        for (String protocol : com.aether.client.core.Protocols.DISPLAY_ORDER) {
            if (!vpnMode && !com.aether.client.core.Protocols.visibleInProxyMode(protocol)) continue;
            if (protocol.equals(current)) return index;
            index++;
        }
        return 0;
    }

    private String[] scanLabels(String language) {
        boolean fa = "fa".equals(language);
        return new String[]{
                fa ? "توربو" : "Turbo",
                fa ? "متعادل" : "Balanced",
                fa ? "کامل" : "Thorough",
                // "stealth" is the stored value; "Verified" is what v2.3.0 calls it.
                fa ? "تأییدشده" : "Verified",
                fa ? "آهنین" : "Ironclad"
        };
    }

    private String[] transportLabels(String language) {
        return "fa".equals(language)
                ? new String[]{"HTTP/2 (TCP)", "HTTP/3 (QUIC)"}
                : new String[]{"HTTP/2 (TCP)", "HTTP/3 (QUIC)"};
    }

    private String[] goolLabels(String language) {
        boolean fa = "fa".equals(language);
        return new String[]{
                fa ? "حمل‌شده با MASQUE" : "MASQUE-carried",
                fa ? "کلاسیک (WARP در WARP)" : "Classic (WARP-in-WARP)"
        };
    }

    private String[] psiphonTransportLabels(String language) {
        boolean fa = "fa".equals(language);
        return new String[]{fa ? "خودکار" : "Auto", fa ? "مستقیم" : "Direct"};
    }

    private String[] obfuscationLabels(String language) {
        boolean fa = "fa".equals(language);
        return new String[]{
                fa ? "فایروال" : "Firewall",
                fa ? "دیواره بزرگ" : "GFW",
                fa ? "متعادل" : "Balanced",
                fa ? "تهاجمی" : "Aggressive",
                fa ? "خاموش" : "Off"
        };
    }

    private String[] ipModeLabels(String language) {
        // These are protocol identifiers, deliberately not translated.
        return new String[]{"IPv4", "IPv6", "IPv4 + IPv6"};
    }

    private String[] routingLabels(String language) {
        boolean fa = "fa".equals(language);
        return new String[]{
                fa ? "همه ترافیک" : "Full device",
                fa ? "حفظ شبکه محلی" : "Keep local network",
                fa ? "فقط برنامه‌های انتخابی" : "Only selected apps",
                fa ? "همه جز برنامه‌های انتخابی" : "All but selected apps"
        };
    }

    private String[] languageLabels() {
        return new String[]{"فارسی", "English"};
    }

    private String[] appearanceLabels(String language) {
        boolean fa = "fa".equals(language);
        return new String[]{
                fa ? "سیستم" : "System",
                fa ? "روشن" : "Light",
                fa ? "تیره" : "Dark"
        };
    }

    private String ipv6ModeLabel(String language) {
        return "tunnel".equals(settings.ipv6Behavior())
                ? ("fa".equals(language) ? "تونل" : "Tunnel")
                : ("fa".equals(language) ? "مسدود" : "Block");
    }

    private String[] regionLabels(String language) {
        String[] countries = com.aether.client.core.Settings.PSIPHON_COUNTRIES;
        String[] out = new String[countries.length + 1];
        out[0] = "fa".equals(language) ? "خودکار" : "Automatic";
        for (int i = 0; i < countries.length; i++) {
            out[i + 1] = flag(countries[i]) + "  " + countryName(countries[i], language);
        }
        return out;
    }

    private int regionIndex() {
        String region = settings.psiphonRegion().trim().toUpperCase(java.util.Locale.US);
        if (region.isEmpty()) return 0;
        for (int i = 0; i < com.aether.client.core.Settings.PSIPHON_COUNTRIES.length; i++) {
            if (com.aether.client.core.Settings.PSIPHON_COUNTRIES[i].equals(region)) return i + 1;
        }
        return 0;
    }

    private static String flag(String code) {
        StringBuilder builder = new StringBuilder();
        for (char c : code.toCharArray()) {
            builder.append((char) (0x1F1E6 + c - 'A'));
        }
        return builder.toString();
    }

    private static String countryName(String code, String language) {
        try {
            return new java.util.Locale("", code).getDisplayCountry(
                    "fa".equals(language) ? new java.util.Locale("fa") : java.util.Locale.ENGLISH);
        } catch (Exception ignored) {
            return code;
        }
    }

    private static int valueIndex(String[] values, String current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) return i;
        }
        return 0;
    }

    // ---- small interfaces --------------------------------------------------------------

    private interface IndexListener {
        void onIndex(int index);
    }

    private interface StringListener {
        void onValue(String value);
    }

    private interface IntListener {
        void onValue(int value);
    }

    private interface BoolListener {
        void onValue(boolean value);
    }

    /** Avoids the abstract-method boilerplate of TextWatcher for one method. */
    private abstract static class SimpleTextWatcher implements android.text.TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}