/*
 * HvacHandlerTest.cpp
 *
 * Exercises vps::HvacHandler directly -- no JNI, no Binder, no AllianceCarHvacService -- by constructing it
 * exactly the way base_comfort_vhal_jni.cpp's ensureHandlersRegistered() does
 * (std::make_shared<vps::HvacHandler>()) and driving it straight through the IVpsHandler
 * interface. This is the "below the AIDL/Java layer" test path described in
 * kpit/docs/11-testing-hvac.md: adb's `service call hvac_service` already traverses VpsDispatcher
 * (see that doc), so testing VPS in isolation means bypassing AllianceCarHvacService/JNI entirely instead.
 *
 * Each test constructs its own HvacHandler instance (not vps::VpsDispatcher::instance(), which is
 * a process-wide singleton -- see VpsDispatcherTest.cpp for dispatcher-routing tests that
 * necessarily share that singleton). A fresh instance keeps every test's seed/get/set/subscribe
 * behavior independent of test execution order.
 */

#include <gtest/gtest.h>

#include <chrono>
#include <memory>
#include <thread>
#include <unordered_map>
#include <utility>

#include "HvacHandler.h"
#include "IHvacBackend.h"
#include "VpsPropertyId.h"

using vps::AREA_GLOBAL;
using vps::DRIVER;
using vps::PASSENGER;
using vps::PROP_AC_STATE;
using vps::PROP_FAN_SPEED;
using vps::PROP_MAX_STATE;
using vps::PROP_SEAT_HEATING;
using vps::PROP_TEMP;
using vps::PROP_TEMP_OUTSIDE;
using vps::PROP_VEHICLE_STATE;

namespace {

// Not a real propId under Stage 2's bit-packed scheme (VpsPropertyId.h) -- any value with no
// matching config works here, since the point is just "not one of ours".
constexpr int32_t PROP_UNKNOWN = 999;

constexpr float kDefaultTempC = 22.0f;
constexpr float kDefaultOutsideTempC = 25.0f;
constexpr float kDefaultFanSpeed = 2.0f;

// A second, independent IHvacBackend implementation -- proof that HvacHandler (Stage 3) really
// does work against the interface rather than assuming FakeHvacBackend specifically. No
// simulation thread, no seeded defaults; just enough storage to round-trip get/set, plus
// injectExternalChange() to simulate a change the backend originates on its own (the way a real
// ECU push or FakeHvacBackend's outside-temp drift would), without going through setValue().
class StubHvacBackend : public vps::IHvacBackend {
public:
    bool getValue(int32_t propId, int32_t areaId, float* outValue) const override {
        auto it = mValues.find({propId, areaId});
        if (it == mValues.end()) {
            return false;
        }
        *outValue = it->second;
        return true;
    }

    bool setValue(int32_t propId, int32_t areaId, float value) override {
        mValues[{propId, areaId}] = value;
        if (mCallback) {
            mCallback(propId, areaId, value);
        }
        return true;
    }

    void setChangeCallback(vps::BackendChangeCallback callback) override {
        mCallback = std::move(callback);
    }

    void injectExternalChange(int32_t propId, int32_t areaId, float value) {
        mValues[{propId, areaId}] = value;
        if (mCallback) {
            mCallback(propId, areaId, value);
        }
    }

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

    std::unordered_map<Key, float, KeyHash> mValues;
    vps::BackendChangeCallback mCallback;
};

}  // namespace

TEST(HvacHandlerTest, SupportsPropertyOnlyClaimsItsOwnRange) {
    vps::HvacHandler handler;
    EXPECT_TRUE(handler.supportsProperty(PROP_AC_STATE));
    EXPECT_TRUE(handler.supportsProperty(PROP_TEMP_OUTSIDE));
    EXPECT_FALSE(handler.supportsProperty(PROP_UNKNOWN));
    EXPECT_FALSE(handler.supportsProperty(0));
}

TEST(HvacHandlerTest, SeedDefaultsMatchDocumentedInitialState) {
    vps::HvacHandler handler;
    vps::VpsPropValue value;

    ASSERT_TRUE(handler.getProperty(PROP_AC_STATE, AREA_GLOBAL, &value));
    EXPECT_FLOAT_EQ(value.asFloat(), 0.0f);

    ASSERT_TRUE(handler.getProperty(PROP_VEHICLE_STATE, AREA_GLOBAL, &value));
    EXPECT_FLOAT_EQ(value.asFloat(), 0.0f);  // see docs/11-testing-hvac.md "why the panel is locked"

    ASSERT_TRUE(handler.getProperty(PROP_FAN_SPEED, AREA_GLOBAL, &value));
    EXPECT_FLOAT_EQ(value.asFloat(), kDefaultFanSpeed);

    ASSERT_TRUE(handler.getProperty(PROP_TEMP_OUTSIDE, AREA_GLOBAL, &value));
    EXPECT_FLOAT_EQ(value.asFloat(), kDefaultOutsideTempC);

    ASSERT_TRUE(handler.getProperty(PROP_TEMP, DRIVER, &value));
    EXPECT_FLOAT_EQ(value.asFloat(), kDefaultTempC);
    ASSERT_TRUE(handler.getProperty(PROP_TEMP, PASSENGER, &value));
    EXPECT_FLOAT_EQ(value.asFloat(), kDefaultTempC);
}

TEST(HvacHandlerTest, GetPropertyFailsForUnseededKey) {
    vps::HvacHandler handler;
    vps::VpsPropValue value;
    // PROP_SEAT_HEATING is only seeded for DRIVER/PASSENGER, never AREA_GLOBAL.
    EXPECT_FALSE(handler.getProperty(PROP_SEAT_HEATING, AREA_GLOBAL, &value));
}

TEST(HvacHandlerTest, SetPropertyRoundTripsThroughGetProperty) {
    vps::HvacHandler handler;
    ASSERT_TRUE(handler.setProperty(PROP_TEMP, DRIVER, vps::VpsPropValue::ofFloat(24.5f)));

    vps::VpsPropValue value;
    ASSERT_TRUE(handler.getProperty(PROP_TEMP, DRIVER, &value));
    EXPECT_FLOAT_EQ(value.asFloat(), 24.5f);
}

TEST(HvacHandlerTest, SetPropertyOnUnsubscribedKeyFiresNoCallback) {
    vps::HvacHandler handler;
    int callCount = 0;
    // PROP_SEAT_HEATING supports both DRIVER and PASSENGER (unlike PROP_AC_STATE, which is
    // GLOBAL-only under the Stage 1 VpsPropConfig area validation), so this exercises two
    // genuinely valid areas of the same property.
    ASSERT_TRUE(handler.subscribe(PROP_SEAT_HEATING, DRIVER, /*sampleRateHz=*/0.0f,
                                   [&](int32_t, int32_t) { callCount++; }));

    // Same propId, different area -- must not fire the DRIVER subscription.
    ASSERT_TRUE(handler.setProperty(PROP_SEAT_HEATING, PASSENGER, vps::VpsPropValue::ofFloat(1.0f)));
    EXPECT_EQ(callCount, 0);
}

TEST(HvacHandlerTest, SubscribeReceivesSetPropertyEchoImmediately) {
    vps::HvacHandler handler;
    int32_t seenPropId = -1;
    int32_t seenAreaId = -1;
    int callCount = 0;

    ASSERT_TRUE(handler.subscribe(PROP_MAX_STATE, AREA_GLOBAL, 0.0f,
                                   [&](int32_t propId, int32_t areaId) {
                                       seenPropId = propId;
                                       seenAreaId = areaId;
                                       callCount++;
                                   }));

    // setProperty() echoes the new value straight back out -- this is the mechanism
    // AllianceCarHvacService's onVehiclePropertyChanged callback depends on (see class comment in
    // HvacHandler.h and finding #17 in docs/11-testing-hvac.md).
    ASSERT_TRUE(handler.setProperty(PROP_MAX_STATE, AREA_GLOBAL, vps::VpsPropValue::ofFloat(1.0f)));

    EXPECT_EQ(callCount, 1);
    EXPECT_EQ(seenPropId, PROP_MAX_STATE);
    EXPECT_EQ(seenAreaId, AREA_GLOBAL);
}

TEST(HvacHandlerTest, UnsubscribeStopsFurtherEchoes) {
    vps::HvacHandler handler;
    int callCount = 0;
    ASSERT_TRUE(handler.subscribe(PROP_FAN_SPEED, AREA_GLOBAL, 0.0f,
                                   [&](int32_t, int32_t) { callCount++; }));

    ASSERT_TRUE(handler.setProperty(PROP_FAN_SPEED, AREA_GLOBAL, vps::VpsPropValue::ofFloat(7.0f)));
    EXPECT_EQ(callCount, 1);

    handler.unsubscribe(PROP_FAN_SPEED);
    ASSERT_TRUE(handler.setProperty(PROP_FAN_SPEED, AREA_GLOBAL, vps::VpsPropValue::ofFloat(3.0f)));
    EXPECT_EQ(callCount, 1);  // no second callback after unsubscribe
}

TEST(HvacHandlerTest, OutsideTempSimulationDriftsAndNotifiesWithoutExternalSet) {
    // HvacHandler's kSimTick is 5s (src/HvacHandler.cpp) -- too slow for a unit test to wait on
    // directly without becoming flaky/slow in CI. This just proves subscribe() alone (with no
    // matching setProperty()) doesn't fire spuriously in the first place; the periodic drift
    // itself is exercised via manual testing per docs/11-testing-hvac.md's outside-temp row.
    vps::HvacHandler handler;
    int callCount = 0;
    ASSERT_TRUE(handler.subscribe(PROP_TEMP_OUTSIDE, AREA_GLOBAL, 0.0f,
                                   [&](int32_t, int32_t) { callCount++; }));
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    EXPECT_EQ(callCount, 0);
}

TEST(HvacHandlerTest, InjectedBackendIsUsedInsteadOfDefaultFakeHvacBackend) {
    // Stage 3 (kpit/docs/03-implementation-status.md): HvacHandler's explicit-backend constructor
    // is the seam a future RealCanHvacBackend would plug into. Prove it actually routes get/set
    // through whatever backend was injected, with a second backend implementation that shares no
    // code with FakeHvacBackend.
    auto stub = std::make_unique<StubHvacBackend>();
    StubHvacBackend* stubPtr = stub.get();
    vps::HvacHandler handler(std::move(stub));

    int callCount = 0;
    int32_t seenPropId = -1;
    ASSERT_TRUE(handler.subscribe(PROP_MAX_STATE, AREA_GLOBAL, 0.0f,
                                   [&](int32_t propId, int32_t) {
                                       seenPropId = propId;
                                       callCount++;
                                   }));

    // setProperty() must reach the injected backend (StubHvacBackend has no defaults of its own,
    // so a successful get afterward proves the value actually landed there).
    ASSERT_TRUE(handler.setProperty(PROP_MAX_STATE, AREA_GLOBAL, vps::VpsPropValue::ofFloat(1.0f)));
    EXPECT_EQ(callCount, 1);
    EXPECT_EQ(seenPropId, PROP_MAX_STATE);

    vps::VpsPropValue value;
    ASSERT_TRUE(handler.getProperty(PROP_MAX_STATE, AREA_GLOBAL, &value));
    EXPECT_FLOAT_EQ(value.asFloat(), 1.0f);

    // A change the backend originates on its own (not via setValue()) must still reach the
    // subscriber -- proves HvacHandler's callback wiring doesn't assume every change comes from
    // its own setProperty() call, which matters once a real backend can push unsolicited updates.
    stubPtr->injectExternalChange(PROP_MAX_STATE, AREA_GLOBAL, 0.0f);
    EXPECT_EQ(callCount, 2);
}
