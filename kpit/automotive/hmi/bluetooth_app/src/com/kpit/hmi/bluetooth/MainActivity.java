package com.kpit.hmi.bluetooth;

import android.os.Bundle;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.kpit.bluetooth.manager.BluetoothDeviceInfo;
import com.kpit.bluetooth.manager.IviPlaybackState;
import com.kpit.bluetooth.manager.MediaPlaybackInfo;
import com.kpit.hmi.bluetooth.viewmodel.BluetoothViewModel;

public class MainActivity extends AppCompatActivity {

    private static final int PROGRESS_MAX_MS = 5 * 60 * 1000;

    private BluetoothViewModel mViewModel;

    private TextView tvDeviceName;
    private TextView tvMacAddress;
    private TextView tvProfileHfp;
    private TextView tvProfileA2dp;
    private TextView tvProfileAvrcp;
    private TextView tvPlaybackState;
    private TextView tvMediaTitle;
    private TextView tvMediaArtist;
    private TextView tvMediaAlbum;
    private ProgressBar pbMediaPosition;
    private TextView tvPositionMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        bindViews();
        setupMediaControls();

        mViewModel = new ViewModelProvider(this).get(BluetoothViewModel.class);
        mViewModel.getDeviceInfoLiveData().observe(this, this::bindDeviceInfo);
        mViewModel.getMediaPlaybackInfoLiveData().observe(this, this::bindMediaMetadata);
        mViewModel.getPlaybackStateLiveData().observe(this, this::bindPlaybackState);
        mViewModel.getPositionMsLiveData().observe(this, this::bindPositionMs);
    }

    private void bindViews() {
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvMacAddress = findViewById(R.id.tvMacAddress);
        tvProfileHfp = findViewById(R.id.tvProfileHfp);
        tvProfileA2dp = findViewById(R.id.tvProfileA2dp);
        tvProfileAvrcp = findViewById(R.id.tvProfileAvrcp);
        tvPlaybackState = findViewById(R.id.tvPlaybackState);
        tvMediaTitle = findViewById(R.id.tvMediaTitle);
        tvMediaArtist = findViewById(R.id.tvMediaArtist);
        tvMediaAlbum = findViewById(R.id.tvMediaAlbum);
        pbMediaPosition = findViewById(R.id.pbMediaPosition);
        tvPositionMs = findViewById(R.id.tvPositionMs);
    }

    private void setupMediaControls() {
        Button btnPrevious = findViewById(R.id.btnPrevious);
        Button btnPlay = findViewById(R.id.btnPlay);
        Button btnPause = findViewById(R.id.btnPause);
        Button btnNext = findViewById(R.id.btnNext);

        btnPrevious.setOnClickListener(v -> mViewModel.previous());
        btnPlay.setOnClickListener(v -> mViewModel.play());
        btnPause.setOnClickListener(v -> mViewModel.pause());
        btnNext.setOnClickListener(v -> mViewModel.next());
    }

    private void bindDeviceInfo(BluetoothDeviceInfo device) {
        if (device == null) {
            tvDeviceName.setText("No device connected");
            tvMacAddress.setText("");
            setProfileBadgeActive(tvProfileHfp, false);
            setProfileBadgeActive(tvProfileA2dp, false);
            setProfileBadgeActive(tvProfileAvrcp, false);
            return;
        }

        tvDeviceName.setText(device.getDeviceName());
        tvMacAddress.setText(getString(R.string.mac_address_prefix, device.getMacAddress()));
        setProfileBadgeActive(tvProfileHfp, device.isConnectedOn(BluetoothDeviceInfo.PROFILE_HFP));
        setProfileBadgeActive(tvProfileA2dp, device.isConnectedOn(BluetoothDeviceInfo.PROFILE_A2DP));
        setProfileBadgeActive(tvProfileAvrcp, device.isConnectedOn(BluetoothDeviceInfo.PROFILE_AVRCP));
    }

    private void setProfileBadgeActive(TextView badge, boolean active) {
        badge.setAlpha(active ? 1.0f : 0.3f);
    }

    private void bindMediaMetadata(MediaPlaybackInfo info) {
        if (info == null) return;
        tvMediaTitle.setText(info.getTitle());
        tvMediaArtist.setText(info.getArtist());
        tvMediaAlbum.setText(info.getAlbum());
    }

    private void bindPlaybackState(Integer state) {
        if (state == null) return;
        switch (state) {
            case IviPlaybackState.STATE_PLAYING:
                tvPlaybackState.setText("STATE_PLAYING");
                break;
            case IviPlaybackState.STATE_PAUSED:
                tvPlaybackState.setText("STATE_PAUSED");
                break;
            case IviPlaybackState.STATE_STOPPED:
            default:
                tvPlaybackState.setText("STATE_STOPPED");
                break;
        }
    }

    private void bindPositionMs(Long positionMs) {
        if (positionMs == null) return;
        tvPositionMs.setText(getString(R.string.position_ms, positionMs));
        pbMediaPosition.setProgress((int) Math.min(100, positionMs * 100 / PROGRESS_MAX_MS));
    }
}
