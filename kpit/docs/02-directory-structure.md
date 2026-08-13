# II. Directory Structure

Status legend: ✅ done · ⏳ todo (partial/placeholder exists) · ❌ not created.
Detailed notes for each item are in [03-implementation-status.md](03-implementation-status.md), referenced by number.

```
vendor/kpit/automotive/
├── hmi/
│    ├── hvac_app/                            ✅ compiles (see 03-implementation-status.md #9 for fixes applied)
│    │    ├── Android.bp
│    │    ├── AndroidManifest.xml
│    │    ├── res/ (drawable, layout, values, xml, mipmap)
│    │    └── src/com/kpit/hmi/hvac/
│    │         ├── HvacActivity.java
│    │         ├── model/ (4 state classes)
│    │         └── viewmodel/HvacViewModel.java
│    └── bluetooth_app/                       ✅ compiles
│         ├── Android.bp, AndroidManifest.xml
│         ├── res/ (drawable, layout/activity_main.xml, values, xml, mipmap)
│         └── src/com/kpit/hmi/bluetooth/
│              ├── MainActivity.java
│              └── viewmodel/BluetoothViewModel.java
│
├── service/                                  (singular "service", not "services")
│    ├── comfort/
│    │    ├── base/                           ✅ done  ("base", not "base_comfort")
│    │    │    ├── Android.bp, AndroidManifest.xml
│    │    │    ├── jni/base_comfort_vhal_jni.cpp
│    │    │    └── src/.../base/{manager/AllianceCarBaseManager, service/AllianceCarBaseService}.java
│    │    ├── hvac/                           ✅ done
│    │    │    ├── Android.bp                 (split: hvac-manager-sdk + hvac-service)
│    │    │    ├── AndroidManifest.xml
│    │    │    ├── aidl/.../hvac/{HvacEvent, IHVACVehicleCallback, IHVACVehicleService}.aidl
│    │    │    └── src/.../hvac/
│    │    │         ├── HvacEvent.java
│    │    │         ├── manager/ (HvacListener, AllianceCarHvacManager, HvacProperty, IHvacController, SystemListener)
│    │    │         └── service/AllianceCarHvacService.java
│    │    └── seat/                           ⏳ todo
│    │         ├── Android.bp                 ✅ exists but fully commented-out placeholder
│    │         ├── AndroidManifest.xml        ❌ not created
│    │         ├── aidl/.../seat/             ❌ not created (SeatEvent, ISeatVehicleCallback, ISeatVehicleService)
│    │         └── src/                       ❌ not created (SeatEvent, manager/*, service/SeatService)
│    └── connectivity/
│         ├── base/                           ✅ done  ("base", not "base_connectivity")
│         │    ├── Android.bp, AndroidManifest.xml
│         │    └── src/.../base/{manager/BaseConnectivityManager, service/BaseConnectivityService}.java
│         ├── bluetooth/                      ✅ done  (see 05-bluetooth-architecture.md)
│         │    ├── Android.bp                 (split: bluetooth-manager-sdk + bluetooth-service)
│         │    ├── AndroidManifest.xml
│         │    ├── aidl/.../bluetooth/
│         │    │    ├── IIviBluetoothListener.aidl, IIviBluetoothService.aidl
│         │    │    └── manager/{BluetoothDeviceInfo, MediaPlaybackInfo}.aidl
│         │    └── src/.../bluetooth/
│         │         ├── manager/ (BluetoothDeviceInfo, MediaPlaybackInfo, MediaAction,
│         │         │             IviPlaybackState, BluetoothListener, IviBluetoothManager)
│         │         └── service/IviBluetoothService.java
│         └── wifi/                           ❌ not created (directory doesn't exist on disk)
│
└── vps/                                      HVAC portion ✅ done
      ├── Android.bp                          cc_library_shared "libvps" (vendor: true) + cc_test "libvps_test", see 10-build-and-product-integration.md
      ├── include/ (IVpsHandler, VpsDispatcher, HvacHandler, IHvacBackend, FakeHvacBackend,
      │             VpsPropConfig, VpsPropertyId)              [SeatHandler.h ❌ not created]
      ├── src/ (VpsDispatcher.cpp, HvacHandler.cpp, FakeHvacBackend.cpp, VpsPropConfig.cpp)
      │                                                        [SeatHandler.cpp ❌ not created]
      ├── tests/ (HvacHandlerTest.cpp, VpsDispatcherTest.cpp)  libvps_test, see 03-implementation-status.md #13
      ├── aidl/vendor/kpit/vps/ (IVpsService.aidl, IVpsCallback.aidl)  aidl_interface "vendor.kpit.vps",
      │                                                        Stage 4, see 03-implementation-status.md #13
      └── service/                             cc_binary "vendor.kpit.vps-service" (vendor: true) — hosts
           (VpsServiceImpl.{h,cpp}, service_main.cpp,           VpsDispatcher/HvacHandler behind the AIDL
            vps-service.rc, vps-service.xml,                    interface above; base_comfort_vhal_jni.cpp
            Android.bp)                                         is its Binder client. Stage 4, not build/
                                                                  boot verified — see 03-implementation-status.md #13
```

Sibling of `automotive/` and `docs/` under `vendor/kpit/` (not nested inside the tree above, and
not Soong-built — no `Android.bp` anywhere under it):

```
vendor/kpit/
└── tools/
     └── vspManagerTool/                       ✅ done, see 03-implementation-status.md #5
          ├── README.md, build.bat, build.sh   plain javac+jar, zero external dependencies
          ├── src/com/kpit/vspmanager/
          │    ├── Main.java
          │    ├── adb/ (AdbClient, AdbException)
          │    ├── model/ (HvacProperty, PropertySnapshot, DumpResult, SetResult)
          │    ├── parse/ (DumpParser)
          │    ├── poll/ (PropertyPoller, PollListener)
          │    └── ui/ (MainFrame, PropertyTableModel, HistoryLogPane)
          └── test/com/kpit/vspmanager/parse/ (DumpParserTest)
```
