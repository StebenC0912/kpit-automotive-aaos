package com.kpit.bluetooth;

import com.kpit.bluetooth.manager.BluetoothDeviceInfo;
import com.kpit.bluetooth.manager.MediaPlaybackInfo;

// oneway per rule IV.2 - never let a slow/dead HMI listener block the Service's IPC thread.
oneway interface IIviBluetoothListener {
    void onDeviceConnectionChanged(in BluetoothDeviceInfo device, boolean connected);
    void onPlaybackStateChanged(int state, long positionMs);
    void onMediaMetadataChanged(in MediaPlaybackInfo info);
}
