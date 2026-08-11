# III. Implementation Status & Notes

### ✅ HVAC domain (Comfort) — complete
1. `hvac_app` — full MVVM UI, `LiveData` state, all resources present.
2. `AllianceCarHvacManager` — extends `AllianceCarBaseManager<IHVACVehicleService>`.
3. `AllianceCarHvacService` — extends `AllianceCarBaseService<IHVACVehicleCallback>`.
4. AIDL: `HvacEvent`, `IHVACVehicleCallback`, `IHVACVehicleService`.
5. Helpers: `HvacListener`, `SystemListener`, `HvacProperty`, `IHvacController`.
6. `AllianceCarBaseService`/`AllianceCarBaseManager` — JNI bridge foundation.
7. VPS C++ (HVAC): `IVpsHandler`/`VpsDispatcher`/`HvacHandler`; JNI calls `VpsDispatcher` in-process
   (see [06-technical-requirements.md](06-technical-requirements.md) #7).
8. Comfort permissions wired end-to-end for `base` and `hvac` (06-technical-requirements.md #4/#6).
9. Four build issues hit and fixed along the way — see 06-technical-requirements.md #8–#10, #13.
10. Pressed/activated-state UI feedback wired for all `hvac_app` controls — see 06-technical-requirements.md #12.
11. `HvacApplication` starts `AllianceCarHvacService` at boot (`persistent="true"` alone doesn't) — see 06-technical-requirements.md #16.
12. `AllianceCarHvacManager.registerSystemListener()`/`registerPropertyListener()` force a connection to the
    service so registered listeners actually receive events — see 06-technical-requirements.md #17.
13. **`vps/` moving closer to real AOSP VHAL's structure — staged roadmap (discussed 2026-08-10).**
    `vps/`'s design (in-process C++ singleton, no separate HAL process/Binder boundary) intentionally
    diverges from the real Vehicle HAL — see
    06-technical-requirements.md #7 for why a real `IVehicle` HAL binary was rejected. Closing that
    gap without redoing the whole thing at once, staged cheapest-first:
    - **Stage 1 — config-driven properties. ✅ Implemented and verified 2026-08-10.** New
      `vps/include/VpsPropConfig.h` / `vps/src/VpsPropConfig.cpp`: a `VpsPropConfig` struct
      (`type`, `access` READ/WRITE/READ_WRITE, `changeMode`, `supportedAreas`, `minValue`/
      `maxValue`) modeled on real VHAL's `VehiclePropConfig`. `HvacHandler::buildConfigs()` builds
      one entry per property (all 12) in the constructor; `getProperty()`/`setProperty()` now
      validate every call against it — unknown property, wrong access mode, unsupported area, or
      out-of-range value are all rejected before touching `mStore`, instead of trusting
      propId/areaId/value blindly the way the handler did before this. Access is `READ_WRITE`
      across the board, including `PROP_VEHICLE_STATE`/`PROP_TEMP_OUTSIDE` (a real vehicle would
      expose both READ-only, as system/sensor signals rather than HMI commands) — kept writable so
      [11-testing-hvac.md](11-testing-hvac.md)'s documented adb test flow (unlocking the panel,
      injecting an outside-temp value) keeps working; only type/area/range are actually enforced
      for those two. `vps/Android.bp` updated to add `src/VpsPropConfig.cpp` to `libvps`'s `srcs`.
      One existing test (`HvacHandlerTest.cpp`'s `SetPropertyOnUnsubscribedKeyFiresNoCallback`) set
      `PROP_AC_STATE` at area `DRIVER`, which the new area validation now correctly rejects
      (`PROP_AC_STATE` is `GLOBAL`-only) — swapped to `PROP_SEAT_HEATING` (driver vs. passenger,
      both valid areas) to keep the test's actual point (different area → no callback) intact.
      **Also found and fixed in passing:** `vps/tests/VpsDispatcherTest.cpp` (added in commit
      `4b7a4a5`, untouched by this change) had an unclosed anonymous `namespace { ... }` block —
      a genuine pre-existing compile error, not something this change introduced; `libvps_test`
      could not have compiled before this fix regardless of Stage 1. **Verified:** no Soong/emulator
      build available in this session, so verification was a standalone host compile — `g++
      -std=c++17 -Wall -Wextra -Werror` against `vps/src/*.cpp` (clean, matching `Android.bp`'s own
      flags), then a full link against real gtest sources from `external/googletest/` and a run of
      the resulting `libvps_test` binary: all 12 tests (8 `HvacHandlerTest` + 4 `VpsDispatcherTest`)
      pass. Not yet done: an actual Soong-built `libvps_test` run via `atest`/`device-tests` on a
      real device/emulator.
    - **Stage 2 — standardized-style property ID encoding. ✅ Implemented and verified 2026-08-10.**
      New `vps/include/VpsPropertyId.h`: mirrors real AOSP `VehiclePropertyIds`' bit layout exactly
      (bits 31-28 `VehiclePropertyGroup`, bits 27-24 `VehicleArea` *type*, bits 23-16
      `VehiclePropertyType`, bits 15-0 a plain index) via a `constexpr makePropId(group, areaType,
      type, index)` helper, replacing the old flat `1..12` propId scheme. Only `kPropertyGroupSystem`
      (`0x10000000`) is used (VENDOR/BACKPORTED don't apply to this stub); area type is
      `kAreaTypeGlobal` for global properties or `kAreaTypeSeat` for the two that vary per seat
      (`PROP_TEMP`, `PROP_SEAT_HEATING`); type is whichever of `kPropertyTypeBoolean`/`Int32`/`Float`
      matches the property. The header is now the single source of truth for all 12 `PROP_*`
      constants natively — `vps/src/HvacHandler.cpp`'s old hand-duplicated `constexpr` block and both
      test files' (`HvacHandlerTest.cpp`, `VpsDispatcherTest.cpp`) hand-duplicated copies were deleted
      in favor of including it, cutting the "kept in sync by hand" burden from three native copies
      down to one (native header vs. `HvacProperties.java`, which still can't share code with C++
      across the JNI boundary). One correctness fix Stage 2 required: `HvacHandler::supportsProperty()`
      used to be a contiguous range check (`propId >= PROP_AC_STATE && propId <= PROP_TEMP_OUTSIDE`),
      which only worked because the old IDs were sequential 1..12 — bit-packed IDs aren't contiguous,
      so it now delegates to `findConfig(propId) != nullptr` (Stage 1's config lookup) instead, which
      is arguably more correct regardless of ID scheme. `HvacProperties.java` mirrors the same layout
      by hand: `private static final int` group/area-type/type constants combined with `|` into each
      `PROP_*` value — still compile-time constant expressions under JLS constant-folding rules, so
      `AllianceCarHvacManager.java`'s and `AllianceCarHvacService.java`'s existing `switch`/`case` statements on `PROP_*`
      keep working unmodified. Deliberately unchanged: the separate `areaId` parameter every
      get/set/subscribe call still takes (`AREA_GLOBAL`/`DRIVER`/`PASSENGER`, now defined once in
      `VpsPropertyId.h` instead of duplicated per file) — real VHAL also keeps *which* seat as a
      separate call parameter distinct from the area *type* baked into the propId, so this wasn't a
      gap to close. `VpsDispatcher.cpp` needed no changes: it already routed purely via
      `supportsProperty()`, never assuming anything about ID ranges. **Verified:** same method as
      Stage 1 — standalone host `g++ -std=c++17 -Wall -Wextra -Werror` compile of `vps/src/*.cpp` and
      both test files, linked against real gtest sources from `external/googletest/`; all 12 tests (8
      `HvacHandlerTest` + 4 `VpsDispatcherTest`) pass unchanged. Not yet done: an actual Soong-built
      `libvps_test` run via `atest`/`device-tests`, and an emulator boot test of the real JNI/Java
      path end to end (nothing in `base_comfort_vhal_jni.cpp` needed to change — propId flows through
      it opaquely as a `jint` — but that's untested on-device in this session).
    - **Stage 3 — pluggable fake/real backend. ✅ Implemented and verified 2026-08-10.** New
      `vps/include/IHvacBackend.h`: a small interface (`getValue`/`setValue`/`setChangeCallback`)
      covering pure value storage/IO, with a `BackendChangeCallback` the backend uses to report
      every value change — whether caused by its own `setValue()` or self-originated (e.g. a
      simulated or real sensor). `vps/include/FakeHvacBackend.h` / `vps/src/FakeHvacBackend.cpp`:
      the old `mStore`/`seedDefaults()`/`simulationLoop()` extracted out of `HvacHandler` verbatim
      (same seeded defaults, same 5s outside-temp drift tick, same "setValue() echoes immediately"
      contract) behind that interface. `HvacHandler` no longer owns storage at all — `mStore` is
      gone from it entirely; `getProperty()`/`setProperty()` still validate against `mConfigs`
      (Stage 1) exactly as before, then delegate the actual read/write to `mBackend`, an
      `IHvacBackend` it now holds by `std::unique_ptr`. Subscription bookkeeping
      (`mSubscribedKeys`/`mCallback`, `subscribe()`/`unsubscribe()`) stayed on `HvacHandler` rather
      than moving to the backend — deliberate scope call: which `(propId, areaId)` keys the Java
      layer wants events for is a routing concern independent of where a value physically lives,
      and `sampleRateHz` (the one genuinely backend-specific piece of a subscription — see the
      "advisory" comment on `IVpsHandler::subscribe`) isn't exercised or tested by anything today,
      so forwarding it into `IHvacBackend` was left out as speculative rather than built as
      not-really-used surface. The old `notify(propId, areaId)` became
      `onBackendValueChanged(propId, areaId, value)`, wired up once in the constructor as
      `mBackend`'s change callback — same subscription-gating logic as before (no-op if nobody's
      subscribed to that exact key), just fed by the backend instead of reading straight out of a
      store `HvacHandler` no longer has. Added a second constructor,
      `explicit HvacHandler(std::unique_ptr<IHvacBackend> backend)` — the default constructor just
      delegates to it with a `FakeHvacBackend` — which is the seam a future `RealCanHvacBackend`
      would plug into without touching `HvacHandler` itself. One correctness point handled
      explicitly: `~HvacHandler()` calls `mBackend.reset()` as its entire body (rather than relying
      on implicit member teardown) so `FakeHvacBackend`'s simulation thread is guaranteed fully
      joined — and therefore can't fire the change callback into a partially-destroyed
      `HvacHandler` — before the destructor returns. `vps/Android.bp` updated to add
      `src/FakeHvacBackend.cpp` to `libvps`'s `srcs`. Added
      `HvacHandlerTest.InjectedBackendIsUsedInsteadOfDefaultFakeHvacBackend`, using a second,
      independent test-only `IHvacBackend` implementation (`StubHvacBackend`, sharing no code with
      `FakeHvacBackend`) to prove `HvacHandler` actually routes get/set through whatever backend is
      injected, and that a change the backend originates on its own (not via `setValue()`) still
      reaches a subscriber — the two behaviors a real backend swap would depend on. **Verified:**
      same method as Stages 1–2 — standalone host `g++ -std=c++17 -Wall -Wextra -Werror` compile of
      `vps/src/*.cpp` and both test files, linked against real gtest sources from
      `external/googletest/`; all 13 tests (9 `HvacHandlerTest` + 4 `VpsDispatcherTest`) pass. Not
      yet done: an actual Soong-built `libvps_test` run via `atest`/`device-tests` on a real
      device/emulator, and a `RealCanHvacBackend` itself (Stage 3 only built the seam, not a real
      implementation to plug into it — there's no real CAN/ECU integration in this project).
    - **Stage 4 — real Binder HAL boundary.** Move `VpsDispatcher` out of in-process `libvps.so`
      into its own AIDL service/process, registered with `servicemanager`, with
      `base_comfort_vhal_jni.cpp` becoming a Binder client instead of a same-process call bridge —
      this is the previously-rejected "real `IVehicle` HAL binary" alternative from
      06-technical-requirements.md #7. Not started; recommended, if ever pursued, to consume AOSP's
      real prebuilt `android.hardware.automotive.vehicle` AIDL library rather than hand-writing a
      custom interface, which would replace Stage 2's hand-rolled `VpsPropertyId.h` with the real
      `VehiclePropertyIds` constants for free.
14. **Real-VHAL-id cross-reference in logs/`dump()`, and keeping it out of the
    Manager↔Service dependency graph (2026-08-10).** A `Map<Integer, Integer>`
    (`REAL_VHAL_PROPERTY_IDS`) mapping each of this stub's `HvacProperties` propIds to the
    equivalent real `android.car.VehiclePropertyIds` constant (values confirmed against
    `packages/services/Car/car-lib/src/android/car/VehiclePropertyIds.java`, present in this tree —
    not guessed), exposed via a `realVhalPropertyIdLabel(propId)` helper returning e.g.
    `"0x15400500"` or `"none"` for the one propId with no real analog (`PROP_VEHICLE_STATE`, this
    demo's own "is the panel locked" concept). Purely a debug/logging aid — never consulted for
    routing/validation. Used in two places: `AllianceCarHvacManager.setProperty()`'s existing debug
    log line, and `AllianceCarHvacService.handleDumpGet()`'s per-property output (see item 4's
    example line above).
    - **Deliberately duplicated, not shared, between Manager and Service.** First pass put this map
      in `HvacProperties.java` (shared by both, since both already depend on it independently) and
      had `AllianceCarHvacService.dump()` call into `AllianceCarHvacManager.realVhalPropertyIdLabel()`
      directly for convenience — reverted immediately: `AllianceCarHvacService` (system service)
      must never reference `AllianceCarHvacManager` (client SDK) or anything under
      `com.kpit.hvac.manager` at all; the two are only supposed to talk over the `IHVACVehicleService`/
      `IHVACVehicleCallback` AIDL contract, in one direction (Manager → Service). Landed on: each
      class owns its own independent copy of `REAL_VHAL_PROPERTY_IDS`/`realVhalPropertyIdLabel()` —
      `AllianceCarHvacManager`'s stays in that class; `AllianceCarHvacService`'s is local to
      `AllianceCarHvacService.java` alongside its own local `PROP_*`/`AREA_*` int constants (also
      deliberately not imported from `HvacProperties` — see below), all "kept in sync by hand" the
      same way `vps/include/VpsPropertyId.h` stays in sync with `HvacProperties.java` across the
      JNI boundary.
    - **`AllianceCarHvacService` no longer imports anything from `com.kpit.hvac.manager`.** It
      declares its own local `private static final int PROP_AC_STATE = 0x11200001;` etc. (12
      propIds) and `AREA_GLOBAL`/`DRIVER`/`PASSENGER`, numerically identical to `HvacProperties.java`'s
      copy, instead of importing `HvacProperties`. These are raw ints that cross the AIDL/Binder
      boundary as-is (`setVehicleProperty(int id, ...)`), so correctness only requires the two
      copies to match numerically, not to be the same Java field — same reasoning as the
      Manager/Service map duplication above. Note this only removes the *code-level* dependency:
      `hvac-service`'s `Android.bp` still statically links the whole `hvac-manager-sdk` module (which
      is where `AllianceCarHvacManager.class` actually lives), so the class is still present on the
      service's build classpath/APK even though nothing in the service calls it — closing that would
      mean splitting `hvac-manager-sdk` into a shared AIDL+`HvacEvent`+`HvacProperties` module plus a
      client-only module for `AllianceCarHvacManager`/`HvacListener`/`SystemListener`/
      `IHvacController`. Discussed, not done.
    - **Not compile-verified** — no `javac`/Android SDK available in this session (same limitation
      noted throughout this doc); verified instead by full manual re-reads of every changed file
      after each move.

### ✅ Bluetooth domain (Connectivity) — complete
1. `IviBluetoothManager` — extends `BaseConnectivityManager<IIviBluetoothService>`, same
   `ServiceManager.getService()` pattern as `AllianceCarHvacManager`.
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
4. **✅ `dumpsys hvac_service` debug entry point (`--get` + `--set`) — implemented 2026-08-10, not yet
   rebuilt/tested on device.** `mBinder` (the `IHVACVehicleService.Stub` registered via
   `ServiceManager.addService`) now overrides `dump(FileDescriptor fd, PrintWriter pw, String[] args)`
   in `AllianceCarHvacService.java`, since `dumpsys hvac_service` calls `dump()` on whatever `IBinder` that name
   resolves to — not a method on the `Service` class itself. Property access previously only worked via
   `adb shell service call hvac_service 1 i32 <id> i32 <area> f <value>` (the real AIDL transaction —
   see [11-testing-hvac.md](11-testing-hvac.md)); this is a second, human-readable entry point
   alongside it, not a replacement. Still not the same route as the real AOSP
   `dumpsys android.hardware.automotive.vehicle.IVehicle/default --set HVAC_TEMPERATURE_SET ...` —
   this repo doesn't implement or register that VHAL service at all, and has no `HVAC_TEMPERATURE_SET`-
   style property names (custom `HvacProperties` ints instead). On-device foundation for item 5 below
   (`vspManagerTool`). Implementation:
   - `mBinder.dump()` dispatches to `handleDumpGet(pw)` (no args, or anything not starting with
     `--set`) or `handleDumpSet(pw, args)` (first arg `--set`).
   - **`handleDumpGet`:** iterates the existing `GLOBAL_PROPS`/`PER_SEAT_PROPS` arrays already in
     `AllianceCarHvacService.java` (lines 74–90), calls `nativeGetFloatProperty(handle, propId, areaId)` for each
     (global props at area `AREA_GLOBAL`, per-seat props at both `DRIVER` and `PASSENGER`), and prints
     one parseable line per property, e.g. `PROP_TEMP (real VHAL=0x15600503) area=1 value=22.5` (the
     `(real VHAL=...)` cross-reference was added later — see the "HVAC domain" section's item 14
     above). A snapshot, not a
     subscription — `dump()` is a synchronous one-shot Binder call with no push capability, so anything
     reading it has to poll and diff (see item 5's Watch behavior).
   - **`handleDumpSet`:** parses `--set <PROP_NAME> -a <area> -f <value>`, resolves the name to a
     `HvacProperties` int via a new `idOf()` lookup (and `nameOf()` for the reverse, used by
     `handleDumpGet`), then calls the sibling `setVehicleProperty(propId, area, value)` method already
     on `mBinder` — the same AIDL method a real caller uses, so it round-trips through
     `HvacHandler`'s echo-on-write path and reaches any listening app, same as `11-testing-hvac.md`'s
     `service call` route.
   - No VPS/JNI changes — both handlers reuse `AllianceCarBaseService`'s existing native bridge methods.
   - **Not yet done:** rebuild + boot-test against the emulator to confirm `adb shell dumpsys
     hvac_service` and `adb shell dumpsys hvac_service --set PROP_TEMP -a 1 -f 24.0` behave as designed.
5. **`vspManagerTool` — standalone Windows GUI, replaces the earlier on-device "kitchen sink" app
   idea** — no persistence exists anywhere in this repo today (no Room/SQLite/SharedPreferences/file
   I/O; confirmed by full-repo search 2026-08-07) and there's no way to watch or set HVAC values
   except one-shot adb commands run by hand. Decided 2026-08-07 to build this as a **host-side Java
   GUI tool that runs on the developer's Windows machine, not an Android app inside the
   emulator/device** — no new AIDL, no new on-device app, no Room DB in-emulator. Plan (not
   implemented; item 4's on-device `dump()` half is now done, see above):
   - Plain Java desktop app (Swing/JavaFX), packaged as a runnable jar, run on Windows against an
     emulator/device reachable over `adb` (same adb the host already uses to talk to the emulator).
   - **Transport is adb only** — the tool shells out via `ProcessBuilder`, it does not open its own
     socket to the device. Depends entirely on item 4's `dump()` override existing on-device first
     (now done).
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
     exactly as volatile as it is today (`FakeHvacBackend`'s `mStore`, Stage 3, is still lost on
     process restart).
   - **Scope:** HVAC first (`hvac_service`'s `GLOBAL_PROPS`/`PER_SEAT_PROPS`); extending the same
     `dump()` pattern to `IviBluetoothService` for Bluetooth state is a stretch goal, not required for
     v1.
   - **Full property surface (14 rows the GUI's table must cover, discussed 2026-08-10, prop ids
     updated 2026-08-10 for Stage 2's bit-packed scheme — see the "HVAC domain" section's item 13
     above)** — exactly what `AllianceCarHvacService.handleDumpGet` emits, nothing more. Since Stage
     2 (item 13 above), prop ids are no longer flat `1..12` -- they're bit-packed the same way real
     `VehiclePropertyIds` are (`vps/include/VpsPropertyId.h`); each `dump()` line also now prints a
     `(real VHAL=0x...)` cross-reference label (`HvacProperties.realVhalPropertyIdLabel()`/
     `AllianceCarHvacService`'s own copy of the same table — see the "HVAC domain" section's item 14
     above for notes on that duplication), not shown as its own column below since it's derived from
     the prop id, not
     independent data the GUI needs to track:

     | Prop id | Name | Areas | Value | Notes |
     |---|---|---|---|---|
     | `0x11200001` | `PROP_AC_STATE` | 0 (global) | bool | interlock root — see below |
     | `0x11200002` | `PROP_MAX_STATE` | 0 | bool | |
     | `0x11200003` | `PROP_RECYCLE_STATE` | 0 | bool | |
     | `0x11400004` | `PROP_FAN_SPEED` | 0 | int 0–12 | |
     | `0x11200006` | `PROP_SYNC` | 0 | bool | |
     | `0x11200009` | `PROP_AUTO_MODE` | 0 | bool | |
     | `0x1120000A` | `PROP_DEFROST` | 0 | bool | |
     | `0x11400008` | `PROP_VENTILATION_MODE` | 0 | int 1/2/3 (foot/foot+face/face) | |
     | `0x1140000B` | `PROP_VEHICLE_STATE` | 0 | int | interlock gate: `>=5` unlocks AC (`--set PROP_VEHICLE_STATE -a 0 -f 5.0` — worth a dedicated GUI button, not just a generic row, since it's the prerequisite for every other row meaning anything, per [11-testing-hvac.md](11-testing-hvac.md) Step 1) |
     | `0x1160000C` | `PROP_TEMP_OUTSIDE` | 0 | float °C | drifts every 5s on its own (`FakeHvacBackend::simulationLoop()`, Stage 3) — a manual `--set` will visibly snap back within ~5s; the GUI should note this rather than treat the snap-back as a bug |
     | `0x15600005` | `PROP_TEMP` | 1 (driver), 2 (passenger) | float °C, 0.5° steps | two rows |
     | `0x15200007` | `PROP_SEAT_HEATING` | 1 (driver), 2 (passenger) | bool | two rows |

     No area-0 row exists for `PROP_TEMP`/`PROP_SEAT_HEATING` — `handleDumpGet` only ever queries
     driver/passenger for those two. Seat domain properties don't exist yet (item 1 above) so there's
     nothing to add for Seat until that Java layer lands.

### Session state as of 2026-08-10 (end of session)
Everything below was done in one continuous session; recorded here as a single checkpoint for
picking the work back up.

**Landed and verified this session:**
- `dumpsys hvac_service` `--get`/`--set` debug entry point (item 4 above).
- VHAL-alignment Stages 1–3 (item 13 above): config-driven property validation
  (`VpsPropConfig`), bit-packed propId encoding matching real `VehiclePropertyIds`
  (`VpsPropertyId.h`), and the pluggable `IHvacBackend`/`FakeHvacBackend` split. All three verified
  via standalone host `g++ -Wall -Wextra -Werror` compiles + a real gtest run (13/13 passing) — no
  Soong/AOSP build environment was available in this session, so that's the ceiling of verification
  reached. **Not done: an actual Soong-built `libvps_test` run via `atest`/`device-tests`, or any
  on-device/emulator boot test of anything changed this session** (dump(), Stage 1–3, or the rename
  below) — nothing in this session touched a real device.
- Real-VHAL-id cross-reference in logs/`dump()` (item 14 above), including the deliberate decision
  to duplicate rather than share it between `AllianceCarHvacManager`/`AllianceCarHvacService`.
- **Rename: `HvacManager`→`AllianceCarHvacManager`, `HvacService`→`AllianceCarHvacService`,
  `BaseComfortManager`→`AllianceCarBaseManager`, `BaseComfortService`→`AllianceCarBaseService`.**
  Touched every real dependency (imports, `extends` clauses, `AndroidManifest.xml`'s `<service
  android:name>`, all 10 JNI-exported symbol names in `base_comfort_vhal_jni.cpp`, `HvacApplication`,
  `HvacViewModel`) plus prose across every doc in this tree. Packages were deliberately left
  unchanged (`com.kpit.hvac.*`, `com.kpit.comfort.base.*`). **Not compile-verified** — no
  `javac`/Android SDK available in this session; verified instead by full manual re-reads of every
  changed Java file, cross-checked with `grep` for leftover old-name references (found none).
- A full docs-consistency audit (this and the previous turn) found and fixed: a stale `vps/`
  directory tree in [02-directory-structure.md](02-directory-structure.md); ~40 `adb shell service
  call` example commands across [11-testing-hvac.md](11-testing-hvac.md) that still used the
  pre-Stage-2 flat decimal propIds (`1`..`12`) and would have failed if run as-written; several
  stale `file.java:NN` line citations shifted by this session's edits; and two pre-existing
  off-by-one item-number cross-references between this file and
  [06-technical-requirements.md](06-technical-requirements.md) (unrelated to this session's changes,
  found only because this audit checked every cross-reference). One self-inflicted conflict was
  caught and reverted: an earlier pass in this same audit "fixed" `HvacProperty` → `HvacProperties`
  in two places, not realizing 06-technical-requirements.md #13 explicitly documents that exact
  spelling as intentional shorthand — reverted both back to `HvacProperty`. Historical build-log
  quotes (verbatim compiler errors, dated incident reports) were deliberately left untouched even
  where they cite now-stale line numbers or propId values, since editing them would misrepresent
  what actually happened at the time.

**Not done, in rough priority order for a future session:**
1. Any real Soong/AOSP build or on-device/emulator test of anything above — everything this session
   was verified by host-side compilation/manual review only.
2. VHAL-alignment Stage 4 (item 13 above) — real Binder HAL boundary. Not started; explicitly the
   most expensive stage, only recommended if ever pursued to also consume AOSP's real
   `android.hardware.automotive.vehicle` AIDL library rather than the hand-rolled `IVpsHandler`.
3. Splitting `hvac-manager-sdk` into a shared AIDL+`HvacEvent`+`HvacProperties` module plus a
   client-only module for `AllianceCarHvacManager`/`HvacListener`/`SystemListener`/
   `IHvacController` (discussed in item 14 above) — would remove `AllianceCarHvacManager.class` from
   `hvac-service`'s build classpath entirely, not just from its source code.
4. Seat domain, WiFi placeholder, `SeatHandler` (items 1–3 above) — unstarted, pre-dates this
   session.
5. `vspManagerTool` (item 5 above) — planned, not implemented; the on-device `dump()` half it
   depends on is done.
