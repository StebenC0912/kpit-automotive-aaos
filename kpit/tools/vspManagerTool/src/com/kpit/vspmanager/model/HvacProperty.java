package com.kpit.vspmanager.model;

/**
 * Mirrors the 12 PROP_* constants and 14-row (property x area) surface that
 * AllianceCarHvacService.dump() emits, in the same order as its GLOBAL_PROPS/PER_SEAT_PROPS
 * arrays (service/comfort/hvac/src/com/kpit/hvac/service/AllianceCarHvacService.java).
 */
public enum HvacProperty {
    PROP_AC_STATE(0x11200001, ValueType.BOOLEAN, 0),
    PROP_MAX_STATE(0x11200002, ValueType.BOOLEAN, 0),
    PROP_RECYCLE_STATE(0x11200003, ValueType.BOOLEAN, 0),
    PROP_FAN_SPEED(0x11400004, ValueType.INT, 0),
    PROP_SYNC(0x11200006, ValueType.BOOLEAN, 0),
    PROP_AUTO_MODE(0x11200009, ValueType.BOOLEAN, 0),
    PROP_DEFROST(0x1120000A, ValueType.BOOLEAN, 0),
    PROP_VENTILATION_MODE(0x11400008, ValueType.INT, 0),
    PROP_VEHICLE_STATE(0x1140000B, ValueType.INT, 0),
    PROP_TEMP_OUTSIDE(0x1160000C, ValueType.FLOAT, 0),
    PROP_TEMP(0x15600005, ValueType.FLOAT, 1, 2),
    PROP_SEAT_HEATING(0x15200007, ValueType.BOOLEAN, 1, 2);

    public enum ValueType { BOOLEAN, INT, FLOAT }

    public final int id;
    public final ValueType type;
    public final int[] areas;

    HvacProperty(int id, ValueType type, int... areas) {
        this.id = id;
        this.type = type;
        this.areas = areas;
    }

    /** Matches a dump() line's leading token exactly (case-sensitive), or returns null. */
    public static HvacProperty byName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
