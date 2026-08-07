/*
 * VpsDispatcherTest.cpp
 *
 * Covers VpsDispatcher's own job -- routing get/set/subscribe calls by propId to whichever
 * registered IVpsHandler claims it -- as opposed to HvacHandlerTest.cpp, which covers HVAC
 * property/simulation behavior in isolation. VpsDispatcher::instance() is a process-wide
 * singleton (see VpsDispatcher.h), so unlike HvacHandlerTest this suite necessarily shares one
 * handler/store across all its TEST_F cases; each case only asserts on propIds it privately owns
 * to stay order-independent.
 */

#include <gtest/gtest.h>

#include <memory>

#include "HvacHandler.h"
#include "VpsDispatcher.h"

namespace {

constexpr int32_t PROP_RECYCLE_STATE = 3;
constexpr int32_t PROP_SYNC = 6;
constexpr int32_t PROP_UNKNOWN = 999;

constexpr int32_t AREA_GLOBAL = 0;

class VpsDispatcherTest : public ::testing::Test {
protected:
    static void SetUpTestSuite() {
        // Same registration base_comfort_vhal_jni.cpp's ensureHandlersRegistered() performs --
        // see that file's std::call_once block. Registering more than once here across TEST_F
        // cases would just append a second handler that always loses to the first
        // (VpsDispatcher::findHandler returns the first match), so this must run exactly once
        // per test binary.
        vps::VpsDispatcher::instance().registerHandler(std::make_shared<vps::HvacHandler>());
    }
};

TEST_F(VpsDispatcherTest, UnknownPropertyIdRoutesToNoHandler) {
    float value = 0.0f;
    EXPECT_FALSE(vps::VpsDispatcher::instance().getFloatProperty(PROP_UNKNOWN, AREA_GLOBAL, &value));
    EXPECT_FALSE(vps::VpsDispatcher::instance().setFloatProperty(PROP_UNKNOWN, AREA_GLOBAL, 1.0f));
}

TEST_F(VpsDispatcherTest, SetFloatPropertyRoutesToHvacHandlerAndRoundTrips) {
    ASSERT_TRUE(vps::VpsDispatcher::instance().setFloatProperty(PROP_RECYCLE_STATE, AREA_GLOBAL, 1.0f));
    float value = 0.0f;
    ASSERT_TRUE(vps::VpsDispatcher::instance().getFloatProperty(PROP_RECYCLE_STATE, AREA_GLOBAL, &value));
    EXPECT_FLOAT_EQ(value, 1.0f);
}

// getInt/setInt and getBool/setBool ultimately land on the same HvacHandler store as a float
// (VpsPropValue::asFloat()/asInt32()/asBool() all convert through the tagged union) -- this just
// confirms the dispatcher's int/bool entry points reach the handler at all, matching how
// nativeSetIntProperty/nativeSetBoolProperty in base_comfort_vhal_jni.cpp would call in.
TEST_F(VpsDispatcherTest, SetBoolPropertyRoutesToHvacHandlerAndRoundTrips) {
    ASSERT_TRUE(vps::VpsDispatcher::instance().setBoolProperty(PROP_SYNC, AREA_GLOBAL, true));
    bool value = false;
    ASSERT_TRUE(vps::VpsDispatcher::instance().getBoolProperty(PROP_SYNC, AREA_GLOBAL, &value));
    EXPECT_TRUE(value);
}

TEST_F(VpsDispatcherTest, SubscribeAndUnsubscribeRouteToHvacHandler) {
    int callCount = 0;
    ASSERT_TRUE(vps::VpsDispatcher::instance().subscribe(
            PROP_RECYCLE_STATE, AREA_GLOBAL, 0.0f,
            [&](int32_t, int32_t) { callCount++; }));

    ASSERT_TRUE(vps::VpsDispatcher::instance().setFloatProperty(PROP_RECYCLE_STATE, AREA_GLOBAL, 0.0f));
    EXPECT_EQ(callCount, 1);

    vps::VpsDispatcher::instance().unsubscribe(PROP_RECYCLE_STATE);
    ASSERT_TRUE(vps::VpsDispatcher::instance().setFloatProperty(PROP_RECYCLE_STATE, AREA_GLOBAL, 1.0f));
    EXPECT_EQ(callCount, 1);  // no further events after unsubscribe
}
