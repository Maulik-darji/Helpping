package com.example.helpping;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.net.wifi.WifiManager;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

public class InAppCallActivity extends AppCompatActivity {

    private static final String TAG = "InAppCallActivity";

    private static final int PERMISSION_REQ_CODE = 1001;

    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private static final int HEADER_BYTES = 12;
    private static final int FRAME_BYTES = 640; // 20ms @ 16kHz mono PCM16 (320 samples * 2 bytes)

    private ListenerRegistration callStatusListener;
    private String requestId;

    private TextView tvChannel;
    private TextView tvStatus;
    private TextView tvMode;
    private Button btnTalk;
    private Button btnEndCall;
    private Button btnRelaySettings;

    private AudioManager audioManager;
    private PttSession pttSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_in_app_call);

        tvChannel = findViewById(R.id.tvChannel);
        tvStatus = findViewById(R.id.tvStatus);
        tvMode = findViewById(R.id.tvMode);
        btnTalk = findViewById(R.id.btnTalk);
        btnEndCall = findViewById(R.id.btnEndCall);
        btnRelaySettings = findViewById(R.id.btnRelaySettings);

        requestId = getIntent().getStringExtra("requestId");
        if (requestId == null || requestId.isEmpty()) {
            requestId = "default_room";
        }

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            audioManager.setMicrophoneMute(false);
            setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioManager.requestAudioFocus(new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setOnAudioFocusChangeListener(focusChange -> { })
                        .build());
                } else {
                    //noinspection deprecation
                    audioManager.requestAudioFocus(focusChange -> { }, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Audio focus request failed", t);
            }
        }

        if (!"default_room".equals(requestId)) {
            DocumentReference docRef = FirebaseFirestore.getInstance().collection("emergency_requests").document(requestId);
            callStatusListener = docRef.addSnapshotListener((snapshot, e) -> {
                if (e != null) return;
                if (snapshot != null && snapshot.exists()) {
                    String callStatus = snapshot.getString("callStatus");
                    if ("ENDED".equals(callStatus)) {
                        finish();
                    }
                } else if (snapshot != null && !snapshot.exists()) {
                    finish();
                }
            });
        }

        btnEndCall.setOnClickListener(v -> {
            if (!"default_room".equals(requestId)) {
                FirebaseFirestore.getInstance()
                    .collection("emergency_requests")
                    .document(requestId)
                    .update("callStatus", "ENDED");
            }
            finish();
        });

        btnRelaySettings.setOnClickListener(v -> {
            startActivity(new Intent(this, PttRelaySettingsActivity.class));
        });

        btnTalk.setOnClickListener(v -> toggleMic());

        checkPermissionsAndStart();
    }

    private void checkPermissionsAndStart() {
        String[] permissions = {Manifest.permission.RECORD_AUDIO};
        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQ_CODE);
        } else {
            startSession();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQ_CODE) return;

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
            }
        }

        if (allGranted) {
            startSession();
        } else {
            Toast.makeText(this, "Microphone permission is required for walkie-talkie.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void startSession() {
        Channel channel = Channel.fromRequestId(requestId);
        setStatus("Mic muted (tap to talk)", false);
        btnTalk.setText("TAP TO TALK");

        RelayConfig relayConfig = RelayConfig.load(this);
        if (relayConfig != null) {
            tvMode.setText("Mode: Internet relay (" + relayConfig.host + ":" + relayConfig.port + ")");
            tvChannel.setText("Channel: " + requestId);
            pttSession = new PttSession(getApplicationContext(), new TcpRelayTransport(relayConfig.host, relayConfig.port, requestId));
        } else {
            tvMode.setText("Mode: Wi‑Fi LAN (multicast)");
            tvChannel.setText("Channel: " + channel.group.getHostAddress() + ":" + channel.port);
            pttSession = new PttSession(getApplicationContext(), new LanMulticastTransport(getApplicationContext(), channel.group, channel.port));
        }

        try {
            pttSession.start();
        } catch (IOException e) {
            Log.w(TAG, "Failed to start session (first choice).", e);
            if (relayConfig != null) {
                // Relay is what enables any-distance calls. If it fails, fall back to LAN (same Wi‑Fi only).
                Toast.makeText(this, "Relay failed. Falling back to Wi‑Fi LAN (same network only).", Toast.LENGTH_LONG).show();
                tvMode.setText("Mode: Wi‑Fi LAN (multicast)");
                tvChannel.setText("Channel: " + channel.group.getHostAddress() + ":" + channel.port);
                pttSession = new PttSession(getApplicationContext(), new LanMulticastTransport(getApplicationContext(), channel.group, channel.port));
                try {
                    pttSession.start();
                } catch (IOException e2) {
                    Log.e(TAG, "Failed to start LAN session", e2);
                    Toast.makeText(this, "Failed to start audio session.", Toast.LENGTH_LONG).show();
                    finish();
                }
            } else {
                Toast.makeText(this, "Failed to start audio session.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void toggleMic() {
        if (pttSession == null) return;

        if (pttSession.isTransmitting()) {
            pttSession.stopTransmit();
            btnTalk.setText("TAP TO TALK");
            setStatus("Mic muted (tap to talk)", false);
            return;
        }

        setStatus("Mic live (tap to mute)", true);
        btnTalk.setText("TAP TO MUTE");
        pttSession.startTransmit();
    }

    private void setStatus(String text, boolean talking) {
        tvStatus.setText(text);
        if (talking) {
            btnTalk.setEnabled(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                btnTalk.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_red_dark));
            }
        } else {
            btnTalk.setEnabled(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                btnTalk.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_green_dark));
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (callStatusListener != null) {
            callStatusListener.remove();
            callStatusListener = null;
        }

        if (pttSession != null) {
            pttSession.stop();
            pttSession = null;
        }

        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(false);
        }

        super.onDestroy();
    }

    private static final class Channel {
        final InetAddress group;
        final int port;

        private Channel(InetAddress group, int port) {
            this.group = group;
            this.port = port;
        }

        static Channel fromRequestId(String requestId) {
            int hash = requestId == null ? 0 : requestId.hashCode();
            int a = ((hash >> 8) & 0xFF);
            int b = (hash & 0xFF);
            a = (a % 254) + 1;
            b = (b % 254) + 1;

            String groupAddr = "239.255." + a + "." + b;
            int port = 40000 + (Math.abs(hash) % 20000);
            try {
                return new Channel(InetAddress.getByName(groupAddr), port);
            } catch (Exception e) {
                try {
                    return new Channel(InetAddress.getByName("239.255.0.1"), 45000);
                } catch (Exception impossible) {
                    throw new RuntimeException(impossible);
                }
            }
        }
    }

    private static final class PttSession {
        private final Context appContext;
        private final Transport transport;
        private final int senderId;

        private volatile boolean running;
        private volatile boolean transmitting;

        private AudioTrack audioTrack;
        private Thread receiveThread;
        private Thread transmitThread;

        private volatile LastRemoteAudio lastRemoteAudio;

        private PttSession(Context appContext, Transport transport) {
            this.appContext = appContext;
            this.transport = transport;
            this.senderId = ThreadLocalRandom.current().nextInt();
        }

        int getSenderId() {
            return senderId;
        }

        boolean isTransmitting() {
            return transmitting;
        }

        LastRemoteAudio getLastRemoteAudio() {
            return lastRemoteAudio;
        }

        void start() throws IOException {
            running = true;
            transmitting = false;

            transport.start();

            int outMin = AudioTrack.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_OUT, ENCODING);
            if (outMin <= 0) outMin = SAMPLE_RATE_HZ;
            audioTrack = new AudioTrack(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                new AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(CHANNEL_OUT)
                    .build(),
                outMin * 2,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            );
            audioTrack.play();

            receiveThread = new Thread(this::receiveLoop, "HelpPingPttRecv");
            receiveThread.start();
        }

        void stop() {
            running = false;
            stopTransmit();

            transport.stop();

            if (audioTrack != null) {
                try {
                    audioTrack.pause();
                } catch (Throwable ignore) {
                }
                try {
                    audioTrack.flush();
                } catch (Throwable ignore) {
                }
                try {
                    audioTrack.release();
                } catch (Throwable ignore) {
                }
                audioTrack = null;
            }
        }

        void startTransmit() {
            if (!running) return;
            if (transmitting) return;
            transmitting = true;

            transmitThread = new Thread(this::transmitLoop, "HelpPingPttTx");
            transmitThread.start();
        }

        void stopTransmit() {
            transmitting = false;
            if (transmitThread != null) {
                try {
                    transmitThread.join(400);
                } catch (InterruptedException ignored) {
                }
                transmitThread = null;
            }
        }

        private void transmitLoop() {
            AudioRecord recorder = null;
            AcousticEchoCanceler aec = null;
            NoiseSuppressor ns = null;
            AutomaticGainControl agc = null;
            try {
                int inMin = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_IN, ENCODING);
                if (inMin <= 0) inMin = FRAME_BYTES * 4;

                recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE_HZ,
                    CHANNEL_IN,
                    ENCODING,
                    Math.max(inMin, FRAME_BYTES * 8)
                );

                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord not initialized");
                    return;
                }

                int sessionId = recorder.getAudioSessionId();
                if (sessionId != AudioManager.ERROR && sessionId != 0) {
                    try {
                        if (AcousticEchoCanceler.isAvailable()) {
                            aec = AcousticEchoCanceler.create(sessionId);
                            if (aec != null) aec.setEnabled(true);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "AEC enable failed", t);
                    }
                    try {
                        if (NoiseSuppressor.isAvailable()) {
                            ns = NoiseSuppressor.create(sessionId);
                            if (ns != null) ns.setEnabled(true);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "NS enable failed", t);
                    }
                    try {
                        if (AutomaticGainControl.isAvailable()) {
                            agc = AutomaticGainControl.create(sessionId);
                            if (agc != null) agc.setEnabled(true);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "AGC enable failed", t);
                    }
                }

                recorder.startRecording();

                byte[] packet = new byte[HEADER_BYTES + FRAME_BYTES];
                ByteBuffer header = ByteBuffer.wrap(packet, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);

                int seq = 0;
                while (running && transmitting) {
                    header.put(0, (byte) 'H');
                    header.put(1, (byte) 'P');
                    header.put(2, (byte) 1); // version
                    header.put(3, (byte) 0); // flags
                    header.putInt(4, senderId);
                    header.putInt(8, seq++);

                    int read = recorder.read(packet, HEADER_BYTES, FRAME_BYTES);
                    if (read <= 0) continue;

                    try {
                        transport.send(packet, HEADER_BYTES + read);
                    } catch (IOException e) {
                        Log.w(TAG, "Send failed", e);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Transmit loop crashed", t);
            } finally {
                try {
                    if (aec != null) aec.release();
                } catch (Throwable ignore) {
                }
                try {
                    if (ns != null) ns.release();
                } catch (Throwable ignore) {
                }
                try {
                    if (agc != null) agc.release();
                } catch (Throwable ignore) {
                }
                if (recorder != null) {
                    try {
                        recorder.stop();
                    } catch (Throwable ignore) {
                    }
                    try {
                        recorder.release();
                    } catch (Throwable ignore) {
                    }
                }
                transmitting = false;
            }
        }

        private void receiveLoop() {
            while (running) {
                Transport.Received received;
                try {
                    received = transport.receive(1000);
                } catch (SocketTimeoutException timeout) {
                    continue;
                } catch (IOException e) {
                    if (running) Log.w(TAG, "Receive failed", e);
                    continue;
                }
                if (received == null) continue;

                byte[] buf = received.buf;
                int len = received.len;
                if (len < HEADER_BYTES + 2) continue;
                if (buf[0] != 'H' || buf[1] != 'P') continue;
                if (buf[2] != 1) continue;

                int pktSenderId = ByteBuffer.wrap(buf, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (pktSenderId == senderId) continue;

                lastRemoteAudio = new LastRemoteAudio(pktSenderId, SystemClock.elapsedRealtime());

                AudioTrack track = audioTrack;
                if (track == null) continue;

                int audioLen = len - HEADER_BYTES;
                if (audioLen <= 0) continue;
                try {
                    track.write(buf, HEADER_BYTES, audioLen);
                } catch (Throwable t) {
                    Log.w(TAG, "AudioTrack write failed", t);
                }
            }
        }

        static final class LastRemoteAudio {
            final int senderId;
            final long atMs;

            LastRemoteAudio(int senderId, long atMs) {
                this.senderId = senderId;
                this.atMs = atMs;
            }
        }
    }

    private interface Transport {
        final class Received {
            final byte[] buf;
            final int len;

            Received(byte[] buf, int len) {
                this.buf = buf;
                this.len = len;
            }
        }

        void start() throws IOException;

        void stop();

        void send(byte[] buf, int len) throws IOException;

        Received receive(int timeoutMs) throws IOException;
    }

    private static final class LanMulticastTransport implements Transport {
        private final InetAddress group;
        private final int port;
        private final Context appContext;

        private WifiManager.MulticastLock multicastLock;
        private MulticastSocket receiveSocket;
        private DatagramSocket sendSocket;

        LanMulticastTransport(Context appContext, InetAddress group, int port) {
            this.group = group;
            this.port = port;
            this.appContext = appContext.getApplicationContext();
        }

        @Override
        public void start() throws IOException {
            Context ctx = appContext;
            if (ctx != null) {
                WifiManager wifiManager = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    multicastLock = wifiManager.createMulticastLock("HelpPingPtt");
                    multicastLock.setReferenceCounted(false);
                    multicastLock.acquire();
                }
            }

            receiveSocket = new MulticastSocket(port);
            receiveSocket.setReuseAddress(true);
            receiveSocket.setTimeToLive(1);
            try {
                receiveSocket.setLoopbackMode(true);
            } catch (Throwable ignore) {
            }
            receiveSocket.joinGroup(group);

            sendSocket = new DatagramSocket();
            sendSocket.setReuseAddress(true);
        }

        @Override
        public void stop() {
            if (receiveSocket != null) {
                try {
                    receiveSocket.leaveGroup(group);
                } catch (Throwable ignore) {
                }
                try {
                    receiveSocket.close();
                } catch (Throwable ignore) {
                }
                receiveSocket = null;
            }

            if (sendSocket != null) {
                try {
                    sendSocket.close();
                } catch (Throwable ignore) {
                }
                sendSocket = null;
            }

            if (multicastLock != null) {
                try {
                    multicastLock.release();
                } catch (Throwable ignore) {
                }
                multicastLock = null;
            }
        }

        @Override
        public void send(byte[] buf, int len) throws IOException {
            DatagramSocket socket = sendSocket;
            if (socket == null) throw new IOException("LAN transport not started");
            DatagramPacket dp = new DatagramPacket(buf, len, group, port);
            socket.send(dp);
        }

        @Override
        public Received receive(int timeoutMs) throws IOException {
            MulticastSocket socket = receiveSocket;
            if (socket == null) throw new IOException("LAN transport not started");
            socket.setSoTimeout(timeoutMs);
            byte[] buf = new byte[1500];
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            socket.receive(dp);
            return new Received(buf, dp.getLength());
        }
    }

    private static final class TcpRelayTransport implements Transport {
        private static final int MAGIC = 0x48505054; // 'HPPT'
        private static final byte VERSION = 1;
        private static final byte TYPE_JOIN = 1;
        private static final byte TYPE_AUDIO = 2;

        private final String host;
        private final int port;
        private final String room;

        private Socket socket;
        private InputStream in;
        private OutputStream out;

        TcpRelayTransport(String host, int port, String room) {
            this.host = host;
            this.port = port;
            this.room = room;
        }

        @Override
        public void start() throws IOException {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(1000);
            in = socket.getInputStream();
            out = socket.getOutputStream();

            byte[] roomBytes = room.getBytes(StandardCharsets.UTF_8);
            ByteBuffer join = ByteBuffer.allocate(4 + 1 + 1 + 2 + 2 + roomBytes.length).order(ByteOrder.BIG_ENDIAN);
            join.putInt(MAGIC);
            join.put(VERSION);
            join.put(TYPE_JOIN);
            join.putShort((short) 0);
            join.putShort((short) roomBytes.length);
            join.put(roomBytes);
            out.write(join.array());
            out.flush();
        }

        @Override
        public void stop() {
            try {
                if (socket != null) socket.close();
            } catch (Throwable ignore) {
            }
            socket = null;
            in = null;
            out = null;
        }

        @Override
        public void send(byte[] buf, int len) throws IOException {
            if (out == null) throw new IOException("Relay transport not started");

            ByteBuffer header = ByteBuffer.allocate(4 + 1 + 1 + 2 + 2).order(ByteOrder.BIG_ENDIAN);
            header.putInt(MAGIC);
            header.put(VERSION);
            header.put(TYPE_AUDIO);
            header.putShort((short) 0);
            header.putShort((short) len);
            out.write(header.array());
            out.write(buf, 0, len);
            out.flush();
        }

        @Override
        public Received receive(int timeoutMs) throws IOException {
            if (in == null) throw new IOException("Relay transport not started");
            if (socket != null) socket.setSoTimeout(timeoutMs);

            byte[] fixed = readExact(4 + 1 + 1 + 2 + 2);
            ByteBuffer bb = ByteBuffer.wrap(fixed).order(ByteOrder.BIG_ENDIAN);
            int magic = bb.getInt();
            byte ver = bb.get();
            byte type = bb.get();
            bb.getShort(); // reserved
            int len = bb.getShort() & 0xFFFF;

            if (magic != MAGIC || ver != VERSION) {
                throw new IOException("Bad relay frame");
            }
            if (type != TYPE_AUDIO) {
                // Ignore non-audio frames.
                readExact(len);
                return null;
            }
            if (len <= 0 || len > 4096) {
                throw new IOException("Bad audio length: " + len);
            }
            byte[] payload = readExact(len);
            return new Received(payload, payload.length);
        }

        private byte[] readExact(int n) throws IOException {
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r < 0) throw new IOException("Relay socket closed");
                off += r;
            }
            return buf;
        }
    }

    private static final class RelayConfig {
        final String host;
        final int port;

        RelayConfig(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static RelayConfig load(Context context) {
            android.content.SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
            String host = prefs.getString("ptt_relay_host", "");
            int port = prefs.getInt("ptt_relay_port", 0);

            if (host == null || host.trim().isEmpty() || port <= 0) {
                // Fallback to resources (lets you hardcode during builds if you want).
                int hostId = context.getResources().getIdentifier("ptt_relay_host", "string", context.getPackageName());
                int portId = context.getResources().getIdentifier("ptt_relay_port", "string", context.getPackageName());
                if (hostId != 0) {
                    host = context.getString(hostId);
                }
                if (portId != 0) {
                    try {
                        port = Integer.parseInt(context.getString(portId));
                    } catch (Throwable ignore) {
                        port = 0;
                    }
                }
            }

            if (host == null) host = "";
            host = host.trim();
            if (host.isEmpty() || port <= 0) return null;
            return new RelayConfig(host, port);
        }
    }
}
