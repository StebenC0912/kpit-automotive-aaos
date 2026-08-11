#include "FakeHvacBackend.h"

#include <chrono>
#include <cmath>

#include "VpsPropertyId.h"

#define LOG_TAG "FakeHvacBackend"
#include <log/log.h>

namespace vps {

namespace {

constexpr float kDefaultTempC = 22.0f;
constexpr float kDefaultOutsideTempC = 25.0f;
constexpr float kDefaultFanSpeed = 2.0f;

constexpr auto kSimTick = std::chrono::seconds(5);

}  // namespace

FakeHvacBackend::FakeHvacBackend() {
    ALOGD("FakeHvacBackend: constructing, seeding defaults and starting simulation thread");
    seedDefaults();
    mRunning = true;
    mSimThread = std::thread(&FakeHvacBackend::simulationLoop, this);
}

FakeHvacBackend::~FakeHvacBackend() {
    ALOGD("FakeHvacBackend: destructing, stopping simulation thread");
    mRunning = false;
    if (mSimThread.joinable()) {
        mSimThread.join();
    }
}

void FakeHvacBackend::seedDefaults() {
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

bool FakeHvacBackend::getValue(int32_t propId, int32_t areaId, float* outValue) const {
    std::lock_guard<std::mutex> lock(mMutex);
    auto it = mStore.find({propId, areaId});
    if (it == mStore.end()) {
        ALOGW("getValue: no stored value for propId=%d areaId=%d", propId, areaId);
        return false;
    }
    *outValue = it->second;
    ALOGD("getValue: propId=%d areaId=%d value=%f", propId, areaId, it->second);
    return true;
}

bool FakeHvacBackend::setValue(int32_t propId, int32_t areaId, float value) {
    ALOGD("setValue: propId=%d areaId=%d value=%f", propId, areaId, value);
    {
        std::lock_guard<std::mutex> lock(mMutex);
        mStore[{propId, areaId}] = value;
    }
    fireChangeCallback(propId, areaId, value);
    return true;
}

void FakeHvacBackend::setChangeCallback(BackendChangeCallback callback) {
    std::lock_guard<std::mutex> lock(mMutex);
    mChangeCallback = std::move(callback);
}

void FakeHvacBackend::fireChangeCallback(int32_t propId, int32_t areaId, float value) {
    BackendChangeCallback callback;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        callback = mChangeCallback;
    }
    if (callback) {
        callback(propId, areaId, value);
    }
}

// Simulates a real outside-air-temperature sensor: drifts PROP_TEMP_OUTSIDE by a small amount
// every tick, independent of anything the HMI/Manager does, and reports it through the same
// change-callback path a real ECU push would use.
void FakeHvacBackend::simulationLoop() {
    float phase = 0.0f;
    while (mRunning) {
        std::this_thread::sleep_for(kSimTick);
        if (!mRunning) {
            break;
        }
        phase += 0.1f;
        float outsideTemp = kDefaultOutsideTempC + std::sin(phase) * 3.0f;
        ALOGD("simulationLoop: drifting PROP_TEMP_OUTSIDE to %f", outsideTemp);
        {
            std::lock_guard<std::mutex> lock(mMutex);
            mStore[{PROP_TEMP_OUTSIDE, AREA_GLOBAL}] = outsideTemp;
        }
        fireChangeCallback(PROP_TEMP_OUTSIDE, AREA_GLOBAL, outsideTemp);
    }
}

}  // namespace vps
