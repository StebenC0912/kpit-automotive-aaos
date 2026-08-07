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
    ALOGD("registerHandler: now have %zu handler(s)", mHandlers.size());
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
    ALOGD("getIntProperty: propId=%d areaId=%d value=%d", propId, areaId, *outValue);
    return true;
}

bool VpsDispatcher::setIntProperty(int32_t propId, int32_t areaId, int32_t value) {
    IVpsHandler* handler = findHandler(propId);
    if (handler == nullptr) {
        ALOGW("setIntProperty: no handler registered for propId=%d", propId);
        return false;
    }
    ALOGD("setIntProperty: propId=%d areaId=%d value=%d", propId, areaId, value);
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
    ALOGD("getFloatProperty: propId=%d areaId=%d value=%f", propId, areaId, *outValue);
    return true;
}

bool VpsDispatcher::setFloatProperty(int32_t propId, int32_t areaId, float value) {
    IVpsHandler* handler = findHandler(propId);
    if (handler == nullptr) {
        ALOGW("setFloatProperty: no handler registered for propId=%d", propId);
        return false;
    }
    ALOGD("setFloatProperty: propId=%d areaId=%d value=%f", propId, areaId, value);
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
    ALOGD("getBoolProperty: propId=%d areaId=%d value=%d", propId, areaId, *outValue);
    return true;
}

bool VpsDispatcher::setBoolProperty(int32_t propId, int32_t areaId, bool value) {
    IVpsHandler* handler = findHandler(propId);
    if (handler == nullptr) {
        ALOGW("setBoolProperty: no handler registered for propId=%d", propId);
        return false;
    }
    ALOGD("setBoolProperty: propId=%d areaId=%d value=%d", propId, areaId, value);
    return handler->setProperty(propId, areaId, VpsPropValue::ofBool(value));
}

bool VpsDispatcher::subscribe(int32_t propId, int32_t areaId, float sampleRateHz,
                               VpsEventCallback callback) {
    IVpsHandler* handler = findHandler(propId);
    if (handler == nullptr) {
        ALOGW("subscribe: no handler registered for propId=%d", propId);
        return false;
    }
    ALOGD("subscribe: propId=%d areaId=%d sampleRateHz=%f", propId, areaId, sampleRateHz);
    return handler->subscribe(propId, areaId, sampleRateHz, std::move(callback));
}

void VpsDispatcher::unsubscribe(int32_t propId) {
    IVpsHandler* handler = findHandler(propId);
    if (handler != nullptr) {
        ALOGD("unsubscribe: propId=%d", propId);
        handler->unsubscribe(propId);
    }
}

}  // namespace vps
