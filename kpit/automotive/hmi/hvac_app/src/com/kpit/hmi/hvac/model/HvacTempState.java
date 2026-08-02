package com.kpit.hmi.hvac.model;

public class HvacTempState {
    private final float rightZoneTemp;
    private final float leftZoneTemp;
    private final boolean isSyncOn;

    public HvacTempState(float rightZoneTemp, float leftZoneTemp, boolean isSyncOn) {
        this.rightZoneTemp = rightZoneTemp;
        this.leftZoneTemp = leftZoneTemp;
        this.isSyncOn = isSyncOn;
    }

    public float getRightZoneTemp() {
        return rightZoneTemp;
    }

    public float getLeftZoneTemp() {
        return leftZoneTemp;
    }

    public boolean isSyncOn() {
        return isSyncOn;
    }
}
