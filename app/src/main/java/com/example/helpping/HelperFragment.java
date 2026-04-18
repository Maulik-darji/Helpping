package com.example.helpping;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import com.google.android.gms.maps.model.BitmapDescriptor;

public class HelperFragment extends Fragment implements OnMapReadyCallback {

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private TextView tvLookingForHelp, tvVictimName, tvDistance, tvAddressHelp, tvTimestamp;
    private View cardEmergencyDetails, cardNavigation;
    private Button btnAccept, btnReject, btnStartNavigation, btnEndEmergency;

    private Location currentHelperLocation;
    private FirebaseFirestore db;
    private ListenerRegistration requestsListener;

    private DocumentSnapshot selectedRequest;
    private Marker victimMarker;
    private String activeRequestId = null;
    private LatLng activeVictimLocation = null;
    private com.google.firebase.firestore.QuerySnapshot lastSnapshots;
    private Location lastRefreshLocation;
    private java.util.Set<String> rejectedRequestIds = new java.util.HashSet<>();

    private LocationCallback navigationLocationCallback;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_helper, container, false);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());
        db = FirebaseFirestore.getInstance();

        tvLookingForHelp = view.findViewById(R.id.tvLookingForHelp);
        cardEmergencyDetails = view.findViewById(R.id.cardEmergencyDetails);
        cardNavigation = view.findViewById(R.id.cardNavigation);
        tvVictimName = view.findViewById(R.id.tvVictimName);
        tvDistance = view.findViewById(R.id.tvDistance);
        tvAddressHelp = view.findViewById(R.id.tvAddressHelp);
        tvTimestamp = view.findViewById(R.id.tvTimestamp);

        btnAccept = view.findViewById(R.id.btnAccept);
        btnReject = view.findViewById(R.id.btnReject);
        btnStartNavigation = view.findViewById(R.id.btnStartNavigation);
        btnEndEmergency = view.findViewById(R.id.btnEndEmergency);

        btnReject.setOnClickListener(v -> {
            if (selectedRequest != null) {
                rejectedRequestIds.add(selectedRequest.getId());
            }
            hideRequestCard();
        });
        btnAccept.setOnClickListener(v -> acceptRequest());
        btnStartNavigation.setOnClickListener(v -> startNavigation());
        btnEndEmergency.setOnClickListener(v -> endEmergency());

        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.helperMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        return view;
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            
            CancellationTokenSource cts = new CancellationTokenSource();
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            currentHelperLocation = location;
                            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(location.getLatitude(), location.getLongitude()), 14f));
                            startTrackingHelperLocation();
                            listenForPendingRequests();
                        } else {
                            // Fallback to last location
                            fusedLocationClient.getLastLocation().addOnSuccessListener(lastLoc -> {
                                if (lastLoc != null) {
                                    currentHelperLocation = lastLoc;
                                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lastLoc.getLatitude(), lastLoc.getLongitude()), 14f));
                                    startTrackingHelperLocation();
                                    listenForPendingRequests();
                                } else {
                                    tvLookingForHelp.setText("Unable to find your location. Please turn on GPS.");
                                    // Even without location, try to listen
                                    listenForPendingRequests();
                                }
                            });
                        }
                    });
        }
        
        mMap.setOnMarkerClickListener(marker -> {
            if (activeRequestId == null && marker.getTag() instanceof DocumentSnapshot) {
                showRequestCard((DocumentSnapshot) marker.getTag());
                return true;
            }
            return false;
        });
    }

    private void listenForPendingRequests() {
        if (requestsListener != null) return; // Already listening

        requestsListener = db.collection("emergency_requests")
                .whereEqualTo("status", "PENDING")
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null) {
                        if (getContext() != null) {
                            Toast.makeText(getContext(), "Database Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                        return;
                    }
                    if (snapshots == null || activeRequestId != null) return;
                    
                    this.lastSnapshots = snapshots;
                    refreshMarkersAndUI();
                });
    }

    private void refreshMarkersAndUI() {
        if (lastSnapshots == null || mMap == null) return;

        mMap.clear();
        boolean foundNearby = false;
        boolean selectedRequestStillExists = false;
        String currentUserId = FirebaseAuth.getInstance().getCurrentUser() != null ? 
                             FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        long tenMinsAgo = System.currentTimeMillis() - (10 * 60 * 1000);

        if (lastSnapshots.isEmpty()) {
            hideRequestCard();
        }

        // Convert to list so we can sort manually without needing a Firebase Index
        java.util.List<DocumentSnapshot> docs = new java.util.ArrayList<>(lastSnapshots.getDocuments());
        java.util.Collections.sort(docs, (d1, d2) -> {
            Long t1 = d1.getLong("timestamp");
            Long t2 = d2.getLong("timestamp");
            if (t1 == null) return 1;
            if (t2 == null) return -1;
            return t2.compareTo(t1); // Newest first
        });

        for (DocumentSnapshot doc : docs) {
            String requestId = doc.getId();
            if (rejectedRequestIds.contains(requestId)) {
                continue;
            }

            String victimId = doc.getString("victimId");
            // NOTE: Temporarily disabling self-id filter to trace the bug.
            // if (currentUserId != null && currentUserId.equals(victimId)) {
            //     Toast.makeText(getContext(), "DEBUG: Ignored own request", Toast.LENGTH_SHORT).show();
            //     continue;
            // }

            Long timestamp = doc.getLong("timestamp");
            // NOTE: Temporarily disabling the 10-minute timeout filter in case phone clocks are not synced.
            // if (timestamp == null || timestamp < tenMinsAgo) {
            //     Toast.makeText(getContext(), "DEBUG: Ignored old request", Toast.LENGTH_SHORT).show();
            //     continue;
            // }

            if (selectedRequest != null && doc.getId().equals(selectedRequest.getId())) {
                selectedRequestStillExists = true;
            }

            Double lat = doc.getDouble("victimLat");
            Double lng = doc.getDouble("victimLng");
                // Logic: Found a valid request. Prepare coordinates.
                if (lat != null && lng != null) {
                    Location vLoc = new Location("");
                    vLoc.setLatitude(lat);
                    vLoc.setLongitude(lng);

                    // We are accepting ALL distances for now to ensure testing works perfectly.
                    foundNearby = true;
                    Marker m = mMap.addMarker(new MarkerOptions()
                            .position(new LatLng(lat, lng))
                            .title("Emergency Request")
                            .icon(bitmapDescriptorFromVector(R.drawable.ic_help_marker)));
                    if (m != null) m.setTag(doc);
                    
                    // Priority: If no card is showing, show this one (Newest first).
                    if (selectedRequest == null) {
                        showRequestCard(doc);
                        selectedRequestStillExists = true;
                    } else if (doc.getId().equals(selectedRequest.getId())) {
                        selectedRequestStillExists = true;
                    }
            }
        }
        
        if (selectedRequest != null && !selectedRequestStillExists) {
            hideRequestCard();
        }

        if (foundNearby) {
            tvLookingForHelp.setText("EMERGENCY DETECTED NEARBY!");
            tvLookingForHelp.setBackgroundColor(android.graphics.Color.parseColor("#D32F2F"));
            tvLookingForHelp.setTextColor(android.graphics.Color.WHITE);
        } else {
            tvLookingForHelp.setText("Searching for nearby emergencies...");
            tvLookingForHelp.setBackgroundResource(R.drawable.bg_rounded_white);
            tvLookingForHelp.setTextColor(android.graphics.Color.BLACK);
            // Only hide card if snapshots are truly empty
            if (lastSnapshots.isEmpty()) hideRequestCard();
        }
    }

    private void showRequestCard(DocumentSnapshot request) {
        selectedRequest = request;
        String name = request.getString("victimName");
        String address = request.getString("victimAddress");
        Double lat = request.getDouble("victimLat");
        Double lng = request.getDouble("victimLng");
        Long timestamp = request.getLong("timestamp");

        tvVictimName.setText(name + " Needs Help!");
        tvAddressHelp.setText("Address: " + (address != null ? address : "Unknown"));

        if (timestamp != null && tvTimestamp != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
            tvTimestamp.setText("Requested at: " + sdf.format(new Date(timestamp)));
        } else if (tvTimestamp != null) {
            tvTimestamp.setText("Requested at: Unknown");
        }

        updateDistanceUI(request);
        cardEmergencyDetails.setVisibility(View.VISIBLE);
    }

    private void updateDistanceUI(DocumentSnapshot request) {
        Double lat = request.getDouble("victimLat");
        Double lng = request.getDouble("victimLng");

        if (lat != null && lng != null && currentHelperLocation != null) {
            Location vLoc = new Location("");
            vLoc.setLatitude(lat);
            vLoc.setLongitude(lng);
            float dist = currentHelperLocation.distanceTo(vLoc);
            tvDistance.setText(String.format(Locale.getDefault(), "Distance: %.2f km", dist / 1000));
        } else {
            tvDistance.setText("Distance: Calculating...");
        }
    }

    private void hideRequestCard() {
        cardEmergencyDetails.setVisibility(View.GONE);
        selectedRequest = null;
    }

    private void acceptRequest() {
        if (selectedRequest == null) return;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(requireContext(), "Please Sign In", Toast.LENGTH_SHORT).show();
            return;
        }

        activeRequestId = selectedRequest.getId();
        
        Map<String, Object> updates = new HashMap<>();
        updates.put("status", "ACCEPTED");
        updates.put("helperId", user.getUid());
        updates.put("helperName", user.getDisplayName() != null ? user.getDisplayName() : "A Helper");
        if(currentHelperLocation != null) {
            updates.put("helperLat", currentHelperLocation.getLatitude());
            updates.put("helperLng", currentHelperLocation.getLongitude());
        }

        db.collection("emergency_requests").document(activeRequestId)
                .update(updates)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(requireContext(), "Request Accepted!", Toast.LENGTH_SHORT).show();
                    
                    Double vLat = selectedRequest.getDouble("victimLat");
                    Double vLng = selectedRequest.getDouble("victimLng");
                    if(vLat != null && vLng != null) {
                        activeVictimLocation = new LatLng(vLat, vLng);
                        mMap.addMarker(new MarkerOptions()
                                .position(activeVictimLocation)
                                .title("Victim")
                                .icon(bitmapDescriptorFromVector(R.drawable.ic_help_marker)));
                    }
                    
                    hideRequestCard();
                    if (requestsListener != null) requestsListener.remove();
                    mMap.clear();
                    
                    if (activeVictimLocation != null) {
                         mMap.addMarker(new MarkerOptions()
                                 .position(activeVictimLocation)
                                 .title("Victim")
                                 .icon(bitmapDescriptorFromVector(R.drawable.ic_help_marker)));
                    }
                    
                    tvLookingForHelp.setVisibility(View.GONE);
                    cardNavigation.setVisibility(View.VISIBLE);
                })
                .addOnFailureListener(e -> {
                    activeRequestId = null;
                    Toast.makeText(requireContext(), "Failed to accept", Toast.LENGTH_SHORT).show();
                });
    }

    @SuppressLint("MissingPermission")
    private void startNavigation() {
        btnStartNavigation.setVisibility(View.GONE);
        btnEndEmergency.setVisibility(View.VISIBLE);
        Toast.makeText(requireContext(), "Sharing location in real-time...", Toast.LENGTH_SHORT).show();

        // Launch Google Maps Turn-by-Turn Navigation
        if (activeVictimLocation != null) {
            android.net.Uri gmmIntentUri = android.net.Uri.parse("google.navigation:q=" + activeVictimLocation.latitude + "," + activeVictimLocation.longitude);
            android.content.Intent mapIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW, gmmIntentUri);
            mapIntent.setPackage("com.google.android.apps.maps");
            if (mapIntent.resolveActivity(requireActivity().getPackageManager()) != null) {
                startActivity(mapIntent);
            } else {
                Toast.makeText(requireContext(), "Google Maps app is not installed", Toast.LENGTH_SHORT).show();
            }
        }

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
                .setMinUpdateIntervalMillis(2000)
                .build();

        navigationLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult.getLastLocation() != null && activeRequestId != null) {
                    currentHelperLocation = locationResult.getLastLocation();
                    
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(currentHelperLocation.getLatitude(), currentHelperLocation.getLongitude()), 17f));

                    Map<String, Object> updates = new HashMap<>();
                    updates.put("helperLat", currentHelperLocation.getLatitude());
                    updates.put("helperLng", currentHelperLocation.getLongitude());
                    
                    db.collection("emergency_requests").document(activeRequestId).update(updates);
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, navigationLocationCallback, Looper.getMainLooper());
    }

    private void endEmergency() {
        if (activeRequestId != null) {
            db.collection("emergency_requests").document(activeRequestId)
                    .delete();
        }
        
        if (navigationLocationCallback != null) {
            fusedLocationClient.removeLocationUpdates(navigationLocationCallback);
        }
        
        activeRequestId = null;
        activeVictimLocation = null;
        cardNavigation.setVisibility(View.GONE);
        btnEndEmergency.setVisibility(View.GONE);
        btnStartNavigation.setVisibility(View.VISIBLE);
        tvLookingForHelp.setVisibility(View.VISIBLE);
        
        listenForPendingRequests();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (requestsListener != null) requestsListener.remove();
        if (navigationLocationCallback != null) fusedLocationClient.removeLocationUpdates(navigationLocationCallback);
        if (helperDiscoveryCallback != null) fusedLocationClient.removeLocationUpdates(helperDiscoveryCallback);
    }

    private LocationCallback helperDiscoveryCallback;

    @SuppressLint("MissingPermission")
    private void startTrackingHelperLocation() {
        if (helperDiscoveryCallback != null) return; // Already tracking

        LocationRequest lr = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(3000)
                .build();

        helperDiscoveryCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                if (result.getLastLocation() != null) {
                    currentHelperLocation = result.getLastLocation();
                    
                    // PERFORMANCE FIX: Only refresh markers if moved > 20 meters
                    if (lastRefreshLocation == null || currentHelperLocation.distanceTo(lastRefreshLocation) > 20) {
                        lastRefreshLocation = currentHelperLocation;
                        refreshMarkersAndUI();
                    }

                    // Update only the UI for distance if a card is already showing
                    if (selectedRequest != null && cardEmergencyDetails.getVisibility() == View.VISIBLE) {
                        updateDistanceUI(selectedRequest);
                    }
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(lr, helperDiscoveryCallback, Looper.getMainLooper());
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
