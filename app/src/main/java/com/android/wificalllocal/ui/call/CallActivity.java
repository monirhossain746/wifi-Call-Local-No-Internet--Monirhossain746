package com.android.wificalllocal.ui.call;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.CallLogEntity;
import com.android.wificalllocal.databinding.ActivityCallBinding;
import com.android.wificalllocal.network.PeerConnectionManager;
import com.android.wificalllocal.network.model.NetPacket;
import com.android.wificalllocal.service.CallForegroundService;
import com.android.wificalllocal.util.AvatarLoader;
import com.android.wificalllocal.util.PreferencesManager;
import com.android.wificalllocal.webrtc.WebRtcCallManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.webrtc.VideoTrack;

import java.util.UUID;

public class CallActivity extends AppCompatActivity implements PeerConnectionManager.CallSignalListener, WebRtcCallManager.CallEvents {

    public static final String EXTRA_INCOMING = "incoming";
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_PEER_ID = "peer_id";
    public static final String EXTRA_PEER_NAME = "peer_name";
    public static final String EXTRA_PEER_IP = "peer_ip";
    public static final String EXTRA_CALL_TYPE = "call_type";
    public static final String EXTRA_OFFER_SDP = "offer_sdp";

    private ActivityCallBinding binding;
    private ServiceLocator locator;
    private WebRtcCallManager webRtcManager;

    private boolean isIncoming;
    private String callId;
    private String peerId;
    private String peerName;
    private String peerIp;
    private String callType;
    private String offerSdp;

    private boolean isMuted = false;
    private boolean isSpeaker = false;
    private boolean isVideoEnabled = true;
    private boolean callConnected = false;
    private long connectedAtMs = 0L;
    private String finalStatus = "MISSED";

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (callConnected) {
                long sec = (System.currentTimeMillis() - connectedAtMs) / 1000L;
                binding.callDurationText.setText(String.format("%02d:%02d", sec / 60, sec % 60));
                timerHandler.postDelayed(this, 1000L);
            }
        }
    };

    private final ActivityResultLauncher<String[]> mediaPermLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            results -> {
                boolean micOk = Boolean.TRUE.equals(results.getOrDefault(Manifest.permission.RECORD_AUDIO,
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED));
                if (!micOk) {
                    Toast.makeText(this, R.string.perm_microphone, Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    startCallEngine();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        locator = ServiceLocator.getInstance(this);
        if (locator.getAppControlManager().getCurrentGateState().isBlocked()) {
            locator.getAppControlManager().enforceGateIfBlocked(this, locator.getAppControlManager().getCurrentGateState());
            return;
        }
        locator.getNotificationHelper().cancelIncomingCallNotification();

        isIncoming = getIntent().getBooleanExtra(EXTRA_INCOMING, false);
        callId = getIntent().getStringExtra(EXTRA_CALL_ID);
        if (callId == null || callId.isEmpty()) callId = UUID.randomUUID().toString();
        peerId = getIntent().getStringExtra(EXTRA_PEER_ID);
        peerName = getIntent().getStringExtra(EXTRA_PEER_NAME);
        peerIp = getIntent().getStringExtra(EXTRA_PEER_IP);
        callType = getIntent().getStringExtra(EXTRA_CALL_TYPE);
        if (callType == null) callType = "VOICE";
        offerSdp = getIntent().getStringExtra(EXTRA_OFFER_SDP);

        boolean isVideo = callType.contains("VIDEO");
        isVideoEnabled = isVideo;
        isSpeaker = isVideo;

        binding.callPeerName.setText(peerName != null ? peerName : "Local Peer");
        AvatarLoader.loadPeerAvatar(binding.callAvatar, peerName, peerId, null);
        binding.btnVideoToggle.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        binding.btnFlipCamera.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        binding.btnPip.setVisibility(isVideo ? View.VISIBLE : View.GONE);

        locator.getPeerConnectionManager().setCallSignalListener(this);

        if (isIncoming && PreferencesManager.STATUS_BUSY.equals(locator.getPreferencesManager().getActiveStatus())) {
            NetPacket busy = locator.getPeerConnectionManager().buildBasePacket(NetPacket.TYPE_CALL_BUSY);
            busy.targetId = peerId;
            busy.callId = callId;
            locator.getPeerConnectionManager().sendPacketToPeer(peerId, busy);
            finalStatus = "BUSY";
            finish();
            return;
        }

        setupControls();

        if (isIncoming) {
            binding.callStatusText.setText(isVideo ? R.string.call_incoming_video : R.string.call_incoming_voice);
            binding.incomingControls.setVisibility(View.VISIBLE);
            binding.callControlsCard.setVisibility(View.GONE);
            NetPacket ringing = locator.getPeerConnectionManager().buildBasePacket(NetPacket.TYPE_CALL_RINGING);
            ringing.targetId = peerId;
            ringing.callId = callId;
            locator.getPeerConnectionManager().sendPacketToPeer(peerId, ringing);
        } else {
            binding.callStatusText.setText(R.string.call_status_ringing);
            checkMediaPermissionsAndStart();
        }
    }

    private void setupControls() {
        binding.btnAcceptCall.setOnClickListener(v -> {
            binding.incomingControls.setVisibility(View.GONE);
            binding.callControlsCard.setVisibility(View.VISIBLE);
            checkMediaPermissionsAndStart();
        });

        binding.btnRejectCall.setOnClickListener(v -> {
            finalStatus = "REJECTED";
            sendEndSignal();
            finish();
        });

        binding.btnEndCall.setOnClickListener(v -> {
            finalStatus = callConnected ? "COMPLETED" : "MISSED";
            sendEndSignal();
            finish();
        });

        binding.btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            if (webRtcManager != null) webRtcManager.setMuted(isMuted);
            binding.btnMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        });

        binding.btnSpeaker.setOnClickListener(v -> {
            isSpeaker = !isSpeaker;
            if (webRtcManager != null) webRtcManager.setSpeakerphoneOn(isSpeaker);
            binding.btnSpeaker.setAlpha(isSpeaker ? 1.0f : 0.6f);
        });

        binding.btnVideoToggle.setOnClickListener(v -> {
            isVideoEnabled = !isVideoEnabled;
            if (webRtcManager != null) webRtcManager.setVideoEnabled(isVideoEnabled);
            binding.btnVideoToggle.setImageResource(isVideoEnabled ? R.drawable.ic_video : R.drawable.ic_videocam_off);
        });

        binding.btnFlipCamera.setOnClickListener(v -> {
            if (webRtcManager != null) webRtcManager.switchCamera();
        });

        binding.btnPip.setOnClickListener(v -> enterPip());
    }

    private void checkMediaPermissionsAndStart() {
        boolean isVideo = callType.contains("VIDEO");
        java.util.List<String> req = new java.util.ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.RECORD_AUDIO);
        }
        if (isVideo && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.CAMERA);
        }
        if (req.isEmpty()) {
            startCallEngine();
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_rationale_title)
                    .setMessage(isVideo ? (getString(R.string.perm_microphone) + "\n\n" + getString(R.string.perm_camera))
                            : getString(R.string.perm_microphone))
                    .setPositiveButton(R.string.ok, (d, w) -> mediaPermLauncher.launch(req.toArray(new String[0])))
                    .setNegativeButton(R.string.cancel, (d, w) -> finish())
                    .show();
        }
    }

    private void startCallEngine() {
        boolean isVideo = callType.contains("VIDEO");
        CallForegroundService.start(this, peerName, isVideo);
        webRtcManager = new WebRtcCallManager(this, locator.getPeerConnectionManager());

        if (isVideo) {
            binding.localVideoView.init(webRtcManager.getEglContext(), null);
            binding.localVideoView.setMirror(true);
            binding.localVideoView.setZOrderMediaOverlay(true);
            binding.localVideoView.setVisibility(View.VISIBLE);

            binding.remoteVideoView.init(webRtcManager.getEglContext(), null);
            binding.remoteVideoView.setVisibility(View.VISIBLE);
        }

        webRtcManager.initEngine(isVideo, isVideo ? binding.localVideoView : null, this);
        webRtcManager.setSpeakerphoneOn(isSpeaker);

        binding.callStatusText.setText(R.string.call_status_connecting);
        if (isIncoming) {
            webRtcManager.acceptIncomingCall(callId, peerId, callType, offerSdp);
        } else {
            webRtcManager.startOutgoingCall(callId, peerId, callType);
        }
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(9, 16))
                    .build();
            enterPictureInPictureMode(params);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        binding.callControlsCard.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
        binding.callInfoContainer.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onCallSignal(NetPacket pkt) {
        if (pkt == null || pkt.callId == null || !pkt.callId.equals(callId)) return;
        switch (pkt.type) {
            case NetPacket.TYPE_CALL_RINGING:
                binding.callStatusText.setText(R.string.call_status_ringing);
                break;
            case NetPacket.TYPE_CALL_BUSY:
                finalStatus = "BUSY";
                Toast.makeText(this, R.string.call_status_busy, Toast.LENGTH_SHORT).show();
                finish();
                break;
            case NetPacket.TYPE_CALL_ANSWER:
                if (webRtcManager != null) webRtcManager.handleRemoteAnswer(pkt.senderId, pkt.sdp);
                break;
            case NetPacket.TYPE_CALL_ICE:
                if (webRtcManager != null) {
                    webRtcManager.handleRemoteIce(pkt.senderId, pkt.iceCandidate, pkt.iceSdpMid, pkt.iceSdpMLineIndex);
                }
                break;
            case NetPacket.TYPE_CALL_END:
                finalStatus = callConnected ? "COMPLETED" : "MISSED";
                finish();
                break;
        }
    }

    @Override
    public void onRemoteVideoTrack(String remotePeerId, VideoTrack track) {
        runOnUiThread(() -> {
            binding.remoteVideoView.setVisibility(View.VISIBLE);
            track.addSink(binding.remoteVideoView);
        });
    }

    @Override
    public void onPeerConnected(String remotePeerId) {
        runOnUiThread(() -> {
            callConnected = true;
            finalStatus = "COMPLETED";
            connectedAtMs = System.currentTimeMillis();
            binding.callStatusText.setText(R.string.call_status_connected);
            timerHandler.post(timerRunnable);
        });
    }

    @Override
    public void onPeerDisconnected(String remotePeerId) {
        runOnUiThread(() -> {
            finalStatus = callConnected ? "COMPLETED" : "MISSED";
            finish();
        });
    }

    private void sendEndSignal() {
        if (peerId == null) return;
        NetPacket end = locator.getPeerConnectionManager().buildBasePacket(NetPacket.TYPE_CALL_END);
        end.targetId = peerId;
        end.callId = callId;
        locator.getPeerConnectionManager().sendPacketToPeer(peerId, end);
    }

    @Override
    protected void onDestroy() {
        timerHandler.removeCallbacks(timerRunnable);
        locator.getPeerConnectionManager().setCallSignalListener(null);
        CallForegroundService.stop(this);

        long durationSec = callConnected ? (System.currentTimeMillis() - connectedAtMs) / 1000L : 0L;
        String dir = isIncoming ? (callConnected ? "INCOMING" : "MISSED") : "OUTGOING";
        if (peerId != null) {
            AppDatabase.DB_EXECUTOR.execute(() -> locator.getDatabase().callLogDao().upsert(
                    new CallLogEntity(callId, peerId, peerName != null ? peerName : "Peer",
                            peerIp, callType, dir, finalStatus, durationSec, System.currentTimeMillis())
            ));
        }

        if (webRtcManager != null) {
            try {
                binding.localVideoView.release();
                binding.remoteVideoView.release();
            } catch (Exception ignored) {
            }
            webRtcManager.releaseAll();
        }
        super.onDestroy();
    }
}
