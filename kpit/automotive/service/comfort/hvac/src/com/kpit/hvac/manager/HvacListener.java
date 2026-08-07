package com.kpit.hvac.manager;

public interface HvacListener {
    void onFanSpeedChanged(int speed);

    void onACStateChanged(boolean value);

    void onMaxStateChanged(boolean value);

    void onAirRecycleStateChanged(boolean value);

    void onTempChanged(float value, int area);

    void onSyncStateChanged(boolean value);

    void onHeatingSeatChanged(boolean value, int area);

    void onVentilationModeChanged(int value);

    void onAutoStateChanged(boolean value);

    void onDefrostStateChanged(boolean value);
}
