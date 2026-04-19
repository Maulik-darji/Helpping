package com.example.helpping;

// -------------------- Android (core UI + system) --------------------
import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.view.HapticFeedbackConstants;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

// -------------------- AndroidX --------------------
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

// -------------------- Google / Firebase / Material --------------------
import com.bumptech.glide.Glide;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationBarView;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

public class MainActivity extends AppCompatActivity {

    private GoogleSignInClient googleSignInClient;

    private LinearLayout homeContent;
    private FrameLayout container;
    private Button emergencyBtn;
    private Button emergencySharingBtn;
    private Button call112Btn;

    private String pendingShareReason;
    private String pendingSharePhone;

    private final ActivityResultLauncher<Intent> googleSignInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                    try {
                        GoogleSignInAccount account = task.getResult(ApiException.class);
                        if (account != null) {
                            firebaseAuthWithGoogle(account.getIdToken());
                        }
                    } catch (ApiException e) {
                        Toast.makeText(this, "Google sign-in failed", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private final ActivityResultLauncher<String> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (pendingSharePhone == null) return;
                composeSmsWithOptionalLocation(pendingShareReason, pendingSharePhone, granted);
                pendingShareReason = null;
                pendingSharePhone = null;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply saved theme preference
        android.content.SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                isDarkMode ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        );

        super.onCreate(savedInstanceState);

        boolean isFirstLaunch = prefs.getBoolean("first_launch", true);

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();

        if (isFirstLaunch || currentUser == null) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        getSupportFragmentManager().setFragmentResultListener("emergency_share", this, (requestKey, bundle) -> {
            String reason = bundle.getString("reason", "");
            String phone = bundle.getString("phone", "");
            startEmergencySharing(reason, phone);
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (container != null && container.getVisibility() == View.VISIBLE) {
                    container.setVisibility(View.GONE);
                    homeContent.setVisibility(View.VISIBLE);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // Views
        homeContent = findViewById(R.id.homeContent);
        container = findViewById(R.id.container);
        emergencyBtn = findViewById(R.id.btnEmergency);
        emergencySharingBtn = findViewById(R.id.btnEmergencySharing);
        call112Btn = findViewById(R.id.btnCall112);

        // Toolbar
        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        if (toolbar.getMenu().size() == 0) {
            toolbar.inflateMenu(R.menu.top_app_bar_menu);
        }

        MenuItem profileItem = toolbar.getMenu().findItem(R.id.action_profile);
        View actionView = profileItem != null ? profileItem.getActionView() : null;
        ImageView profileIconImage = actionView != null ? actionView.findViewById(R.id.profileIconImage) : null;

        if (profileIconImage != null && UserSession.photo != null) {
            Glide.with(this)
                    .load(UserSession.photo)
                    .circleCrop()
                    .into(profileIconImage);
        }
        if (actionView != null) {
            actionView.setOnClickListener(v -> {
                // Same behavior as clicking the menu item
                toolbar.getMenu().performIdentifierAction(R.id.action_profile, 0);
            });
        }
        setSupportActionBar(toolbar);

        toolbar.post(this::loadProfileImage);

        // Google Sign-In config
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();

        googleSignInClient = GoogleSignIn.getClient(this, gso);

        // Top app bar menu clicks
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();

            if (id == R.id.action_profile) {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

                if (profileIconImage != null && user != null && user.getPhotoUrl() != null) {
                    UserSession.photo = user.getPhotoUrl().toString();

                    Glide.with(this)
                            .load(UserSession.photo)
                            .circleCrop()
                            .into(profileIconImage);
                }
            }

            return false;
        });

        // Bottom navigation
        NavigationBarView navBar = findViewById(R.id.bottom_navigation);
        navBar.setOnItemSelectedListener(item -> {
            navBar.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);

            int id = item.getItemId();

            if (id == R.id.home) {
                homeContent.setVisibility(View.VISIBLE);
                container.setVisibility(View.GONE);
                return true;

            } else if (id == R.id.helper) {
                homeContent.setVisibility(View.GONE);
                container.setVisibility(View.VISIBLE);

                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.container, new HelperFragment())
                        .commit();
                return true;

            } else if (id == R.id.feature) {
                homeContent.setVisibility(View.GONE);
                container.setVisibility(View.VISIBLE);

                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.container, new FeatureFragment())
                        .commit();
                return true;

            } else if (id == R.id.profile) {
                homeContent.setVisibility(View.GONE);
                container.setVisibility(View.VISIBLE);

                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.container, new YourInfoFragment())
                        .commit();
                return true;
            }

            return false;
        });

        // Emergency button
        emergencyBtn.setOnClickListener(v -> {
            homeContent.setVisibility(View.GONE);
            container.setVisibility(View.VISIBLE);

            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.container, new MapFragment())
                    .addToBackStack(null)
                    .commit();
        });

        if (emergencySharingBtn != null) {
            emergencySharingBtn.setOnClickListener(v ->
                    new EmergencySharingDialogFragment().show(getSupportFragmentManager(), "EmergencySharingDialog")
            );
        }

        if (call112Btn != null) {
            call112Btn.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_DIAL);
                intent.setData(Uri.parse("tel:112"));
                startActivity(intent);
            });
        }
    }

    private void startEmergencySharing(String reason, String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            Toast.makeText(this, "Add an emergency contact in settings", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, EmergencySharingSettingsActivity.class));
            return;
        }

        getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                .putBoolean("sharing_active", true)
                .putLong("sharing_started_at", System.currentTimeMillis())
                .putString("sharing_reason", reason == null ? "" : reason)
                .apply();

        homeContent.setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.container, new MapFragment())
                .addToBackStack(null)
                .commit();

        int permission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        if (permission == PackageManager.PERMISSION_GRANTED) {
            composeSmsWithOptionalLocation(reason, phone, true);
        } else {
            pendingShareReason = reason;
            pendingSharePhone = phone;
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    private void composeSmsWithOptionalLocation(String reason, String phone, boolean hasLocationPermission) {
        if (!hasLocationPermission) {
            openSmsComposer(phone, buildShareMessage(reason, null, null));
            return;
        }

        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(this);
        client.getLastLocation()
                .addOnSuccessListener(location -> {
                    Double lat = location != null ? location.getLatitude() : null;
                    Double lng = location != null ? location.getLongitude() : null;
                    openSmsComposer(phone, buildShareMessage(reason, lat, lng));
                })
                .addOnFailureListener(e -> openSmsComposer(phone, buildShareMessage(reason, null, null)));
    }

    private String buildShareMessage(String reason, Double lat, Double lng) {
        StringBuilder sb = new StringBuilder();
        sb.append("HelpPing: Emergency sharing started.");
        if (reason != null && !reason.trim().isEmpty()) {
            sb.append("\nReason: ").append(reason.trim());
        }
        if (lat != null && lng != null) {
            sb.append("\nLocation: https://maps.google.com/?q=").append(lat).append(",").append(lng);
        } else {
            sb.append("\nLocation: (enable location permission to include a link)");
        }
        return sb.toString();
    }

    private void openSmsComposer(String phone, String message) {
        try {
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("smsto:" + Uri.encode(phone)));
            intent.putExtra("sms_body", message);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No SMS app found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProfileImage();
    }

    private void loadProfileImage() {
        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        if (toolbar == null) return;
        
        MenuItem profileItem = toolbar.getMenu().findItem(R.id.action_profile);
        View actionView = profileItem != null ? profileItem.getActionView() : null;
        ImageView profileIconImage = actionView != null ? actionView.findViewById(R.id.profileIconImage) : null;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (profileIconImage != null && user != null && user.getPhotoUrl() != null) {
            UserSession.photo = user.getPhotoUrl().toString();
            Glide.with(this)
                    .load(UserSession.photo)
                    .circleCrop()
                    .into(profileIconImage);
        }
    }

    // ✅ UPDATED: Save user info after Firebase login
    private void firebaseAuthWithGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        FirebaseAuth.getInstance().signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {

                        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

                        if (user != null) {
                            UserSession.name = user.getDisplayName();
                            UserSession.email = user.getEmail();
                            Uri photoUrl = user.getPhotoUrl();
                            UserSession.photo = photoUrl != null ? photoUrl.toString() : null;
                            loadProfileImage(); // Refresh immediately after login
                        }

                        Toast.makeText(this, "Signed in successfully", Toast.LENGTH_SHORT).show();

                    } else {
                        Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show();
                    }
                });
    }
}
