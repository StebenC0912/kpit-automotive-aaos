// IVpsCallback.aidl
package vendor.kpit.vps;

// Push side of the vps HAL boundary (VHAL-alignment Stage 4, kpit/docs/03-implementation-status.md
// item 13) -- replaces the in-process VpsEventCallback lambda IVpsService::subscribe() used to take
// when VpsDispatcher/HvacHandler lived in the same process as the JNI caller. oneway since this is
// a fire-and-forget push from the vendor.kpit.vps-service daemon back into whichever client process
// (hvac-service today) is subscribed, mirroring IHVACVehicleCallback's oneway convention on the
// Java AIDL side (kpit/docs/13-aidl-callback-threading.md).
interface IVpsCallback {
    oneway void onPropertyEvent(int propId, int areaId);
}
