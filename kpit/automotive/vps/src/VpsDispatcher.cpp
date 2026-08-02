#include "VpsDispatcher.h"

#define LOG_TAG "VpsDispatcher"
#include <log/log.h>

namespace vps {

VpsDispatcher& VpsDispatcher::instance() {
    static VpsDispatcher dispatcher;
    return dispatcher;
}

void VpsDispatcher::registerHandler(std::shared_ptr<IVpsHandler> handler) {
    std::lock_guard<std::mutex> lock(mMutex);
    mHandlers.push_back(std::move(handler));
}

IVpsHandler* VpsDispatcher::findHandler(int32_t propId) const {
    std::lock_guard<std::mutex> lock(mMutex);
    for (const auto& handler : mHandlers) {
        if (handler->supportsProperty(propId)) {
            return handler.get();
        }
    }
    return nullptr;
}

bool VpsDispatcher::getIntProperty(int32_t propId, int32_t areaId, int32_t* outValue) const {
    IVpsHandler* handler = findHandler(propId);
    if (handler == nullptr) {
        ALOGW("getIntProperty: no handler registered for propId=%d", propId);
        return false;
    }
    VpsPropValue value;
    if (!handler->getProperty(propId, areaId, &value)) {
        return false;
    }
    *outValue = value.asInt32();
    return true;
}

bool VpsDispatcher::setIntProperty(int32_t propId, int32_t areaId, int32_t value) {
    IVpsHandler* handler = findHandler(propId);
    if (handler == nullptr) {
        ALOGW("setIntProperty: no handler registered for propId=%d", propId);
        return false;
    }
    return handler->setProperty(propId, areaId, VpsPropValue::ofInt32(value));
}

bool VpsDispatcher::getFloatProperty(int32_t propId, int32_t areaId, float* outValue) const {
    IVpsHandler* handler = findHandler(propId);
    if (handler == nullptr) {
        ALOGW("getFloatProperty: no handler registered for propId=%d", propId);
        return false;
    }
    VpsPropValue value;
    if (!handler->getProperty(propId, areaId, &value)) {
        return false;
    }
    *outValue = value.asFloat();
    return true;
}

bool VpsDispatcher::setFloatProperty(int32_t propId, int32_t areaId, float value) {
    IVpsHandler* handler = findHandler(propId);
    if (handler == nullptr) {
        ALOGW("setFloatProperty: no handler registered for propId=%d", propId);
        return false;
    }
    return handler->setProperty(propId, areaId, VpsPropValue::ofFloat(value));
}

bool VpsDispatcher::getBoolProperty(int32_t propId, int32_t areaId, bool* outValue) const {
    IVpsHandler* handler = findHandler(propId);
    if (handler == nullptr) {
        ALOGW("getBoolProperty: no handler registered for propId=%d", propId);
        return false;
    }
    VpsPropValue value;
    if (!handler->getProperty(propId, areaId, &value)) {
        return false;
    }
    *outValue = value.asBool();
    return true;
}

bool VpsDispatcher::setBoolProperty(int32_t propId, int32_t areaId, bool value) {
    IVpsHandler* handler = findHandler(propId);
    if (handler == nullptr) {
        ALOGW("setBoolProperty: no handler registered for propId=%d", propId);
        return false;
    }
    return handler->setProperty(propId, areaId, VpsPropValue::ofBool(value));
}

bool VpsDispatcher::subscribe(int32_t propId, int32_t areaId, float sampleRateHz,
                               VpsEventCallback callback) {
    IVpsHandler* handler = findHandler(propId);
    if (handler == nullptr) {
        ALOGW("subscribe: no handler registered for propId=%d", propId);
        return false;
    }
    return handler->subscribe(propId, areaId, sampleRateHz, std::move(callback));
}

void VpsDispatcher::unsubscribe(int32_t propId) {
    IVpsHandler* handler = findHandler(propId);
    if (handler != nullptr) {
        handler->unsubscribe(propId);
    }
}

}  // namespace vps
