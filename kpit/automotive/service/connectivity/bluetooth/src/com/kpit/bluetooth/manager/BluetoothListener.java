package com.kpit.bluetooth.manager;

/**
 * App-facing (non-Binder) listener contract that {@code IviBluetoothManager} fans local AIDL
 * callbacks out to. Mirrors {@code HvacListener}'s role in the Comfort domain.
 */
public interface BluetoothListener {
    void onDeviceConnectionChanged(BluetoothDeviceInfo device, boolean connected);

    void onPlaybackStateChanged(int state, long positionMs);

    void onMediaMetadataChanged(MediaPlaybackInfo info);
}
