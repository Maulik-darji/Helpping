package com.example.helpping;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InAppCallActivity extends AppCompatActivity {

    private static final String TAG = "WebRtcCall";
    private static final int PERMISSION_REQ_CODE = 1001;

    // USER'S GCP TURN SERVER DETAILS
    private static final String TURN_URL = "turn:34.93.141.147:3478"; 
    private static final String TURN_USERNAME = "testuser";
    private static final String TURN_PASSWORD = "testpass";

    private TextView tvChannel, tvStatus, tvMode;
    private Button btnTalk, btnEndCall, btnRelaySettings, btnSpeakerToggle;
    private Chronometer chronometer;
    private AudioManager audioManager;

    private String requestId;
    private FirebaseFirestore db;
    private DocumentReference signalingRef;
    private CollectionReference candidatesRef;
    private ListenerRegistration signalingListener;
    private ListenerRegistration candidateListener;
    private ListenerRegistration callStatusListener;

    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private AudioTrack localAudioTrack;
    private boolean isTransmitting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_in_app_call);

        requestId = getIntent().getStringExtra("requestId");
        if (requestId == null || requestId.isEmpty()) requestId = "default_room";

        db = FirebaseFirestore.getInstance();
        signalingRef = db.collection("call_signaling").document(requestId);
        candidatesRef = signalingRef.collection("candidates");

        tvChannel = findViewById(R.id.tvChannel);
        tvStatus = findViewById(R.id.tvStatus);
        tvMode = findViewById(R.id.tvMode);
        btnTalk = findViewById(R.id.btnTalk);
        btnEndCall = findViewById(R.id.btnEndCall);
        btnRelaySettings = findViewById(R.id.btnRelaySettings);

        tvChannel.setText("Channel: " + requestId);
        tvMode.setText("Mode: Global P2P + Relay (UDP/TURN)");
        tvStatus.setText("Connecting...");

        btnRelaySettings.setOnClickListener(v -> 
                startActivity(new Intent(this, PttRelaySettingsActivity.class)));

        btnSpeakerToggle = findViewById(R.id.btnSpeakerToggle);
        btnSpeakerToggle.setOnClickListener(v -> toggleSpeaker());

        chronometer = findViewById(R.id.chronometer);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        btnEndCall.setOnClickListener(v -> endCall());
        btnTalk.setOnClickListener(v -> togglePushToTalk());

        observeCallTermination();
        checkPermissionsAndStart();
    }

    private void checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQ_CODE);
        } else {
            initializeWebRtc();
        }
    }

    private void initializeWebRtc() {
        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(null, true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(null))
                .createPeerConnectionFactory();

        createPeerConnection();
        setupAudio();
        startSignaling();
    }

    private void createPeerConnection() {
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(getIceServers());
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        
        // DIAGNOSTIC: Force Relay mode to test if the TURN server is reachable
        // If it works in RELAY mode, then the server is 100% fine.
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.RELAY;
        // ENABLE STABLE RE-GATHERING: Helps when switching between mobile towers
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.iceConnectionReceivingTimeout = 10000; // Increase timeout for long-distance latency

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                // LOGGING: See what kind of candidates we are finding
                Log.d(TAG, "ICE Candidate Found: " + iceCandidate.sdp);
                
                Map<String, Object> candidate = new HashMap<>();
                candidate.put("sdpMid", iceCandidate.sdpMid);
                candidate.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                candidate.put("sdp", iceCandidate.sdp);
                candidate.put("from", FirebaseAuth.getInstance().getUid());
                candidatesRef.add(candidate);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "ICE Connection State Change: " + iceConnectionState);
                runOnUiThread(() -> {
                    if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                        tvStatus.setText("Listening (Relay Active)");
                        chronometer.setBase(android.os.SystemClock.elapsedRealtime());
                        chronometer.start();
                    } else if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                        tvStatus.setText("Searching Signal...");
                        chronometer.stop();
                    } else if (iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                        tvStatus.setText("Relay Connection Failed");
                        chronometer.stop();
                        Log.e(TAG, "ICE CONNECTION FAILED. Check GCP Firewall ports 49152-65535!");
                    }
                });
            }

            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "ICE Gathering State: " + iceGatheringState);
            }

            @Override public void onTrack(org.webrtc.RtpTransceiver transceiver) {}
            @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override public void onAddStream(MediaStream mediaStream) {}
            @Override public void onRemoveStream(MediaStream mediaStream) {}
            @Override public void onDataChannel(org.webrtc.DataChannel dataChannel) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, MediaStream[] mediaStreams) {}
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState newState) {}
            @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState newState) {}
            @Override public void onSelectedCandidatePairChanged(org.webrtc.CandidatePairChangeEvent event) {}
        });
    }

    private List<PeerConnection.IceServer> getIceServers() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        
        // 1. Google's standard STUN
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        // 2. Your Private TURN Server
        iceServers.add(PeerConnection.IceServer.builder(TURN_URL)
                .setUsername(TURN_USERNAME)
                .setPassword(TURN_PASSWORD)
                .createIceServer());

        return iceServers;
    }

    // (HMAC helper removed as you are using static user/pass)

    private void toggleSpeaker() {
        if (audioManager == null) return;
        boolean isSpeakerOn = audioManager.isSpeakerphoneOn();
        audioManager.setSpeakerphoneOn(!isSpeakerOn);
        
        if (!isSpeakerOn) {
            btnSpeakerToggle.setText("SPEAKER: ON");
            btnSpeakerToggle.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
        } else {
            btnSpeakerToggle.setText("SPEAKER: OFF");
            btnSpeakerToggle.setTextColor(android.graphics.Color.LTGRAY);
        }
    }

    private void setupAudio() {
        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);
        localAudioTrack.setEnabled(false);
        peerConnection.addTrack(localAudioTrack);

        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true); // Default to speaker for walkie-talkie mode
    }

    private void startSignaling() {
        signalingRef.get().addOnSuccessListener(snapshot -> {
            if (!snapshot.exists() || snapshot.get("offer") == null) {
                createOffer();
            } else {
                handleOffer(snapshot);
            }
        });

        signalingListener = signalingRef.addSnapshotListener((snapshot, e) -> {
            if (snapshot != null && snapshot.exists()) {
                String type = snapshot.getString("type");
                if ("answer".equals(type) && peerConnection.getRemoteDescription() == null) {
                    SessionDescription sdp = new SessionDescription(
                            SessionDescription.Type.ANSWER, snapshot.getString("answer"));
                    peerConnection.setRemoteDescription(new SimpleSdpObserver(), sdp);
                }
            }
        });

        candidateListener = candidatesRef.addSnapshotListener((snapshots, e) -> {
            if (snapshots != null) {
                for (DocumentSnapshot doc : snapshots.getDocuments()) {
                    String from = doc.getString("from");
                    if (from != null && !from.equals(FirebaseAuth.getInstance().getUid())) {
                        IceCandidate candidate = new IceCandidate(
                                doc.getString("sdpMid"),
                                (int) (long) doc.getLong("sdpMLineIndex"),
                                doc.getString("sdp")
                        );
                        peerConnection.addIceCandidate(candidate);
                    }
                }
            }
        });
    }

    private void createOffer() {
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                Map<String, Object> offer = new HashMap<>();
                offer.put("offer", sessionDescription.description);
                offer.put("type", "offer");
                signalingRef.set(offer);
            }
        }, new MediaConstraints());
    }

    private void handleOffer(DocumentSnapshot snapshot) {
        SessionDescription sdp = new SessionDescription(
                SessionDescription.Type.OFFER, snapshot.getString("offer"));
        peerConnection.setRemoteDescription(new SimpleSdpObserver(), sdp);
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                Map<String, Object> answer = new HashMap<>();
                answer.put("answer", sessionDescription.description);
                answer.put("type", "answer");
                signalingRef.update(answer);
            }
        }, new MediaConstraints());
    }

    private void togglePushToTalk() {
        if (localAudioTrack == null) return;
        isTransmitting = !isTransmitting;
        localAudioTrack.setEnabled(isTransmitting);
        if (isTransmitting) {
            btnTalk.setText("TRANSMITTING...");
            btnTalk.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_red_dark));
            tvStatus.setText("Talking");
        } else {
            btnTalk.setText("TAP TO TALK");
            btnTalk.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_green_dark));
            tvStatus.setText("Listening (Secure)");
        }
    }

    private void endCall() {
        db.collection("emergency_requests").document(requestId).update("callStatus", "ENDED");
        signalingRef.delete();
        finish();
    }

    private void observeCallTermination() {
        DocumentReference docRef = db.collection("emergency_requests").document(requestId);
        callStatusListener = docRef.addSnapshotListener((snapshot, e) -> {
            if (snapshot != null && snapshot.exists()) {
                if ("ENDED".equals(snapshot.getString("callStatus"))) finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (chronometer != null) chronometer.stop();
        if (signalingListener != null) signalingListener.remove();
        if (candidateListener != null) candidateListener.remove();
        if (callStatusListener != null) callStatusListener.remove();
        if (peerConnection != null) peerConnection.close();
        if (peerConnectionFactory != null) peerConnectionFactory.dispose();
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) { Log.e(TAG, "SDP Error: " + s); }
        @Override public void onSetFailure(String s) { Log.e(TAG, "SDP Error: " + s); }
    }
}
