package com.kpit.hvac.manager;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.kpit.comfort.base.manager.BaseComfortManager;
import com.kpit.hvac.HvacEvent;
import com.kpit.hvac.IHVACVehicleCallback;
import com.kpit.hvac.IHVACVehicleService;

import java.util.ArrayList;
import java.util.List;

public class HvacManager extends BaseComfortManager<IHVACVehicleService> implements IHvacController {
    private static final String TAG = "HvacManager";
    private static final String SERVICE_NAME = "hvac_service";

    private static HvacManager sInstance;

    private final List<HvacListener> hvacListenerList = new ArrayList<>();
    private final List<SystemListener> systemListenerList = new ArrayList<>();
    private int currentVehicleStates = -1;

    public static synchronized HvacManager getInstance() {
        if (sInstance == null) {
            sInstance = new HvacManager();
        }
        return sInstance;
    }

    public HvacManager() {
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
            Log.d(TAG, "Register successfully");
        } catch (RemoteException e) {
            Log.e(TAG, "Cannot register callback", e);
        }
    }

    @Override
    protected void onServiceDisconnected() {
        Log.d(TAG, "Disconnect service");
    }

    @Override
    public void registerSystemListener(SystemListener systemListener) {
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
        synchronized (systemListenerList) {
            systemListenerList.clear();
        }
    }

    @Override
    public void registerPropertyListener(HvacListener hvacListener) {
        synchronized (hvacListenerList) {
            hvacListenerList.add(hvacListener);
        }
        // See registerSystemListener() -- same forced-connect reasoning, needed here too since
        // callers may register only one of the two listener types.
        getService();
    }

    public void unregisterHvacListener() {
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
        IHVACVehicleService service = getService();
        if (service == null) {
            Log.w(TAG, "setProperty: service not connected, propertyId=" + propertyId);
            return;
        }
        try {
            service.setVehicleProperty(propertyId, areaId, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Cannot set property " + propertyId, e);
        }
    }

    private final IHVACVehicleCallback.Stub mBinderCallback = new IHVACVehicleCallback.Stub() {
        @Override
        public void onChangeEvent(HvacEvent event) {
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
                    hvacListener.onTempChanged((int) event.getValue(), event.getAreaId());
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
