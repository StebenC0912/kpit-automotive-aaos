package com.kpit.hvac.manager;

public interface SystemListener {
    void onVehicleStateChange(int value);
    void onTempOutsideChanged(int value);
}
