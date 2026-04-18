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

        nameText.setText(UserSession.name != null ? UserSession.name : "No name");
        emailText.setText(UserSession.email != null ? UserSession.email : "No email");

        if (UserSession.photo != null) {
            Glide.with(this)
                    .load(UserSession.photo)
                    .circleCrop()
                    .into(profileImage);
        }

        return view;
    }
}