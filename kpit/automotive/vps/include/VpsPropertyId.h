#pragma once

#include <cstdint>

namespace vps {

// Stage 2 of moving vps/ closer to real VHAL's structure (see
// kpit/docs/03-implementation-status.md): property IDs are bit-packed the same way real AOSP
// VehiclePropertyIds are, instead of being flat sequential ints. Real VHAL packs (from
// hardware/interfaces/automotive/vehicle -- VehiclePropertyGroup/VehicleArea/VehiclePropertyType):
//
//   bits 31-28: VehiclePropertyGroup (SYSTEM/VENDOR/BACKPORTED)
//   bits 27-24: VehicleArea          (GLOBAL/WINDOW/MIRROR/SEAT/DOOR/WHEEL/VENDOR)
//   bits 23-16: VehiclePropertyType  (BOOLEAN/INT32/INT64/FLOAT/STRING/BYTES/MIXED/...)
//   bits 15-0:  a plain index, unique only within (group, area, type)
//
// We only ever need SYSTEM group and a handful of areas/types, so this mirrors that same layout
// (same bit positions, same magic numbers where AOSP defines them) without pulling in the real
// VehiclePropertyIds.aidl -- see Stage 4 in the docs for why we don't consume the real AIDL yet.
//
// One subtlety carried over faithfully from the real scheme: the "area" packed into the propId is
// the *area type* a property varies over (GLOBAL vs. SEAT), not the specific instance. Which
// concrete seat (DRIVER vs. PASSENGER) is still passed as a separate areaId parameter on every
// get/set/subscribe call, exactly as before Stage 2 -- see AREA_GLOBAL/DRIVER/PASSENGER below,
// which are unchanged by this file.

// VehiclePropertyGroup, matching AOSP bit-for-bit. We only ever use SYSTEM (VENDOR/BACKPORTED
// exist in the real enum for OEM/versioning use cases that don't apply to this in-process stub).
constexpr int32_t kPropertyGroupSystem = 0x10000000;

// VehicleArea (the *type* of area a property varies over), matching AOSP's values for the two
// area types this stub actually has properties in.
constexpr int32_t kAreaTypeGlobal = 0x01000000;
constexpr int32_t kAreaTypeSeat = 0x05000000;

// VehiclePropertyType, matching AOSP's values for the three VpsPropValue::Type variants we have.
constexpr int32_t kPropertyTypeBoolean = 0x00200000;
constexpr int32_t kPropertyTypeInt32 = 0x00400000;
constexpr int32_t kPropertyTypeFloat = 0x00600000;

constexpr int32_t makePropId(int32_t group, int32_t areaType, int32_t type, int32_t index) {
    return group | areaType | type | index;
}

// Mirrors com.kpit.hvac.manager.HvacProperties.java exactly -- this is a Java/C++ boundary with no
// shared code-gen, so keep the two in sync by hand if either ever changes. The low-16-bit index
// (1..12) is the same one the old flat scheme used, just now packed alongside group/area/type
// instead of standing alone.
constexpr int32_t PROP_AC_STATE =
        makePropId(kPropertyGroupSystem, kAreaTypeGlobal, kPropertyTypeBoolean, 1);
constexpr int32_t PROP_MAX_STATE =
        makePropId(kPropertyGroupSystem, kAreaTypeGlobal, kPropertyTypeBoolean, 2);
constexpr int32_t PROP_RECYCLE_STATE =
        makePropId(kPropertyGroupSystem, kAreaTypeGlobal, kPropertyTypeBoolean, 3);
constexpr int32_t PROP_FAN_SPEED =
        makePropId(kPropertyGroupSystem, kAreaTypeGlobal, kPropertyTypeInt32, 4);
// Varies per seat (DRIVER/PASSENGER), so it's packed with kAreaTypeSeat, not kAreaTypeGlobal.
constexpr int32_t PROP_TEMP =
        makePropId(kPropertyGroupSystem, kAreaTypeSeat, kPropertyTypeFloat, 5);
constexpr int32_t PROP_SYNC =
        makePropId(kPropertyGroupSystem, kAreaTypeGlobal, kPropertyTypeBoolean, 6);
// Also varies per seat.
constexpr int32_t PROP_SEAT_HEATING =
        makePropId(kPropertyGroupSystem, kAreaTypeSeat, kPropertyTypeBoolean, 7);
constexpr int32_t PROP_VENTILATION_MODE =
        makePropId(kPropertyGroupSystem, kAreaTypeGlobal, kPropertyTypeInt32, 8);
constexpr int32_t PROP_AUTO_MODE =
        makePropId(kPropertyGroupSystem, kAreaTypeGlobal, kPropertyTypeBoolean, 9);
constexpr int32_t PROP_DEFROST =
        makePropId(kPropertyGroupSystem, kAreaTypeGlobal, kPropertyTypeBoolean, 10);
constexpr int32_t PROP_VEHICLE_STATE =
        makePropId(kPropertyGroupSystem, kAreaTypeGlobal, kPropertyTypeInt32, 11);
constexpr int32_t PROP_TEMP_OUTSIDE =
        makePropId(kPropertyGroupSystem, kAreaTypeGlobal, kPropertyTypeFloat, 12);

// Specific-area IDs (as opposed to the area *type* packed into the propIds above) -- unchanged
// from the pre-Stage-2 flat scheme, and passed as the separate areaId parameter on every
// get/set/subscribe call, exactly like real VHAL's VehicleAreaSeat bitmask parameter.
constexpr int32_t AREA_GLOBAL = 0;
constexpr int32_t DRIVER = 1;
constexpr int32_t PASSENGER = 2;

}  // namespace vps
