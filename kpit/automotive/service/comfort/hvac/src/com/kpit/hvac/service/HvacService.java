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
            Log.d(TAG, "setVehicleProperty: id=" + id + " areaId=" + areaId + " value=" + value);
            mExecutorPool.execute(() -> {
                long handle = getNativeHandle();
                if (handle == 0) {
                    Log.w(TAG, "setVehicleProperty: native VHAL bridge not ready, id=" + id);
                    return;
                }
                if (!nativeSetFloatProperty(handle, id, areaId, value)) {
                    Log.w(TAG, "setVehicleProperty: native set failed, id=" + id);
                } else {
                    Log.d(TAG, "setVehicleProperty: native set succeeded, id=" + id);
                }
            });
        }

        @Override
        public void registerCallback(IHVACVehicleCallback callback) {
            Log.d(TAG, "registerCallback: " + callback);
            mCallbacks.register(callback);
        }

        @Override
        public void unregisterCallback(IHVACVehicleCallback callback) {
            Log.d(TAG, "unregisterCallback: " + callback);
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
        Log.d(TAG, "subscribeToVehicleProperties: native handle ready, subscribing to all properties");
        for (int propId : GLOBAL_PROPS) {
            boolean subscribed = nativeSubscribe(handle, propId, HvacProperties.AREA_GLOBAL, DEFAULT_SAMPLE_RATE_HZ);
            Log.d(TAG, "subscribeToVehicleProperties: propId=" + propId + " areaId=GLOBAL subscribed=" + subscribed);
        }
        for (int propId : PER_SEAT_PROPS) {
            boolean driverSubscribed = nativeSubscribe(handle, propId, HvacProperties.DRIVER, DEFAULT_SAMPLE_RATE_HZ);
            Log.d(TAG, "subscribeToVehicleProperties: propId=" + propId + " areaId=DRIVER subscribed=" + driverSubscribed);
            boolean passengerSubscribed = nativeSubscribe(handle, propId, HvacProperties.PASSENGER, DEFAULT_SAMPLE_RATE_HZ);
            Log.d(TAG, "subscribeToVehicleProperties: propId=" + propId + " areaId=PASSENGER subscribed=" + passengerSubscribed);
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
            Log.w(TAG, "onVehiclePropertyChanged: native VHAL bridge not ready, propId=" + propId);
            return;
        }
        HvacEvent event = new HvacEvent(propId, areaId, nativeGetFloatProperty(handle, propId, areaId));
        Log.d(TAG, "onVehiclePropertyChanged: broadcasting " + event);
        int listenerCount = broadcastToListeners(callback -> callback.onChangeEvent(event));
        Log.d(TAG, "onVehiclePropertyChanged: broadcast to " + listenerCount + " listener(s)");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
