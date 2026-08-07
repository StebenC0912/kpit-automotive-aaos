# XII. Functional Testing — Bluetooth App (manual, no source changes)

Companion to [11-testing-hvac.md](11-testing-hvac.md), for `bluetooth_app`. Written 2026-08-02, not
yet executed (no adb/emulator available in the environment this was written from — run it on the
Windows dev machine, [08-emulator-setup.md](08-emulator-setup.md)). Structurally different from
HVAC's test plan: there's no VPS/JNI stub to poke via `service call` for the actual
connection/media state — that state is owned entirely by Android's real Bluetooth stack
(`BluetoothHeadsetClient`/`BluetoothA2dpSink`) and the `MediaSessionManager`/`MediaController`
session framework ([05-bluetooth-architecture.md](05-bluetooth-architecture.md), "no VHAL/JNI").
Two things to know before testing:

**Needs the [06-technical-requirements.md](06-technical-requirements.md) #16 fix to boot at all.**
`bluetooth_service` had the same "never registered" gap as `hvac_service`
([11-testing-hvac.md](11-testing-hvac.md)'s note) — `persistent="true"` doesn't start
`IviBluetoothService` on its own, only `com.kpit.bluetooth`'s `Application`. Fixed via
`IviBluetoothApplication`; needs a rebuild + fresh boot before Tier 1 below (or the
`bluetooth_service` line in Tier 2 step 3) will find the service at all.

**Build confirmed (2026-08-03), functional test still outstanding.** The #16/#17/#18 rebuild that
fixed and confirmed `hvac_app`/`hvac_service` ([11-testing-hvac.md](11-testing-hvac.md)) included
`bluetooth-app`/`bluetooth-service` in the same build and boot — it compiled and booted clean, so
the same fixes are in this image. Tier 1/Tier 2 below have not actually been run yet (no Bluetooth
peer used in that pass) — don't assume pass, this still needs its own run.

**Manual workaround (only needed on a boot image that predates the #16 fix):** start the service
by hand before running Tier 1 below. With the Netsim two-AVD setup ([08-emulator-setup.md](08-emulator-setup.md))
more than one device is attached, so every `adb` command below needs `-s <serial>` — plain `adb root`/
`adb shell ...` fails with `more than one device/emulator` once a second AVD is running. Get serials
from `adb devices` first (example below uses `emulator-5554` for the Automotive guest):
```bash
adb devices
adb -s emulator-5554 root
adb -s emulator-5554 shell am start-service com.kpit.bluetooth/com.kpit.bluetooth.service.IviBluetoothService
```
If you see `Error: Requires permission com.kpit.bluetooth.permission.BIND_BLUETOOTH_SERVICE`, root
didn't actually apply before the `start-service` call — confirm `adb -s emulator-5554 root` printed
`restarting adbd as root` (or `adbd is already running as root`), then retry the `start-service` line.

**Second bug found right after #16, same as HVAC's (#17):** `IviBluetoothManager` never connected
to the service (never sent the remote `registerListener()`) until an outbound `connect()`/
`disconnect()`/`sendMediaCommand()` call happened — and since `bluetooth_app` has no connect button
at all, that could easily never happen. Fixed by making `registerBluetoothListener()` force the
connection; see 06-technical-requirements.md #17. The "Expect `IviBluetoothManager: Register
successfully` shortly after boot" line in Tier 1 below now actually holds — before this fix it did
not, regardless of how long you waited.

**No unlock gate, but also no connect button.** Unlike HVAC's `PROP_VEHICLE_STATE` lock,
`BaseConnectivityService.onCreate()` runs `onConnectivitySourceConnect()` immediately
(`service/connectivity/base/src/com/kpit/connectivity/base/service/BaseConnectivityService.java:51`),
so `bluetooth_app` is interactive the instant it launches, once the service above is actually
running — no per-boot nudge needed. But
`IviBluetoothManager.connect(mac)`/`disconnect(mac)`
(`service/connectivity/bluetooth/src/com/kpit/bluetooth/manager/IviBluetoothManager.java:79-103`)
are fully wired to the AIDL service yet never called by `BluetoothViewModel` or `MainActivity` —
there is no connect/disconnect button anywhere in `activity_main.xml`. `bluetooth_app` is a pure
connection-status/media display; pairing has to happen through the OS's own Bluetooth Settings UI
(or adb), not this app. Not a bug to fix, just a fact to know before hunting for a UI element that
isn't there by design.

**Stale doc comment, not a functional bug:** `MediaAction.java`'s class Javadoc still describes
commands being "translated into AVRCP passthrough key codes" via
`BluetoothAvrcpController#sendPassThroughCmd()` — leftover from before the AVRCP rework in
[05-bluetooth-architecture.md](05-bluetooth-architecture.md). The real code path
(`IviBluetoothService.dispatchMediaCommand()`) uses
`MediaController.TransportControls.play()/pause()/skipToNext()/skipToPrevious()`. Left as-is (no
source changes requested for this pass); noted here so it isn't mistaken for the actual behavior
while reading logs.

**Known blocker (2026-08-04): A2DP connects in source role, not sink — Tier 2 music/metadata test
cannot pass as-is.** HFP connects and works correctly (`HeadsetClientService`, car as Hands-Free
client, confirmed via `service call bluetooth_service 1 s16 "<MAC>"` → `dumpsys bluetooth_manager`
showing `state=Connected`). A2DP does not: `dumpsys bluetooth_manager` shows `Profile: A2dpService`
(source role) with `mActiveDevice` set to the peer and `AvrcpTargetService` actively streaming
`com.android.car.radio` out to it, while `Profile: A2dpSinkService` (the role
`IviBluetoothService.connectDevice()` actually requests via `mA2dpProxy.setConnectionPolicy(device,
ALLOWED)`) stays at `Devices Tracked = 0` — the sink-role request has no visible effect while the
source-role link to the same peer is already active. This is why `bluetooth_app` never shows
device/media info even though OS Settings reports "Connected" and HFP genuinely works.

Root cause is **not** in `vendor/kpit/` — confirmed via `grep` that neither `device/generic/car/`
nor `device/google_car/` (the device trees this build uses) ship any `packages/apps/Bluetooth`
overlay, unlike other sink-role reference devices (e.g. `device/amlogic/yukawa/overlay/packages/apps/Bluetooth/res/values/config.xml`,
which explicitly sets `profile_supported_a2dp=false` / `profile_supported_a2dp_sink=true`). Without
that override this build appears to leave both A2DP source and sink support enabled, so the car can
register as a source (offering `com.android.car.radio`) instead of being sink-only as a head unit
should be — and whichever role gets negotiated first during pairing wins, blocking the other.
(Can't confirm the literal default flag value in `config.xml` — the Bluetooth app/module is a
prebuilt mainline APEX in this tree, not checked-out source; this conclusion is inferred from the
dumpsys behavior plus the missing device-tree override.)

**Fix (not applied — out of scope for `vendor/kpit/`, needs `device/generic/car/` owner):** add a
`packages/apps/Bluetooth` RRO/overlay under `device/generic/car/` setting
`profile_supported_a2dp=false` / `profile_supported_a2dp_sink=true`, mirroring `yukawa`'s pattern, so
the car can no longer register as an A2DP source at all. `IviBluetoothService.connectDevice()`'s
existing sink-role `setConnectionPolicy()` call should work once that's in place — no `kpit` source
changes anticipated. Until then, Tier 2 steps 4-6 below (device info populating, music
metadata/playback control) cannot pass, even with the correct real peer MAC address in uppercase.

**Demo path around the blocker (no code/device-tree changes): HFP-only connection.** `toDeviceInfo()`
builds a fresh `BluetoothDeviceInfo` per profile event
(`IviBluetoothService.java:378`), so an HFP-only connect is enough to drive a real, non-fake "device
connected" screen: `tvDeviceName`/`tvMacAddress` populate, the HFP badge (`tvProfileHfp`) goes to
full opacity, A2DP/AVRCP badges stay dimmed at `0.3` — all through the real
`onDeviceConnectionChanged()` broadcast path, no music metadata involved. To show it:
```powershell
adb -s emulator-5554 shell service call bluetooth_service 1 s16 "<PEER_MAC_UPPERCASE>"
```
Then confirm in `dumpsys` and open `bluetooth_app` (`com.kpit.hmi.bluetooth`) — device name/MAC and
the lit HFP badge should be visible immediately, demonstrating the AIDL→service→real-Bluetooth-stack
chain works end to end for the profile that isn't blocked by the A2DP role bug above.

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

**Troubleshooting: app shows no device info even though the OS reports the two emulators paired.**
"Connected" in the OS Bluetooth Settings UI only means bonded, not that HFP/A2DP profiles connected,
and separately the app only calls `registerBluetoothListener()` once (on start/first UI action) — if
`bluetooth_app` was already running before the service came up (manual workaround above, or a service
restart), it never retries. Check both, always with `-s <serial>` if more than one emulator is attached:
```bash
adb -s emulator-5554 logcat -s IviBluetoothManager:* IviBluetoothService:*
```
Look for `IviBluetoothManager: Register successfully`. If it's missing, force-stop and relaunch the
app so it registers against whatever service instance is currently alive — note the **app** is
`bluetooth_app`, package `com.kpit.hmi.bluetooth` (`hmi/bluetooth_app/`), a different package from
the **service**, `com.kpit.bluetooth` (`service/connectivity/bluetooth/`) used in the manual-workaround
commands above — force-stopping/starting the wrong one either no-ops or (for `am start`) fails with
`Activity class ... does not exist`:
```bash
adb -s emulator-5554 shell am force-stop com.kpit.hmi.bluetooth
adb -s emulator-5554 shell am start com.kpit.hmi.bluetooth/.MainActivity
```
If `IviBluetoothService: onCreate` appears more than once in the log with no corresponding
`force-stop com.kpit.bluetooth` from you in between, the service process is being killed by something
else — dump the buffered log from around that time and filter for a crash, substituting the actual
timestamp of the first `onCreate`. `grep` isn't available in plain `cmd.exe` — use PowerShell's
`Select-String`, or drop the filter in `cmd.exe` and search the dump by eye:
```powershell
adb -s emulator-5554 logcat -d -T "08-04 13:24:41.000" | Select-String -Pattern "FATAL","AndroidRuntime","kpit.bluetooth"
```
```cmd
adb -s emulator-5554 logcat -d -T "08-04 13:24:41.000" > crashdump.txt
notepad crashdump.txt
```

**Root cause found (2026-08-04): lowercase MAC address crashes the service.**
`BluetoothAdapter.getRemoteDevice()` validates its argument against a case-sensitive regex requiring
**uppercase** hex digits and throws `IllegalArgumentException` on anything else — including a
syntactically valid but lowercase address, e.g. one copied straight out of
`/data/misc/bluedroid/bt_config.conf`, which always prints lowercase. That exception was uncaught on
the executor thread inside `connectDevice()`/`disconnectDevice()`
(`IviBluetoothService.java`), which killed the whole `persistent="true"` process — explaining the
repeated unprompted `onCreate` restarts seen while testing transaction `1`/`2` below. Confirmed via:
```
java.lang.IllegalArgumentException: bb:bb:bb:00:00:02 is not a valid Bluetooth address
	at android.bluetooth.BluetoothAdapter.getRemoteDevice(BluetoothAdapter.java:1087)
	at com.kpit.bluetooth.service.IviBluetoothService.connectDevice(IviBluetoothService.java:307)
```
Fixed in source: `connectDevice()`/`disconnectDevice()` now normalize the address to uppercase and
catch `IllegalArgumentException` (logs and no-ops instead of crashing the process). Still worth
passing an uppercase address by habit when testing manually — `service call` below.

### Tier 2 — real connection + media test (needs a peer)
No adb shortcut exists for this part — it's real OS Bluetooth state, not a local stub. Needs a real
phone or a second AVD (a Phone/Tablet image, not another Automotive one) with Bluetooth enabled,
both discoverable:

**Using a second AVD instead of a real phone:** a plain `emulator -avd <name>` doesn't expose Bluetooth
hardware, so two emulators can't see each other by default. Launch *both* AVDs (the Automotive guest
and the Phone/Tablet peer) with `-packet-streamer-endpoint default` added to the command line — this
connects each emulator's virtual Bluetooth radio to **Netsim**, the emulator's shared virtual
controller, starting Netsim automatically if it isn't already running. Any emulators pointed at the
same Netsim instance can then discover and pair with each other over the standard Settings pairing UI,
same as step 2 below. Requires emulator 33.1.10+ (older versions may need this flag regardless; check
`emulator -version`). The Phone/Tablet-not-Automotive requirement above still applies even with
Netsim — it's about which side of HFP/A2DP each stack plays (the car head unit connects as a
client/sink to a phone acting as source), not about whether Bluetooth hardware is reachable.
1. Enable Bluetooth on the guest: `adb shell cmd bluetooth_manager enable` (or via Settings).
2. Pair the two devices through the Automotive guest's standard Bluetooth Settings UI.
3. Since there's no in-app connect button, pairing itself should trigger HFP/A2DP auto-connect once
   the new bond's connection policy defaults to `ALLOWED`. If it doesn't auto-connect, drive it
   manually by hitting the same AIDL entry point `bluetooth_app` itself never calls:
   ```bash
   adb shell service call bluetooth_service 1 s16 "BB:BB:BB:00:00:22"
   ```
   Transaction `1` = `connect(String)`, the first method in
   `service/connectivity/bluetooth/aidl/com/kpit/bluetooth/IIviBluetoothService.aidl`; substitute
   the peer's real MAC address. `2` = `disconnect(String)`, same shape, for the reverse test.

   **"Connected" in Settings but the app still shows nothing:** Settings only reports the bond, not
   the HFP/A2DP profile connection `IviBluetoothService` actually reads from — this is the normal
   case the transaction-`1` fallback above is for. Full sequence, with `-s <serial>` if more than one
   emulator is attached. Run in PowerShell (`Select-String` is the `grep` equivalent, with `-Context`
   for the lines-after behavior `grep -A` gives); `cmd.exe` has no context-line option, so if you're
   stuck there, drop the filter and dump to a file instead:
   ```powershell
   # 1. Get the peer's MAC from the bonded-device list
   adb -s emulator-5554 shell dumpsys bluetooth_manager | Select-String -Pattern "Bonded devices" -Context 0,3

   # 2. Force the profile connect
   adb -s emulator-5554 shell service call bluetooth_service 1 s16 "<peer_MAC>"

   # 3. Watch for ACTION_CONNECTION_STATE_CHANGED while step 2 runs
   .\adb.exe -s emulator-5554 logcat -s IviBluetoothService:*

   # 4. If step 2 does nothing, check whether the bond's connection policy is even ALLOWED
   .\adb.exe -s emulator-5554 shell dumpsys bluetooth_manager | Select-String -Pattern "hfp","a2dp","policy","connection state"
   .\adb.exe -s emulator-5554 shell dumpsys bluetooth_manager | Select-String -Pattern "A2dpSinkService" -Context 0,5
   ```
   **MAC address must be uppercase.** `BluetoothAdapter.getRemoteDevice()` requires uppercase hex
   digits and throws on lowercase — which crashes the (`persistent="true"`) service process if
   uncaught, so use `BB:BB:BB:00:00:02` style, not the lowercase form `bt_config.conf` prints (see
   the root-cause note in Tier 1 above). Getting the peer's real address (Settings UI redacts it to
   the last 2 octets in `dumpsys`) requires root:
   ```powershell
   adb -s emulator-5554 root
   adb -s emulator-5554 shell cat /data/misc/bluedroid/bt_config.conf
   ```
   ```cmd
   :: cmd.exe fallback for steps 1 and 4 — no context lines, just dump and read
   adb -s emulator-5554 shell dumpsys bluetooth_manager > btdump.txt
   notepad btdump.txt
   ```
4. Confirm in the UI: device name/MAC populate, HFP/A2DP/AVRCP badges go full opacity. Watch
   `adb logcat -s IviBluetoothService:*` for `ACTION_CONNECTION_STATE_CHANGED` handling
   (`IviBluetoothService.handleBroadcast()`).
5. Start music playback on the phone — title/artist/album, the playback-state badge, and the
   position bar should populate via `MediaController.Callback`
   (`onMetadataChanged`/`onPlaybackStateChanged` → `publishMetadata()`/`publishPlaybackState()`).
6. Tap **Play / Pause / Next / Previous** in the app — should now control playback on the connected
   phone (command path, reverse direction from step 5, via `dispatchMediaCommand()`).

### Not covered by this pass
WiFi — not yet implemented ([03-implementation-status.md](03-implementation-status.md)), nothing to
test yet.
