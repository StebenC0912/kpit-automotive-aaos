package com.kpit.vspmanager.model;

import java.time.Instant;

/** One (property, area) row as parsed from a single dumpsys hvac_service --get call. */
public final class PropertySnapshot {

    private final HvacProperty property;
    private final int area;
    private final float value;
    private final String realVhalLabel;
    private final Instant timestamp;

    public PropertySnapshot(HvacProperty property, int area, float value, String realVhalLabel,
            Instant timestamp) {
        this.property = property;
        this.area = area;
        this.value = value;
        this.realVhalLabel = realVhalLabel;
        this.timestamp = timestamp;
    }

    public HvacProperty getProperty() {
        return property;
    }

    public int getArea() {
        return area;
    }

    public float getValue() {
        return value;
    }

    public String getRealVhalLabel() {
        return realVhalLabel;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    /** Identity for a (property, area) row, independent of value/timestamp. */
    public String key() {
        return property.name() + "@" + area;
    }
}
