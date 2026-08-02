package com.kpit.bluetooth.manager;

/**
 * Semantic playback state ids carried by {@code IIviBluetoothListener#onPlaybackStateChanged(int, long)}.
 * {@code IviBluetoothService} maps these from {@code android.media.session.PlaybackState#getState()}
 * as reported by {@code BluetoothAvrcpController#getPlaybackState(BluetoothDevice)}.
 */
public final class IviPlaybackState {
    private IviPlaybackState() {
    }

    public static final int STATE_STOPPED = 0;
    public static final int STATE_PLAYING = 1;
    public static final int STATE_PAUSED = 2;
}
