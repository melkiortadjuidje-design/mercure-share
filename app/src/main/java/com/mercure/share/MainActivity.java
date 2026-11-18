package com.mercure.share;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements ShareState.Listener {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView txtVpn, txtPower, txtStatus, txtError;
    private ImageView imgPower, imgQr;
    private View btnPower;
    private LinearLayout cardWifi, cardWifiFail, cardProxy, cardStats, wifiDetails;
    private TextView txtWifiState, txtSsid, txtPass, txtWifiFail;
    private TextView txtProxyIp, txtProxyPort, txtPac, txtOtherIps;
    private TextView txtStatDevices, txtStatWifi, txtStatConn, txtStatDown, txtStatUp, txtStatTotal;

    private String lastQrPayload;
    private Prefs prefs;

    private ActivityResultLauncher<String[]> permissionLauncher;

    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            refreshVpn();
            refreshStatusLine();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        txtVpn = findViewById(R.id.txtVpn);
        btnPower = findViewById(R.id.btnPower);
        imgPower = findViewById(R.id.imgPower);
        txtPower = findViewById(R.id.txtPower);
        txtStatus = findViewById(R.id.txtStatus);
        txtError = findViewById(R.id.txtError);
        cardWifi = findViewById(R.id.cardWifi);
        cardWifiFail = findViewById(R.id.cardWifiFail);
        cardProxy = findViewById(R.id.cardProxy);
        cardStats = findViewById(R.id.cardStats);
        wifiDetails = findViewById(R.id.wifiDetails);
        txtWifiState = findViewById(R.id.txtWifiState);
        txtSsid = findViewById(R.id.txtSsid);
        txtPass = findViewById(R.id.txtPass);
        imgQr = findViewById(R.id.imgQr);
        txtWifiFail = findViewById(R.id.txtWifiFail);
        txtProxyIp = findViewById(R.id.txtProxyIp);
        txtProxyPort = findViewById(R.id.txtProxyPort);
        txtPac = findViewById(R.id.txtPac);
        txtOtherIps = findViewById(R.id.txtOtherIps);
        txtStatDevices = findViewById(R.id.txtStatDevices);
        txtStatWifi = findViewById(R.id.txtStatWifi);
        txtStatConn = findViewById(R.id.txtStatConn);
        txtStatDown = findViewById(R.id.txtStatDown);
        txtStatUp = findViewById(R.id.txtStatUp);
        txtStatTotal = findViewById(R.id.txtStatTotal);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionsResult);

        btnPower.setOnClickListener(v -> onPowerClicked());
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnGuide).setOnClickListener(v ->
                startActivity(new Intent(this, GuideActivity.class)));
        findViewById(R.id.btnHotspot).setOnClickListener(v -> openHotspotSettings());

        findViewById(R.id.btnCopySsid).setOnClickListener(v -> copy(txtSsid.getText()));
        findViewById(R.id.btnCopyPass).setOnClickListener(v -> copy(txtPass.getText()));
        findViewById(R.id.btnCopyIp).setOnClickListener(v -> copy(txtProxyIp.getText()));
        findViewById(R.id.btnCopyPort).setOnClickListener(v -> copy(txtProxyPort.getText()));
        findViewById(R.id.btnCopyPac).setOnClickListener(v -> copy(txtPac.getText()));
    }

    @Override
    protected void onStart() {
        super.onStart();
        ShareState.get().addListener(this);
        render(ShareState.get());
        handler.post(uiTicker);
    }

    @Override
    protected void onStop() {
        ShareState.get().removeListener(this);
        handler.removeCallbacks(uiTicker);
        super.onStop();
    }

    @Override
    public void onShareStateChanged(ShareState state) {
        render(state);
    }

    // ------------------------------------------------------------------ démarrage

    private void onPowerClicked() {
        ShareState st = ShareState.get();
        if (st.isActive()) {
            ShareService.stop(this);
            return;
        }
        prefs = new Prefs(this);
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (prefs.isWifiDirectEnabled()) addIfMissing(needed, Manifest.permission.NEARBY_WIFI_DEVICES);
            addIfMissing(needed, Manifest.permission.POST_NOTIFICATIONS);
        } else if (prefs.isWifiDirectEnabled()) {
            addIfMissing(needed, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!needed.isEmpty()) {
            permissionLauncher.launch(needed.toArray(new String[0]));
        } else {
            checkWifiThenStart();
        }
    }

    private void addIfMissing(List<String> list, String perm) {
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) list.add(perm);
    }

    private void onPermissionsResult(Map<String, Boolean> result) {
        boolean denied = false;
        for (Map.Entry<String, Boolean> e : result.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())
                    && !Manifest.permission.POST_NOTIFICATIONS.equals(e.getKey())) denied = true;
        }
        if (denied) Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show();
        checkWifiThenStart();
    }

    private void checkWifiThenStart() {
        if (prefs.isWifiDirectEnabled()) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dlg_wifi_title)
                        .setMessage(R.string.dlg_wifi_msg)
                        .setPositiveButton(R.string.dlg_open_settings, (d, w) -> openWifiSettings())
                        .setNeutralButton(R.string.dlg_without_wifi, (d, w) -> checkVpnThenStart())
                        .setNegativeButton(R.string.dlg_cancel, null)
                        .show();
                return;
            }
            if (Build.VERSION.SDK_INT <= 32 && !isLocationEnabled()) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dlg_location_title)
                        .setMessage(R.string.dlg_location_msg)
                        .setPositiveButton(R.string.dlg_open_settings, (d, w) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                            } catch (Exception ignored) {
                            }
                        })
                        .setNeutralButton(R.string.dlg_continue, (d, w) -> checkVpnThenStart())
                        .setNegativeButton(R.string.dlg_cancel, null)
                        .show();
                return;
            }
        }
        checkVpnThenStart();
    }

    private void checkVpnThenStart() {
        if (!NetUtils.isVpnActive(this)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dlg_no_vpn_title)
                    .setMessage(R.string.dlg_no_vpn_msg)
                    .setPositiveButton(R.string.dlg_continue, (d, w) -> ShareService.start(this))
                    .setNegativeButton(R.string.dlg_cancel, null)
                    .show();
        } else {
            ShareService.start(this);
        }
    }

    private boolean isLocationEnabled() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return true;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return true;
        }
    }

    private void openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            } else {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    private void openHotspotSettings() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        try {
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ affichage

    private void refreshVpn() {
        boolean vpn = NetUtils.isVpnActive(this);
        txtVpn.setText(vpn ? R.string.vpn_on : R.string.vpn_off);
        txtVpn.setTextColor(ContextCompat.getColor(this, vpn ? R.color.green : R.color.orange));
        txtVpn.setBackgroundResource(vpn ? R.drawable.bg_chip_ok : R.drawable.bg_chip_warn);
    }

    private void refreshStatusLine() {
        ShareState st = ShareState.get();
        switch (st.status) {
            case RUNNING:
                txtStatus.setText(getString(R.string.status_running,
                        NetUtils.formatDuration(System.currentTimeMillis() - st.startedAt)));
                break;
            case STARTING:
                txtStatus.setText(R.string.status_starting);
                break;
            default:
                txtStatus.setText(R.string.status_stopped);
        }
    }

    private void render(ShareState st) {
        boolean active = st.isActive();
        boolean running = st.status == ShareState.Status.RUNNING;

        btnPower.setBackgroundResource(active ? R.drawable.bg_power_on : R.drawable.bg_power_off);
        int color = ContextCompat.getColor(this, active ? R.color.green : R.color.cyan);
        txtPower.setText(active ? R.string.stop : R.string.start);
        txtPower.setTextColor(color);
        imgPower.setColorFilter(color);
        refreshStatusLine();

        if (st.status == ShareState.Status.ERROR && st.error != null) {
            txtError.setText(st.error);
            txtError.setVisibility(View.VISIBLE);
        } else {
            txtError.setVisibility(View.GONE);
        }

        // Wi-Fi Direct
        boolean wdShown = running && st.wifiDirect != ShareState.WifiDirect.DISABLED
                && st.wifiDirect != ShareState.WifiDirect.FAILED;
        cardWifi.setVisibility(wdShown ? View.VISIBLE : View.GONE);
        cardWifiFail.setVisibility(running && st.wifiDirect == ShareState.WifiDirect.FAILED ? View.VISIBLE : View.GONE);
        if (st.wifiDirectError != null) txtWifiFail.setText(st.wifiDirectError);

        boolean wdReady = st.wifiDirect == ShareState.WifiDirect.READY && st.ssid != null;
        if (wdShown) {
            txtWifiState.setText(wdReady ? R.string.wifi_ready : R.string.wifi_creating);
            wifiDetails.setVisibility(wdReady ? View.VISIBLE : View.GONE);
            if (wdReady) {
                txtSsid.setText(st.ssid);
                txtPass.setText(st.passphrase);
                String payload = QrUtils.wifiPayload(st.ssid, st.passphrase);
                if (!payload.equals(lastQrPayload)) {
                    Bitmap qr = QrUtils.generate(payload, 600);
                    if (qr != null) imgQr.setImageBitmap(qr);
                    lastQrPayload = payload;
                }
            }
        }

        // Proxy
        cardProxy.setVisibility(running ? View.VISIBLE : View.GONE);
        cardStats.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) {
            String ip = NetUtils.primaryAddress(wdReady);
            txtProxyIp.setText(ip);
            txtProxyPort.setText(String.valueOf(st.port));
            txtPac.setText("http://" + ip + ":" + st.port + "/proxy.pac");

            StringBuilder sb = new StringBuilder();
            for (NetUtils.LocalAddress a : NetUtils.getLocalAddresses()) {
                if (a.ip.equals(ip)) continue;
                if (sb.length() == 0) sb.append("Autres adresses du téléphone :\n");
                sb.append("• ").append(a.label).append(" : ").append(a.ip).append(':').append(st.port).append('\n');
            }
            txtOtherIps.setText(sb.toString().trim());
            txtOtherIps.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);

            txtStatDevices.setText(String.valueOf(st.proxyClients));
            txtStatWifi.setText(wdReady ? String.valueOf(st.wifiClients) : "–");
            txtStatConn.setText(String.valueOf(st.connections));
            txtStatDown.setText(NetUtils.formatRate(st.rateDown));
            txtStatUp.setText(NetUtils.formatRate(st.rateUp));
            txtStatTotal.setText(NetUtils.formatBytes(st.bytesUp + st.bytesDown));
        }
    }

    private void copy(CharSequence text) {
        if (text == null || text.length() == 0) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Mercure Share", text));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }
}
