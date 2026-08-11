#pragma once

#include <cstdint>
#include <functional>

namespace vps {

// Tagged value carried across the VpsDispatcher <-> IVpsHandler boundary. Mirrors the three
// native get/set families AllianceCarBaseService.java declares (nativeGet/SetIntProperty,
// nativeGet/SetFloatProperty, nativeGet/SetBoolProperty) so a handler never has to guess which
// one the JNI caller used.
struct VpsPropValue {
    enum class Type { INT32, FLOAT, BOOL };

    Type type = Type::FLOAT;
    int32_t int32Value = 0;
    float floatValue = 0.0f;
    bool boolValue = false;

    static VpsPropValue ofInt32(int32_t v) {
        VpsPropValue p;
        p.type = Type::INT32;
        p.int32Value = v;
        return p;
    }
    static VpsPropValue ofFloat(float v) {
        VpsPropValue p;
        p.type = Type::FLOAT;
        p.floatValue = v;
        return p;
    }
    static VpsPropValue ofBool(bool v) {
        VpsPropValue p;
        p.type = Type::BOOL;
        p.boolValue = v;
        return p;
    }

    float asFloat() const {
        switch (type) {
            case Type::INT32:
                return static_cast<float>(int32Value);
            case Type::BOOL:
                return boolValue ? 1.0f : 0.0f;
            case Type::FLOAT:
            default:
                return floatValue;
        }
    }
    int32_t asInt32() const {
        switch (type) {
            case Type::FLOAT:
                return static_cast<int32_t>(floatValue);
            case Type::BOOL:
                return boolValue ? 1 : 0;
            case Type::INT32:
            default:
                return int32Value;
        }
    }
    bool asBool() const {
        switch (type) {
            case Type::FLOAT:
                return floatValue != 0.0f;
            case Type::INT32:
                return int32Value != 0;
            case Type::BOOL:
            default:
                return boolValue;
        }
    }
};

// Fired by a handler whenever a subscribed property changes out from under the Java layer --
// e.g. an ECU push, or (for now) a simulated sensor tick. VpsDispatcher supplies one callback per
// subscribe() call, bound to the AllianceCarBaseService instance that asked for it.
using VpsEventCallback = std::function<void(int32_t propId, int32_t areaId)>;

// One IVpsHandler per Comfort domain (HvacHandler, future SeatHandler, ...). Per instruction.md
// section I.3, VpsDispatcher owns the polymorphic routing and never knows HVAC/Seat semantics
// itself -- only which propId range each registered handler claims.
class IVpsHandler {
public:
    virtual ~IVpsHandler() = default;

    // True if this handler owns propId (independent of areaId).
    virtual bool supportsProperty(int32_t propId) const = 0;

    virtual bool getProperty(int32_t propId, int32_t areaId, VpsPropValue* outValue) const = 0;
    virtual bool setProperty(int32_t propId, int32_t areaId, const VpsPropValue& value) = 0;

    // Starts pushing async property-change events for propId/areaId through callback, until a
    // matching unsubscribe(propId). sampleRateHz is advisory (only meaningful to handlers that
    // run a periodic simulation/poll loop, e.g. an outside-temperature sensor).
    virtual bool subscribe(int32_t propId, int32_t areaId, float sampleRateHz,
                            VpsEventCallback callback) = 0;
    virtual void unsubscribe(int32_t propId) = 0;
};

}  // namespace vps
