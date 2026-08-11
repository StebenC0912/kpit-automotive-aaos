package com.kpit.hvac.service;

import android.content.Intent;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import com.kpit.comfort.base.service.AllianceCarBaseService;
import com.kpit.hvac.HvacEvent;
import com.kpit.hvac.IHVACVehicleCallback;
import com.kpit.hvac.IHVACVehicleService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

public class AllianceCarHvacService extends AllianceCarBaseService<IHVACVehicleCallback> {
    private static final String TAG = "AllianceCarHvacService";
    private static final String SERVICE_NAME = "hvac_service";
    private static final float DEFAULT_SAMPLE_RATE_HZ = 5.0f;
    private static final int NATIVE_HANDLE_WAIT_ATTEMPTS = 20;
    private static final long NATIVE_HANDLE_WAIT_DELAY_MS = 50;

    // Property/area IDs, deliberately NOT imported from com.kpit.hvac.manager.HvacProperties --
    // this service must not depend on anything under the manager package (client-only surface).
    // These raw ints flow over Binder as-is (see IHVACVehicleService/IHVACVehicleCallback AIDL),
    // so they MUST stay numerically identical to HvacProperties.java's copy -- kept in sync by
    // hand across that boundary, the same way vps/include/VpsPropertyId.h stays in sync with
    // HvacProperties.java across the JNI boundary (see that file for the full bit-layout
    // explanation: bits 31-28 group, 27-24 area type, 23-16 value type, 15-0 index).
    private static final int PROP_AC_STATE = 0x11200001;
    private static final int PROP_MAX_STATE = 0x11200002;
    private static final int PROP_RECYCLE_STATE = 0x11200003;
    private static final int PROP_FAN_SPEED = 0x11400004;
    private static final int PROP_TEMP = 0x15600005;
    private static final int PROP_SYNC = 0x11200006;
    private static final int PROP_SEAT_HEATING = 0x15200007;
    private static final int PROP_VENTILATION_MODE = 0x11400008;
    private static final int PROP_AUTO_MODE = 0x11200009;
    private static final int PROP_DEFROST = 0x1120000A;
    private static final int PROP_VEHICLE_STATE = 0x1140000B;
    private static final int PROP_TEMP_OUTSIDE = 0x1160000C;

    private static final int AREA_GLOBAL = 0;
    private static final int DRIVER = 1;
    private static final int PASSENGER = 2;

    // Cross-reference to the equivalent real android.car.VehiclePropertyIds property -- a
    // debug/logging aid only for dump() below, duplicated from HvacProperties.java for the same
    // "no manager-package import" reason as the propId constants above. See that file for the
    // full explanation of which of these are exact matches vs. closest equivalents.
    private static final Map<Integer, Integer> REAL_VHAL_PROPERTY_IDS = new HashMap<>();
    static {
        REAL_VHAL_PROPERTY_IDS.put(PROP_AC_STATE, 0x15200505);          // HVAC_AC_ON
        REAL_VHAL_PROPERTY_IDS.put(PROP_MAX_STATE, 0x15200506);         // HVAC_MAX_AC_ON
        REAL_VHAL_PROPERTY_IDS.put(PROP_RECYCLE_STATE, 0x15200508);     // HVAC_RECIRC_ON
        REAL_VHAL_PROPERTY_IDS.put(PROP_FAN_SPEED, 0x15400500);         // HVAC_FAN_SPEED
        REAL_VHAL_PROPERTY_IDS.put(PROP_TEMP, 0x15600503);              // HVAC_TEMPERATURE_SET
        REAL_VHAL_PROPERTY_IDS.put(PROP_SYNC, 0x15200509);              // HVAC_DUAL_ON
        REAL_VHAL_PROPERTY_IDS.put(PROP_SEAT_HEATING, 0x1540050B);      // HVAC_SEAT_TEMPERATURE
        REAL_VHAL_PROPERTY_IDS.put(PROP_VENTILATION_MODE, 0x15400501);  // HVAC_FAN_DIRECTION
        REAL_VHAL_PROPERTY_IDS.put(PROP_AUTO_MODE, 0x1520050A);         // HVAC_AUTO_ON
        REAL_VHAL_PROPERTY_IDS.put(PROP_DEFROST, 0x13200504);           // HVAC_DEFROSTER
        REAL_VHAL_PROPERTY_IDS.put(PROP_TEMP_OUTSIDE, 0x11600703);      // ENV_OUTSIDE_TEMPERATURE
        // PROP_VEHICLE_STATE intentionally has no entry -- no real VHAL analog.
    }

    private static String realVhalPropertyIdLabel(int propId) {
        Integer realId = REAL_VHAL_PROPERTY_IDS.get(propId);
        return realId == null ? "none" : String.format("0x%08X", realId);
    }

    private static final int[] GLOBAL_PROPS = {
            PROP_AC_STATE,
            PROP_MAX_STATE,
            PROP_RECYCLE_STATE,
            PROP_FAN_SPEED,
            PROP_SYNC,
            PROP_AUTO_MODE,
            PROP_DEFROST,
            PROP_VENTILATION_MODE,
            PROP_VEHICLE_STATE,
            PROP_TEMP_OUTSIDE,
    };

    private static final int[] PER_SEAT_PROPS = {
            PROP_TEMP,
            PROP_SEAT_HEATING,
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

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (args != null && args.length > 0 && "--set".equals(args[0])) {
                handleDumpSet(pw, args);
            } else {
                handleDumpGet(pw);
            }
        }

        private void handleDumpGet(PrintWriter pw) {
            long handle = getNativeHandle();
            if (handle == 0) {
                pw.println("ERROR native VHAL bridge not ready");
                return;
            }
            for (int propId : GLOBAL_PROPS) {
                pw.println(nameOf(propId) + " (real VHAL=" + realVhalPropertyIdLabel(propId)
                        + ") area=" + AREA_GLOBAL
                        + " value=" + nativeGetFloatProperty(handle, propId, AREA_GLOBAL));
            }
            for (int propId : PER_SEAT_PROPS) {
                String realVhalLabel = realVhalPropertyIdLabel(propId);
                pw.println(nameOf(propId) + " (real VHAL=" + realVhalLabel + ") area=" + DRIVER
                        + " value=" + nativeGetFloatProperty(handle, propId, DRIVER));
                pw.println(nameOf(propId) + " (real VHAL=" + realVhalLabel + ") area=" + PASSENGER
                        + " value=" + nativeGetFloatProperty(handle, propId, PASSENGER));
            }
        }

        // --set <PROP_NAME> -a <area> -f <value>
        private void handleDumpSet(PrintWriter pw, String[] args) {
            if (args.length < 6 || !"-a".equals(args[2]) || !"-f".equals(args[4])) {
                pw.println("ERROR usage: --set <PROP_NAME> -a <area> -f <value>");
                return;
            }
            Integer propId = idOf(args[1]);
            if (propId == null) {
                pw.println("ERROR unknown property " + args[1]);
                return;
            }
            int area;
            float value;
            try {
                area = Integer.parseInt(args[3]);
                value = Float.parseFloat(args[5]);
            } catch (NumberFormatException e) {
                pw.println("ERROR invalid area/value: " + e.getMessage());
                return;
            }
            setVehicleProperty(propId, area, value);
            pw.println("OK sent " + args[1] + " area=" + area + " value=" + value);
        }
    };

    private static String nameOf(int propId) {
        switch (propId) {
            case PROP_AC_STATE: return "PROP_AC_STATE";
            case PROP_MAX_STATE: return "PROP_MAX_STATE";
            case PROP_RECYCLE_STATE: return "PROP_RECYCLE_STATE";
            case PROP_FAN_SPEED: return "PROP_FAN_SPEED";
            case PROP_TEMP: return "PROP_TEMP";
            case PROP_SYNC: return "PROP_SYNC";
            case PROP_SEAT_HEATING: return "PROP_SEAT_HEATING";
            case PROP_VENTILATION_MODE: return "PROP_VENTILATION_MODE";
            case PROP_AUTO_MODE: return "PROP_AUTO_MODE";
            case PROP_DEFROST: return "PROP_DEFROST";
            case PROP_VEHICLE_STATE: return "PROP_VEHICLE_STATE";
            case PROP_TEMP_OUTSIDE: return "PROP_TEMP_OUTSIDE";
            default: return "PROP_UNKNOWN_" + propId;
        }
    }

    private static Integer idOf(String name) {
        switch (name) {
            case "PROP_AC_STATE": return PROP_AC_STATE;
            case "PROP_MAX_STATE": return PROP_MAX_STATE;
            case "PROP_RECYCLE_STATE": return PROP_RECYCLE_STATE;
            case "PROP_FAN_SPEED": return PROP_FAN_SPEED;
            case "PROP_TEMP": return PROP_TEMP;
            case "PROP_SYNC": return PROP_SYNC;
            case "PROP_SEAT_HEATING": return PROP_SEAT_HEATING;
            case "PROP_VENTILATION_MODE": return PROP_VENTILATION_MODE;
            case "PROP_AUTO_MODE": return PROP_AUTO_MODE;
            case "PROP_DEFROST": return PROP_DEFROST;
            case "PROP_VEHICLE_STATE": return PROP_VEHICLE_STATE;
            case "PROP_TEMP_OUTSIDE": return PROP_TEMP_OUTSIDE;
            default: return null;
        }
    }

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
            boolean subscribed = nativeSubscribe(handle, propId, AREA_GLOBAL, DEFAULT_SAMPLE_RATE_HZ);
            Log.d(TAG, "subscribeToVehicleProperties: propId=" + propId + " areaId=GLOBAL subscribed=" + subscribed);
        }
        for (int propId : PER_SEAT_PROPS) {
            boolean driverSubscribed = nativeSubscribe(handle, propId, DRIVER, DEFAULT_SAMPLE_RATE_HZ);
            Log.d(TAG, "subscribeToVehicleProperties: propId=" + propId + " areaId=DRIVER subscribed=" + driverSubscribed);
            boolean passengerSubscribed = nativeSubscribe(handle, propId, PASSENGER, DEFAULT_SAMPLE_RATE_HZ);
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
