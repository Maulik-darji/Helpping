package com.example.helpping;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;

public class YourInfoFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_your_info, container, false);

        TextView nameText = view.findViewById(R.id.nameText);
        TextView emailText = view.findViewById(R.id.emailText);
        ImageView profileImage = view.findViewById(R.id.profileImage);

        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        
        if (user != null) {
            String name = user.getDisplayName();
            String email = user.getEmail();
            android.net.Uri photoUrl = user.getPhotoUrl();

            nameText.setText(name != null ? name : "No name found");
            emailText.setText(email != null ? email : "No email found");

            // Priority 1: Use the centrally cached session photo
            // Priority 2: Fallback to the live Firebase user photo
            String finalPhotoUrl = (UserSession.photo != null) ? UserSession.photo : (photoUrl != null ? photoUrl.toString() : null);

            if (finalPhotoUrl != null) {
                // Main profile image
                Glide.with(this)
                        .load(finalPhotoUrl)
                        .circleCrop()
                        .into(profileImage);
            }
        } else {
            nameText.setText("Not signed in");
            emailText.setText("Not signed in");
        }

        com.google.android.material.materialswitch.MaterialSwitch themeSwitch = view.findViewById(R.id.themeSwitch);
        android.content.SharedPreferences prefs = requireActivity().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE);
        
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        themeSwitch.setChecked(isDarkMode);
        themeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("dark_mode", isChecked).apply();
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    isChecked ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            );
        });
 
        android.widget.EditText etPhone = view.findViewById(R.id.etPhone);
        android.widget.Button btnSavePhone = view.findViewById(R.id.btnSavePhone);
        TextView tvRadiusValue = view.findViewById(R.id.tvRadiusValue);
        com.google.android.material.slider.Slider distanceSlider = view.findViewById(R.id.distanceSlider);

        etPhone.setText(prefs.getString("my_phone", ""));
        float savedRadius = prefs.getFloat("search_radius", 50.0f);
        distanceSlider.setValue(savedRadius);
        tvRadiusValue.setText((int)savedRadius + " Km");
        
        distanceSlider.addOnChangeListener((slider, value, fromUser) -> {
            tvRadiusValue.setText((int)value + " Km");
        });
        
        btnSavePhone.setOnClickListener(v -> {
            prefs.edit()
                 .putString("my_phone", etPhone.getText().toString().trim())
                 .putFloat("search_radius", distanceSlider.getValue())
                 .apply();
            android.widget.Toast.makeText(requireContext(), "Settings saved", android.widget.Toast.LENGTH_SHORT).show();
        });

        android.widget.Button btnLogout = view.findViewById(R.id.btnLogout);
        btnLogout.setOnClickListener(v -> {
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut();
            com.google.android.gms.auth.api.signin.GoogleSignInOptions gso = new com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN).build();
            com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(requireActivity(), gso).signOut().addOnCompleteListener(task -> {
                android.content.Intent intent = new android.content.Intent(requireActivity(), MainActivity.class);
                intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                requireActivity().finish();
            });
        });

        return view;
    }
}