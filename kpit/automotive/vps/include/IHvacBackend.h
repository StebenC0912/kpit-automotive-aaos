#pragma once

#include <cstdint>
#include <functional>

namespace vps {

// Reported by an IHvacBackend whenever propId/areaId's value changes, whether that change was
// just requested via setValue() or the backend originated it itself (e.g. FakeHvacBackend's
// simulated outside-temperature drift, or eventually a real ECU push). HvacHandler is the only
// listener -- it decides whether anything upstream (the Java layer) actually asked to hear about
// this propId/areaId before forwarding the change any further; see HvacHandler::onBackendValueChanged.
using BackendChangeCallback = std::function<void(int32_t propId, int32_t areaId, float value)>;

// Pure storage/IO for HVAC property values -- no validation (HvacHandler's VpsPropConfig already
// covers that, see VpsPropConfig.h) and no subscription bookkeeping (also HvacHandler's job, since
// which propIds the Java layer cares about is a routing concern independent of where the value
// actually lives). Stage 3 of moving vps/ closer to real VHAL's structure (see
// kpit/docs/03-implementation-status.md): this is the seam that lets HvacHandler work identically
// whether it's backed by FakeHvacBackend's in-memory simulation or a future RealCanHvacBackend.
class IHvacBackend {
public:
    virtual ~IHvacBackend() = default;

    virtual bool getValue(int32_t propId, int32_t areaId, float* outValue) const = 0;
    virtual bool setValue(int32_t propId, int32_t areaId, float value) = 0;

    // Called exactly once, right after construction, before any getValue()/setValue() call --
    // every subsequent value change (self-caused or backend-originated) must be reported through
    // this callback, or HvacHandler's subscribers will never hear about it.
    virtual void setChangeCallback(BackendChangeCallback callback) = 0;
};

}  // namespace vps
