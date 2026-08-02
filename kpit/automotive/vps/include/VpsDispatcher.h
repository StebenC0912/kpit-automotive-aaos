#pragma once

#include <memory>
#include <mutex>
#include <vector>

#include "IVpsHandler.h"

namespace vps {

// Component 3's single entry point from JNI (base_comfort_vhal_jni.cpp calls straight into this,
// in-process -- no Binder/HAL registration involved, see instruction.md section II steps 5-6).
// Routes every get/set/subscribe call by propId to whichever registered IVpsHandler claims it.
class VpsDispatcher {
public:
    static VpsDispatcher& instance();

    void registerHandler(std::shared_ptr<IVpsHandler> handler);

    bool getIntProperty(int32_t propId, int32_t areaId, int32_t* outValue) const;
    bool setIntProperty(int32_t propId, int32_t areaId, int32_t value);
    bool getFloatProperty(int32_t propId, int32_t areaId, float* outValue) const;
    bool setFloatProperty(int32_t propId, int32_t areaId, float value);
    bool getBoolProperty(int32_t propId, int32_t areaId, bool* outValue) const;
    bool setBoolProperty(int32_t propId, int32_t areaId, bool value);

    bool subscribe(int32_t propId, int32_t areaId, float sampleRateHz, VpsEventCallback callback);
    void unsubscribe(int32_t propId);

    VpsDispatcher(const VpsDispatcher&) = delete;
    VpsDispatcher& operator=(const VpsDispatcher&) = delete;

private:
    VpsDispatcher() = default;

    IVpsHandler* findHandler(int32_t propId) const;

    mutable std::mutex mMutex;
    std::vector<std::shared_ptr<IVpsHandler>> mHandlers;
};

}  // namespace vps
