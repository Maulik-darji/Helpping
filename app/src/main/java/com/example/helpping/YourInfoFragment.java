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

            if (photoUrl != null) {
                Glide.with(this)
                        .load(photoUrl)
                        .circleCrop()
                        .into(profileImage);
            }
        } else {
            nameText.setText("Not signed in");
            emailText.setText("Not signed in");
        }

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