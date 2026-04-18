package com.example.helpping;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class HelperFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(android.R.layout.simple_list_item_1, container, false);
        TextView textView = view.findViewById(android.R.id.text1);
        textView.setText("Helper Center\n\nFind people who can assist you in case of emergency.");
        textView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        textView.setPadding(20, 100, 20, 20);
        return view;
    }
}
