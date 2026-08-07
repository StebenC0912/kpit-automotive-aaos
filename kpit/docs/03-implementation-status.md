# III. Implementation Status & Notes

### ✅ HVAC domain (Comfort) — complete
1. `hvac_app` — full MVVM UI, `LiveData` state, all resources present.
2. `HvacManager` — extends `BaseComfortManager<IHVACVehicleService>`.
3. `HvacService` — extends `BaseComfortService<IHVACVehicleCallback>`.
4. AIDL: `HvacEvent`, `IHVACVehicleCallback`, `IHVACVehicleService`.
5. Helpers: `HvacListener`, `SystemListener`, `HvacProperty`, `IHvacController`.
6. `BaseComfortService`/`BaseComfortManager` — JNI bridge foundation.
7. VPS C++ (HVAC): `IVpsHandler`/`VpsDispatcher`/`HvacHandler`; JNI calls `VpsDispatcher` in-process
   (see [06-technical-requirements.md](06-technical-requirements.md) #7).
8. Comfort permissions wired end-to-end for `base` and `hvac` (06-technical-requirements.md #4/#6).
9. Four build issues hit and fixed along the way — see 06-technical-requirements.md #8–#10, #13.
10. Pressed/activated-state UI feedback wired for all `hvac_app` controls — see 06-technical-requirements.md #11.
11. `HvacApplication` starts `HvacService` at boot (`persistent="true"` alone doesn't) — see 06-technical-requirements.md #16.
12. `HvacManager.registerSystemListener()`/`registerPropertyListener()` force a connection to the
    service so registered listeners actually receive events — see 06-technical-requirements.md #17.

### ✅ Bluetooth domain (Connectivity) — complete
1. `IviBluetoothManager` — extends `BaseConnectivityManager<IIviBluetoothService>`, same
   `ServiceManager.getService()` pattern as `HvacManager`.
2. `IviBluetoothService` — extends `BaseConnectivityService<IIviBluetoothListener>`; owns two profile
   proxies (HFP/A2DP) plus a `MediaSessionManager`/`MediaController` attachment for AVRCP (build
   lesson, [05-bluetooth-architecture.md](05-bluetooth-architecture.md)), each eagerly synced on connect per
   06-technical-requirements.md #5.
   `connect()`/`disconnect()` (the ViewModel-facing API) drive HFP+A2DP together via
   `setConnectionPolicy()` — not the profile proxies' own `connect()`/`disconnect()` methods, which
   are unreachable from outside the Bluetooth mainline module (build lesson, 06-technical-requirements.md
   #14); `sendMediaCommand()` maps `MediaAction` → `MediaController.TransportControls` calls (`play()`/
   `pause()`/`skipToNext()`/`skipToPrevious()`).
3. AIDL: `IIviBluetoothService`, `IIviBluetoothListener` (oneway callbacks, 06-technical-requirements.md #2).
4. Parcelables: `BluetoothDeviceInfo` (MAC/name/profile bitmask), `MediaPlaybackInfo` (title/artist/album/state/position).
5. Helpers: `BluetoothListener`, `MediaAction`, `IviPlaybackState`.
6. `BaseConnectivityService`/`BaseConnectivityManager` — no JNI; lifecycle hooks
   `onConnectivitySourceConnect()`/`Disconnect()` bake in #5's eager resync.
7. Connectivity permissions wired end-to-end; `bluetooth-service` built with `platform_apis: true`
   for hidden profile API access (build note in [05-bluetooth-architecture.md](05-bluetooth-architecture.md)).
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
   `IviBluetoothService` via `ServiceManager.getService()` (06-technical-requirements.md #6).
9. `IviBluetoothApplication` starts `IviBluetoothService` at boot (`persistent="true"` alone
   doesn't) — see 06-technical-requirements.md #16.
10. `IviBluetoothManager.registerBluetoothListener()` forces a connection to the service so
    registered listeners actually receive events — see 06-technical-requirements.md #17.

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
4. **`dumpsys hvac_service` debug entry point (`--get` + `--set`)** — `HvacService`/
   `BaseComfortService` don't override `dump()` today, so property access only works via
   `adb shell service call hvac_service 1 i32 <id> i32 <area> f <value>` (the real AIDL transaction —
   see [11-testing-hvac.md](11-testing-hvac.md)). Not the same route as the real AOSP
   `dumpsys android.hardware.automotive.vehicle.IVehicle/default --set HVAC_TEMPERATURE_SET ...` —
   this repo doesn't implement or register that VHAL service at all, and has no `HVAC_TEMPERATURE_SET`-
   style property names (custom `HvacProperties` ints instead). Needed as the on-device foundation for
   item 5 below (`vspManagerTool`); plan (discussed 2026-08-07, not implemented):
   - Override `dump(FileDescriptor fd, PrintWriter pw, String[] args)` in `HvacService`.
   - **No args (or `--get`):** iterate the existing `GLOBAL_PROPS`/`PER_SEAT_PROPS` arrays already in
     `HvacService.java` (lines 21–37), call `nativeGetFloatProperty(handle, propId, areaId)` for each,
     and print one parseable line per property (e.g. `PROP_TEMP area=1 value=22.5`). This is a
     snapshot, not a subscription — `dump()` is a synchronous one-shot Binder call with no push
     capability, so anything reading it has to poll and diff.
   - **`--set <PROP_NAME> -a <area> -f <value>`:** resolve the name to a `HvacProperties` int and call
     the existing `mBinder.setVehicleProperty()` internally — same sketch as before, still no VPS/JNI
     changes needed.
5. **`vspManagerTool` — standalone Windows GUI, replaces the earlier on-device "kitchen sink" app
   idea** — no persistence exists anywhere in this repo today (no Room/SQLite/SharedPreferences/file
   I/O; confirmed by full-repo search 2026-08-07) and there's no way to watch or set HVAC values
   except one-shot adb commands run by hand. Decided 2026-08-07 to build this as a **host-side Java
   GUI tool that runs on the developer's Windows machine, not an Android app inside the
   emulator/device** — no new AIDL, no new on-device app, no Room DB in-emulator. Plan (not
   implemented):
   - Plain Java desktop app (Swing/JavaFX), packaged as a runnable jar, run on Windows against an
     emulator/device reachable over `adb` (same adb the host already uses to talk to the emulator).
   - **Transport is adb only** — the tool shells out via `ProcessBuilder`, it does not open its own
     socket to the device. Depends entirely on item 4's `dump()` override existing on-device first.
   - **Watch:** polls `adb shell dumpsys hvac_service` on a timer (e.g. every 300–500ms), parses the
     `--get` output format from item 4, diffs it against the previous poll, and updates a live table +
     append-only log/history pane in the GUI. This is polling dressed up as "watching," not true
     push — flagged explicitly since `dumpsys`/`dump()` has no subscribe mechanism to build real push
     on top of (the alternative considered and rejected for now: a TCP-socket companion service on
     the device pushed to over `adb forward`, which would remove the polling lag at the cost of a new
     on-device component).
   - **Set:** sends `adb shell dumpsys hvac_service --set <PROP_NAME> -a <area> -f <value>` for
     user-driven writes from the GUI's per-property fields/buttons.
   - **Persistence:** local to the tool itself (e.g. a simple file/embedded DB on the Windows side for
     history), not inside the emulator — separate concern from item 4/on-device state, which stays
     exactly as volatile as it is today (`HvacHandler`'s `mStore` is still lost on process restart).
   - **Scope:** HVAC first (`hvac_service`'s `GLOBAL_PROPS`/`PER_SEAT_PROPS`); extending the same
     `dump()` pattern to `IviBluetoothService` for Bluetooth state is a stretch goal, not required for
     v1.
