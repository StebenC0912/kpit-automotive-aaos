#pragma once

#include <memory>
#include <mutex>
#include <unordered_set>
#include <vector>

#include "IHvacBackend.h"
#include "IVpsHandler.h"
#include "VpsPropConfig.h"

namespace vps {

// Validation and Java-facing subscription routing for every property declared in
// com.kpit.hvac.manager.HvacProperties.java -- everything an IVpsHandler is responsible for (see
// IVpsHandler.h). Storage and simulation are not owned here directly; they live behind an
// IHvacBackend (FakeHvacBackend by default -- see kpit/docs/03-implementation-status.md Stage 3),
// so this class works identically no matter what's actually holding the values.
//
// getProperty()/setProperty() validate every call against mConfigs (a VpsPropConfig per property
// -- type/access/supported areas/value range, see VpsPropConfig.h) before delegating the actual
// read/write to mBackend. subscribe()/unsubscribe() track which (propId, areaId) keys the Java
// layer wants events for; onBackendValueChanged() -- wired up as the backend's change callback --
// decides whether each value change the backend reports (whether self-caused by setProperty() or
// backend-originated, e.g. FakeHvacBackend's simulated outside-temperature drift) is actually
// forwarded to that Java layer. This mirrors what a real VHAL/ECU does: setProperty() only ever
// learns a command took effect by seeing it echoed back through this same path -- there's no
// separate "did my set succeed" return value AllianceCarHvacService relies on.
class HvacHandler : public IVpsHandler {
public:
    HvacHandler();
    explicit HvacHandler(std::unique_ptr<IHvacBackend> backend);
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

    void buildConfigs();
    const VpsPropConfig* findConfig(int32_t propId) const;
    void onBackendValueChanged(int32_t propId, int32_t areaId, float value);

    // Built once in the constructor, never modified afterward -- safe to read without mMutex.
    std::vector<VpsPropConfig> mConfigs;

    std::unique_ptr<IHvacBackend> mBackend;

    mutable std::mutex mMutex;
    std::unordered_set<Key, KeyHash> mSubscribedKeys;
    VpsEventCallback mCallback;
};

}  // namespace vps
