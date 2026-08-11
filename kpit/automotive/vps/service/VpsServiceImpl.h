#pragma once

#include <memory>
#include <vector>

#include <aidl/vendor/kpit/vps/BnVpsService.h>
#include <android/binder_auto_utils.h>

namespace vps {
namespace service {

// BnVpsService implementation for vendor.kpit.vps-service (VHAL-alignment Stage 4,
// kpit/docs/03-implementation-status.md item 13): a thin AIDL wrapper around the existing
// in-process vps::VpsDispatcher singleton (vps/include/VpsDispatcher.h) -- VpsDispatcher and
// HvacHandler themselves are untouched by Stage 4, only how base_comfort_vhal_jni.cpp reaches them
// changes (function call -> Binder call). HvacHandler is registered with VpsDispatcher once, in the
// constructor, the same std::call_once-guarded registration base_comfort_vhal_jni.cpp used to do
// in-process before this daemon existed.
class VpsServiceImpl : public aidl::vendor::kpit::vps::BnVpsService {
public:
    VpsServiceImpl();

    ndk::ScopedAStatus getIntProperty(int32_t propId, int32_t areaId, std::vector<int32_t>* value,
                                       bool* _aidl_return) override;
    ndk::ScopedAStatus setIntProperty(int32_t propId, int32_t areaId, int32_t value,
                                       bool* _aidl_return) override;
    ndk::ScopedAStatus getFloatProperty(int32_t propId, int32_t areaId, std::vector<float>* value,
                                         bool* _aidl_return) override;
    ndk::ScopedAStatus setFloatProperty(int32_t propId, int32_t areaId, float value,
                                         bool* _aidl_return) override;
    ndk::ScopedAStatus getBoolProperty(int32_t propId, int32_t areaId, std::vector<bool>* value,
                                        bool* _aidl_return) override;
    ndk::ScopedAStatus setBoolProperty(int32_t propId, int32_t areaId, bool value,
                                        bool* _aidl_return) override;

    ndk::ScopedAStatus subscribe(
            int32_t propId, int32_t areaId, float sampleRateHz,
            const std::shared_ptr<aidl::vendor::kpit::vps::IVpsCallback>& callback,
            bool* _aidl_return) override;
    ndk::ScopedAStatus unsubscribe(int32_t propId) override;

private:
    // vps::HvacHandler only ever holds one VpsEventCallback total (see HvacHandler.h) -- this
    // stays single-subscriber to match, since hvac-service is still the only client process (Seat
    // is unimplemented). mActiveCallback/mDeathRecipient exist purely so a client process dying
    // doesn't leave a stale linked binder around; a dead callback fails its next
    // onPropertyEvent() call silently (no crash), it isn't proactively unsubscribed from
    // VpsDispatcher until the next subscribe() call replaces it. Multi-client fan-out (a map keyed
    // by (propId, areaId) instead of one field) is real future work if/when Seat needs simultaneous
    // VPS access, not built speculatively here.
    std::shared_ptr<aidl::vendor::kpit::vps::IVpsCallback> mActiveCallback;
    ndk::ScopedAIBinder_DeathRecipient mDeathRecipient;

    static void onCallbackDied(void* cookie);
};

}  // namespace service
}  // namespace vps
