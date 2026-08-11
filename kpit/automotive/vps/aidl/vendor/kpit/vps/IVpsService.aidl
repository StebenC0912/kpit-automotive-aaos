// IVpsService.aidl
package vendor.kpit.vps;

import vendor.kpit.vps.IVpsCallback;

// Binder HAL surface for VHAL-alignment Stage 4 (kpit/docs/03-implementation-status.md item 13):
// mirrors vps::VpsDispatcher's public C++ methods 1:1 (see vps/include/VpsDispatcher.h), now
// crossing a real process boundary instead of being called in-process from
// base_comfort_vhal_jni.cpp. Implemented by vendor.kpit.vps-service (vps/service/VpsServiceImpl),
// running on /vendor; base_comfort_vhal_jni.cpp is this interface's only client today.
//
// AIDL has no scalar out-parameter -- a single-element out array is the standard idiom for pairing
// a value with the "did this succeed" boolean VpsDispatcher's own get*Property methods return.
interface IVpsService {
    boolean getIntProperty(int propId, int areaId, out int[] value);
    boolean setIntProperty(int propId, int areaId, int value);
    boolean getFloatProperty(int propId, int areaId, out float[] value);
    boolean setFloatProperty(int propId, int areaId, float value);
    boolean getBoolProperty(int propId, int areaId, out boolean[] value);
    boolean setBoolProperty(int propId, int areaId, boolean value);

    boolean subscribe(int propId, int areaId, float sampleRateHz, IVpsCallback callback);
    void unsubscribe(int propId);
}
