#pragma once

#include <atomic>
#include <mutex>
#include <thread>
#include <unordered_map>

#include "IHvacBackend.h"

namespace vps {

// The Stage-1-era in-memory store plus simulated outside-temperature drift, extracted out of
// HvacHandler as part of Stage 3 (see kpit/docs/03-implementation-status.md) so HvacHandler can
// work against any IHvacBackend instead of only this one. Behavior is unchanged from before the
// extraction: same seeded defaults, same 5s drift tick, same "setValue() echoes immediately"
// contract -- just reachable through IHvacBackend instead of being welded into HvacHandler.
class FakeHvacBackend : public IHvacBackend {
public:
    FakeHvacBackend();
    ~FakeHvacBackend() override;

    bool getValue(int32_t propId, int32_t areaId, float* outValue) const override;
    bool setValue(int32_t propId, int32_t areaId, float value) override;
    void setChangeCallback(BackendChangeCallback callback) override;

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
    void simulationLoop();
    void fireChangeCallback(int32_t propId, int32_t areaId, float value);

    mutable std::mutex mMutex;
    std::unordered_map<Key, float, KeyHash> mStore;
    BackendChangeCallback mChangeCallback;

    std::thread mSimThread;
    std::atomic<bool> mRunning{false};
};

}  // namespace vps
