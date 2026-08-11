package com.kpit.hvac.manager;

public final class HvacProperties {
    // Property IDs are bit-packed the same way real AOSP VehiclePropertyIds are, instead of being
    // flat sequential ints -- see vps/include/VpsPropertyId.h (the native mirror of this file) for
    // the full explanation and bit layout. Kept in sync by hand across that Java/C++ boundary.
    private static final int GROUP_SYSTEM = 0x10000000;
    private static final int AREA_TYPE_GLOBAL = 0x01000000;
    private static final int AREA_TYPE_SEAT = 0x05000000; // properties that vary per DRIVER/PASSENGER
    private static final int TYPE_BOOLEAN = 0x00200000;
    private static final int TYPE_INT32 = 0x00400000;
    private static final int TYPE_FLOAT = 0x00600000;

    // prop id
    public static final int PROP_AC_STATE = GROUP_SYSTEM | AREA_TYPE_GLOBAL | TYPE_BOOLEAN | 1;
    public static final int PROP_MAX_STATE = GROUP_SYSTEM | AREA_TYPE_GLOBAL | TYPE_BOOLEAN | 2;
    public static final int PROP_RECYCLE_STATE = GROUP_SYSTEM | AREA_TYPE_GLOBAL | TYPE_BOOLEAN | 3;
    public static final int PROP_FAN_SPEED = GROUP_SYSTEM | AREA_TYPE_GLOBAL | TYPE_INT32 | 4;
    public static final int PROP_TEMP = GROUP_SYSTEM | AREA_TYPE_SEAT | TYPE_FLOAT | 5;
    public static final int PROP_SYNC = GROUP_SYSTEM | AREA_TYPE_GLOBAL | TYPE_BOOLEAN | 6;
    public static final int PROP_SEAT_HEATING = GROUP_SYSTEM | AREA_TYPE_SEAT | TYPE_BOOLEAN | 7;
    public static final int PROP_VENTILATION_MODE = GROUP_SYSTEM | AREA_TYPE_GLOBAL | TYPE_INT32 | 8;
    public static final int PROP_AUTO_MODE = GROUP_SYSTEM | AREA_TYPE_GLOBAL | TYPE_BOOLEAN | 9;
    public static final int PROP_DEFROST = GROUP_SYSTEM | AREA_TYPE_GLOBAL | TYPE_BOOLEAN | 10;
    public static final int PROP_VEHICLE_STATE = GROUP_SYSTEM | AREA_TYPE_GLOBAL | TYPE_INT32 | 11;
    public static final int PROP_TEMP_OUTSIDE = GROUP_SYSTEM | AREA_TYPE_GLOBAL | TYPE_FLOAT | 12;

    // areaId
    public static final int AREA_GLOBAL = 0;
    public static final int DRIVER = 1;
    public static final int PASSENGER = 2;

    // value
    public static final int ENABLE = 101;
    public static final int DISABLE = 102;
}
