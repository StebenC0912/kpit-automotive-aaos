// IHVACVehicleCallback.aidl
package com.kpit.hvac;

import com.kpit.hvac.HvacEvent;
interface IHVACVehicleCallback {
    oneway void onChangeEvent(in HvacEvent event);
}