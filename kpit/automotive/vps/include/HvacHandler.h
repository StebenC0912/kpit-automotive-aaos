#pragma once

#include <atomic>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <unordered_set>

#include "IVpsHandler.h"

namespace vps {

// Simulated ECU/hardware backing for every property declared in
// com.kpit.hvac.manager.HvacProperties.java (PROP_AC_STATE .. PROP_TEMP_OUTSIDE, propId 1..12).
// Owns an in-memory property store keyed by (propId, areaId).
//
// Two things drive an async property-change notification here, mirroring what a real VHAL/ECU
// would do:
//   1. setProperty() echoes the new value back out as an event immediately -- this is how a real
//      vehicle's HAL confirms a command actually took effect, and it's the only way HvacService
//      (which only reacts to onVehiclePropertyChanged, never to its own setVehicleProperty
//      return value) learns a set succeeded.
//   2. A background thread drifts PROP_TEMP_OUTSIDE the way a real outside-air-temperature sensor
//      would, independent of anything the HMI does.
class HvacHandler : public IVpsHandler {
public:
    HvacHandler();
    ~HvacHandler() override;

    bool supportsProperty(int32_t propId) const override;
    bool getProperty(int32_t propId, int32_t areaId, VpsPropValue* outValue) const override;
    bool setProperty(int32_t propId, int32_t areaId, const VpsPropValue& value) override;
    bool subscribe(int32_t propId, int32_t areaId, float sampleRateHz,
                    VpsEventCallback callback) override;
    void unsubscribe(int32_t propId) override;

private:
    struct Key {
        int32_t propId;
        int32_t areaId;
        bool operator==(const Key& other) const {
            return propId == other.propId && areaId == other.areaId;
        }
    };
    struct KeyHash {
        size_t operator()(const Key& k) const {
            return static_cast<size_t>(
                (static_cast<uint64_t>(static_cast<uint32_t>(k.propId)) << 32) ^
                static_cast<uint64_t>(static_cast<uint32_t>(k.areaId)));
        }
    };

    void seedDefaults();
    void notify(int32_t propId, int32_t areaId);
    void simulationLoop();

    mutable std::mutex mMutex;
    std::unordered_map<Key, float, KeyHash> mStore;
    std::unordered_set<Key, KeyHash> mSubscribedKeys;
    VpsEventCallback mCallback;

    std::thread mSimThread;
    std::atomic<bool> mRunning{false};
};

}  // namespace vps
