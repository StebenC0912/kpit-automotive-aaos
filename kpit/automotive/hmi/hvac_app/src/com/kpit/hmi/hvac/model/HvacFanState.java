package com.kpit.hmi.hvac.model;

public class HvacFanState {
    private final int fanSpeed;

    public HvacFanState(int fanSpeed) {
        this.fanSpeed = fanSpeed;
    }

    public int getFanSpeed() {
        return fanSpeed;
    }
}
