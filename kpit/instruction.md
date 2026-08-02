# SYSTEM ARCHITECT DIRECTION: 3-TIER DECOUPLED AAOS CUSTOM SYSTEM SERVICES, MODULAR C++ VPS, & MVVM HMI

Act as a **Senior System Architect specializing in Android Automotive OS (AAOS) and AOSP source code**.

Task: implement 100% of the source code (Java Activities, ViewModels, Layout XMLs, SDK Managers,
Services, AIDL, C++, JNI, `Android.bp`, `AndroidManifest.xml`) for a 3-tier decoupled architecture at:

`[AOSP_ROOT]/vendor/kpit/automotive/`

---

## I. ARCHITECTURE OVERVIEW

Three decoupled components, two domains (**Comfort**, **Connectivity**):

1. **HMI Apps** — `hmi/` — MVVM UI apps (`hvac_app`, `bluetooth_app`).
   Activity never talks to the Manager SDK directly — always through a `ViewModel`.
   Activity only renders UI and observes `LiveData`/`StateFlow`.
   ViewModel owns the Manager instance, listener lifecycle, and state — has zero knowledge of JNI/HAL/Service internals.

2. **Services & Managers** — `service/` — combined System Service + Manager SDK per domain.
   - `service/comfort/` — `base` (shared plumbing), `hvac` (✅ done), `seat` (⏳ todo)
   - `service/connectivity/` — `base` (shared plumbing), `bluetooth` (✅ done), `wifi` (⏳ todo)
   - Bluetooth is the odd one out: no VHAL property exists for MAC address/device name/HFP-A2DP-AVRCP
     state, so it skips `vps/`/JNI entirely. HFP/A2DP call Android's hidden Bluetooth profile
     proxies directly (`BluetoothHeadsetClient`, `BluetoothA2dpSink`); AVRCP calls the
     profile-agnostic `MediaSessionManager`/`MediaController` instead, since `BluetoothAvrcpController`
     is unreachable outside the Bluetooth mainline module in this AOSP version. Full rationale in
     section V.

3. **VPS (Vehicle Platform Service)** — `vps/` — C++ `libvps.so`.
   Polymorphic `IVpsHandler` interface; `HvacHandler`/`SeatHandler` each implement it;
   `VpsDispatcher` routes by property ID.

---

## II. DIRECTORY STRUCTURE

Status legend: ✅ done · ⏳ todo (partial/placeholder exists) · ❌ not created.
Detailed notes for each item are in **section III** below, referenced by number.

```
vendor/kpit/automotive/
├── hmi/
│    ├── hvac_app/                            ✅ compiles (see III.8 for fixes applied)
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
│    │    │    └── src/.../base/{manager/BaseComfortManager, service/BaseComfortService}.java
│    │    ├── hvac/                           ✅ done
│    │    │    ├── Android.bp                 (split: hvac-manager-sdk + hvac-service)
│    │    │    ├── AndroidManifest.xml
│    │    │    ├── aidl/.../hvac/{HvacEvent, IHVACVehicleCallback, IHVACVehicleService}.aidl
│    │    │    └── src/.../hvac/
│    │    │         ├── HvacEvent.java
│    │    │         ├── manager/ (HvacListener, HvacManager, HvacProperty, IHvacController, SystemListener)
│    │    │         └── service/HvacService.java
│    │    └── seat/                           ⏳ todo
│    │         ├── Android.bp                 ✅ exists but fully commented-out placeholder
│    │         ├── AndroidManifest.xml        ❌ not created
│    │         ├── aidl/.../seat/             ❌ not created (SeatEvent, ISeatVehicleCallback, ISeatVehicleService)
│    │         └── src/                       ❌ not created (SeatEvent, manager/*, service/SeatService)
│    └── connectivity/
│         ├── base/                           ✅ done  ("base", not "base_connectivity")
│         │    ├── Android.bp, AndroidManifest.xml
│         │    └── src/.../base/{manager/BaseConnectivityManager, service/BaseConnectivityService}.java
│         ├── bluetooth/                      ✅ done  (see section V)
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
      ├── Android.bp                          cc_library_shared "libvps", vendor:true
      ├── include/ (IVpsHandler, HvacHandler, VpsDispatcher)   [SeatHandler.h ❌ not created]
      └── src/ (HvacHandler.cpp, VpsDispatcher.cpp)             [SeatHandler.cpp ❌ not created]
```

---

## III. IMPLEMENTATION STATUS & NOTES

### ✅ HVAC domain (Comfort) — complete
1. `hvac_app` — full MVVM UI, `LiveData` state, all resources present.
2. `HvacManager` — extends `BaseComfortManager<IHVACVehicleService>`.
3. `HvacService` — extends `BaseComfortService<IHVACVehicleCallback>`.
4. AIDL: `HvacEvent`, `IHVACVehicleCallback`, `IHVACVehicleService`.
5. Helpers: `HvacListener`, `SystemListener`, `HvacProperty`, `IHvacController`.
6. `BaseComfortService`/`BaseComfortManager` — JNI bridge foundation.
7. VPS C++ (HVAC): `IVpsHandler`/`VpsDispatcher`/`HvacHandler`; JNI calls `VpsDispatcher` in-process (VI.7).
8. Comfort permissions wired end-to-end for `base` and `hvac` (VI.4/VI.6).
9. Four build issues hit and fixed along the way — see VI.8–VI.10, VI.13.
10. Pressed/activated-state UI feedback wired for all `hvac_app` controls — see VI.11.

### ✅ Bluetooth domain (Connectivity) — complete
1. `IviBluetoothManager` — extends `BaseConnectivityManager<IIviBluetoothService>`, same
   `ServiceManager.getService()` pattern as `HvacManager`.
2. `IviBluetoothService` — extends `BaseConnectivityService<IIviBluetoothListener>`; owns two profile
   proxies (HFP/A2DP) plus a `MediaSessionManager`/`MediaController` attachment for AVRCP (build
   lesson, section V), each eagerly synced on connect per rule VI.5.
   `connect()`/`disconnect()` (the ViewModel-facing API) drive HFP+A2DP together via
   `setConnectionPolicy()` — not the profile proxies' own `connect()`/`disconnect()` methods, which
   are unreachable from outside the Bluetooth mainline module (build lesson, VI.14); `sendMediaCommand()`
   maps `MediaAction` → `MediaController.TransportControls` calls (`play()`/`pause()`/`skipToNext()`/
   `skipToPrevious()`).
3. AIDL: `IIviBluetoothService`, `IIviBluetoothListener` (oneway callbacks, rule VI.2).
4. Parcelables: `BluetoothDeviceInfo` (MAC/name/profile bitmask), `MediaPlaybackInfo` (title/artist/album/state/position).
5. Helpers: `BluetoothListener`, `MediaAction`, `IviPlaybackState`.
6. `BaseConnectivityService`/`BaseConnectivityManager` — no JNI; lifecycle hooks
   `onConnectivitySourceConnect()`/`Disconnect()` bake in rule VI.5's eager resync.
7. Connectivity permissions wired end-to-end; `bluetooth-service` built with `platform_apis: true`
   for hidden profile API access (VI build note).
8. `bluetooth_app` — full MVVM UI: `MainActivity` renders connection state (device name/MAC,
   HFP/A2DP/AVRCP badges dimmed via `isConnectedOn()`) and media playback (title/artist/album,
   state, position, transport buttons) purely via `findViewById` + `LiveData` observers, never
   touching `IviBluetoothManager` directly. `BluetoothViewModel` registers with
   `IviBluetoothManager` in its constructor, implements `BluetoothListener`, exposes four
   `LiveData` streams (device info, media metadata, playback state, position), routes
   `play()/pause()/next()/previous()` through `sendMediaCommand(MediaAction.*)`, and unregisters
   in `onCleared()` — same MVVM shape as `HvacViewModel`. Same privileged/platform-signed posture
   as `hvac_app` (`sharedUserId="android.uid.system"`, `certificate: "platform"`,
   `privileged: true`, `platform_apis: true`), since it calls `IviBluetoothManager` which resolves
   `IviBluetoothService` via `ServiceManager.getService()` (VI.6).

### Package layout quick reference
| Area                        | Path                                                                        |
|-----------------------------|-----------------------------------------------------------------------------|
| HVAC manager / service      | `com.kpit.hvac.manager` / `com.kpit.hvac.service`                           |
| Comfort base                | `com.kpit.comfort.base.manager` / `.service`                                |
| Connectivity base           | `com.kpit.connectivity.base.manager` / `.service` (mirrors Comfort's split) |
| Bluetooth manager / service | `com.kpit.bluetooth.manager` / `com.kpit.bluetooth.service`                 |
| Bluetooth AIDL interfaces   | `com.kpit.bluetooth` root (`IIviBluetoothService`, `IIviBluetoothListener`) |
| Bluetooth AIDL parcelables  | `com.kpit.bluetooth.manager` (`BluetoothDeviceInfo`, `MediaPlaybackInfo`)   |
| HMI (hvac_app)              | `com.kpit.hmi.hvac.viewmodel` / `.model`                                    |
| HMI (bluetooth_app)         | `com.kpit.hmi.bluetooth` (`MainActivity`) / `.viewmodel`                    |

### ⏳ Still to implement
1. **Seat** — AIDL (`ISeatVehicleService`/`ISeatVehicleCallback`), `SeatManager`/`SeatService`
   extending the Comfort base classes, `AndroidManifest.xml`, `SeatEvent` parcelable.
2. **WiFi** — placeholder inheriting `BaseConnectivityManager`/`BaseConnectivityService`, mirrors
   Bluetooth's pattern; signal set not yet specified.
3. **VPS Seat handler** — `SeatHandler` implementing `IVpsHandler`, registered in `VpsDispatcher`,
   once the Seat Java layer exists.

---

## IV. BI-DIRECTIONAL SIGNAL FLOW

Both domains share the same HMI→ViewModel→Manager→Service (and reverse) shape, diverging only at
the Service: Comfort continues into VPS/JNI, Connectivity stops at an OS API call. Section V has
the full Bluetooth write-up; this is the general shape for both.

### Path 1 — Command (HMI → ViewModel → Manager → Service → domain-specific sink)
1. Activity calls a ViewModel method (e.g. `hvacViewModel.toggleAc()`, `bluetoothViewModel.connect(mac)`).
2. ViewModel calls the matching Manager method (e.g. `hvacManager.setAcState(true)`).
3. Manager SDK sends it over Binder via the AIDL interface to the Service.
4. The Service receives the semantic call. What happens next depends on the domain:
   - **Comfort (HVAC/Seat):** translates it into `setProperty(int propertyId, Object value)`, dispatching
     to `nativeSetBooleanProperty`/`nativeSetIntProperty` by the runtime type of `value`.
   - **Connectivity (Bluetooth/WiFi):** calls the relevant Android framework API directly — e.g.
     `IviBluetoothService` calls `BluetoothHeadsetClient`/`BluetoothA2dpSink`'s `connect()`/`disconnect()`,
     or a `MediaController.TransportControls` method for media keys (section V). No JNI, no `setProperty()`.
5. **Comfort only:** JNI passes propertyId + value to the C++ `VpsDispatcher`, which routes to
   `HvacHandler`/`SeatHandler` by property ID. Connectivity has no equivalent step — the framework API
   call in step 4 is the final hop (section I.2, section V).

### Path 2 — Event (domain-specific source → Service → Manager → ViewModel → HMI)
1. The signal originates differently per domain:
   - **Comfort:** the ECU triggers an event; the C++ Handler captures it and invokes a JNI callback into
     the Service's generic `onChangeEvent(int propertyId, Object value)`.
   - **Connectivity:** Android's Bluetooth stack pushes the event directly into the Service — a
     `BroadcastReceiver` for HFP/A2DP (`ACTION_CONNECTION_STATE_CHANGED`), a `MediaController.Callback`
     for AVRCP (section V) — no JNI, no ECU involved.
2. The Service maps the raw signal to the semantic AIDL listener call — e.g. `onAcStateChanged(boolean)`
   for Comfort, `onDeviceConnectionChanged(...)` for Connectivity — and fans it out via
   `RemoteCallbackList` (shared by `BaseComfortService`/`BaseConnectivityService`).
3. The Manager's AIDL stub receives the callback and forwards it to the registered ViewModel listener.
4. ViewModel updates state via `liveData.postValue(...)` (thread-safe).
5. Activity, observing `LiveData`, updates the UI on the main thread.

### Domain properties

**HVAC:**
| Signal                  | Command                       | Event                               |
|-------------------------|-------------------------------|-------------------------------------|
| AC state (bool)         | `setAcState(boolean)`         | `onAcStateChanged(boolean)`         |
| Temperature (int)       | `setTemperature(int)`         | `onTemperatureChanged(int)`         |
| Seat heater level (int) | `setSeatHeaterLevel(int)`     | `onSeatHeaterLevelChanged(int)`     |
| Seat ventilation (bool) | `setSeatVentilation(boolean)` | `onSeatVentilationChanged(boolean)` |

**Bluetooth** (no VHAL/JNI — see section V):
| Signal            | Command                              | Event                                                     |
|-------------------|--------------------------------------|-----------------------------------------------------------|
| Device connection | `connect`/`disconnect(String mac)`   | `onDeviceConnectionChanged(BluetoothDeviceInfo, boolean)` |
| Device identity   | — (carried in `BluetoothDeviceInfo`) | same event as above                                       |
| Media command     | `sendMediaCommand(int action)`       | `onPlaybackStateChanged(int state, long positionMs)`      |
| Media metadata    | — (read-only)                        | `onMediaMetadataChanged(MediaPlaybackInfo)`               |

---

## V. BLUETOOTH ARCHITECTURE (NO VHAL/JNI)

**Use case:** show phone connection state (MAC, name) and now-playing info (title/artist/state) over
HFP/A2DP/AVRCP.

**Why it skips `vps/`:** HVAC/Seat go through VPS because they're real vehicle signals only the
Vehicle HAL can provide. Bluetooth pairing state and AVRCP metadata are owned by Android's own
Bluetooth stack, not the vehicle — so "Component 3" here is Android's Bluetooth framework APIs
called directly from `IviBluetoothService`, not C++/JNI/VHAL.

**Chosen tier:** HFP/A2DP use the hidden/system profile proxies (`BluetoothHeadsetClient`,
`BluetoothA2dpSink`) — `@SystemApi`/`@hide`, gated by `BLUETOOTH_PRIVILEGED`. AVRCP uses
`MediaSessionManager`/`MediaController`, not `BluetoothAvrcpController` — reversed from the
original plan, per the build lesson below.

**Build lesson — `BluetoothAvrcpController` is unreachable in this AOSP version (2026-07-31):**
the original plan used it as a third profile proxy, same tier as HFP/A2DP. It doesn't compile:
unlike `BluetoothHeadsetClient`/`BluetoothA2dpSink` (both `@SystemApi`), `BluetoothAvrcpController`
carries no `@SystemApi` annotation — just a `{@hide}` javadoc tag. Bluetooth is a mainline module
(APEX), and a non-`@SystemApi` class is module-internal only, excluded from every stub jar outside
that module (`platform_apis` included) — genuinely unreachable from `vendor/kpit`. Its assumed
method set also doesn't exist on it here: no `sendPassThroughCmd()`/`getCurrentMetadata()`/
`getPlaybackState()`.

Fix: `IviBluetoothService` reaches AVRCP via `MediaSessionManager`/`MediaController` instead — fully
public SDK API, gated only by the `MEDIA_CONTENT_CONTROL` signature|privileged permission, which
`bluetooth-service` qualifies for since it's system-signed. The Bluetooth module's own
`BluetoothMediaBrowserService` (package `com.android.bluetooth`) publishes the AVRCP-backed
session; `IviBluetoothService` finds it via `getActiveSessions()` filtered on
`getPackageName().equals("com.android.bluetooth")`, then drives it with `MediaController.Callback`
(metadata/playback state) and `TransportControls` (`play()`/`pause()`/`skipToNext()`/
`skipToPrevious()`). Consequence: AVRCP has no independent `getConnectedDevices()`, so its
`PROFILE_AVRCP` badge bit rides along with `PROFILE_A2DP` wherever A2DP connection state syncs —
matches the "AVRCP rides on the A2DP link" note already in `BluetoothDeviceInfo`'s class doc.

**Build lesson — `connect()`/`disconnect()` on the HFP/A2DP proxies are unreachable too, at the
method level (2026-07-31):** `IviBluetoothService.connectDevice()`/`disconnectDevice()` originally
called `mHfpProxy.connect(device)`/`mA2dpProxy.connect(device)` directly and failed with `cannot
find symbol`, even though `getConnectedDevices()`/`setConnectionPolicy()` on the same classes
compiled fine. Root cause: `connect(BluetoothDevice)`/`disconnect(BluetoothDevice)` carry `@hide`
(some overloads also `@UnsupportedAppUsage(maxTargetSdk = R)`) but no `@SystemApi`, so they're
stripped from the mainline module's exported stub the same way as `BluetoothAvrcpController` above
— just at the method level instead of the whole class. `getConnectedDevices()` survives because it
overrides a public method declared on the `BluetoothProfile` interface; `connect()`/`disconnect()`
have no such fallback. Fix: `connectDevice()`/`disconnectDevice()` now call `setConnectionPolicy(
device, BluetoothProfile.CONNECTION_POLICY_ALLOWED)` / `CONNECTION_POLICY_FORBIDDEN` instead — both
`@SystemApi`, gated by the same `BLUETOOTH_CONNECT`+`BLUETOOTH_PRIVILEGED` pair. A paired device
auto-connects once its policy is `ALLOWED`, and `ACTION_CONNECTION_STATE_CHANGED` still fires the
same way, so the rest of the command/event flow (section IV) is unchanged.

**Car-side profile roles invert from the phone's:**
| Profile | Phone's role  | Car's role | Car-side API                                                                         |
|---------|---------------|------------|--------------------------------------------------------------------------------------|
| HFP     | Audio Gateway | Hands-Free | `BluetoothHeadsetClient`                                                             |
| A2DP    | Source        | Sink       | `BluetoothA2dpSink`                                                                  |
| AVRCP   | Target        | Controller | `MediaSessionManager`/`MediaController` (see above — not `BluetoothAvrcpController`) |

**Vehicle vs. Bluetooth signal path, side by side:**
| Step       | Vehicle (Hvac/Seat)                                      | Bluetooth                                                                                                            |
|------------|----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| Dispatch   | JNI → `VpsDispatcher` (in-process, VI.7) → `HvacHandler` | `IviBluetoothService` → `BluetoothAdapter` (HFP/A2DP) + `MediaSessionManager` (AVRCP)                                |
| Read/write | `nativeGet/SetFloatProperty`                             | `getConnectedDevices()` (HFP/A2DP); `MediaController.getMetadata()`/`getPlaybackState()`/`TransportControls` (AVRCP) |
| Push       | ECU → JNI callback → `onVehiclePropertyChanged`          | `BroadcastReceiver` (HFP/A2DP) / `MediaController.Callback` (AVRCP) → same Service→Manager→ViewModel→HMI fan-out     |

### Protocol primer — what HFP/A2DP/AVRCP each carry
Three separate profiles, three separate jobs — none subsumes another:

| Profile   | Transport                 | Carries                                      | Codec                                |
|-----------|---------------------------|----------------------------------------------|--------------------------------------|
| **HFP**   | SCO/eSCO + AT over RFCOMM | Voice-call audio (mono) + call control       | CVSD / mSBC                          |
| **A2DP**  | AVDTP over L2CAP          | Streaming music (stereo), one-way phone→car  | SBC (mandatory), AAC/aptX (optional) |
| **AVRCP** | AVCTP over L2CAP          | Transport control + track metadata, no audio | n/a                                  |

`IviBluetoothService` never touches SCO/RFCOMM/AVDTP/L2CAP/HCI directly — Android's Bluetooth stack
(Fluoride/Bluedroid) implements that once, system-wide, and exposes it via the `BluetoothHeadsetClient`/
`BluetoothA2dpSink` proxies (HFP/A2DP) and `MediaSessionManager`/`MediaController` (AVRCP, see above).

### Data model
HVAC's single-primitive `setProperty(int, Object)` + `HvacEvent{id, areaId, value}` shape doesn't fit
Bluetooth signals, which carry identity and text. Two dedicated parcelables live under
`service/connectivity/bluetooth/aidl/com/kpit/bluetooth/manager/` (the `bluetooth/` root above it is
reserved for the two service/listener AIDL interfaces only):
- **`BluetoothDeviceInfo`**: `macAddress`, `deviceName`, `connectionState` (int bitmask: HFP|A2DP|AVRCP)
- **`MediaPlaybackInfo`**: `title`, `artist`, `album`, `playbackState`, `positionMs`

The general command/event shape (including how it diverges from Comfort's VPS/JNI path) is in
section IV. Both directions still run off the main thread here too (rule VI.1).

### Fixing "not sync" (rule VI.5)
Symptom: service only listens for *future* broadcasts/callbacks, never asks the OS for *current*
state on start — so it shows stale state for anything already connected/playing before the service
started. Fix: in `BluetoothProfile.ServiceListener#onServiceConnected()`, immediately call
`getConnectedDevices()` (HFP/A2DP); for AVRCP, `attachToBluetoothSession()` calls
`mMediaSessionManager.getActiveSessions(null)` once up front (in addition to registering
`addOnActiveSessionsChangedListener()` for future changes) and immediately pulls
`MediaController.getMetadata()`/`getPlaybackState()` for whatever session it finds — both before
relying on incremental broadcasts/callbacks.

### Required permissions
- `BLUETOOTH_PRIVILEGED` (signature|privileged) — required to obtain the HFP/A2DP profile proxies
  at all and call their hidden methods. Being system-signed makes the *signature* half of
  `signature|privileged` match, but a priv-app additionally needs an explicit
  `privapp-permissions` allowlist entry for any `signature|privileged` permission — see the
  correction below.
- `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` (Android 12+ runtime permissions) — basic adapter/device ops.
- `MEDIA_CONTENT_CONTROL` (signature|privileged) — required for AVRCP's `MediaSessionManager`/
  `MediaController` path (see above).
- Declared in `bluetooth/AndroidManifest.xml`, layered per the general/domain-specific split in VI.4.
- **Correction (2026-08-02):** this section previously claimed `MEDIA_CONTENT_CONTROL` needed no
  `privapp-permissions` allowlist entry because the service is system-signed — that was wrong and
  caused a real boot failure. Being system-signed only satisfies the *signature* half of a
  `signature|privileged` permission; the `|privileged` half is checked independently and requires
  an explicit `<permission name="..."/>` entry in a `privapp-permissions` XML for that package,
  with no exception for system-signed apps. Without it, `PackageManagerService.systemReady()`
  throws `IllegalStateException("Signature|privileged permissions not in privapp-permissions
  allowlist: ...")`, which crashes `system_server` at boot — visible as `system_server` restarting
  every ~5s in `logcat` (`AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: main`) and the
  guest hanging on the boot animation forever, never setting `sys.boot_completed`. Both
  `BLUETOOTH_PRIVILEGED` and `MEDIA_CONTENT_CONTROL` are allowlisted in
  `privapp_permissions_bluetooth.xml` (VI.4/X below); `MEDIA_CONTENT_CONTROL` was missing from it
  until this fix even though it was already declared in the manifest, because it was added to the
  manifest during the AVRCP rework without updating the allowlist file to match.

### Build note
HFP/A2DP (`BluetoothHeadsetClient`/`BluetoothA2dpSink`) are the same category as
`ServiceManager.addService/getService` (VI.6) — need `platform_apis: true`, not an `sdk_version`
stub, because those proxy classes are hidden `@SystemApi`. AVRCP's `MediaSessionManager`/
`MediaController` path is fully public SDK API and needs no special sdk handling on its own —
`platform_apis: true` stays only because HFP/A2DP (and `ServiceManager`, VI.6) still need it.

---

## VI. TECHNICAL & SYSTEM REQUIREMENTS

1. **ANR prevention:** Services use an `ExecutorService` or `HandlerThread` for Binder IPC, JNI calls,
   and property updates — never on the main thread. All `onChangeEvent` calls from JNI run on this
   background thread too.
2. **AIDL callbacks:** every listener method must be `oneway` to avoid blocking the IPC thread.
3. **Boot:** system services declare `android:persistent="true"` and `sharedUserId="android.uid.system"`.
4. **Permissions — general vs. domain-specific:**
   - `service/comfort/base/AndroidManifest.xml` declares the domain-wide permissions:
     `BIND_COMFORT_SERVICE` (bind) and `ACCESS_COMFORT_SERVICE` (call), both `signature`-level.
   - Each concrete domain (e.g. `hvac/AndroidManifest.xml`) adds its own on top, e.g.
     `BIND_HVAC_SERVICE`, applied to that domain's `<service>` via `android:permission`.
   - `sharedUserId`/`persistent` (rule 3) live in the domain manifest, not `base/` — `base` produces no APK.
   - Build wiring: `base-comfort-manager`/`-service` are plain `java_library` (no manifest). The
     manifest lives in a separate manifest-only `android_library`, `base-comfort-permissions`, pulled
     in via `static_libs` by any domain `android_app` (e.g. `hvac-service`) so Soong actually merges it in.
5. **Connectivity state sync on (re)bind:** any Connectivity service must eagerly query current state
   the moment it (re)connects — never rely solely on future broadcasts, or it shows stale state after a
   restart/reboot/app-install-while-already-paired. See section V for the Bluetooth-specific fix.
6. **Manager IPC via `ServiceManager` (registry lookup, not bind):**
   - `HvacManager` extends `BaseComfortManager<IHVACVehicleService>` and resolves the service via
     `ServiceManager.getService("hvac_service")`; `HvacService.onCreate()` publishes itself with
     `ServiceManager.addService(...)` to make that lookup succeed.
   - `getService`/`addService` are restricted `@SystemApi` — any module touching them needs the full
     platform classpath, not a curated `sdk_version` stub. This is transitive: `base-comfort-manager`,
     `hvac-manager-sdk`, `hvac-service`, and `hvac_app` all set `platform_apis: true` and drop
     `sdk_version`. `hvac_app` additionally needs `sharedUserId="android.uid.system"`,
     `certificate: "platform"`, `privileged: true` — same posture as the service itself, since it's the
     process calling `HvacManager` at runtime.
   - Alternative not taken: `bindService()` + a `BIND_HVAC_SERVICE` permission would let `hvac_app` stay
     unprivileged — the standard cross-APK mechanism, tried earlier and reverted to keep `HvacManager`
     on the `BaseComfortManager` hierarchy. Worth revisiting if a non-privileged client is ever needed.
7. **JNI ↔ VPS wiring — in-process call, not a Binder/HAL service:**
   - `base_comfort_vhal_jni.cpp` calls `vps::VpsDispatcher::instance()` directly, in-process, rather
     than binding a real `IVehicle` HAL over Binder. `libbase_comfort_jni` links `libvps` as a
     `shared_libs` dependency.
   - Alternative not taken: making `vps/` a real `IVehicle` AIDL HAL binary. Rejected — it would require
     exactly matching AOSP's real `IVehicle` method set from outside the prebuilt AIDL library, an
     unverifiable and fragile assumption from this vendor tree.
   - `VpsDispatcher` is a process-wide singleton, one per `BaseComfortService` process. It owns no
     domain knowledge — just routes by `propId` to whichever registered `IVpsHandler` claims it.
     Handlers register once per process (`std::call_once` in `nativeInit()`); a process registering an
     irrelevant handler is harmless since each handler only answers the propIds it owns.
   - `HvacHandler` keeps an in-memory store for all 12 `HvacProperties` (kept in sync by hand with
     `HvacProperties.java` — no shared codegen across the JNI boundary). `setProperty()` echoes the new
     value back as an event immediately — that's the only path the Service uses to learn a set took
     effect. `PROP_TEMP_OUTSIDE` also drifts every 5s on a background thread, simulating a real sensor.
8. **Build lesson — ViewBinding unsupported, resource-directory typos:**
   - This tree's Soong has no ViewBinding support at all (`enable_viewbinding`/`enable_view_binding`
     both fail — confirmed by grepping `build/soong/java/*.go` for zero hits). Fixed by using
     `findViewById` in `HvacActivity.java` instead.
   - `res/values/` was misspelled `res/value/` (singular) — an invalid aapt2 resource-type name, so
     `strings.xml`/`dimens.xml`/`colors.xml`/`themes.xml` were silently invisible to the build even
     though the files existed on disk. Renamed to `values/`.
   - `res/mipmap/ic_launcher.xml` existed but was 0 bytes, which fails aapt2 linking the same as if
     missing. Populated as an adaptive-icon XML matching the sibling `ic_launcher_round.xml`.
   - Lesson: "file exists at the expected path" isn't sufficient — also check it's non-empty and its
     directory name is one aapt2 actually recognizes. Soong silently drops anything in a misnamed
     resource directory. Also verify a Bp property actually exists in this tree's Soong sources before
     relying on it — it may not be standard here even if it is elsewhere.
9. **Build lesson — stale Bazel workspace after checkout relocation:**
   - After fixing #8, the build failed with `fork/exec ./build/bazel/bin/bazel: no such file or
     directory` during Soong's Bazel mixed-builds analysis phase.
   - Root cause: Soong's synthetic Bazel workspace (`out/soong/workspace`) had symlinks still pointing
     at the checkout's old absolute path, from before it was relocated. Incremental symlink-forest
     regeneration didn't recreate the missing `build/bazel/bin/` entry.
   - Fix: `rm -rf out/soong/workspace out/soong/bp2build out/bazel out/soong/bazelsocket.sock`, then
     rebuild. `rm -rf out/` also works but costs a full rebuild — use only if the targeted clean fails.
   - Lesson: after any checkout relocation, treat `out/`'s Soong/Bazel symlink forests as suspect —
     this class of failure has nothing to do with `vendor/kpit/` source and won't be fixed by editing it.
10. **Build lesson — `vendor: true` + `platform_apis: true` conflict panics Soong:**
    - `hvac-app`/`hvac-service` both errored with `sdk_version must have a value when the module is
      located at vendor` immediately followed by a Soong-internal panic in `generateJavaUsedByApex` —
      the first error left the module half-initialized, and the second was fallout from that, not an
      independent bug.
    - Root cause: `vendor: true` makes the module `SocSpecific()`, and `RequiresStableAPIs()`
      (`build/soong/android/module.go`) returns true unconditionally for any SoC-specific module —
      not gated by a product variable the way the error text implies. That then demands a real
      `sdk_version`, which conflicts with `platform_apis: true` (needed for `ServiceManager` access, VI.6).
    - Fix: removed `vendor: true` from both modules — nothing in this spec required vendor-partition
      placement; they now install to `/system/priv-app` instead, still privileged/platform-signed.
    - Lesson: don't assume a `PropertyErrorf` message's parenthetical fully describes the guard
      condition — read the actual source, since the real condition here was broader than the message
      suggested.
11. **Full build verified successful (2026-07-30):** after fixes 8–10 above, a full build of
    `vendor/kpit/automotive/` — `hvac_app`, `bluetooth_app`, both `service/comfort` and
    `service/connectivity` trees (HVAC + Bluetooth only; Seat/WiFi not yet implemented), and `vps/`
    (`libvps.so`) — completed with no errors. No outstanding build issues remain for the components
    marked ✅ in sections II/III.
    **Update (2026-07-31):** a later rebuild surfaced two more errors — a `javac` filename mismatch
    and an unreachable Bluetooth API — see VI.13 and VI.14. Both fixed; no outstanding issues as of
    2026-07-31.
    **Update (2026-08-01):** full product build (`m`) re-run after the artifact-path-requirement,
    manifest-XML, AVRCP rework, EdgeToEdge/NonNull/Material-theme, `jni_headers`, x86 `size_t`
    shift-overflow, and sepolicy fixes in section X completed successfully — no errors. This
    confirms the artifact-path allowed-list fix for `libbase_comfort_jni`/`libvps` (previously
    applied-but-unconfirmed) is correct. No outstanding build issues remain as of 2026-08-01.
    Boot-test/logcat verification (section X's "Build & verify commands") not yet performed.
12. **UI lesson — `setActivated()`/`state_pressed` do nothing without a selector, and selector item
    order matters:**
    - `hvac_app`'s `ImageButton`s all rendered a static `android:src` PNG. `View.setActivated()` — already
      called throughout `HvacActivity`'s `LiveData` observers — has zero visual effect unless `src` points
      at a state-list selector instead of a plain drawable. Added `res/drawable/selector_ac.xml`,
      `selector_max.xml`, `selector_cycle.xml`, `selector_sync.xml`, `selector_auto.xml` (keyed on
      `state_activated`, for the toggle buttons that already carry persisted on/off state from the
      ViewModel), plus `selector_temp_up.xml`, `selector_temp_down.xml`, `selector_fan_low.xml`,
      `selector_fan_high.xml` (keyed on native `state_pressed`, for the momentary +/- arrow buttons that
      have no persisted state at all). Repointed the matching `android:src` attributes in
      `activity_main.xml` at these selectors.
    - Two bugs found in `selector_fan_speed.xml`, the one selector that already existed: (1) it referenced
      `@drawable/fan_speed_bar_normal`, but the asset on disk was `fan_speed_bar_normall.png` (typo'd,
      extra `l`) — resource didn't exist, renamed the asset to match. (2) it listed the no-state ("normal")
      `<item>` *before* the `state_activated` item; a `<selector>` walks items top-to-bottom and a bare
      item with no state attributes matches unconditionally, so the activated bar art could never be
      reached regardless of state. Fixed by moving all state-qualified items before the no-state default
      in every selector added here.
    - Six buttons (`btnDefrost`, `btnHeatingLeft`/`btnHeatingRight`, `btnVentilationFoot`/
      `btnVentilationFootAndFace`/`btnVentilationFace`) have only a `_normal.png` in `res/drawable/` — no
      `_pressed` asset exists to build a selector from. Per explicit direction, left their `src` static and
      added an alpha cue instead: `HvacActivity.setToggleAlpha()` sets alpha to `INACTIVE_TOGGLE_ALPHA =
      0.5f` when off and `1.0f` when on, alongside the existing `setActivated()` call, inside
      `renderHvacSystemBelowUi()`.
    - Also fixed an unrelated copy-paste typo found while touching `setPanelInteractivity()`: its
      `hvacControlPanel` array listed `btnTempRightDown` twice instead of once as `btnTempRightUp`, so the
      right temp-up arrow never got disabled/re-enabled with the rest of the panel.
13. **Build lesson — `HvacProperty.java` filename didn't match its public class (2026-07-31):**
    `javac` failed with `class HvacProperties is public, should be declared in a file named
    HvacProperties.java` on `service/comfort/hvac/src/com/kpit/hvac/manager/HvacProperty.java`. The
    file held the constants class referenced everywhere else in the tree — `HvacManager.java`,
    `HvacService.java`, `vps/include/HvacHandler.h`, `vps/src/HvacHandler.cpp` — as `HvacProperties`
    (plural, matching VI.7's own reference to "`HvacProperties.java`"); only the file on disk was
    typo'd singular. Fix: renamed the file to `HvacProperties.java`; the class and every caller were
    already correct, so no source changes were needed. Section II's directory listing still says
    `HvacProperty` in the helper list — that's the pre-existing shorthand name for "the HVAC property
    constants file", not a claim about the literal filename.
14. **Build lesson — `BluetoothHeadsetClient`/`BluetoothA2dpSink` `connect()`/`disconnect()`
    unreachable from `vendor/kpit` (2026-07-31):** full root cause and fix
    (`setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED/FORBIDDEN)`) are written up in section V
    rather than duplicated here — same "mainline module strips non-`@SystemApi` members from its
    exported stub" story as the `BluetoothAvrcpController` lesson above, just applying at the method
    level instead of the whole class.

---

## VII. OUTPUT RULES

1. **No placeholders/abbreviations:** every file you write must be complete and compilable.
   Seat is the one component still marked ⏳ todo (section III) — when asked to implement it, write it
   in full, matching the HVAC pattern; don't leave it partially done once you start it.
2. **MVVM enforcement:** show Manager init + listener registration in the ViewModel's init block,
   cleanup in `onCleared()`, and `Observer` setup in the Activity's `onCreate()`.
3. **`setProperty()`:** show the Service method explicitly handling `Boolean`/`Integer` casting before
   the JNI call.
4. **Data flow summary:** end with a concise recap of the full two-way call chain.

---

## VIII. RUNNING THE EMULATOR (WINDOWS DEV MACHINE)

AVD: `C:\Users\linhk\.android\avd\Automotive_1408p_landscape.avd`

```
emulator -avd Automotive_1408p_landscape -writable-system -no-snapshot
```

- `-writable-system` — makes `/system` and `/vendor` writable (needed for `adb remount`/`adb push`).
- `-no-snapshot` — forces a cold boot every time; disables both snapshot load and save.
- If `emulator` isn't on `PATH`, use the full path instead:
  `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd Automotive_1408p_landscape -writable-system -no-snapshot`

### With kernel + verbose logging (debugging boot issues)
PowerShell (captures the log to a file and still shows it live):
```powershell
emulator -avd Automotive_1408p_landscape -wipe-data -writable-system -no-snapshot -show-kernel -verbose 2>&1 | Tee-Object -FilePath emulator_boot.log
```
cmd.exe (file only, no live view — `Tee-Object` doesn't exist outside PowerShell):
```cmd
emulator -avd Automotive_1408p_landscape -wipe-data -writable-system -no-snapshot -show-kernel -verbose > emulator_boot.log 2>&1
```

- `-wipe-data` — resets `userdata.img` to a clean state before boot. Needed after swapping in a
  newly packaged system image (section X's `pack_emulator.sh`/`emu_img_zip` fix) — stale userdata from
  a previous, differently-partitioned image can itself cause first-stage-mount failures independent of
  whatever image bug is being chased.
- `-show-kernel` — prints kernel boot log (`dmesg`-equivalent) to the console as the guest boots,
  including early-boot messages that happen before `adb logcat` is reachable.
- `-verbose` — enables emulator-side debug logging (AVD/HAX/HVF setup, disk image resolution, GPU
  backend selection, `config.ini` parsing) — useful for diagnosing the `image.sysdir.1`/zip-layout
  issues described below, not just guest kernel issues.
- `2>&1 | Tee-Object -FilePath emulator_boot.log` — merges stderr into stdout (the emulator's
  `-verbose`/`DEBUG` output goes to stderr) and captures both to a file for later `grep`, without
  losing the live console view.

### `PATH` lesson — `emulator.exe` isn't in `platform-tools`/`tools` (2026-08-01)
Adding `D:\Sdk\platform-tools` and `D:\Sdk\tools` to `PATH` is not sufficient — `emulator` is a
separate top-level SDK package (since it was split out of `tools/` several SDK releases ago) and
installs to its own `<sdk_root>\emulator\` directory, e.g. `D:\Sdk\emulator\emulator.exe`. Fix: add
`<sdk_root>\emulator` to `PATH` as well, then open a new shell (PATH changes don't apply to an
already-open PowerShell/cmd session). If `<sdk_root>\emulator\emulator.exe` doesn't exist at all, the
Android Emulator package itself isn't installed — install it via Android Studio → SDK Manager → SDK
Tools tab → "Android Emulator".

### `config.ini` — `image.sysdir.1` and the `images`/`emulator` zip layout
`Automotive_1408p_landscape.avd\config.ini` contains:
```
image.sysdir.1=system-images\android-35-ext15\android-automotive\x86_64\
```
This path is **relative to the SDK root** (`%LOCALAPPDATA%\Android\Sdk`, i.e. `$ANDROID_SDK_ROOT`), not
relative to the `.avd` folder. Leave this line as-is — don't repoint it at wherever the zip was
extracted. Instead, when a downloaded system-image zip unpacks into two sibling folders, `images\` and
`emulator\`, place each one where the SDK already expects it:

1. `emulator\` → copy/merge into `%LOCALAPPDATA%\Android\Sdk\emulator\`, overwriting the existing
   emulator binaries (the bundled emulator is often newer than the SDK Manager's copy and is required
   to boot the `ext15` image).
2. `images\` (contents) → copy into
   `%LOCALAPPDATA%\Android\Sdk\system-images\android-35-ext15\android-automotive\x86_64\`, creating
   that directory chain if needed, so `system.img`/`vendor.img`/etc. land directly inside it (no extra
   nested `images\` folder in between).

After that, `image.sysdir.1` already matches the real location and the AVD should boot without edits.

### Creating a custom AVD hardware profile in Android Studio (to match `sdk_car_x86_64`)
GUI alternative to hand-editing `config.ini` above — useful for a fresh AVD (e.g. on a new machine)
that matches what `sdk_car_x86_64` / `PRODUCT_DEVICE=emulator_car_x86_64` actually builds. Values
below are pulled from the tree: `device/generic/car/common/config.ini` (the file `sdk_car_x86_64.mk`
copies in as the product's `config.ini`) and `device/generic/car/emulator_car_x86_64/BoardConfig.mk`.

1. Android Studio → **Tools → Device Manager** → **Create Virtual Device** (`+`).
2. On **Select Hardware**, click **New Hardware Profile** (not a stock phone/tablet profile).
3. Set these fields, then **Finish**:

   | Field                                    | Value                | Source                                                |
   |-------------------------------------------|----------------------|--------------------------------------------------------|
   | Device Type                              | Automotive           | matches `car_generic_system.mk` target                |
   | Screen resolution                        | 1408 × 792 px        | `skin.name=1408x792`                                  |
   | Density                                  | 160dpi (mdpi)        | `hw.lcd.density=160`                                   |
   | RAM                                      | 4096 MB              | `hw.ramSize=4096`                                      |
   | Has hardware keyboard                    | Yes                  | `hw.keyboard=yes`                                      |
   | Nav / hardware keys                      | None                 | `hw.mainKeys=no`                                       |
   | Front/back camera                        | None                 | `hw.camera.front/back=none`                             |
   | Accelerometer / Gyroscope                | Enabled              | `hw.accelerometer/gyroscope=yes`                        |
   | GPS / proximity / light / other sensors  | Disabled             | not set (or explicitly `no`) in `config.ini`           |
   | Supported device states                  | Landscape only       | AVD is named `*_landscape`, single-orientation skin    |

4. Back on **Select Hardware**, pick the new profile → **Next**.
5. On **System Image**, switch to the **x86 Images** tab (a locally placed custom image won't
   appear under "Recommended"). Select the `android-35-ext15` / Automotive / x86_64 image already
   placed at `...\Sdk\system-images\android-35-ext15\android-automotive\x86_64\` — reopen Device
   Manager first if it doesn't show up, so Studio re-scans `system-images/`. **Next.**
6. On **AVD Configuration**: name it `Automotive_1408p_landscape` (matching convention), startup
   orientation **Landscape**, then under **Show Advanced Settings** confirm RAM = 4096 MB and
   Graphics = **Hardware – GLES 2.0** (matches `hw.gpu.enabled=yes`/`hw.gpu.mode=auto`).
   `disk.dataPartition.size=6G` has no AVD-wizard equivalent (it's a build-time partition size, not
   an emulator setting) — leave Internal Storage at default. **Finish**.
7. Verify: open the generated `<name>.avd\config.ini` and confirm `image.sysdir.1` matches the path
   above (Studio-created AVDs normally get this right automatically).
8. Launch with the same flags as the rest of this section: `-writable-system -no-snapshot`.

---

## IX. HMI KNOWLEDGE-SHARE TOPICS

Framework-side topics for cross-team sharing with the HMI team.

1. **AIDL Callback Threading Rules — Keeping Binder Callbacks Off the Main Thread**
   Manager→ViewModel callbacks arrive over AIDL as `oneway` methods on a background Binder thread, not
   the UI thread. ViewModels must forward this state via `LiveData.postValue()` (never `setValue()` from
   a callback) so updates land safely on the main thread. Covers why listener methods must stay
   non-blocking, and how this pattern is shared across both Comfort (HVAC/Seat) and Connectivity
   (Bluetooth/WiFi) domains.

---

## X. BUILD, PRODUCT INTEGRATION & EMULATOR PACKAGING

Merged in from `claude_master_prompt.md` (removed — this section supersedes it and corrects several
of its stale claims).

### Product wiring (was previously missing entirely)
Nothing in `device/generic/car/` referenced `vendor/kpit/` (confirmed by grepping for `kpit` across
`device/` — zero hits). Fixed:
- `vendor/kpit/automotive/products/kpit_apps.mk` — `PRODUCT_PACKAGES` for `hvac-app`, `hvac-service`,
  `bluetooth-app`, `bluetooth-service`, `libvps`, `libbase_comfort_jni`, and
  `privapp_permissions_bluetooth.xml` (module names, not display names — Soong `name:` fields).
- Inherited via `$(call inherit-product, vendor/kpit/automotive/products/kpit_apps.mk)` from both
  `device/generic/car/aosp_car_x86_64.mk` and `device/generic/car/sdk_car_x86_64.mk`.
- Seat/WiFi have no buildable modules yet (section III), so nothing to add for them until implemented.

**Lunch target → device mapping** (`emulator_car_x86_64` isn't a real `PRODUCT_NAME` anywhere in
this tree — `out/target/product/emulator_car_x86_64` exists because `sdk_car_x86_64.mk` sets
`PRODUCT_DEVICE := emulator_car_x86_64` while `PRODUCT_NAME := sdk_car_x86_64`):
| Lunch combo                 | PRODUCT_DEVICE (→ BoardConfig.mk dir, out/ dir) |
|-----------------------------|-------------------------------------------------|
| `sdk_car_x86_64-userdebug`  | `emulator_car_x86_64`                           |
| `aosp_car_x86_64-userdebug` | `generic_car_x86_64`                            |

### Privapp permissions (needs one file, not two)
Only `bluetooth-service` (`com.kpit.bluetooth`) requests `signature|privileged` **platform**
permissions — `android.permission.BLUETOOTH_PRIVILEGED` and `android.permission.MEDIA_CONTENT_CONTROL`
(section V). Everything else in this tree (`BIND_COMFORT_SERVICE`, `ACCESS_COMFORT_SERVICE`,
`BIND_HVAC_SERVICE`, `BIND_CONNECTIVITY_SERVICE`, `BIND_BLUETOOTH_SERVICE`) is a custom
`signature`-level permission, which auto-grants to same-signature apps and needs no allowlist entry.
So `hvac_app`, `bluetooth_app`, and `hvac-service` need nothing here.
- `vendor/kpit/automotive/service/connectivity/bluetooth/privapp_permissions_bluetooth.xml` —
  allowlists both `BLUETOOTH_PRIVILEGED` and `MEDIA_CONTENT_CONTROL` for `com.kpit.bluetooth`.
  **Boot lesson (2026-08-02):** `MEDIA_CONTENT_CONTROL` was missing from this file from the AVRCP
  rework (section V) up through the first successful full build (VI.11) — a `signature|privileged`
  permission being *declared* in the manifest and the app being *system-signed* are both necessary
  but not sufficient; every such permission also needs its own allowlist entry here regardless of
  signature, or `system_server` throws `IllegalStateException` and crash-loops at boot (full
  writeup in section V's "Correction"). The build itself doesn't catch this — it's a runtime-only
  failure, only visible via `logcat`/`adb shell getprop sys.boot_completed` after the guest boots.
- Registered as a `prebuilt_etc` (`sub_dir: "permissions"`) in that module's `Android.bp`, and added
  to `kpit_apps.mk`'s `PRODUCT_PACKAGES`.

### SEPolicy — reverted; the real fix was partition placement, not a new type (2026-07-31)
`hvac-service` runs in the `system_app` domain — confirmed via `system/sepolicy/private/seapp_contexts`:
`user=system seinfo=platform → domain=system_app` (matches its `sharedUserId="android.uid.system"` +
`certificate: "platform"`). It `System.loadLibrary()`s `libbase_comfort_jni.so`, which links
`libvps.so` (both `cc_library_shared`, were `vendor: true` — installed to `/vendor/lib64`).

First attempt added a narrow `kpit_jni_lib_file` type (`vendor/kpit/automotive/sepolicy/{file.te,
file_contexts, system_app.te}`, wired via `BOARD_SEPOLICY_DIRS` in `kpit_apps.mk`) granting
`system_app` `{ read open getattr execute map }` on it. Still failed: two `neverallow` checks in
`system/sepolicy/{private,public}/domain.te` (`sepolicy_neverallows_vendor`) flatly forbid any
`coredomain` process from touching `/vendor` files at all under Full-Treble, via a fixed, hardcoded
exception list (`crash_dump`, `init`, `kernel`, `crosvm`, `ueventd`, …) — `system_app` isn't on it,
and there's no per-type escape hatch, since the neverallow keys off `coredomain`-vs-vendor-file
access in general, not the specific type. Stronger than "no plain `vendor_file` access" — it's "no
`/vendor` access at all" for any coredomain domain, which `system_app` is.

Root fix: `hvac-service`/`bluetooth-service` are deliberately coredomain system apps (VI.10 already
made this call for the APKs themselves, same Treble reason). A native library a coredomain process
loads in-process therefore has to live on `/system` too — `/vendor` is for vendor-domain processes
(real HALs), which `VpsDispatcher` explicitly isn't (VI.7, "alternative not taken"). Removed
`vendor: true` from `libbase_comfort_jni` (`service/comfort/base/Android.bp`) and `libvps`
(`vps/Android.bp`) — both now install to `/system/lib64`. `system_app` gets read/execute/map on
plain `system_file` for free from existing base policy, so no custom sepolicy is needed at all;
deleted `vendor/kpit/automotive/sepolicy/` and the `BOARD_SEPOLICY_DIRS` line in `kpit_apps.mk`
entirely rather than leaving a now-pointless rule in place.
- Lesson: for a coredomain app's in-process native dependency, check partition placement
  (`vendor: true` or not) before reaching for a custom sepolicy type — a `neverallow` on
  `coredomain`-vs-`/vendor` access in general can't be satisfied by narrowing the *type* being
  accessed, only by not being on `/vendor` at all.

### Boot lesson — `pack_emulator.sh` packaged plain `system.img` instead of `system-qemu.img`, causing a boot loop (2026-08-01)
Symptom: guest boots the kernel, then `init` prints
`partition(s) not found in /sys, waiting for their uevent(s): super, vbmeta`, times out after ~10s,
aborts (`InitFatalReboot: signal 6`), and reboots to `bootloader` — repeating forever. Confirmed via
`-show-kernel -verbose` (this section's emulator commands) piped to a log file.

First attempt (wrong): added `vbmeta.img`/`super.img` to `TOP_LEVEL_FILES` as standalone top-level
files, guessing the emulator's sysdir loader auto-detects them by filename the way it does
`system.img`/`vendor.img`. It doesn't — `-verbose` never emitted a `-vbmeta`/`-super` line even after
re-extracting a zip containing both, and the boot loop was unchanged. Root cause was one level deeper.

Actual root cause: `emulator_car_x86_64` sets `BUILD_QEMU_IMAGES := true` and
`BOARD_BUILD_SUPER_IMAGE_BY_DEFAULT := true` (`build/make/target/board/BoardConfigEmuCommon.mk`,
comment: "emulator needs super.img"). For that config, the build doesn't hand the emulator a raw
`vbmeta.img`/`super.img` at all — it combines them into a single **GPT-partitioned disk image** named
`system-qemu.img`, via `build/make/core/Makefile`'s `INSTALLED_QEMU_SYSTEMIMAGE` rule:
```
$(INSTALLED_SYSTEM_QEMU_CONFIG): ...
	@echo "$(PRODUCT_OUT)/vbmeta.img vbmeta 1" > $@
	@echo "$(INSTALLED_SUPERIMAGE_TARGET) super 2" >> $@
$(INSTALLED_QEMU_SYSTEMIMAGE): ...
	$(MK_COMBINE_QEMU_IMAGE) -i $(INSTALLED_SYSTEM_QEMU_CONFIG) -o $@
```
i.e. `system-qemu.img` *is* a disk with two GPT partitions literally named `vbmeta` and `super` — the
exact two names `init`'s first-stage fstab was waiting on. `pack_emulator.sh` was staging the plain
`system.img` (raw ext4, no GPT table, no `vbmeta`/`super` partitions inside it) under the
`system.img` filename the emulator's `-drive id=system` slot reads from — so that slot could never
satisfy the wait, no matter what else got added to the zip. Same category of bug as the pre-existing
`ramdisk-qemu.img`-vs-`ramdisk.img` handling a few lines below in this script — the same "`-qemu`
variant is the one the emulator actually wants" trap, just on `system.img` instead of `ramdisk.img`.

Fix: `pack_emulator.sh` now copies `system-qemu.img` → staged as `system.img` (falling back to plain
`system.img` only if `system-qemu.img` doesn't exist), and no longer copies raw `vbmeta.img`/
`super.img` as standalone top-level files — they're already inside `system-qemu.img`. Re-extract the
new zip into the SDK's `system-images/<api-tag>/android-automotive/x86_64/` per section VIII, no
`config.ini` changes needed. The staged `system.img` is now ~5.5 GB (up from ~1.3 GB) since it's
really `system-qemu.img` under the hood; packaged zip total is ~877 MB.

### `m emu_img_zip` — the official equivalent of `pack_emulator.sh` (2026-08-01)
AOSP's own build system already has a dist target for exactly this: `m emu_img_zip` (after `lunch
sdk_car_x86_64-userdebug`) packages `out/target/product/emulator_car_x86_64/sdk-repo-linux-system-
images-<user>.zip` directly, containing `x86_64/{system.img, vendor.img, ramdisk.img, kernel-ranchu,
encryptionkey.img, userdata.img, build.prop, NOTICE.txt, VerifiedBootParams.textproto,
advancedFeatures.ini, data/...}`. Confirmed via `build.log` (2026-08-01): its `system.img` is
5,859,442,688 bytes — the exact size of the GPT-combined `system-qemu.img` described above — so the
official target already does the same rename `pack_emulator.sh` was fixed to do by hand. Either path
works now; `m emu_img_zip` needs no vendor/kpit script maintenance, so prefer it when convenient. One
difference from a real Google SDK download: no `package.xml` is generated, so Android Studio's
Device Manager won't auto-list it — not needed here, since this project's AVD setup hand-edits
`config.ini`'s `image.sysdir.1` directly (section VIII) rather than going through Studio's
package-detection path.

### Build & verify commands
Two stages — a fast module-only compile to catch Soong/manifest/sepolicy-syntax errors, then a full
product build if that passes (a full build has taken ~2.5h on this tree per prior `build.log` runs).

```bash
cd /config/workspace/android_auto_os
source build/envsetup.sh
lunch sdk_car_x86_64-userdebug   # or aosp_car_x86_64-userdebug — see mapping table above

# 1. Quick compile check — modules + the new prebuilt_etc + sepolicy
m hvac-app bluetooth-app hvac-service bluetooth-service privapp_permissions_bluetooth.xml

# 2. Full product build (only after the above succeeds)
m
```

Package and boot-test (`PRODUCT` arg to `pack_emulator.sh` is the `PRODUCT_DEVICE`, not the lunch
combo — see mapping table above):
```bash
./pack_emulator.sh emulator_car_x86_64      # for sdk_car_x86_64-userdebug
# or: ./pack_emulator.sh generic_car_x86_64 # for aosp_car_x86_64-userdebug

# extract the resulting zip per section VIII, then:
emulator -avd Automotive_1408p_landscape -writable-system -no-snapshot
```

Verify after boot:
```bash
adb shell pm list packages | grep -E "kpit|com.kpit"        # hvac-app/bluetooth-app/services installed
adb shell dumpsys package com.kpit.bluetooth | grep -A2 BLUETOOTH_PRIVILEGED  # privapp allowlist took
adb logcat -d | grep -i "avc:.*denied"                       # SELinux denials — see SEPolicy note above
```

### `pack_emulator.sh` — location and ramdisk fix (already applied)
Lives at the **workspace root** (`android_auto_os/pack_emulator.sh`), not under `vendor/kpit/` as an
earlier draft of this doc claimed. Already fixed and verified: copies `ramdisk-qemu.img` →
`ramdisk.img` (not plain `ramdisk.img`, which has no fstab and crash-loops the guest at
`ReadDefaultFstab() failed`). Usage: `./pack_emulator.sh [PRODUCT] [OUTPUT_ZIP]`, where `PRODUCT` is
the `PRODUCT_DEVICE` value (e.g. `emulator_car_x86_64` for the `sdk_car_x86_64` lunch combo, per the
mapping table above), not the `PRODUCT_NAME`/lunch-combo string.

### Build lesson — GSI artifact path requirement rejects system/priv-app additions
`m`/full-build failed in `build/make/core/artifact_path_requirements.mk` with `sdk_car_x86_64.mk
produces files inside packages/services/Car/car_product/build/car_generic_system.mks artifact path
requirement`, listing all of `hvac-app`/`hvac-service`/`bluetooth-app`/`bluetooth-service`'s
installed files plus `privapp_permissions_bluetooth.xml` under `system/priv-app/*` and
`system/etc/permissions/`. Root cause: `car_generic_system.mk` calls `require-artifacts-in-path` over
`TARGET_COPY_OUT_ROOT`/`TARGET_COPY_OUT_SYSTEM` with an empty allowed list — it's the GSI
system-image makefile and only its own declared packages may land in `system.img`.
`sdk_car_x86_64.mk` sets `PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS := strict` right after
inheriting it, then inherits `kpit_apps.mk` afterward, which adds four more `system/priv-app`
packages the GSI makefile knows nothing about.

Fix: added `PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST` entries in `kpit_apps.mk` itself (a
plain product-global var, so it doesn't matter that it's set from the inherited child) — one
`%`-wildcard entry per app's `system/priv-app/<name>/` directory plus the exact path to
`privapp_permissions_bluetooth.xml`. Standard AOSP escape hatch for "strict" GSI enforcement, not a
tree-specific workaround. `libvps`/`libbase_comfort_jni` needed no entry at the time — both were
`vendor: true`, installed to `/vendor/lib64`, outside the path requirement entirely.

**Correction (2026-07-31):** the assumption that `/system/lib64` wouldn't trigger the requirement
was wrong. After the SEPolicy fix above moved both libraries off `/vendor` (a coredomain process
can't load a `/vendor` library under Full-Treble `neverallow`), the same check failed again — this
time listing `system/lib/libbase_comfort_jni.so`, `system/lib/libvps.so`,
`system/lib64/libbase_comfort_jni.so`, `system/lib64/libvps.so` (32- and 64-bit, since this target
builds both ABIs). `car_generic_system.mk`'s empty allowed list covers all of
`TARGET_COPY_OUT_SYSTEM`, not just `system/priv-app/*` — any new install path under `/system` needs
its own entry. Fixed by adding those four exact paths to `kpit_apps.mk`'s
`PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST` (exact paths, not `%`-wildcarded, since each library
installs a single file per ABI dir rather than a whole app directory).

### Build lesson — stray `--` inside an XML comment breaks `manifest_fixer`
Next build failed in Soong's `manifest_fixer` step on
`service/connectivity/bluetooth/AndroidManifest.xml` with a plain XML parse error —
`error: not well-formed (invalid token): line 7, column 6`. Root cause: the file's top-of-file doc
comment used `--` as an em-dash substitute (twice); XML disallows `--` anywhere inside a comment body
except as the closing `-->`. Fix: replaced both occurrences with `—` (em dash). Checked every other
`AndroidManifest.xml` under `vendor/kpit/` for the same pattern — no other occurrences.

### Build lesson — see section V for the AVRCP/`BluetoothAvrcpController` rework
Next build failed with `could not resolve BluetoothAvrcpController` — a real API-reachability
problem, not a syntax slip. Full root cause, fix (`MediaSessionManager`/`MediaController`), and the
`MEDIA_CONTENT_CONTROL` permission it needed are written up in section V.

### Build lesson — three independent AndroidX/resource errors, further into the same build
Once the fixes above cleared, the next attempt hit three unrelated pre-existing gaps in the HMI apps
in one pass:
- **`bluetooth_app`: `cannot find symbol androidx.activity.EdgeToEdge`** — that class was added in
  `androidx.activity:activity:1.8.0`; this tree's prebuilt (`prebuilts/sdk/current/androidx/`) only
  has `1.7.0-alpha05` — the class doesn't exist in this tree at any version. Fix: removed the
  `EdgeToEdge.enable(this)` call and its import from `MainActivity.java` (unused Android Studio
  template boilerplate; the `WindowInsetsCompat`/`ViewCompat`/`Insets` padding logic right below it
  is unrelated and unaffected).
- **`hvac-manager-sdk`: `symbol not found androidx.annotation.NonNull`** — `HvacEvent.java` uses
  `@NonNull`, but `hvac-manager-sdk`'s `Android.bp` only listed `base-comfort-manager` in
  `static_libs`, never `androidx.annotation_annotation` (unlike `hvac-app`/`bluetooth-app`). Fix:
  added `androidx.annotation_annotation` to `hvac-manager-sdk`'s `static_libs`.
- **`hvac_app`: aapt2 link failure on `themes.xml`** — `Theme.MaterialComponents.DayNight.DarkActionBar`
  and its `colorPrimaryVariant`/`colorOnPrimary`/`colorSecondary`/`colorSecondaryVariant`/
  `colorOnSecondary` attrs come from the Material Components library, which `hvac_app`'s `Android.bp`
  never depended on (only `androidx.appcompat_appcompat` + lifecycle + annotation). Grepped every
  `.xml`/`.java` in `hmi/` for `MaterialComponents`/`MaterialButton`/`MaterialCardView` — this was the
  only reference in the whole tree, so pulling in the whole Material library for one theme parent
  wasn't worth it. Fix: rewrote `themes.xml` to use `Theme.AppCompat.DayNight.DarkActionBar` (already
  available via `androidx.appcompat_appcompat`) with the matching AppCompat attrs
  (`colorPrimary`/`colorPrimaryDark`/`colorAccent`); dropped the three attrs with no AppCompat
  equivalent since nothing else in the app referenced them.

### Build lesson — `libbase_comfort_jni` missing `jni.h` include path
Next build failed on both the x86_64 and x86 variants of `libbase_comfort_jni` with a plain compiler
error: `vendor/kpit/automotive/service/comfort/base/jni/base_comfort_vhal_jni.cpp:18:10: fatal error:
'jni.h' file not found` (`#include <jni.h>`). Root cause: `libbase_comfort_jni`'s `cc_library_shared`
in `service/comfort/base/Android.bp` only listed `liblog`/`libvps` in `shared_libs` — nothing exports
the JNI include path. Unlike Java `java_library`s (which get the JNI surface for free through the
framework), a `cc_library_shared` compiling raw JNI `.cpp` needs that header path pulled in
explicitly. Fix: added `header_libs: ["jni_headers"]` to `libbase_comfort_jni` — the standard Soong
`cc_library_headers` module declared in `libnativehelper/Android.bp`
(`export_include_dirs: ["include_jni"]`), the same module every other native JNI bridge in the tree
already depends on for this exact header. No `shared_libs` change needed, since `jni_headers` is
headers-only and adds no runtime link dependency. Lesson: this specific error on a
`cc_library_shared` compiling JNI glue means a missing `header_libs: ["jni_headers"]` entry, not a
missing SDK/NDK include path — don't hand-roll an `include_dirs:` pointing at the header directly.

### Build lesson — `HvacHandler.h`'s hash function overflowed `size_t` on the x86 (32-bit) variant
Next full-build attempt got much further — 1% into ~155,875 ninja actions — before failing only on
the `libbase_comfort_jni` **x86** variant (x86_64 built fine earlier in the same run):
`vps/include/HvacHandler.h:47:74: error: shift count >= width of type [-Werror,-Wshift-count-overflow]`
on `Key::KeyHash::operator()`'s `static_cast<size_t>(...) << 32`. Root cause: this target builds
`libbase_comfort_jni`/`libvps` for both x86_64 and x86 ABIs (`i686-linux-android34`, `-m32` on x86).
`size_t` is 64 bits on x86_64 but only 32 bits on x86, so `<< 32` on a `size_t`-typed value is a
no-op-or-worse on the 32-bit variant — caught only because `-Werror` is on, not a logic bug that
would otherwise silently corrupt the hash. Fix: changed `KeyHash::operator()` to do the shift/XOR on
an explicit `uint64_t` (always 64 bits regardless of ABI) and only `static_cast<size_t>(...)` the
final combined result once, at the end. Lesson: any hash/bit-packing helper in `vps/` that assumes a
64-bit-wide integer must use a fixed-width type (`uint64_t`), never `size_t` — this tree builds
native code for both x86_64 and x86 in the same `m` invocation, and `size_t`'s width isn't portable
across them.

### Outstanding after this section
1. Implement Seat (AIDL, manager/service, VPS handler) and WiFi (section III) — no build/product
   changes needed for either until real modules exist.
2. ~~Re-run the full build~~ — **done, successful (2026-08-01)**, no errors. Full fix list already
   in VI.11's 2026-08-01 update — not repeated here.
3. Boot-test in the emulator and grep `logcat` for `avc: denied` tied to
   `hvac-service`/`libbase_comfort_jni`/`libvps` (section X's "Build & verify commands") to confirm
   no further access is denied now that both libraries are on `/system` instead of `/vendor` —
   **not yet performed.**
4. Functional test of `hvac_app`'s controls, once boot-test above passes — procedure and known
   findings written up in section XI below. Not yet run against a real device/emulator (no adb
   available from where this was written) — this is a procedure to execute on the dev machine from
   section VIII, not a confirmed pass.

---

## XI. FUNCTIONAL TESTING — HVAC APP (manual, no source changes)

Procedure to exercise every `hvac_app` control end-to-end against the real `hvac-service` +
`libvps` stack, without editing any source — plus two findings surfaced while mapping the
command/event path for this plan that change what "the button worked" actually means per control.
Written 2026-08-02, not yet executed (no adb/emulator available in the environment this was
written from — run it on the Windows dev machine from section VIII).

### Why the panel is locked on first boot
`HvacViewModel` starts `mCurrentVehicleState = -1` and only enables the AC button — and, once AC is
toggled on, the rest of the panel — once `mCurrentVehicleState >= 5`
(`hmi/hvac_app/src/com/kpit/hmi/hvac/viewmodel/HvacViewModel.java:27,55,102-133`). That value only
moves via a `PROP_VEHICLE_STATE` event, and `HvacHandler::seedDefaults()` seeds it to `0.0f` with
nothing in the file ever touching it again (`vps/src/HvacHandler.cpp:63`) — there's no simulated
ignition/ECU signal in this stub. Not an app bug; just means the panel needs one manual nudge per
boot before it's testable (Step 1 below).

### Fixed — `HvacManager.onChangeEvent` only dispatched 4 of the 12 property IDs (found 2026-08-02, fixed 2026-08-02)
`service/comfort/hvac/src/com/kpit/hvac/manager/HvacManager.java`'s binder callback originally
routed only `PROP_AC_STATE`/`PROP_FAN_SPEED` (to `dispatchToProperty`) and
`PROP_VEHICLE_STATE`/`PROP_TEMP_OUTSIDE` (to `dispatchToSystem`) — every other property id
(`PROP_MAX_STATE`, `PROP_RECYCLE_STATE`, `PROP_TEMP`, `PROP_SYNC`, `PROP_SEAT_HEATING`,
`PROP_VENTILATION_MODE`, `PROP_AUTO_MODE`, `PROP_DEFROST`) fell into the outer `default` and was
logged away, even though `HvacViewModel` implements a matching `HvacListener` method for each one.
Fixed by adding all eight missing property ids to the outer switch's `dispatchToProperty` case, and
adding a matching `case` inside `dispatchToProperty` itself for each one, calling the corresponding
listener method (`onMaxStateChanged`/`onAirRecycleStateChanged`/`onTempChanged(value, areaId)`/
`onSyncStateChanged`/`onHeatingSeatChanged(value, areaId)`/`onVentilationModeChanged`/
`onAutoStateChanged`/`onDefrostStateChanged`) with `event.getValue() != 0` for the boolean ones —
same pattern the pre-existing `PROP_FAN_SPEED` case already used. `PROP_TEMP`/`PROP_SEAT_HEATING`
pass `event.getAreaId()` straight through as the `area` parameter, since `HvacProperties.DRIVER`/
`PASSENGER` (1/2) already match the 1/2 area convention `HvacViewModel.onTempChanged`/
`onHeatingSeatChanged` expect — no translation needed.

**Second bug found in the same method while fixing this:** the pre-existing `PROP_AC_STATE` case
read `hvacListener.onACStateChanged(event.getValue() == 0)` — inverted. `HvacManager.setAcState()`
sends `1.0f` for on/`0.0f` for off, so the echoed confirmation event was reporting AC as *off*
whenever it was actually turned on (and vice versa) — every AC tap would optimistically show "on",
then immediately flip back to "off" the instant the confirmation event arrived. Fixed to
`event.getValue() != 0`. This means the AC row in Step 2's matrix below, previously listed as a
fully-confirmed ✅ round trip, was not actually correct prior to this fix — it looked fine only
because the flip happened fast enough (single-digit ms, same device) to be easy to miss without
watching logcat closely.

**Residual, not fixed:** `PROP_TEMP`'s echoed value is truncated to `int` before reaching
`onTempChanged(int value, int area)` — pre-existing on `HvacListener`'s interface signature, not
something this fix touched. `HvacViewModel` sends temperature in 0.5°C steps
(`incrementTemp`/`decreaseTemp`), so a confirmed round trip for Temp will now show a value rounded
down to the nearest whole degree rather than the exact 0.5-step value already displayed by the
optimistic `pushTempState()` call. Only visible once you're watching the *confirmed* value instead
of the optimistic one; flagged here in case it's surprising during Step 2 testing, not fixed since
it needs an interface signature change beyond the scope of this fix.

### Fixed — two logic bugs in the command path for Max and Sync (found 2026-08-02, fixed 2026-08-02)
Surfaced while tracing what each `toggleX()` actually sends, both in
`hmi/hvac_app/src/com/kpit/hmi/hvac/viewmodel/HvacViewModel.java`:
- `toggleMax()` called `mHvacVehicleManager.setMaxState(mIsAcOn)` — sent the **AC** toggle's
  boolean, not `mIsMaxOn` (line 245). Fixed to send `mIsMaxOn`.
- `toggleSync()` computed `mIsSyncOn = !mIsCycleOn` instead of `!mIsSyncOn` (line 258) — Sync's
  toggle direction was driven by the Recycle button's state, not its own previous state. Fixed to
  `!mIsSyncOn`.
Both were pre-existing, not introduced by this test plan. At the time these were found,
`PROP_MAX_STATE`/`PROP_SYNC` were also in the dispatcher's dropped set (finding above), so neither
bug was reachable via the confirmed event round trip either — only the dispatcher fix (also applied
above) makes Max/Sync's *event* path testable at all. Re-run Step 2's Max/Sync rows after both
fixes to confirm the command now carries the right value **and** the confirmation event reflects it.

### Step 1 — unlock the panel
```bash
adb shell service call hvac_service 1 i32 11 i32 0 f 5.0
```
Transaction `1` = `setVehicleProperty` (first method in `IHVACVehicleService.aidl`), `11` =
`PROP_VEHICLE_STATE`, `0` = `AREA_GLOBAL`, `5.0` = value. This hits the exact same AIDL entry point
a real VHAL push would use — not a mock/bypass. Then tap **AC** once in the running app to flip
`mIsAcOn`, which unlocks the rest of the panel via `checkInterlockingAndEvaluate()`.

### Step 2 — per-control matrix
For each row: tap the control in the UI and watch for the expected visual change, then check
`adb logcat -s HvacViewModel:* HvacManager:* HvacService:* HvacHandler:*` for the call chain. The
"inject" command simulates the *event* path (as if the signal came from the vehicle instead of the
HMI) via the same `setVehicleProperty` hook as Step 1 — useful to test each property independently
of whatever the ViewModel's optimistic UI update is doing.

| Control          | ViewModel method                             | Prop id / area                    | Confirmed round trip?                                                                                     | Inject (event-path test)                              |
|------------------|----------------------------------------------|-----------------------------------|-----------------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| AC               | `toggleAc()`                                 | 1 / `GLOBAL`(0)                   | ✅ (polarity fixed above — verify it no longer flips back off right after turning on)                      | `service call hvac_service 1 i32 1 i32 0 f 1.0`       |
| Fan +/−          | `increaseFanSpeed()` / `decrementFanSpeed()` | 4 / `GLOBAL`(0)                   | ✅ (no optimistic update at all — bars move only via the echoed event)                                     | `service call hvac_service 1 i32 4 i32 0 f 7.0`       |
| Vehicle state    | n/a (system signal)                          | 11 / `GLOBAL`(0)                  | ✅                                                                                                         | `f 3.0` (drops below 5, re-locks AC) / `f 5.0`        |
| Outside temp     | n/a (system signal)                          | 12 / `GLOBAL`(0)                  | ✅ but logcat-only — `onTempOutsideChanged` only `Log.d()`s, no `LiveData`/UI                              | `f 30.0`                                              |
| Max              | `toggleMax()`                                | 2 / `GLOBAL`(0)                   | ✅ now dispatched — verify the command also carries `mIsMaxOn` after the Max/Sync fix below, not `mIsAcOn` | `service call hvac_service 1 i32 2 i32 0 f 1.0`       |
| Recycle          | `toggleRecycle()`                            | 3 / `GLOBAL`(0)                   | ✅ now dispatched                                                                                          | `... i32 3 i32 0 f 1.0`                               |
| Temp left/right  | `incrementTemp(area)` / `decreaseTemp(area)` | 5 / `DRIVER`(1) or `PASSENGER`(2) | ✅ now dispatched, but confirmed value is truncated to whole degrees (residual note above)                 | `... i32 5 i32 1 f 24.0`                              |
| Sync             | `toggleSync()`                               | 6 / `GLOBAL`(0)                   | ✅ now dispatched — verify toggle direction after the Max/Sync fix below                                   | `... i32 6 i32 0 f 1.0`                               |
| Seat heating L/R | `toggleSeatHeating(area)`                    | 7 / `DRIVER`(1) or `PASSENGER`(2) | ✅ now dispatched                                                                                          | `... i32 7 i32 2 f 1.0`                               |
| Ventilation mode | `toggleVentilationMode(mode)`                | 8 / `GLOBAL`(0)                   | ✅ now dispatched                                                                                          | `... i32 8 i32 0 f 2.0` (1=foot, 2=foot+face, 3=face) |
| Auto             | `toggleAuto()`                               | 9 / `GLOBAL`(0)                   | ✅ now dispatched                                                                                          | `... i32 9 i32 0 f 1.0`                               |
| Defrost          | `toggleDefrost()`                            | 10 / `GLOBAL`(0)                  | ✅ now dispatched                                                                                          | `... i32 10 i32 0 f 1.0`                              |

All 12 rows are now expected to round-trip; none should still land on
`HvacManager: onChangeEvent: not handle this property` in logcat. If any row still hits that log
line, the dispatcher fix above didn't take (re-check the build), not a re-emergence of the original
gap.

### Step 3 — full command reference (copy-paste into PowerShell)
Every property from Step 2's matrix, expanded to full `adb shell service call` commands — no
quoting needed, runs as-is in PowerShell. Transaction `1` is always `setVehicleProperty`; areas are
`0`=`GLOBAL`, `1`=`DRIVER`, `2`=`PASSENGER`. Run the vehicle-state=5 line first, then tap **AC**
once in the app to unlock the rest of the panel — otherwise these land in `HvacHandler`'s store but
the UI stays locked and won't visibly react.

```powershell
# --- Vehicle state (unlock the panel — do this first) ---
adb shell service call hvac_service 1 i32 11 i32 0 f 5.0   # PROP_VEHICLE_STATE = 5 (enables AC)
adb shell service call hvac_service 1 i32 11 i32 0 f 3.0   # PROP_VEHICLE_STATE = 3 (below 5, re-locks AC)
adb shell service call hvac_service 1 i32 11 i32 0 f 0.0   # PROP_VEHICLE_STATE = 0 (default on boot)

# --- AC state ---
adb shell service call hvac_service 1 i32 1 i32 0 f 1.0    # PROP_AC_STATE on
adb shell service call hvac_service 1 i32 1 i32 0 f 0.0    # PROP_AC_STATE off

# --- Max ---
adb shell service call hvac_service 1 i32 2 i32 0 f 1.0    # PROP_MAX_STATE on
adb shell service call hvac_service 1 i32 2 i32 0 f 0.0    # PROP_MAX_STATE off

# --- Recycle ---
adb shell service call hvac_service 1 i32 3 i32 0 f 1.0    # PROP_RECYCLE_STATE on
adb shell service call hvac_service 1 i32 3 i32 0 f 0.0    # PROP_RECYCLE_STATE off

# --- Fan speed (0-12) ---
adb shell service call hvac_service 1 i32 4 i32 0 f 7.0    # PROP_FAN_SPEED = 7
adb shell service call hvac_service 1 i32 4 i32 0 f 0.0    # PROP_FAN_SPEED = 0 (off)

# --- Temperature (driver = area 1, passenger = area 2) ---
adb shell service call hvac_service 1 i32 5 i32 1 f 24.0   # PROP_TEMP driver
adb shell service call hvac_service 1 i32 5 i32 2 f 20.0   # PROP_TEMP passenger

# --- Sync ---
adb shell service call hvac_service 1 i32 6 i32 0 f 1.0    # PROP_SYNC on
adb shell service call hvac_service 1 i32 6 i32 0 f 0.0    # PROP_SYNC off

# --- Seat heating (driver = area 1, passenger = area 2) ---
adb shell service call hvac_service 1 i32 7 i32 1 f 1.0    # PROP_SEAT_HEATING driver on
adb shell service call hvac_service 1 i32 7 i32 1 f 0.0    # PROP_SEAT_HEATING driver off
adb shell service call hvac_service 1 i32 7 i32 2 f 1.0    # PROP_SEAT_HEATING passenger on
adb shell service call hvac_service 1 i32 7 i32 2 f 0.0    # PROP_SEAT_HEATING passenger off

# --- Ventilation mode (1=foot, 2=foot+face, 3=face) ---
adb shell service call hvac_service 1 i32 8 i32 0 f 1.0
adb shell service call hvac_service 1 i32 8 i32 0 f 2.0
adb shell service call hvac_service 1 i32 8 i32 0 f 3.0

# --- Auto ---
adb shell service call hvac_service 1 i32 9 i32 0 f 1.0    # PROP_AUTO_MODE on
adb shell service call hvac_service 1 i32 9 i32 0 f 0.0    # PROP_AUTO_MODE off

# --- Defrost ---
adb shell service call hvac_service 1 i32 10 i32 0 f 1.0   # PROP_DEFROST on
adb shell service call hvac_service 1 i32 10 i32 0 f 0.0   # PROP_DEFROST off

# --- Outside temp (logcat-only, no UI — onTempOutsideChanged just Log.d()s) ---
adb shell service call hvac_service 1 i32 12 i32 0 f 30.0
```

### Not covered by this pass
`bluetooth_app` — different chain entirely (real Bluetooth profile proxies, no VPS/JNI, no adb
injection equivalent since there's no in-process store to poke). Covered separately in section XII.

---

## XII. FUNCTIONAL TESTING — BLUETOOTH APP (manual, no source changes)

Companion to section XI, for `bluetooth_app`. Written 2026-08-02, not yet executed (no adb/emulator
available in the environment this was written from — run it on the Windows dev machine from section
VIII). Structurally different from HVAC's test plan: there's no VPS/JNI stub to poke via
`service call` for the actual connection/media state — that state is owned entirely by Android's
real Bluetooth stack (`BluetoothHeadsetClient`/`BluetoothA2dpSink`) and the
`MediaSessionManager`/`MediaController` session framework (section V, "no VHAL/JNI"). Two things to
know before testing:

**No unlock gate, but also no connect button.** Unlike HVAC's `PROP_VEHICLE_STATE` lock,
`BaseConnectivityService.onCreate()` runs `onConnectivitySourceConnect()` immediately
(`service/connectivity/base/src/com/kpit/connectivity/base/service/BaseConnectivityService.java:51`),
so `bluetooth_app` is interactive the instant it launches — no per-boot nudge needed. But
`IviBluetoothManager.connect(mac)`/`disconnect(mac)`
(`service/connectivity/bluetooth/src/com/kpit/bluetooth/manager/IviBluetoothManager.java:79-103`)
are fully wired to the AIDL service yet never called by `BluetoothViewModel` or `MainActivity` —
there is no connect/disconnect button anywhere in `activity_main.xml`. `bluetooth_app` is a pure
connection-status/media display; pairing has to happen through the OS's own Bluetooth Settings UI
(or adb), not this app. Not a bug to fix, just a fact to know before hunting for a UI element that
isn't there by design.

**Stale doc comment, not a functional bug:** `MediaAction.java`'s class Javadoc still describes
commands being "translated into AVRCP passthrough key codes" via
`BluetoothAvrcpController#sendPassThroughCmd()` — leftover from before the AVRCP rework in section
V. The real code path (`IviBluetoothService.dispatchMediaCommand()`) uses
`MediaController.TransportControls.play()/pause()/skipToNext()/skipToPrevious()`. Left as-is (no
source changes requested for this pass); noted here so it isn't mistaken for the actual behavior
while reading logs.

### Tier 1 — verify the wiring, no phone/pairing needed
Confirms Activity→ViewModel→Manager→Service actually connects, before any real Bluetooth hardware
is involved:
```bash
adb logcat -s IviBluetoothManager:* IviBluetoothService:*
```
Expect `IviBluetoothManager: Register successfully` shortly after boot. The app should show "No
device connected" with all three profile badges (`tvProfileHfp`/`tvProfileA2dp`/`tvProfileAvrcp`)
dimmed to alpha `0.3` (`MainActivity.bindDeviceInfo()`/`setProfileBadgeActive()`).

Tap **Play / Pause / Next / Previous** with nothing connected — expect no crash, and:
```
IviBluetoothService: dispatchMediaCommand: no active Bluetooth media session
```
That confirms the full command chain (`MainActivity`→`BluetoothViewModel`→`IviBluetoothManager`→AIDL
→`IviBluetoothService`) executes correctly with no device present — it only no-ops at the final
guard check inside `dispatchMediaCommand()`.

### Tier 2 — real connection + media test (needs a peer)
No adb shortcut exists for this part — it's real OS Bluetooth state, not a local stub. Needs a real
phone or a second AVD (a Phone/Tablet image, not another Automotive one) with Bluetooth enabled,
both discoverable:
1. Enable Bluetooth on the guest: `adb shell cmd bluetooth_manager enable` (or via Settings).
2. Pair the two devices through the Automotive guest's standard Bluetooth Settings UI.
3. Since there's no in-app connect button, pairing itself should trigger HFP/A2DP auto-connect once
   the new bond's connection policy defaults to `ALLOWED`. If it doesn't auto-connect, drive it
   manually by hitting the same AIDL entry point `bluetooth_app` itself never calls:
   ```bash
   adb shell service call bluetooth_service 1 s16 "AA:BB:CC:DD:EE:FF"
   ```
   Transaction `1` = `connect(String)`, the first method in
   `service/connectivity/bluetooth/aidl/com/kpit/bluetooth/IIviBluetoothService.aidl`; substitute
   the peer's real MAC address. `2` = `disconnect(String)`, same shape, for the reverse test.
4. Confirm in the UI: device name/MAC populate, HFP/A2DP/AVRCP badges go full opacity. Watch
   `adb logcat -s IviBluetoothService:*` for `ACTION_CONNECTION_STATE_CHANGED` handling
   (`IviBluetoothService.handleBroadcast()`).
5. Start music playback on the phone — title/artist/album, the playback-state badge, and the
   position bar should populate via `MediaController.Callback`
   (`onMetadataChanged`/`onPlaybackStateChanged` → `publishMetadata()`/`publishPlaybackState()`).
6. Tap **Play / Pause / Next / Previous** in the app — should now control playback on the connected
   phone (command path, reverse direction from step 5, via `dispatchMediaCommand()`).

### Not covered by this pass
WiFi — not yet implemented (section III), nothing to test yet.
