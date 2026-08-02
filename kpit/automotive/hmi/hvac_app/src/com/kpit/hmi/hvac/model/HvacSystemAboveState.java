package com.kpit.hmi.hvac.model;

public final class HvacSystemAboveState {
    private final boolean isAcEnable;
    private final boolean isACActivate;
    private final boolean isMaxActivate;
    private final boolean isRecycleActivate;

    public HvacSystemAboveState(boolean isAcEnable, boolean isACActivate, boolean isMaxActivate, boolean isRecycleActivate) {
        this.isAcEnable = isAcEnable;
        this.isACActivate = isACActivate;
        this.isMaxActivate = isMaxActivate;
        this.isRecycleActivate = isRecycleActivate;
    }

    public boolean isAcEnable() {
        return isAcEnable;
    }

    public boolean isACActivate() {
        return isACActivate;
    }

    public boolean isMaxActivate() {
        return isMaxActivate;
    }

    public boolean isRecycleActivate() {
        return isRecycleActivate;
    }
}
