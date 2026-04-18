package com.example.helpping;

import android.app.Dialog;
//Dialog -> Popup window
import android.content.Context;
//Giving access to app environment for toast, starting acvtivity etc.
import android.content.Intent;
//Moving from one screen to another
import android.content.SharedPreferences;
// Local storage
import android.graphics.Paint;
//Style Text
import android.os.Bundle;
//Passing values between activities used to store and pass data.
import android.view.LayoutInflater;
//convert xml layout -> Actual view obnject
import android.view.View;
//base class of all ui
import android.view.ViewGroup;
//container holding multple view
import android.view.Window;
//Represents the window of dialog/activity.
import android.widget.LinearLayout;
//A layout that arranges items: Vertically OR horizontally
import android.widget.TextView;
//Used to display text on screen.
import android.widget.Toast;
//Shows a small popup message at bottom of screen.

import androidx.annotation.NonNull;
//Means value must NOT be null.
import androidx.annotation.Nullable;
//Means value can be null.
import androidx.fragment.app.DialogFragment;
//A modern way to create dialogs using fragment lifecycle.

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
//Used to create modern alert dialogs.
import com.google.android.material.textfield.TextInputEditText;
//Advanced input field used inside TextInputLayout.

public class EmergencySharingDialogFragment extends DialogFragment {

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_emergency_sharing, null);

        TextView changeSettingsLink = view.findViewById(R.id.changeSettingsLink);
        changeSettingsLink.setPaintFlags(changeSettingsLink.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);

        TextView contactName = view.findViewById(R.id.contactName);
        TextView contactPhone = view.findViewById(R.id.contactPhone);
        MaterialCheckBox contactCheck = view.findViewById(R.id.contactCheck);
        LinearLayout contactRow = view.findViewById(R.id.contactRow);

        TextInputEditText reasonEditText = view.findViewById(R.id.reasonEditText);

        MaterialButton cancelButton = view.findViewById(R.id.cancelButton);
        MaterialButton shareButton = view.findViewById(R.id.shareButton);

        SharedPreferences prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        String name = prefs.getString("emergency_contact_name", null);
        String phone = prefs.getString("emergency_contact_phone", null);
        if (name != null && !name.trim().isEmpty()) {
            contactName.setText(name);
        }
        if (phone != null && !phone.trim().isEmpty()) {
            contactPhone.setText(phone);
        }

        View.OnClickListener toggle = v -> contactCheck.setChecked(!contactCheck.isChecked());
        contactRow.setOnClickListener(toggle);

        changeSettingsLink.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), EmergencySharingSettingsActivity.class))
        );

        cancelButton.setOnClickListener(v -> dismiss());

        shareButton.setOnClickListener(v -> {
            if (!contactCheck.isChecked()) {
                Toast.makeText(requireContext(), "Select at least one contact", Toast.LENGTH_SHORT).show();
                return;
            }

            String phoneNumber = contactPhone.getText() != null ? contactPhone.getText().toString().trim() : "";
            if (phoneNumber.isEmpty() || "Add in settings".contentEquals(phoneNumber)) {
                Toast.makeText(requireContext(), "Add an emergency contact in settings", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(requireContext(), EmergencySharingSettingsActivity.class));
                return;
            }

            String reason = reasonEditText.getText() != null ? reasonEditText.getText().toString().trim() : "";
            Bundle result = new Bundle();
            result.putString("reason", reason);
            result.putString("phone", phoneNumber);
            getParentFragmentManager().setFragmentResult("emergency_share", result);
            dismiss();
        });

        return new MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
