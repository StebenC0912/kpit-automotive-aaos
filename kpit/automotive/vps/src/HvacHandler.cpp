#include "HvacHandler.h"

#include "FakeHvacBackend.h"
#include "VpsPropertyId.h"

#define LOG_TAG "HvacHandler"
#include <log/log.h>

namespace vps {

HvacHandler::HvacHandler() : HvacHandler(std::make_unique<FakeHvacBackend>()) {}

HvacHandler::HvacHandler(std::unique_ptr<IHvacBackend> backend) : mBackend(std::move(backend)) {
    ALOGD("HvacHandler: constructing, building configs and wiring backend change callback");
    buildConfigs();
    mBackend->setChangeCallback([this](int32_t propId, int32_t areaId, float value) {
        onBackendValueChanged(propId, areaId, value);
    });
}

HvacHandler::~HvacHandler() {
    ALOGD("HvacHandler: destructing, releasing backend");
    // Explicit reset (rather than leaving it to implicit member teardown) guarantees the backend
    // -- and any simulation thread it owns, e.g. FakeHvacBackend's -- is fully stopped before this
    // destructor returns, so onBackendValueChanged() can never fire against a partially-destroyed
    // *this.
    mBackend.reset();
}

// One entry per property this handler owns, modeled on real VHAL's VehiclePropConfig. Access is
// READ_WRITE across the board: even PROP_VEHICLE_STATE and PROP_TEMP_OUTSIDE, which a real
// vehicle would expose READ-only (they're system/sensor signals, not HMI commands), stay
// READ_WRITE here because docs/11-testing-hvac.md's documented test flow depends on setting both
// via adb (unlocking the panel, injecting an outside-temp value) -- enforcing READ would break
// that without being asked to. Everything else (type, supported areas, value range) is enforced.
void HvacHandler::buildConfigs() {
    using Access = VpsPropConfig::Access;
    using ChangeMode = VpsPropConfig::ChangeMode;
    using Type = VpsPropValue::Type;

    auto boolGlobal = [](int32_t id) {
        VpsPropConfig c;
        c.propId = id;
        c.type = Type::BOOL;
        c.access = Access::READ_WRITE;
        c.changeMode = ChangeMode::ON_CHANGE;
        c.supportedAreas = {AREA_GLOBAL};
        return c;
    };

    mConfigs.push_back(boolGlobal(PROP_AC_STATE));
    mConfigs.push_back(boolGlobal(PROP_MAX_STATE));
    mConfigs.push_back(boolGlobal(PROP_RECYCLE_STATE));

    VpsPropConfig fanSpeed;
    fanSpeed.propId = PROP_FAN_SPEED;
    fanSpeed.type = Type::INT32;
    fanSpeed.access = Access::READ_WRITE;
    fanSpeed.changeMode = ChangeMode::ON_CHANGE;
    fanSpeed.supportedAreas = {AREA_GLOBAL};
    fanSpeed.minValue = 0.0f;
    fanSpeed.maxValue = 12.0f;  // matches HvacViewModel's increaseFanSpeed/decrementFanSpeed clamp
    mConfigs.push_back(fanSpeed);

    mConfigs.push_back(boolGlobal(PROP_SYNC));
    mConfigs.push_back(boolGlobal(PROP_AUTO_MODE));
    mConfigs.push_back(boolGlobal(PROP_DEFROST));

    VpsPropConfig ventMode;
    ventMode.propId = PROP_VENTILATION_MODE;
    ventMode.type = Type::INT32;
    ventMode.access = Access::READ_WRITE;
    ventMode.changeMode = ChangeMode::ON_CHANGE;
    ventMode.supportedAreas = {AREA_GLOBAL};
    ventMode.minValue = 1.0f;
    ventMode.maxValue = 3.0f;  // 1=foot, 2=foot+face, 3=face
    mConfigs.push_back(ventMode);

    VpsPropConfig vehicleState;
    vehicleState.propId = PROP_VEHICLE_STATE;
    vehicleState.type = Type::INT32;
    vehicleState.access = Access::READ_WRITE;
    vehicleState.changeMode = ChangeMode::ON_CHANGE;
    vehicleState.supportedAreas = {AREA_GLOBAL};
    mConfigs.push_back(vehicleState);

    VpsPropConfig tempOutside;
    tempOutside.propId = PROP_TEMP_OUTSIDE;
    tempOutside.type = Type::FLOAT;
    tempOutside.access = Access::READ_WRITE;
    tempOutside.changeMode = ChangeMode::CONTINUOUS;  // drifts continuously via the backend
    tempOutside.supportedAreas = {AREA_GLOBAL};
    tempOutside.minValue = -40.0f;
    tempOutside.maxValue = 60.0f;
    mConfigs.push_back(tempOutside);

    VpsPropConfig temp;
    temp.propId = PROP_TEMP;
    temp.type = Type::FLOAT;
    temp.access = Access::READ_WRITE;
    temp.changeMode = ChangeMode::ON_CHANGE;
    temp.supportedAreas = {DRIVER, PASSENGER};
    temp.minValue = 16.0f;
    temp.maxValue = 30.0f;
    mConfigs.push_back(temp);

    VpsPropConfig seatHeating;
    seatHeating.propId = PROP_SEAT_HEATING;
    seatHeating.type = Type::BOOL;
    seatHeating.access = Access::READ_WRITE;
    seatHeating.changeMode = ChangeMode::ON_CHANGE;
    seatHeating.supportedAreas = {DRIVER, PASSENGER};
    mConfigs.push_back(seatHeating);
}

const VpsPropConfig* HvacHandler::findConfig(int32_t propId) const {
    for (const auto& config : mConfigs) {
        if (config.propId == propId) {
            return &config;
        }
    }
    return nullptr;
}

bool HvacHandler::supportsProperty(int32_t propId) const {
    return findConfig(propId) != nullptr;
}

bool HvacHandler::getProperty(int32_t propId, int32_t areaId, VpsPropValue* outValue) const {
    const VpsPropConfig* config = findConfig(propId);
    if (config == nullptr) {
        ALOGW("getProperty: no config for propId=%d (not one of ours)", propId);
        return false;
    }
    if (!config->isReadable()) {
        ALOGW("getProperty: propId=%d is write-only, rejecting read", propId);
        return false;
    }
    if (!config->supportsArea(areaId)) {
        ALOGW("getProperty: propId=%d does not support areaId=%d", propId, areaId);
        return false;
    }

    float value = 0.0f;
    if (!mBackend->getValue(propId, areaId, &value)) {
        return false;
    }
    *outValue = VpsPropValue::ofFloat(value);
    return true;
}

bool HvacHandler::setProperty(int32_t propId, int32_t areaId, const VpsPropValue& value) {
    const VpsPropConfig* config = findConfig(propId);
    if (config == nullptr) {
        ALOGW("setProperty: no config for propId=%d (not one of ours)", propId);
        return false;
    }
    if (!config->isWritable()) {
        ALOGW("setProperty: propId=%d is read-only, rejecting write", propId);
        return false;
    }
    if (!config->supportsArea(areaId)) {
        ALOGW("setProperty: propId=%d does not support areaId=%d", propId, areaId);
        return false;
    }
    if (!config->isInRange(value.asFloat())) {
        ALOGW("setProperty: propId=%d value=%f out of range [%f, %f]", propId, value.asFloat(),
              config->minValue, config->maxValue);
        return false;
    }

    // The backend echoes the confirmed new value back out through onBackendValueChanged() -- see
    // class comment. That's the only path AllianceCarHvacService uses to learn a set actually took effect.
    return mBackend->setValue(propId, areaId, value.asFloat());
}

bool HvacHandler::subscribe(int32_t propId, int32_t areaId, float sampleRateHz,
                             VpsEventCallback callback) {
    ALOGD("subscribe: propId=%d areaId=%d sampleRateHz=%f", propId, areaId, sampleRateHz);
    std::lock_guard<std::mutex> lock(mMutex);
    mSubscribedKeys.insert({propId, areaId});
    mCallback = std::move(callback);
    return true;
}

void HvacHandler::unsubscribe(int32_t propId) {
    ALOGD("unsubscribe: propId=%d", propId);
    std::lock_guard<std::mutex> lock(mMutex);
    for (auto it = mSubscribedKeys.begin(); it != mSubscribedKeys.end();) {
        if (it->propId == propId) {
            it = mSubscribedKeys.erase(it);
        } else {
            ++it;
        }
    }
}

void HvacHandler::onBackendValueChanged(int32_t propId, int32_t areaId, float value) {
    VpsEventCallback callback;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mSubscribedKeys.find({propId, areaId}) == mSubscribedKeys.end() || !mCallback) {
            ALOGD("onBackendValueChanged: propId=%d areaId=%d value=%f skipped (not subscribed or no callback)",
                  propId, areaId, value);
            return;
        }
        callback = mCallback;
    }
    ALOGD("onBackendValueChanged: propId=%d areaId=%d value=%f firing callback", propId, areaId,
          value);
    callback(propId, areaId);
}

}  // namespace vps
