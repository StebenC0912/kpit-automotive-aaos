package com.kpit.hvac.manager;

public interface IHvacController {
    void registerSystemListener(SystemListener listener);
    void registerPropertyListener(HvacListener listener);
    void unregisterAll();
    void setAcState(boolean value);
    void setMaxState(boolean value);
    void setCycleState(boolean value);
    void setFanSpeed(int value);
    void setTemp(int area, float value);
    void setSync(boolean value);
    void setAuto(boolean value);
    void setHeatingSeat(int area, boolean value);
    void setVentilationMode(int value);
    void setDefrost(boolean value);
}
