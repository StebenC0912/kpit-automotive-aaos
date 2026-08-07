package com.kpit.hvac.service;

import android.app.Application;
import android.content.Intent;

/**
 * Starts {@link HvacService} as soon as the process comes up. {@code android:persistent="true"}
 * (AndroidManifest.xml) only guarantees this {@link Application#onCreate()} runs at boot -- it
 * does not start any {@code <service>} declared inside the app -- so without this, HvacService's
 * onCreate() (and its ServiceManager.addService("hvac_service", ...) call) never runs and the
 * service never registers.
 */
public class HvacApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        startService(new Intent(this, HvacService.class));
    }
}
