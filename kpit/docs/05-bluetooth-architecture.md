# V. Bluetooth Architecture (No VHAL/JNI)

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
same way, so the rest of the command/event flow ([04-signal-flow.md](04-signal-flow.md)) is unchanged.

**Car-side profile roles invert from the phone's:**
| Profile | Phone's role  | Car's role | Car-side API                                                                         |
|---------|---------------|------------|--------------------------------------------------------------------------------------|
| HFP     | Audio Gateway | Hands-Free | `BluetoothHeadsetClient`                                                             |
| A2DP    | Source        | Sink       | `BluetoothA2dpSink`                                                                  |
| AVRCP   | Target        | Controller | `MediaSessionManager`/`MediaController` (see above — not `BluetoothAvrcpController`) |

**Vehicle vs. Bluetooth signal path, side by side:**
| Step       | Vehicle (Hvac/Seat)                                      | Bluetooth                                                                                                            |
|------------|----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| Dispatch   | JNI → `VpsDispatcher` (in-process) → `HvacHandler`       | `IviBluetoothService` → `BluetoothAdapter` (HFP/A2DP) + `MediaSessionManager` (AVRCP)                                |
| Read/write | `nativeGet/SetFloatProperty`                             | `getConnectedDevices()` (HFP/A2DP); `MediaController.getMetadata()`/`getPlaybackState()`/`TransportControls` (AVRCP) |
| Push       | ECU → JNI callback → `onVehiclePropertyChanged`          | `BroadcastReceiver` (HFP/A2DP) / `MediaController.Callback` (AVRCP) → same Service→Manager→ViewModel→HMI fan-out     |

### Protocol primer — what HFP/A2DP/AVRCP each carry
Three separate profiles, three separate jobs — none subsumes another:

| Profile   | Transport                 | Carries                                      | Codec                                |
|-----------|----------------------------|----------------------------------------------|---------------------------------------|
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
[04-signal-flow.md](04-signal-flow.md). Both directions still run off the main thread here too
(06-technical-requirements.md #1).

### Fixing "not sync" (06-technical-requirements.md #5)
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
- Declared in `bluetooth/AndroidManifest.xml`, layered per the general/domain-specific split in
  06-technical-requirements.md #4.
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
  `privapp_permissions_bluetooth.xml` (06-technical-requirements.md #4 /
  [10-build-and-product-integration.md](10-build-and-product-integration.md));
  `MEDIA_CONTENT_CONTROL` was missing from it until this fix even though it was already declared in
  the manifest, because it was added to the manifest during the AVRCP rework without updating the
  allowlist file to match.

### Build note
HFP/A2DP (`BluetoothHeadsetClient`/`BluetoothA2dpSink`) are the same category as
`ServiceManager.addService/getService` (06-technical-requirements.md #6) — need
`platform_apis: true`, not an `sdk_version` stub, because those proxy classes are hidden
`@SystemApi`. AVRCP's `MediaSessionManager`/`MediaController` path is fully public SDK API and
needs no special sdk handling on its own — `platform_apis: true` stays only because HFP/A2DP (and
`ServiceManager`, #6) still need it.
