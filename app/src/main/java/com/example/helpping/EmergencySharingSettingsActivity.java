package com.example.helpping;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class EmergencySharingSettingsActivity extends AppCompatActivity {

    private TextInputEditText contactNameEdit;
    private TextInputEditText contactPhoneEdit;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency_sharing_settings);

        MaterialToolbar toolbar = findViewById(R.id.settingsTopAppBar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        contactNameEdit = findViewById(R.id.contactNameEdit);
        contactPhoneEdit = findViewById(R.id.contactPhoneEdit);
        MaterialButton saveButton = findViewById(R.id.saveContactButton);

        SharedPreferences prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        contactNameEdit.setText(prefs.getString("emergency_contact_name", ""));
        contactPhoneEdit.setText(prefs.getString("emergency_contact_phone", ""));

        saveButton.setOnClickListener(v -> {
            String name = contactNameEdit.getText() != null ? contactNameEdit.getText().toString().trim() : "";
            String phone = contactPhoneEdit.getText() != null ? contactPhoneEdit.getText().toString().trim() : "";

            prefs.edit()
                    .putString("emergency_contact_name", name)
                    .putString("emergency_contact_phone", phone)
                    .apply();

            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}

