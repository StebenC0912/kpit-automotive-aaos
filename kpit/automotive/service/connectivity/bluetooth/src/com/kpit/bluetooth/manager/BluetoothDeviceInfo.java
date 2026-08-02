package com.kpit.bluetooth.manager;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Backing implementation for the {@code BluetoothDeviceInfo} AIDL parcelable (instruction.md
 * section VI). Carries device identity plus per-profile connection state as a bitmask, since a
 * device is commonly connected on more than one profile at once (HFP + A2DP together, with AVRCP
 * riding on the A2DP link).
 */
public final class BluetoothDeviceInfo implements Parcelable {

    public static final int PROFILE_HFP = 1 << 0;    // BluetoothHeadsetClient (car = Hands-Free)
    public static final int PROFILE_A2DP = 1 << 1;   // BluetoothA2dpSink (car = Sink)
    public static final int PROFILE_AVRCP = 1 << 2;  // BluetoothAvrcpController (car = Controller)

    private final String macAddress;
    private final String deviceName;
    private final int connectionState;

    public BluetoothDeviceInfo(String macAddress, String deviceName, int connectionState) {
        this.macAddress = macAddress;
        this.deviceName = deviceName;
        this.connectionState = connectionState;
    }

    private BluetoothDeviceInfo(Parcel in) {
        macAddress = in.readString();
        deviceName = in.readString();
        connectionState = in.readInt();
    }

    public String getMacAddress() {
        return macAddress;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public int getConnectionState() {
        return connectionState;
    }

    public boolean isConnectedOn(int profileFlag) {
        return (connectionState & profileFlag) != 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(macAddress);
        dest.writeString(deviceName);
        dest.writeInt(connectionState);
    }

    public static final Creator<BluetoothDeviceInfo> CREATOR = new Creator<BluetoothDeviceInfo>() {
        @Override
        public BluetoothDeviceInfo createFromParcel(Parcel in) {
            return new BluetoothDeviceInfo(in);
        }

        @Override
        public BluetoothDeviceInfo[] newArray(int size) {
            return new BluetoothDeviceInfo[size];
        }
    };
}
