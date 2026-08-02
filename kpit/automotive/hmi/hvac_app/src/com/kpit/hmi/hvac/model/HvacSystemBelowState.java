package com.kpit.hmi.hvac.model;

public class HvacSystemBelowState {
    private final boolean isAutoActivate;
    private final boolean isHeatingLeftActive;
    private final boolean isVentilationFootActive;
    private final boolean isVentilationFootFaceActive;
    private final boolean isVentilationFaceActive;
    private final boolean isDefrostActive;
    private final boolean isHeatingRightActive;

    public HvacSystemBelowState(boolean isAutoActivate, boolean isHeatingLeftActive, boolean isVentilationFootActive, boolean isVentilationFootFaceActive, boolean isVentilationFaceActive, boolean isDefrostActive, boolean isHeatingRightActive) {
        this.isAutoActivate = isAutoActivate;
        this.isHeatingLeftActive = isHeatingLeftActive;
        this.isVentilationFootActive = isVentilationFootActive;
        this.isVentilationFootFaceActive = isVentilationFootFaceActive;
        this.isVentilationFaceActive = isVentilationFaceActive;
        this.isDefrostActive = isDefrostActive;
        this.isHeatingRightActive = isHeatingRightActive;
    }

    public boolean isAutoActivate() {
        return isAutoActivate;
    }

    public boolean isHeatingLeftActive() {
        return isHeatingLeftActive;
    }

    public boolean isVentilationFootActive() {
        return isVentilationFootActive;
    }

    public boolean isVentilationFootFaceActive() {
        return isVentilationFootFaceActive;
    }

    public boolean isVentilationFaceActive() {
        return isVentilationFaceActive;
    }

    public boolean isDefrostActive() {
        return isDefrostActive;
    }

    public boolean isHeatingRightActive() {
        return isHeatingRightActive;
    }
}
