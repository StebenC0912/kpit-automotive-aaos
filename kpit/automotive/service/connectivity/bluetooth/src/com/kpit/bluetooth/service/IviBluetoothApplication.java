package com.kpit.bluetooth.service;

import android.app.Application;
import android.content.Intent;

/**
 * Starts {@link IviBluetoothService} as soon as the process comes up. {@code
 * android:persistent="true"} (AndroidManifest.xml) only guarantees this
 * {@link Application#onCreate()} runs at boot -- it does not start any {@code <service>} declared
 * inside the app -- so without this, IviBluetoothService's onCreate() (and its
 * ServiceManager.addService("bluetooth_service", ...) call) never runs and the service never
 * registers. Mirrors HvacApplication (service/comfort/hvac).
 */
public class IviBluetoothApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        startService(new Intent(this, IviBluetoothService.class));
    }
}
