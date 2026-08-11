package com.kpit.hvac.manager;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.kpit.comfort.base.manager.AllianceCarBaseManager;
import com.kpit.hvac.HvacEvent;
import com.kpit.hvac.IHVACVehicleCallback;
import com.kpit.hvac.IHVACVehicleService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AllianceCarHvacManager extends AllianceCarBaseManager<IHVACVehicleService> implements IHvacController {
    private static final String TAG = "AllianceCarHvacManager";
    private static final String SERVICE_NAME = "hvac_service";

    // Cross-reference from this stub's own propIds (HvacProperties.java) to the equivalent real
    // property in android.car.VehiclePropertyIds (packages/services/Car/car-lib/src/android/car/
    // VehiclePropertyIds.java) -- a debug/logging aid only for the setProperty() log line below,
    // never consulted for routing. AllianceCarHvacService keeps its own independent copy of this
    // (see its class -- it must not depend on anything under this manager package), so this one
    // is now solely for the manager's own use. A few of ours don't map cleanly onto a real
    // property: PROP_VEHICLE_STATE is this demo's own "is the panel locked" concept with no real
    // VHAL analog (omitted below); PROP_SEAT_HEATING maps to HVAC_SEAT_TEMPERATURE even though
    // ours is a boolean and the real property is a signed level; PROP_SYNC maps to HVAC_DUAL_ON
    // as the closest real equivalent of a driver/passenger sync toggle. Values are the literal
    // ints from that real file (confirmed against it, not guessed) rather than an import, since
    // this vendor app has no build dependency on car-lib.
    private static final Map<Integer, Integer> REAL_VHAL_PROPERTY_IDS = new HashMap<>();
    static {
        REAL_VHAL_PROPERTY_IDS.put(HvacProperties.PROP_AC_STATE, 0x15200505);          // HVAC_AC_ON
        REAL_VHAL_PROPERTY_IDS.put(HvacProperties.PROP_MAX_STATE, 0x15200506);         // HVAC_MAX_AC_ON
        REAL_VHAL_PROPERTY_IDS.put(HvacProperties.PROP_RECYCLE_STATE, 0x15200508);     // HVAC_RECIRC_ON
        REAL_VHAL_PROPERTY_IDS.put(HvacProperties.PROP_FAN_SPEED, 0x15400500);         // HVAC_FAN_SPEED
        REAL_VHAL_PROPERTY_IDS.put(HvacProperties.PROP_TEMP, 0x15600503);              // HVAC_TEMPERATURE_SET
        REAL_VHAL_PROPERTY_IDS.put(HvacProperties.PROP_SYNC, 0x15200509);              // HVAC_DUAL_ON
        REAL_VHAL_PROPERTY_IDS.put(HvacProperties.PROP_SEAT_HEATING, 0x1540050B);      // HVAC_SEAT_TEMPERATURE
        REAL_VHAL_PROPERTY_IDS.put(HvacProperties.PROP_VENTILATION_MODE, 0x15400501);  // HVAC_FAN_DIRECTION
        REAL_VHAL_PROPERTY_IDS.put(HvacProperties.PROP_AUTO_MODE, 0x1520050A);         // HVAC_AUTO_ON
        REAL_VHAL_PROPERTY_IDS.put(HvacProperties.PROP_DEFROST, 0x13200504);           // HVAC_DEFROSTER
        REAL_VHAL_PROPERTY_IDS.put(HvacProperties.PROP_TEMP_OUTSIDE, 0x11600703);      // ENV_OUTSIDE_TEMPERATURE
        // PROP_VEHICLE_STATE intentionally has no entry -- no real VHAL analog.
    }

    private static String realVhalPropertyIdLabel(int propId) {
        Integer realId = REAL_VHAL_PROPERTY_IDS.get(propId);
        return realId == null ? "none" : String.format("0x%08X", realId);
    }

    private static AllianceCarHvacManager sInstance;

    private final List<HvacListener> hvacListenerList = new ArrayList<>();
    private final List<SystemListener> systemListenerList = new ArrayList<>();
    private int currentVehicleStates = -1;

    public static synchronized AllianceCarHvacManager getInstance() {
        if (sInstance == null) {
            sInstance = new AllianceCarHvacManager();
        }
        return sInstance;
    }

    public AllianceCarHvacManager() {
        super(SERVICE_NAME);
    }

    @Override
    protected IHVACVehicleService asInterface(IBinder binder) {
        return IHVACVehicleService.Stub.asInterface(binder);
    }

    @Override
    protected void onServiceConnected(IHVACVehicleService service) {
        try {
            service.registerCallback(mBinderCallback);
            Log.d(TAG, "onServiceConnected: registered callback successfully");
        } catch (RemoteException e) {
            Log.e(TAG, "onServiceConnected: cannot register callback", e);
        }
    }

    @Override
    protected void onServiceDisconnected() {
        Log.d(TAG, "onServiceDisconnected: service disconnected");
    }

    @Override
    public void registerSystemListener(SystemListener systemListener) {
        Log.d(TAG, "registerSystemListener: " + systemListener);
        synchronized (systemListenerList) {
            if (!systemListenerList.contains(systemListener))
                systemListenerList.add(systemListener);
        }
        // Registering a listener is the app's signal it wants events -- force the connection (and
        // therefore the remote registerCallback()) now instead of waiting for some future outbound
        // setProperty() call, since nothing else guarantees one will ever happen (HvacViewModel's UI
        // toggles are themselves gated on state that only arrives via a callback event).
        getService();
    }

    public void unregisterSystemListener() {
        Log.d(TAG, "unregisterSystemListener: clearing " + systemListenerList.size() + " listener(s)");
        synchronized (systemListenerList) {
            systemListenerList.clear();
        }
    }

    @Override
    public void registerPropertyListener(HvacListener hvacListener) {
        Log.d(TAG, "registerPropertyListener: " + hvacListener);
        synchronized (hvacListenerList) {
            hvacListenerList.add(hvacListener);
        }
        // See registerSystemListener() -- same forced-connect reasoning, needed here too since
        // callers may register only one of the two listener types.
        getService();
    }

    public void unregisterHvacListener() {
        Log.d(TAG, "unregisterHvacListener: clearing " + hvacListenerList.size() + " listener(s)");
        synchronized (hvacListenerList) {
            hvacListenerList.clear();
        }
    }

    @Override
    public void unregisterAll() {
        unregisterHvacListener();
        unregisterSystemListener();
    }

    @Override
    public void setAcState(boolean value) {
        setProperty(HvacProperties.PROP_AC_STATE, HvacProperties.AREA_GLOBAL, value ? 1.0f : 0.0f);
    }

    @Override
    public void setMaxState(boolean value) {
        setProperty(HvacProperties.PROP_MAX_STATE, HvacProperties.AREA_GLOBAL, value ? 1.0f : 0.0f);
    }

    @Override
    public void setCycleState(boolean value) {
        setProperty(HvacProperties.PROP_RECYCLE_STATE, HvacProperties.AREA_GLOBAL, value ? 1.0f : 0.0f);
    }

    @Override
    public void setFanSpeed(int value) {
        setProperty(HvacProperties.PROP_FAN_SPEED, HvacProperties.AREA_GLOBAL, value);
    }

    @Override
    public void setTemp(int area, float value) {
        setProperty(HvacProperties.PROP_TEMP, area == 1 ? HvacProperties.DRIVER : HvacProperties.PASSENGER, value);
    }

    @Override
    public void setSync(boolean value) {
        setProperty(HvacProperties.PROP_SYNC, HvacProperties.AREA_GLOBAL, value ? 1.0f : 0.0f);
    }

    @Override
    public void setAuto(boolean value) {
        setProperty(HvacProperties.PROP_AUTO_MODE, HvacProperties.AREA_GLOBAL, value ? 1.0f : 0.0f);
    }

    @Override
    public void setHeatingSeat(int area, boolean value) {
        setProperty(HvacProperties.PROP_SEAT_HEATING, area == 1 ? HvacProperties.DRIVER : HvacProperties.PASSENGER,
                value ? 1.0f : 0.0f);
    }

    @Override
    public void setVentilationMode(int value) {
        setProperty(HvacProperties.PROP_VENTILATION_MODE, HvacProperties.AREA_GLOBAL, value);
    }

    @Override
    public void setDefrost(boolean value) {
        setProperty(HvacProperties.PROP_DEFROST, HvacProperties.AREA_GLOBAL, value ? 1.0f : 0.0f);
    }

    private void setProperty(int propertyId, int areaId, float value) {
        Log.d(TAG, "setProperty: propertyId=" + propertyId + " (real VHAL="
                + realVhalPropertyIdLabel(propertyId) + ") areaId=" + areaId + " value=" + value);
        IHVACVehicleService service = getService();
        if (service == null) {
            Log.w(TAG, "setProperty: service not connected, propertyId=" + propertyId);
            return;
        }
        try {
            service.setVehicleProperty(propertyId, areaId, value);
        } catch (RemoteException e) {
            Log.e(TAG, "setProperty: cannot set property " + propertyId, e);
        }
    }

    private final IHVACVehicleCallback.Stub mBinderCallback = new IHVACVehicleCallback.Stub() {
        @Override
        public void onChangeEvent(HvacEvent event) {
            Log.d(TAG, "onChangeEvent: " + event);
            int id = event.getId();
            switch (id) {
                case HvacProperties.PROP_VEHICLE_STATE:
                case HvacProperties.PROP_TEMP_OUTSIDE:
                    dispatchToSystem(event);
                    break;
                case HvacProperties.PROP_AC_STATE:
                case HvacProperties.PROP_MAX_STATE:
                case HvacProperties.PROP_RECYCLE_STATE:
                case HvacProperties.PROP_FAN_SPEED:
                case HvacProperties.PROP_TEMP:
                case HvacProperties.PROP_SYNC:
                case HvacProperties.PROP_SEAT_HEATING:
                case HvacProperties.PROP_VENTILATION_MODE:
                case HvacProperties.PROP_AUTO_MODE:
                case HvacProperties.PROP_DEFROST:
                    dispatchToProperty(event);
                    break;
                default:
                    Log.w(TAG, "onChangeEvent: not handle this property");
                    break;
            }
        }
    };

    private void dispatchToProperty(HvacEvent event) {
        Log.d(TAG, "dispatchToProperty: fanning out " + event + " to " + hvacListenerList.size() + " listener(s)");
        for (HvacListener hvacListener : hvacListenerList) {
            switch (event.getId()) {
                case HvacProperties.PROP_AC_STATE:
                    hvacListener.onACStateChanged(event.getValue() != 0);
                    break;
                case HvacProperties.PROP_MAX_STATE:
                    hvacListener.onMaxStateChanged(event.getValue() != 0);
                    break;
                case HvacProperties.PROP_RECYCLE_STATE:
                    hvacListener.onAirRecycleStateChanged(event.getValue() != 0);
                    break;
                case HvacProperties.PROP_FAN_SPEED:
                    hvacListener.onFanSpeedChanged((int) event.getValue());
                    break;
                case HvacProperties.PROP_TEMP:
                    hvacListener.onTempChanged(event.getValue(), event.getAreaId());
                    break;
                case HvacProperties.PROP_SYNC:
                    hvacListener.onSyncStateChanged(event.getValue() != 0);
                    break;
                case HvacProperties.PROP_SEAT_HEATING:
                    hvacListener.onHeatingSeatChanged(event.getValue() != 0, event.getAreaId());
                    break;
                case HvacProperties.PROP_VENTILATION_MODE:
                    hvacListener.onVentilationModeChanged((int) event.getValue());
                    break;
                case HvacProperties.PROP_AUTO_MODE:
                    hvacListener.onAutoStateChanged(event.getValue() != 0);
                    break;
                case HvacProperties.PROP_DEFROST:
                    hvacListener.onDefrostStateChanged(event.getValue() != 0);
                    break;
                default:
                    Log.w(TAG, "dispatchToProperty: not handle this property");
                    break;
            }
        }
    }

    private void dispatchToSystem(HvacEvent event) {
        int requestValue = (int) event.getValue();
        Log.d(TAG, "dispatchToSystem: fanning out " + event + " to " + systemListenerList.size() + " listener(s)");
        for (SystemListener systemListener : systemListenerList) {
            switch (event.getId()) {
                case HvacProperties.PROP_VEHICLE_STATE:
                    systemListener.onVehicleStateChange(requestValue);
                    currentVehicleStates = requestValue;
                    break;
                case HvacProperties.PROP_TEMP_OUTSIDE:
                    systemListener.onTempOutsideChanged(requestValue);
                    break;
                default:
                    Log.w(TAG, "dispatchToSystem: not handle this property");
                    break;
            }
        }
    }
}
