package com.kpit.hmi.bluetooth.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.kpit.bluetooth.manager.BluetoothListener;
import com.kpit.bluetooth.manager.BluetoothDeviceInfo;
import com.kpit.bluetooth.manager.IviBluetoothManager;
import com.kpit.bluetooth.manager.MediaAction;
import com.kpit.bluetooth.manager.MediaPlaybackInfo;

public class BluetoothViewModel extends AndroidViewModel implements BluetoothListener {
    private static final String TAG = "BluetoothViewModel";
    private final MutableLiveData<BluetoothDeviceInfo> mDeviceInfoLiveData = new MutableLiveData<>();
    private final MutableLiveData<MediaPlaybackInfo> mMediaPlaybackInfoLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> mPlaybackStateLiveData = new MutableLiveData<>();
    private final MutableLiveData<Long> mPositionMsLiveData = new MutableLiveData<>();
    private final IviBluetoothManager bluetoothManager;

    public BluetoothViewModel(@NonNull Application application) {
        super(application);
        bluetoothManager = IviBluetoothManager.getInstance();
        bluetoothManager.registerBluetoothListener(this);
    }

    public MutableLiveData<BluetoothDeviceInfo> getDeviceInfoLiveData() {
        return mDeviceInfoLiveData;
    }

    public MutableLiveData<MediaPlaybackInfo> getMediaPlaybackInfoLiveData() {
        return mMediaPlaybackInfoLiveData;
    }

    public MutableLiveData<Integer> getPlaybackStateLiveData() {
        return mPlaybackStateLiveData;
    }

    public MutableLiveData<Long> getPositionMsLiveData() {
        return mPositionMsLiveData;
    }

    public void play() {
        bluetoothManager.sendMediaCommand(MediaAction.ACTION_PLAY);
    }

    public void pause() {
        bluetoothManager.sendMediaCommand(MediaAction.ACTION_PAUSE);
    }

    public void next() {
        bluetoothManager.sendMediaCommand(MediaAction.ACTION_NEXT);
    }

    public void previous() {
        bluetoothManager.sendMediaCommand(MediaAction.ACTION_PREVIOUS);
    }

    @Override
    public void onDeviceConnectionChanged(BluetoothDeviceInfo device, boolean connected) {
        mDeviceInfoLiveData.postValue(connected ? device : null);
    }

    @Override
    public void onPlaybackStateChanged(int state, long positionMs) {
        mPlaybackStateLiveData.postValue(state);
        mPositionMsLiveData.postValue(positionMs);
    }

    @Override
    public void onMediaMetadataChanged(MediaPlaybackInfo info) {
        mMediaPlaybackInfoLiveData.postValue(info);
        mPlaybackStateLiveData.postValue(info.getPlaybackState());
        mPositionMsLiveData.postValue(info.getPositionMs());
    }

    @Override
    protected void onCleared() {
        bluetoothManager.unregisterBluetoothListener(this);
        super.onCleared();
    }
}
