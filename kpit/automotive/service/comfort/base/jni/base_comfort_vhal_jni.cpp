/*
 * base_comfort_vhal_jni.cpp
 *
 * JNI bridge shared by every Comfort domain service (AllianceCarHvacService, SeatService, ...).
 * VHAL-alignment Stage 4 (kpit/docs/03-implementation-status.md item 13): this used to call
 * vps::VpsDispatcher::instance() directly, in-process (libvps.so linked straight into this .so).
 * It's now a Binder client of vendor.kpit.vps-service (vps/service/), the daemon that owns
 * VpsDispatcher/HvacHandler on /vendor -- every native*Property/nativeSubscribe/nativeUnsubscribe
 * call below is an AIDL transaction on vendor.kpit.vps.IVpsService/default instead of a function
 * call. This is the Treble-sanctioned crossing point that let libvps go back to vendor: true (see
 * 10-build-and-product-integration.md's sepolicy section and vps/Android.bp's header comment).
 *
 * One VhalBridge is created per AllianceCarBaseService instance (per process), holding the
 * connected IVpsService remote plus the one IVpsCallback binder this process registers -- and just
 * enough to call back into the owning Java service (onNativePropertyEvent) whenever the daemon
 * reports a subscribed property changed.
 */

#define LOG_TAG "BaseComfortVhalJni"

#include <jni.h>
#include <log/log.h>

#include <memory>
#include <string>
#include <vector>

#include <aidl/vendor/kpit/vps/BnVpsCallback.h>
#include <aidl/vendor/kpit/vps/IVpsService.h>
#include <android/binder_auto_utils.h>
#include <android/binder_manager.h>

using aidl::vendor::kpit::vps::BnVpsCallback;
using aidl::vendor::kpit::vps::IVpsService;

namespace {

struct VhalBridge;

// Forwards every onPropertyEvent() the daemon pushes back into the same JNI-attach +
// CallVoidMethod logic base_comfort_vhal_jni.cpp always used, whether the event originated
// in-process (pre-Stage-4) or, now, across a real Binder call from vendor.kpit.vps-service.
class VpsCallbackImpl : public BnVpsCallback {
public:
    explicit VpsCallbackImpl(VhalBridge* bridge) : mBridge(bridge) {}
    ndk::ScopedAStatus onPropertyEvent(int32_t propId, int32_t areaId) override;

private:
    VhalBridge* mBridge;
};

struct VhalBridge {
    JavaVM* javaVm = nullptr;
    jobject serviceGlobalRef = nullptr;         // global ref to the owning AllianceCarBaseService
    jmethodID onPropertyEventMethod = nullptr;  // AllianceCarBaseService#onNativePropertyEvent(II)V
    std::shared_ptr<IVpsService> remote;        // connection to vendor.kpit.vps.IVpsService/default
    std::shared_ptr<VpsCallbackImpl> callback;  // this process's one registered IVpsCallback
};

ndk::ScopedAStatus VpsCallbackImpl::onPropertyEvent(int32_t propId, int32_t areaId) {
    ALOGD("VpsCallbackImpl::onPropertyEvent: propId=%d areaId=%d", propId, areaId);
    JNIEnv* env = nullptr;
    bool didAttach = false;
    JavaVM* vm = mBridge->javaVm;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            ALOGE("Failed to attach VPS event thread to JVM");
            return ndk::ScopedAStatus::ok();
        }
        didAttach = true;
    }
    env->CallVoidMethod(mBridge->serviceGlobalRef, mBridge->onPropertyEventMethod,
                         static_cast<jint>(propId), static_cast<jint>(areaId));
    if (env->ExceptionCheck()) {
        ALOGE("Java onNativePropertyEvent threw an exception");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (didAttach) {
        vm->DetachCurrentThread();
    }
    return ndk::ScopedAStatus::ok();
}

VhalBridge* bridgeFromHandle(jlong handle) {
    return reinterpret_cast<VhalBridge*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeInit(JNIEnv* env, jobject thiz) {
    ALOGD("nativeInit: connecting to vendor.kpit.vps.IVpsService/default");

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

    const std::string instance = std::string(IVpsService::descriptor) + "/default";
    ndk::SpAIBinder binder(AServiceManager_waitForService(instance.c_str()));
    bridge->remote = IVpsService::fromBinder(binder);
    if (bridge->remote == nullptr) {
        ALOGE("nativeInit: failed to connect to %s", instance.c_str());
        delete bridge;
        return 0;
    }
    bridge->callback = ndk::SharedRefBase::make<VpsCallbackImpl>(bridge);

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
    if (bridge == nullptr || bridge->remote == nullptr) {
        ALOGW("nativeSubscribe: null bridge/remote, propId=%d areaId=%d", propId, areaId);
        return JNI_FALSE;
    }
    bool subscribed = false;
    ndk::ScopedAStatus status =
            bridge->remote->subscribe(propId, areaId, sampleRateHz, bridge->callback, &subscribed);
    if (!status.isOk()) {
        ALOGE("nativeSubscribe: Binder call failed: %s", status.getDescription().c_str());
        return JNI_FALSE;
    }
    ALOGD("nativeSubscribe: propId=%d areaId=%d sampleRateHz=%f subscribed=%d", propId, areaId,
          sampleRateHz, subscribed);
    return subscribed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeUnsubscribe(JNIEnv* /*env*/,
                                                                          jobject /*thiz*/,
                                                                          jlong handle,
                                                                          jint propId) {
    ALOGD("nativeUnsubscribe: propId=%d", propId);
    VhalBridge* bridge = bridgeFromHandle(handle);
    if (bridge == nullptr || bridge->remote == nullptr) {
        return;
    }
    ndk::ScopedAStatus status = bridge->remote->unsubscribe(propId);
    if (!status.isOk()) {
        ALOGE("nativeUnsubscribe: Binder call failed: %s", status.getDescription().c_str());
    }
}

JNIEXPORT jboolean JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeSetIntProperty(JNIEnv* /*env*/,
                                                                             jobject /*thiz*/,
                                                                             jlong handle,
                                                                             jint propId,
                                                                             jint areaId,
                                                                             jint value) {
    VhalBridge* bridge = bridgeFromHandle(handle);
    if (bridge == nullptr || bridge->remote == nullptr) {
        ALOGW("nativeSetIntProperty: null bridge/remote, propId=%d", propId);
        return JNI_FALSE;
    }
    bool ok = false;
    ndk::ScopedAStatus status = bridge->remote->setIntProperty(propId, areaId, value, &ok);
    ok = status.isOk() && ok;
    ALOGD("nativeSetIntProperty: propId=%d areaId=%d value=%d ok=%d", propId, areaId, value, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeGetIntProperty(JNIEnv* /*env*/,
                                                                             jobject /*thiz*/,
                                                                             jlong handle,
                                                                             jint propId,
                                                                             jint areaId) {
    VhalBridge* bridge = bridgeFromHandle(handle);
    if (bridge == nullptr || bridge->remote == nullptr) {
        ALOGW("nativeGetIntProperty: null bridge/remote, propId=%d", propId);
        return 0;
    }
    std::vector<int32_t> value;
    bool ok = false;
    ndk::ScopedAStatus status = bridge->remote->getIntProperty(propId, areaId, &value, &ok);
    int32_t result = (status.isOk() && !value.empty()) ? value[0] : 0;
    ALOGD("nativeGetIntProperty: propId=%d areaId=%d value=%d ok=%d", propId, areaId, result, ok);
    return static_cast<jint>(result);
}

JNIEXPORT jboolean JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeSetFloatProperty(JNIEnv* /*env*/,
                                                                               jobject /*thiz*/,
                                                                               jlong handle,
                                                                               jint propId,
                                                                               jint areaId,
                                                                               jfloat value) {
    VhalBridge* bridge = bridgeFromHandle(handle);
    if (bridge == nullptr || bridge->remote == nullptr) {
        ALOGW("nativeSetFloatProperty: null bridge/remote, propId=%d", propId);
        return JNI_FALSE;
    }
    bool ok = false;
    ndk::ScopedAStatus status = bridge->remote->setFloatProperty(propId, areaId, value, &ok);
    ok = status.isOk() && ok;
    ALOGD("nativeSetFloatProperty: propId=%d areaId=%d value=%f ok=%d", propId, areaId, value, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeGetFloatProperty(JNIEnv* /*env*/,
                                                                               jobject /*thiz*/,
                                                                               jlong handle,
                                                                               jint propId,
                                                                               jint areaId) {
    VhalBridge* bridge = bridgeFromHandle(handle);
    if (bridge == nullptr || bridge->remote == nullptr) {
        ALOGW("nativeGetFloatProperty: null bridge/remote, propId=%d", propId);
        return 0.0f;
    }
    std::vector<float> value;
    bool ok = false;
    ndk::ScopedAStatus status = bridge->remote->getFloatProperty(propId, areaId, &value, &ok);
    float result = (status.isOk() && !value.empty()) ? value[0] : 0.0f;
    ALOGD("nativeGetFloatProperty: propId=%d areaId=%d value=%f ok=%d", propId, areaId, result, ok);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeSetBoolProperty(JNIEnv* /*env*/,
                                                                              jobject /*thiz*/,
                                                                              jlong handle,
                                                                              jint propId,
                                                                              jint areaId,
                                                                              jboolean value) {
    VhalBridge* bridge = bridgeFromHandle(handle);
    if (bridge == nullptr || bridge->remote == nullptr) {
        ALOGW("nativeSetBoolProperty: null bridge/remote, propId=%d", propId);
        return JNI_FALSE;
    }
    bool ok = false;
    ndk::ScopedAStatus status =
            bridge->remote->setBoolProperty(propId, areaId, value == JNI_TRUE, &ok);
    ok = status.isOk() && ok;
    ALOGD("nativeSetBoolProperty: propId=%d areaId=%d value=%d ok=%d", propId, areaId,
          value == JNI_TRUE, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_kpit_comfort_base_service_AllianceCarBaseService_nativeGetBoolProperty(JNIEnv* /*env*/,
                                                                              jobject /*thiz*/,
                                                                              jlong handle,
                                                                              jint propId,
                                                                              jint areaId) {
    VhalBridge* bridge = bridgeFromHandle(handle);
    if (bridge == nullptr || bridge->remote == nullptr) {
        ALOGW("nativeGetBoolProperty: null bridge/remote, propId=%d", propId);
        return JNI_FALSE;
    }
    std::vector<bool> value;
    bool ok = false;
    ndk::ScopedAStatus status = bridge->remote->getBoolProperty(propId, areaId, &value, &ok);
    bool result = (status.isOk() && !value.empty()) ? value[0] : false;
    ALOGD("nativeGetBoolProperty: propId=%d areaId=%d value=%d ok=%d", propId, areaId, result, ok);
    return result ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
