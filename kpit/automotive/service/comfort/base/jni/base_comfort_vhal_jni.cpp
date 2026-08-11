/*
 * base_comfort_vhal_jni.cpp
 *
 * JNI bridge shared by every Comfort domain service (AllianceCarHvacService, SeatService, ...). Calls
 * straight into vps::VpsDispatcher (libvps.so, Component 3 -- instruction.md section I.3),
 * in-process, matching section II steps 5-6 exactly: "JNI passes the property id and value to the
 * C++ VpsDispatcher, which routes to HvacHandler/SeatHandler based on the property id." There is
 * no Binder/HAL registration on this path -- VpsDispatcher is a plain in-process C++ singleton
 * linked into this .so, not a separate service.
 *
 * One VhalBridge is created per AllianceCarBaseService instance (per process), holding just enough to
 * call back into the owning Java service (onNativePropertyEvent) whenever VpsDispatcher reports a
 * subscribed property changed.
 */

#define LOG_TAG "BaseComfortVhalJni"

#include <jni.h>
#include <log/log.h>

#include <memory>
#include <mutex>

#include "HvacHandler.h"
#include "VpsDispatcher.h"

namespace {

struct VhalBridge {
    JavaVM* javaVm = nullptr;
    jobject serviceGlobalRef = nullptr;         // global ref to the owning AllianceCarBaseService
    jmethodID onPropertyEventMethod = nullptr;  // AllianceCarBaseService#onNativePropertyEvent(II)V
};

VhalBridge* bridgeFromHandle(jlong handle) {
    return reinterpret_cast<VhalBridge*>(static_cast<intptr_t>(handle));
}

// Registered exactly once per process, regardless of which Comfort domain's AllianceCarBaseService
// happens to load this library first. Every IVpsHandler only ever answers propIds it owns (see
// IVpsHandler::supportsProperty), so it's safe -- and far simpler than plumbing a domain name
// down through nativeInit()'s fixed no-arg signature -- to register all known handlers
// unconditionally. Add new domain handlers here as they're implemented (SeatHandler, ...).
std::once_flag gHandlersRegisteredOnce;
void ensureHandlersRegistered() {
    std::call_once(gHandlersRegisteredOnce,
                    [] { vps::VpsDispatcher::instance().registerHandler(std::make_shared<vps::HvacHandler>()); });
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeInit(JNIEnv* env, jobject thiz) {
    ALOGD("nativeInit: initializing VHAL bridge");
    ensureHandlersRegistered();

    auto* bridge = new VhalBridge();
    if (env->GetJavaVM(&bridge->javaVm) != JNI_OK) {
        ALOGE("Failed to obtain JavaVM reference");
        delete bridge;
        return 0;
    }

    jclass serviceClass = env->GetObjectClass(thiz);
    bridge->onPropertyEventMethod = env->GetMethodID(serviceClass, "onNativePropertyEvent", "(II)V");
    env->DeleteLocalRef(serviceClass);
    if (bridge->onPropertyEventMethod == nullptr) {
        ALOGE("AllianceCarBaseService is missing onNativePropertyEvent(int,int)");
        delete bridge;
        return 0;
    }

    bridge->serviceGlobalRef = env->NewGlobalRef(thiz);
    ALOGD("nativeInit: bridge ready, handle=%p", bridge);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(bridge));
}

JNIEXPORT void JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeRelease(JNIEnv* env, jobject /*thiz*/,
                                                                      jlong handle) {
    ALOGD("nativeRelease: handle=%p", bridgeFromHandle(handle));
    VhalBridge* bridge = bridgeFromHandle(handle);
    if (bridge == nullptr) {
        return;
    }
    if (bridge->serviceGlobalRef != nullptr) {
        env->DeleteGlobalRef(bridge->serviceGlobalRef);
    }
    delete bridge;
}

JNIEXPORT jboolean JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeSubscribe(JNIEnv* /*env*/,
                                                                        jobject /*thiz*/,
                                                                        jlong handle, jint propId,
                                                                        jint areaId,
                                                                        jfloat sampleRateHz) {
    VhalBridge* bridge = bridgeFromHandle(handle);
    if (bridge == nullptr) {
        ALOGW("nativeSubscribe: null bridge, propId=%d areaId=%d", propId, areaId);
        return JNI_FALSE;
    }
    bool subscribed = vps::VpsDispatcher::instance().subscribe(
            propId, areaId, sampleRateHz, [bridge](int32_t p, int32_t a) {
                ALOGD("nativeSubscribe: event fired propId=%d areaId=%d", p, a);
                JNIEnv* env = nullptr;
                bool didAttach = false;
                JavaVM* vm = bridge->javaVm;
                if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                        ALOGE("Failed to attach VPS event thread to JVM");
                        return;
                    }
                    didAttach = true;
                }
                env->CallVoidMethod(bridge->serviceGlobalRef, bridge->onPropertyEventMethod,
                                     static_cast<jint>(p), static_cast<jint>(a));
                if (env->ExceptionCheck()) {
                    ALOGE("Java onNativePropertyEvent threw an exception");
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                }
                if (didAttach) {
                    vm->DetachCurrentThread();
                }
            });
    ALOGD("nativeSubscribe: propId=%d areaId=%d sampleRateHz=%f subscribed=%d", propId, areaId,
          sampleRateHz, subscribed);
    return subscribed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeUnsubscribe(JNIEnv* /*env*/,
                                                                          jobject /*thiz*/,
                                                                          jlong /*handle*/,
                                                                          jint propId) {
    ALOGD("nativeUnsubscribe: propId=%d", propId);
    vps::VpsDispatcher::instance().unsubscribe(propId);
}

JNIEXPORT jboolean JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeSetIntProperty(JNIEnv* /*env*/,
                                                                             jobject /*thiz*/,
                                                                             jlong /*handle*/,
                                                                             jint propId,
                                                                             jint areaId,
                                                                             jint value) {
    bool ok = vps::VpsDispatcher::instance().setIntProperty(propId, areaId, value);
    ALOGD("nativeSetIntProperty: propId=%d areaId=%d value=%d ok=%d", propId, areaId, value, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeGetIntProperty(JNIEnv* /*env*/,
                                                                             jobject /*thiz*/,
                                                                             jlong /*handle*/,
                                                                             jint propId,
                                                                             jint areaId) {
    int32_t value = 0;
    bool ok = vps::VpsDispatcher::instance().getIntProperty(propId, areaId, &value);
    ALOGD("nativeGetIntProperty: propId=%d areaId=%d value=%d ok=%d", propId, areaId, value, ok);
    return static_cast<jint>(value);
}

JNIEXPORT jboolean JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeSetFloatProperty(JNIEnv* /*env*/,
                                                                               jobject /*thiz*/,
                                                                               jlong /*handle*/,
                                                                               jint propId,
                                                                               jint areaId,
                                                                               jfloat value) {
    bool ok = vps::VpsDispatcher::instance().setFloatProperty(propId, areaId, value);
    ALOGD("nativeSetFloatProperty: propId=%d areaId=%d value=%f ok=%d", propId, areaId, value, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeGetFloatProperty(JNIEnv* /*env*/,
                                                                               jobject /*thiz*/,
                                                                               jlong /*handle*/,
                                                                               jint propId,
                                                                               jint areaId) {
    float value = 0.0f;
    bool ok = vps::VpsDispatcher::instance().getFloatProperty(propId, areaId, &value);
    ALOGD("nativeGetFloatProperty: propId=%d areaId=%d value=%f ok=%d", propId, areaId, value, ok);
    return value;
}

JNIEXPORT jboolean JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeSetBoolProperty(JNIEnv* /*env*/,
                                                                              jobject /*thiz*/,
                                                                              jlong /*handle*/,
                                                                              jint propId,
                                                                              jint areaId,
                                                                              jboolean value) {
    bool ok = vps::VpsDispatcher::instance().setBoolProperty(propId, areaId, value == JNI_TRUE);
    ALOGD("nativeSetBoolProperty: propId=%d areaId=%d value=%d ok=%d", propId, areaId,
          value == JNI_TRUE, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeGetBoolProperty(JNIEnv* /*env*/,
                                                                              jobject /*thiz*/,
                                                                              jlong /*handle*/,
                                                                              jint propId,
                                                                              jint areaId) {
    bool value = false;
    bool ok = vps::VpsDispatcher::instance().getBoolProperty(propId, areaId, &value);
    ALOGD("nativeGetBoolProperty: propId=%d areaId=%d value=%d ok=%d", propId, areaId, value, ok);
    return value ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
