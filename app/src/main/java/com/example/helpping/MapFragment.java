package com.example.helpping;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class MapFragment extends Fragment implements OnMapReadyCallback {

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private TextView tvAddress;
    private EditText etAddress;
    private ImageButton btnEditAddress;
    private Button btnSaveLocation;
    private FloatingActionButton btnCenterLocation;
    private boolean isEditMode = false;

    private final ActivityResultLauncher<String> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && mMap != null) {
                    enableMyLocationAndCenter();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_map, container, false);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        tvAddress = view.findViewById(R.id.tvAddress);
        etAddress = view.findViewById(R.id.etAddress);
        btnEditAddress = view.findViewById(R.id.btnEditAddress);
        btnSaveLocation = view.findViewById(R.id.btnSaveLocation);
        btnCenterLocation = view.findViewById(R.id.btnCenterLocation);

        if (btnEditAddress != null) {
            btnEditAddress.setOnClickListener(v -> toggleEditMode());
        }

        if (btnSaveLocation != null) {
            btnSaveLocation.setOnClickListener(v -> saveAddress());
        }

        if (btnCenterLocation != null) {
            btnCenterLocation.setOnClickListener(v -> enableMyLocationAndCenter());
        }

        SupportMapFragment mapFragment =
                (SupportMapFragment) getChildFragmentManager()
                        .findFragmentById(R.id.map);

        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        return view;
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        
        // Update address when user stops moving the map
        mMap.setOnCameraIdleListener(() -> {
            LatLng center = mMap.getCameraPosition().target;
            updateAddressText(center.latitude, center.longitude);
        });

        enableMyLocationAndCenter();
    }

    private void enableMyLocationAndCenter() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            // Default center if no permission
            LatLng fallback = new LatLng(23.0225, 72.5714);
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fallback, 12f));
            return;
        }

        mMap.setMyLocationEnabled(true);
        mMap.getUiSettings().setMyLocationButtonEnabled(false); // Using our custom button instead

        // Use getCurrentLocation for the most accurate and fresh location
        CancellationTokenSource cts = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        updateLocationOnMap(location.getLatitude(), location.getLongitude());
                    } else {
                        // Fallback to last known location if getCurrentLocation fails
                        fusedLocationClient.getLastLocation().addOnSuccessListener(lastLoc -> {
                            if (lastLoc != null) {
                                updateLocationOnMap(lastLoc.getLatitude(), lastLoc.getLongitude());
                            }
                        });
                    }
                });
    }

    private void updateLocationOnMap(double lat, double lng) {
        LatLng here = new LatLng(lat, lng);
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(here, 17f));
        updateAddressText(lat, lng);
    }

    private void updateAddressText(double lat, double lng) {
        if (isEditMode) return; // Don't overwrite if user is currently editing

        Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());
        try {
            List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address address = addresses.get(0);
                String addressLine = address.getAddressLine(0);
                if (tvAddress != null) tvAddress.setText(addressLine);
                if (etAddress != null) etAddress.setText(addressLine);
            } else {
                if (tvAddress != null) tvAddress.setText("Address not found");
            }
        } catch (IOException e) {
            if (tvAddress != null) tvAddress.setText("Unable to get address");
        }
    }

    private void toggleEditMode() {
        isEditMode = !isEditMode;
        if (isEditMode) {
            tvAddress.setVisibility(View.GONE);
            etAddress.setVisibility(View.VISIBLE);
            btnSaveLocation.setVisibility(View.VISIBLE);
            btnEditAddress.setImageResource(R.drawable.ic_close);
            etAddress.requestFocus();
        } else {
            tvAddress.setVisibility(View.VISIBLE);
            etAddress.setVisibility(View.GONE);
            btnSaveLocation.setVisibility(View.GONE);
            btnEditAddress.setImageResource(R.drawable.ic_edit);
        }
    }

    private void saveAddress() {
        String newAddress = etAddress.getText().toString();
        tvAddress.setText(newAddress);
        toggleEditMode();
        Toast.makeText(requireContext(), "Location details saved", Toast.LENGTH_SHORT).show();
        
        // You could also save this to SharedPreferences if needed for later use
        requireActivity().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("saved_emergency_address", newAddress)
                .apply();
    }
}
