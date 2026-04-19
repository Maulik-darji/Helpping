package com.example.helpping;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
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

    // Original UI Elements
    private TextView tvChannel, tvStatus, tvMode;
    private Button btnTalk, btnEndCall, btnRelaySettings;

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
        if (requestId == null || requestId.isEmpty()) {
            requestId = "default_room";
        }

        db = FirebaseFirestore.getInstance();
        signalingRef = db.collection("call_signaling").document(requestId);
        candidatesRef = signalingRef.collection("candidates");

        // Initialize Original UI
        tvChannel = findViewById(R.id.tvChannel);
        tvStatus = findViewById(R.id.tvStatus);
        tvMode = findViewById(R.id.tvMode);
        btnTalk = findViewById(R.id.btnTalk);
        btnEndCall = findViewById(R.id.btnEndCall);
        btnRelaySettings = findViewById(R.id.btnRelaySettings);

        tvChannel.setText("Channel: " + requestId);
        tvMode.setText("Mode: WebRTC P2P (Internet)");
        tvStatus.setText("Connecting...");

        btnRelaySettings.setOnClickListener(v -> 
                startActivity(new Intent(this, PttRelaySettingsActivity.class)));

        btnEndCall.setOnClickListener(v -> endCall());
        btnTalk.setOnClickListener(v -> togglePushToTalk());

        observeCallTermination();
        checkPermissionsAndStart();
    }

    private void observeCallTermination() {
        DocumentReference docRef = db.collection("emergency_requests").document(requestId);
        callStatusListener = docRef.addSnapshotListener((snapshot, e) -> {
            if (snapshot != null && snapshot.exists()) {
                if ("ENDED".equals(snapshot.getString("callStatus"))) {
                    finish();
                }
            } else if (snapshot != null && !snapshot.exists() && !"default_room".equals(requestId)) {
                finish();
            }
        });
    }

    private void checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQ_CODE);
        } else {
            initializeWebRtc();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeWebRtc();
        } else {
            Toast.makeText(this, "Mic permission required", Toast.LENGTH_SHORT).show();
            finish();
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
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Map<String, Object> candidate = new HashMap<>();
                candidate.put("sdpMid", iceCandidate.sdpMid);
                candidate.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                candidate.put("sdp", iceCandidate.sdp);
                candidate.put("from", FirebaseAuth.getInstance().getUid());
                candidatesRef.add(candidate);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                runOnUiThread(() -> {
                    if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                        tvStatus.setText("Listening");
                    } else if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                        tvStatus.setText("Disconnected");
                    }
                });
            }

            @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override public void onAddStream(MediaStream mediaStream) {}
            @Override public void onRemoveStream(MediaStream mediaStream) {}
            @Override public void onDataChannel(org.webrtc.DataChannel dataChannel) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, MediaStream[] mediaStreams) {}
            @Override public void onTrack(org.webrtc.RtpTransceiver transceiver) {}

            // Extended methods for newer SDKs
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState newState) {}
            @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState newState) {}
            @Override public void onSelectedCandidatePairChanged(org.webrtc.CandidatePairChangeEvent event) {}
        });
    }

    private void setupAudio() {
        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);
        localAudioTrack.setEnabled(false); // Default to muted for walkie-talkie mode
        peerConnection.addTrack(localAudioTrack);

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);
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
            tvStatus.setText("Listening");
        }
    }

    private void endCall() {
        db.collection("emergency_requests").document(requestId).update("callStatus", "ENDED");
        signalingRef.delete();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
