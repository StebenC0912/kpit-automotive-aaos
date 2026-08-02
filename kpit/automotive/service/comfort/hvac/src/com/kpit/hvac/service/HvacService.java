package com.kpit.hvac.service;

import android.content.Intent;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import com.kpit.comfort.base.service.BaseComfortService;
import com.kpit.hvac.HvacEvent;
import com.kpit.hvac.IHVACVehicleCallback;
import com.kpit.hvac.IHVACVehicleService;
import com.kpit.hvac.manager.HvacProperties;

public class HvacService extends BaseComfortService<IHVACVehicleCallback> {
    private static final String TAG = "HvacService";
    private static final String SERVICE_NAME = "hvac_service";
    private static final float DEFAULT_SAMPLE_RATE_HZ = 5.0f;
    private static final int NATIVE_HANDLE_WAIT_ATTEMPTS = 20;
    private static final long NATIVE_HANDLE_WAIT_DELAY_MS = 50;

    private static final int[] GLOBAL_PROPS = {
            HvacProperties.PROP_AC_STATE,
            HvacProperties.PROP_MAX_STATE,
            HvacProperties.PROP_RECYCLE_STATE,
            HvacProperties.PROP_FAN_SPEED,
            HvacProperties.PROP_SYNC,
            HvacProperties.PROP_AUTO_MODE,
            HvacProperties.PROP_DEFROST,
            HvacProperties.PROP_VENTILATION_MODE,
            HvacProperties.PROP_VEHICLE_STATE,
            HvacProperties.PROP_TEMP_OUTSIDE,
    };

    private static final int[] PER_SEAT_PROPS = {
            HvacProperties.PROP_TEMP,
            HvacProperties.PROP_SEAT_HEATING,
    };

    private final IHVACVehicleService.Stub mBinder = new IHVACVehicleService.Stub() {
        @Override
        public void setVehicleProperty(int id, int areaId, float value) {
            mExecutorPool.execute(() -> {
                long handle = getNativeHandle();
                if (handle == 0) {
                    Log.w(TAG, "setVehicleProperty: native VHAL bridge not ready, id=" + id);
                    return;
                }
                if (!nativeSetFloatProperty(handle, id, areaId, value)) {
                    Log.w(TAG, "setVehicleProperty: native set failed, id=" + id);
                }
            });
        }

        @Override
        public void registerCallback(IHVACVehicleCallback callback) {
            mCallbacks.register(callback);
        }

        @Override
        public void unregisterCallback(IHVACVehicleCallback callback) {
            mCallbacks.unregister(callback);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate: HVAC Service creating");
        ServiceManager.addService(SERVICE_NAME, mBinder);
        mExecutorPool.execute(this::subscribeToVehicleProperties);
    }

    private void subscribeToVehicleProperties() {
        long handle = waitForNativeHandle();
        if (handle == 0) {
            Log.e(TAG, "subscribeToVehicleProperties: native VHAL bridge never became ready");
            return;
        }
        for (int propId : GLOBAL_PROPS) {
            nativeSubscribe(handle, propId, HvacProperties.AREA_GLOBAL, DEFAULT_SAMPLE_RATE_HZ);
        }
        for (int propId : PER_SEAT_PROPS) {
            nativeSubscribe(handle, propId, HvacProperties.DRIVER, DEFAULT_SAMPLE_RATE_HZ);
            nativeSubscribe(handle, propId, HvacProperties.PASSENGER, DEFAULT_SAMPLE_RATE_HZ);
        }
    }

    private long waitForNativeHandle() {
        for (int attempt = 0; attempt < NATIVE_HANDLE_WAIT_ATTEMPTS; attempt++) {
            long handle = getNativeHandle();
            if (handle != 0) {
                return handle;
            }
            try {
                Thread.sleep(NATIVE_HANDLE_WAIT_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        return 0;
    }

    @Override
    protected void onVehiclePropertyChanged(int propId, int areaId) {
        long handle = getNativeHandle();
        if (handle == 0) {
            return;
        }
        HvacEvent event = new HvacEvent(propId, areaId, nativeGetFloatProperty(handle, propId, areaId));
        broadcastToListeners(callback -> callback.onChangeEvent(event));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
