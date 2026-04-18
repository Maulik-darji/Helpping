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
import android.util.Log;

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
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MapFragment extends Fragment implements OnMapReadyCallback {

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private TextView tvAddress;
    private EditText etAddress;
    private ImageButton btnEditAddress;
    private Button btnSaveLocation;
    private FloatingActionButton btnCenterLocation;
    private Button btnBroadcastEmergency;
    private Button btnCancelBroadcast;
    private View alertBanner;
    private TextView tvAlertBannerText;
    private boolean isEditMode = false;
    private LatLng currentVictimLocation;
    private DocumentReference currentRequestRef;
    private Marker helperMarker;

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
        btnBroadcastEmergency = view.findViewById(R.id.btnBroadcastEmergency);
        btnCancelBroadcast = view.findViewById(R.id.btnCancelBroadcast);
        alertBanner = view.findViewById(R.id.alertBanner);
        tvAlertBannerText = view.findViewById(R.id.tvAlertBannerText);

        if (btnEditAddress != null) {
            btnEditAddress.setOnClickListener(v -> toggleEditMode());
        }

        if (btnSaveLocation != null) {
            btnSaveLocation.setOnClickListener(v -> saveAddress());
        }

        if (btnCenterLocation != null) {
            btnCenterLocation.setOnClickListener(v -> enableMyLocationAndCenter());
        }

        if (btnBroadcastEmergency != null) {
            btnBroadcastEmergency.setOnClickListener(v -> broadcastEmergency());
        }

        if (btnCancelBroadcast != null) {
            btnCancelBroadcast.setOnClickListener(v -> cancelBroadcast());
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
        currentVictimLocation = new LatLng(lat, lng);
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentVictimLocation, 17f));
        updateAddressText(lat, lng);
    }

    private void updateAddressText(double lat, double lng) {
        if (isEditMode) return; // Don't overwrite if user is currently editing

        new Thread(() -> {
            Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());
            try {
                List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                
                // Switch back to UI thread to update views
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (addresses != null && !addresses.isEmpty()) {
                            Address address = addresses.get(0);
                            String addressLine = address.getAddressLine(0);
                            if (tvAddress != null) tvAddress.setText(addressLine);
                            if (etAddress != null) etAddress.setText(addressLine);
                        } else {
                            if (tvAddress != null) tvAddress.setText("Address not found");
                        }
                    });
                }
            } catch (IOException e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (tvAddress != null) tvAddress.setText("Unable to get address");
                    });
                }
            }
        }).start();
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

    private void broadcastEmergency() {
        if (currentVictimLocation == null) {
            Toast.makeText(requireContext(), "Waiting for location...", Toast.LENGTH_SHORT).show();
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(requireContext(), "Please Sign In First", Toast.LENGTH_SHORT).show();
            return;
        }

        btnBroadcastEmergency.setEnabled(false);
        btnBroadcastEmergency.setText("BROADCASTING...");

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        
        Map<String, Object> request = new HashMap<>();
        request.put("victimId", user.getUid());
        request.put("victimName", user.getDisplayName() != null ? user.getDisplayName() : "Someone");
        request.put("victimLat", currentVictimLocation.latitude);
        request.put("victimLng", currentVictimLocation.longitude);
        request.put("victimAddress", tvAddress.getText().toString());
        request.put("status", "PENDING");
        request.put("helperId", null);
        request.put("helperName", null);
        request.put("helperLat", null);
        request.put("helperLng", null);
        request.put("timestamp", System.currentTimeMillis());

        db.collection("emergency_requests")
                .add(request)
                .addOnSuccessListener(documentReference -> {
                    Toast.makeText(requireContext(), "Emergency Broadcasted!", Toast.LENGTH_LONG).show();
                    btnBroadcastEmergency.setText("WAITING FOR HELP");
                    btnCancelBroadcast.setVisibility(View.VISIBLE);
                    currentRequestRef = documentReference;
                    listenForHelper();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(requireContext(), "Failed to broadcast", Toast.LENGTH_SHORT).show();
                    btnBroadcastEmergency.setEnabled(true);
                    btnBroadcastEmergency.setText("ASK FOR HELP - BROADCAST");
                });
    }

    private void listenForHelper() {
        if (currentRequestRef == null) return;

        currentRequestRef.addSnapshotListener((snapshot, e) -> {
            if (e != null || snapshot == null) return;

            if (!snapshot.exists()) {
                // If it was cancelled by the victim (us) or deleted
                resetBroadcastUI();
                return;
            }

            String status = snapshot.getString("status");
            String helperName = snapshot.getString("helperName");
            Double helperLat = snapshot.getDouble("helperLat");
            Double helperLng = snapshot.getDouble("helperLng");

            if ("ACCEPTED".equals(status) && helperName != null) {
                alertBanner.setVisibility(View.VISIBLE);
                tvAlertBannerText.setText(helperName + " is coming to help you!");
                btnBroadcastEmergency.setText("HELP IS ON THE WAY");
                btnBroadcastEmergency.setEnabled(false);
                btnCancelBroadcast.setVisibility(View.VISIBLE); 
                btnCancelBroadcast.setText("CANCEL HELP (I AM SAFE)");

                if (helperLat != null && helperLng != null) {
                    LatLng hLoc = new LatLng(helperLat, helperLng);
                    if (helperMarker == null) {
                        helperMarker = mMap.addMarker(new MarkerOptions()
                                .position(hLoc)
                                .title("Helper")
                                .icon(bitmapDescriptorFromVector(R.drawable.ic_navigation_arrow)));
                    } else {
                        helperMarker.setPosition(hLoc);
                    }
                }
            } else if ("RESOLVED".equals(status)) {
                resetBroadcastUI();
            }
        });
    }

    private void cancelBroadcast() {
        if (currentRequestRef != null) {
            currentRequestRef.delete().addOnSuccessListener(aVoid -> {
                Toast.makeText(requireContext(), "Broadcast Cancelled", Toast.LENGTH_SHORT).show();
                resetBroadcastUI();
            });
        }
    }

    private void resetBroadcastUI() {
        alertBanner.setVisibility(View.GONE);
        btnBroadcastEmergency.setEnabled(true);
        btnBroadcastEmergency.setText("ASK FOR HELP - BROADCAST");
        btnCancelBroadcast.setVisibility(View.GONE);
        btnCancelBroadcast.setText("CANCEL BROADCAST");
        if (helperMarker != null) {
            helperMarker.remove();
            helperMarker = null;
        }
        currentRequestRef = null;
    }

    private BitmapDescriptor bitmapDescriptorFromVector(int vectorResId) {
        Drawable vectorDrawable = ContextCompat.getDrawable(requireContext(), vectorResId);
        if (vectorDrawable == null) return BitmapDescriptorFactory.defaultMarker();
        
        vectorDrawable.setBounds(0, 0, vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.draw(canvas);
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }
}
