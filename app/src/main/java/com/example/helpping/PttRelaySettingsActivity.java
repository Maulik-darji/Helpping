package com.example.helpping;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class PttRelaySettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ptt_relay_settings);

        EditText etHost = findViewById(R.id.etRelayHost);
        EditText etPort = findViewById(R.id.etRelayPort);
        Button btnSave = findViewById(R.id.btnSaveRelay);
        Button btnDisable = findViewById(R.id.btnDisableRelay);

        android.content.SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String host = prefs.getString("ptt_relay_host", "");
        int port = prefs.getInt("ptt_relay_port", 0);
        if (!TextUtils.isEmpty(host)) etHost.setText(host);
        if (port > 0) etPort.setText(String.valueOf(port));

        btnSave.setOnClickListener(v -> {
            String newHost = String.valueOf(etHost.getText()).trim();
            String portStr = String.valueOf(etPort.getText()).trim();

            if (newHost.isEmpty()) {
                Toast.makeText(this, "Enter relay host (IP or domain).", Toast.LENGTH_LONG).show();
                return;
            }

            int newPort;
            try {
                newPort = Integer.parseInt(portStr);
            } catch (Throwable t) {
                newPort = -1;
            }

            if (newPort <= 0 || newPort > 65535) {
                Toast.makeText(this, "Enter a valid port (1-65535).", Toast.LENGTH_LONG).show();
                return;
            }

            prefs.edit()
                .putString("ptt_relay_host", newHost)
                .putInt("ptt_relay_port", newPort)
                .apply();

            Toast.makeText(this, "Relay enabled.", Toast.LENGTH_SHORT).show();
            finish();
        });

        btnDisable.setOnClickListener(v -> {
            prefs.edit()
                .remove("ptt_relay_host")
                .remove("ptt_relay_port")
                .apply();
            Toast.makeText(this, "Relay disabled (LAN only).", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}

