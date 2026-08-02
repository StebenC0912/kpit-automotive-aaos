package com.kpit.bluetooth;

import com.kpit.bluetooth.IIviBluetoothListener;

interface IIviBluetoothService {
    void connect(String macAddress);
    void disconnect(String macAddress);
    void sendMediaCommand(int action);
    void registerListener(IIviBluetoothListener listener);
    void unregisterListener(IIviBluetoothListener listener);
}
