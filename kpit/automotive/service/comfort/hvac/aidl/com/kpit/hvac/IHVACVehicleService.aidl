// IHVACVehicleService.aidl
package com.kpit.hvac;

// Declare any non-default types here with import statements
import com.kpit.hvac.IHVACVehicleCallback;
interface IHVACVehicleService {
    void setVehicleProperty(int id, int areaId, float value);
    void registerCallback(IHVACVehicleCallback callback);
    void unregisterCallback(IHVACVehicleCallback callback);
}