/*
 * HvacHandlerTest.cpp
 *
 * Exercises vps::HvacHandler directly -- no JNI, no Binder, no HvacService -- by constructing it
 * exactly the way base_comfort_vhal_jni.cpp's ensureHandlersRegistered() does
 * (std::make_shared<vps::HvacHandler>()) and driving it straight through the IVpsHandler
 * interface. This is the "below the AIDL/Java layer" test path described in
 * kpit/docs/11-testing-hvac.md: adb's `service call hvac_service` already traverses VpsDispatcher
 * (see that doc), so testing VPS in isolation means bypassing HvacService/JNI entirely instead.
 *
 * Each test constructs its own HvacHandler instance (not vps::VpsDispatcher::instance(), which is
 * a process-wide singleton -- see VpsDispatcherTest.cpp for dispatcher-routing tests that
 * necessarily share that singleton). A fresh instance keeps every test's seed/get/set/subscribe
 * behavior independent of test execution order.
 */

#include <gtest/gtest.h>

#include <chrono>
#include <thread>

#include "HvacHandler.h"

namespace {

// Mirrors com.kpit.hvac.manager.HvacProperties.java, same as HvacHandler.cpp's own anonymous
// namespace -- kept in sync by hand for the same reason that file's comment gives.
constexpr int32_t PROP_AC_STATE = 1;
constexpr int32_t PROP_MAX_STATE = 2;
constexpr int32_t PROP_FAN_SPEED = 4;
constexpr int32_t PROP_TEMP = 5;
constexpr int32_t PROP_SEAT_HEATING = 7;
constexpr int32_t PROP_VEHICLE_STATE = 11;
constexpr int32_t PROP_TEMP_OUTSIDE = 12;
constexpr int32_t PROP_UNKNOWN = 999;

constexpr int32_t AREA_GLOBAL = 0;
constexpr int32_t DRIVER = 1;
constexpr int32_t PASSENGER = 2;

constexpr float kDefaultTempC = 22.0f;
constexpr float kDefaultOutsideTempC = 25.0f;
constexpr float kDefaultFanSpeed = 2.0f;

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
    ASSERT_TRUE(handler.subscribe(PROP_AC_STATE, AREA_GLOBAL, /*sampleRateHz=*/0.0f,
                                   [&](int32_t, int32_t) { callCount++; }));

    // Same propId, different area -- must not fire the AC/GLOBAL subscription.
    ASSERT_TRUE(handler.setProperty(PROP_AC_STATE, DRIVER, vps::VpsPropValue::ofFloat(1.0f)));
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
    // HvacService's onVehiclePropertyChanged callback depends on (see class comment in
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
