#include "VpsServiceImpl.h"

#include <mutex>

#include <log/log.h>

#include "HvacHandler.h"
#include "VpsDispatcher.h"

namespace vps {
namespace service {

using aidl::vendor::kpit::vps::IVpsCallback;

namespace {

// Same registration base_comfort_vhal_jni.cpp's ensureHandlersRegistered() used to do in-process
// before Stage 4 -- add new domain handlers here as they're implemented (SeatHandler, ...).
std::once_flag gHandlersRegisteredOnce;
void ensureHandlersRegistered() {
    std::call_once(gHandlersRegisteredOnce, [] {
        vps::VpsDispatcher::instance().registerHandler(std::make_shared<vps::HvacHandler>());
    });
}

}  // namespace

VpsServiceImpl::VpsServiceImpl() {
    ensureHandlersRegistered();
    mDeathRecipient = ndk::ScopedAIBinder_DeathRecipient(
            AIBinder_DeathRecipient_new(&VpsServiceImpl::onCallbackDied));
}

void VpsServiceImpl::onCallbackDied(void* cookie) {
    auto* self = static_cast<VpsServiceImpl*>(cookie);
    ALOGW("VpsServiceImpl: subscriber callback died, dropping active subscription bookkeeping");
    self->mActiveCallback = nullptr;
}

ndk::ScopedAStatus VpsServiceImpl::getIntProperty(int32_t propId, int32_t areaId,
                                                   std::vector<int32_t>* value,
                                                   bool* _aidl_return) {
    int32_t outValue = 0;
    *_aidl_return = vps::VpsDispatcher::instance().getIntProperty(propId, areaId, &outValue);
    value->assign(1, outValue);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus VpsServiceImpl::setIntProperty(int32_t propId, int32_t areaId, int32_t value,
                                                   bool* _aidl_return) {
    *_aidl_return = vps::VpsDispatcher::instance().setIntProperty(propId, areaId, value);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus VpsServiceImpl::getFloatProperty(int32_t propId, int32_t areaId,
                                                     std::vector<float>* value,
                                                     bool* _aidl_return) {
    float outValue = 0.0f;
    *_aidl_return = vps::VpsDispatcher::instance().getFloatProperty(propId, areaId, &outValue);
    value->assign(1, outValue);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus VpsServiceImpl::setFloatProperty(int32_t propId, int32_t areaId, float value,
                                                     bool* _aidl_return) {
    *_aidl_return = vps::VpsDispatcher::instance().setFloatProperty(propId, areaId, value);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus VpsServiceImpl::getBoolProperty(int32_t propId, int32_t areaId,
                                                    std::vector<bool>* value,
                                                    bool* _aidl_return) {
    bool outValue = false;
    *_aidl_return = vps::VpsDispatcher::instance().getBoolProperty(propId, areaId, &outValue);
    value->assign(1, outValue);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus VpsServiceImpl::setBoolProperty(int32_t propId, int32_t areaId, bool value,
                                                    bool* _aidl_return) {
    *_aidl_return = vps::VpsDispatcher::instance().setBoolProperty(propId, areaId, value);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus VpsServiceImpl::subscribe(int32_t propId, int32_t areaId, float sampleRateHz,
                                              const std::shared_ptr<IVpsCallback>& callback,
                                              bool* _aidl_return) {
    if (mActiveCallback != nullptr) {
        AIBinder_unlinkToDeath(mActiveCallback->asBinder().get(), mDeathRecipient.get(), this);
    }
    mActiveCallback = callback;
    if (callback != nullptr) {
        AIBinder_linkToDeath(callback->asBinder().get(), mDeathRecipient.get(), this);
    }

    *_aidl_return = vps::VpsDispatcher::instance().subscribe(
            propId, areaId, sampleRateHz, [callback](int32_t p, int32_t a) {
                callback->onPropertyEvent(p, a);
            });
    ALOGD("VpsServiceImpl::subscribe: propId=%d areaId=%d sampleRateHz=%f subscribed=%d", propId,
          areaId, sampleRateHz, *_aidl_return);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus VpsServiceImpl::unsubscribe(int32_t propId) {
    ALOGD("VpsServiceImpl::unsubscribe: propId=%d", propId);
    vps::VpsDispatcher::instance().unsubscribe(propId);
    return ndk::ScopedAStatus::ok();
}

}  // namespace service
}  // namespace vps
