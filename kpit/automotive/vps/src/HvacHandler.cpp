#include "HvacHandler.h"

#include <chrono>
#include <cmath>

#define LOG_TAG "HvacHandler"
#include <log/log.h>

namespace vps {

namespace {

// Mirrors com.kpit.hvac.manager.HvacProperties.java exactly -- this is a Java/C++ boundary with
// no shared code-gen, so keep the two in sync by hand if that file ever changes.
constexpr int32_t PROP_AC_STATE = 1;
constexpr int32_t PROP_MAX_STATE = 2;
constexpr int32_t PROP_RECYCLE_STATE = 3;
constexpr int32_t PROP_FAN_SPEED = 4;
constexpr int32_t PROP_TEMP = 5;
constexpr int32_t PROP_SYNC = 6;
constexpr int32_t PROP_SEAT_HEATING = 7;
constexpr int32_t PROP_VENTILATION_MODE = 8;
constexpr int32_t PROP_AUTO_MODE = 9;
constexpr int32_t PROP_DEFROST = 10;
constexpr int32_t PROP_VEHICLE_STATE = 11;
constexpr int32_t PROP_TEMP_OUTSIDE = 12;

constexpr int32_t AREA_GLOBAL = 0;
constexpr int32_t DRIVER = 1;
constexpr int32_t PASSENGER = 2;

constexpr float kDefaultTempC = 22.0f;
constexpr float kDefaultOutsideTempC = 25.0f;
constexpr float kDefaultFanSpeed = 2.0f;

constexpr auto kSimTick = std::chrono::seconds(5);

}  // namespace

HvacHandler::HvacHandler() {
    seedDefaults();
    mRunning = true;
    mSimThread = std::thread(&HvacHandler::simulationLoop, this);
}

HvacHandler::~HvacHandler() {
    mRunning = false;
    if (mSimThread.joinable()) {
        mSimThread.join();
    }
}

void HvacHandler::seedDefaults() {
    std::lock_guard<std::mutex> lock(mMutex);
    mStore[{PROP_AC_STATE, AREA_GLOBAL}] = 0.0f;
    mStore[{PROP_MAX_STATE, AREA_GLOBAL}] = 0.0f;
    mStore[{PROP_RECYCLE_STATE, AREA_GLOBAL}] = 0.0f;
    mStore[{PROP_FAN_SPEED, AREA_GLOBAL}] = kDefaultFanSpeed;
    mStore[{PROP_SYNC, AREA_GLOBAL}] = 0.0f;
    mStore[{PROP_AUTO_MODE, AREA_GLOBAL}] = 0.0f;
    mStore[{PROP_DEFROST, AREA_GLOBAL}] = 0.0f;
    mStore[{PROP_VENTILATION_MODE, AREA_GLOBAL}] = 0.0f;
    mStore[{PROP_VEHICLE_STATE, AREA_GLOBAL}] = 0.0f;
    mStore[{PROP_TEMP_OUTSIDE, AREA_GLOBAL}] = kDefaultOutsideTempC;

    mStore[{PROP_TEMP, DRIVER}] = kDefaultTempC;
    mStore[{PROP_TEMP, PASSENGER}] = kDefaultTempC;
    mStore[{PROP_SEAT_HEATING, DRIVER}] = 0.0f;
    mStore[{PROP_SEAT_HEATING, PASSENGER}] = 0.0f;
}

bool HvacHandler::supportsProperty(int32_t propId) const {
    return propId >= PROP_AC_STATE && propId <= PROP_TEMP_OUTSIDE;
}

bool HvacHandler::getProperty(int32_t propId, int32_t areaId, VpsPropValue* outValue) const {
    std::lock_guard<std::mutex> lock(mMutex);
    auto it = mStore.find({propId, areaId});
    if (it == mStore.end()) {
        ALOGW("getProperty: no stored value for propId=%d areaId=%d", propId, areaId);
        return false;
    }
    *outValue = VpsPropValue::ofFloat(it->second);
    return true;
}

bool HvacHandler::setProperty(int32_t propId, int32_t areaId, const VpsPropValue& value) {
    {
        std::lock_guard<std::mutex> lock(mMutex);
        mStore[{propId, areaId}] = value.asFloat();
    }
    // Echo the confirmed new value back out as an event -- see class comment. This is the only
    // path HvacService uses to learn a set actually took effect.
    notify(propId, areaId);
    return true;
}

bool HvacHandler::subscribe(int32_t propId, int32_t areaId, float /*sampleRateHz*/,
                             VpsEventCallback callback) {
    std::lock_guard<std::mutex> lock(mMutex);
    mSubscribedKeys.insert({propId, areaId});
    mCallback = std::move(callback);
    return true;
}

void HvacHandler::unsubscribe(int32_t propId) {
    std::lock_guard<std::mutex> lock(mMutex);
    for (auto it = mSubscribedKeys.begin(); it != mSubscribedKeys.end();) {
        if (it->propId == propId) {
            it = mSubscribedKeys.erase(it);
        } else {
            ++it;
        }
    }
}

void HvacHandler::notify(int32_t propId, int32_t areaId) {
    VpsEventCallback callback;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mSubscribedKeys.find({propId, areaId}) == mSubscribedKeys.end() || !mCallback) {
            return;
        }
        callback = mCallback;
    }
    callback(propId, areaId);
}

// Simulates a real outside-air-temperature sensor: drifts PROP_TEMP_OUTSIDE by a small amount
// every tick, independent of anything the HMI/Manager does, and fires the same event path a real
// ECU push would.
void HvacHandler::simulationLoop() {
    float phase = 0.0f;
    while (mRunning) {
        std::this_thread::sleep_for(kSimTick);
        if (!mRunning) {
            break;
        }
        phase += 0.1f;
        float outsideTemp = kDefaultOutsideTempC + std::sin(phase) * 3.0f;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            mStore[{PROP_TEMP_OUTSIDE, AREA_GLOBAL}] = outsideTemp;
        }
        notify(PROP_TEMP_OUTSIDE, AREA_GLOBAL);
    }
}

}  // namespace vps
