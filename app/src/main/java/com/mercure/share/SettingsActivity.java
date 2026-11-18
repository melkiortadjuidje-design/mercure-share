package com.mercure.share;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {

    private SwitchCompat switchWifiDirect;
    private EditText editName, editPass, editPort;
    private RadioGroup radioBand;
    private TextView txtBatteryHint, btnBattery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        switchWifiDirect = findViewById(R.id.switchWifiDirect);
        editName = findViewById(R.id.editName);
        editPass = findViewById(R.id.editPass);
        editPort = findViewById(R.id.editPort);
        radioBand = findViewById(R.id.radioBand);
        txtBatteryHint = findViewById(R.id.txtBatteryHint);
        btnBattery = findViewById(R.id.btnBattery);

        Prefs p = new Prefs(this);
        switchWifiDirect.setChecked(p.isWifiDirectEnabled());
        editName.setText(p.getSuffix());
        editPass.setText(p.getPassword());
        editPort.setText(String.valueOf(p.getPort()));
        int band = p.getBand();
        radioBand.check(band == Prefs.BAND_5GHZ ? R.id.radio5 : band == Prefs.BAND_AUTO ? R.id.radioAuto : R.id.radio2);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnGenerate).setOnClickListener(v -> editPass.setText(Prefs.generatePassword()));
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        btnBattery.setOnClickListener(v -> requestBatteryExemption());
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ok = isIgnoringBattery();
        txtBatteryHint.setText(ok ? R.string.set_battery_ok : R.string.set_battery_hint);
        btnBattery.setVisibility(ok ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void save() {
        String name = editName.getText().toString().trim();
        String pass = editPass.getText().toString();
        String portStr = editPort.getText().toString().trim();

        if (!Prefs.isValidSuffix(name)) {
            editName.setError(getString(R.string.err_name));
            editName.requestFocus();
            return;
        }
        if (!Prefs.isValidPassword(pass)) {
            editPass.setError(getString(R.string.err_pass));
            editPass.requestFocus();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (port < 1024 || port > 65535) {
            editPort.setError(getString(R.string.err_port));
            editPort.requestFocus();
            return;
        }
        int checked = radioBand.getCheckedRadioButtonId();
        int band = checked == R.id.radio5 ? Prefs.BAND_5GHZ
                : checked == R.id.radioAuto ? Prefs.BAND_AUTO : Prefs.BAND_2GHZ;

        new Prefs(this).save(name, pass, port, band, switchWifiDirect.isChecked());
        Toast.makeText(this, ShareState.get().isActive() ? R.string.set_saved_restart : R.string.set_saved,
                Toast.LENGTH_LONG).show();
        finish();
    }

    private boolean isIgnoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryExemption() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }
}
