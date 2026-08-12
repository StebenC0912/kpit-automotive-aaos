# XI. Functional Testing — HVAC App (manual, no source changes)

Procedure to exercise every `hvac_app` control end-to-end against the real `hvac-service` +
`vendor.kpit.vps-service` stack, without editing any source — plus two findings surfaced while
mapping the command/event path for this plan that change what "the button worked" actually means
per control. Written 2026-08-02. **Step 1 confirmed passing (2026-08-03)** after the fixes below —
the full 12-row control matrix in Step 2 hasn't been individually walked through yet, and as of
2026-08-12 nothing below has been re-run since VHAL-alignment Stage 4 moved `HvacHandler`/
`VpsDispatcher` out of `hvac-service` into their own daemon (see the new prerequisite section right
before Step 1).

**First real attempt (2026-08-03) failed at Step 1** with `Service hvac_service does not exist` —
`hvac_service` was never registered at all, because `persistent="true"` doesn't auto-start the
`<service>` inside the app (only its `Application`). Root cause and fix (`HvacApplication`) in
[06-technical-requirements.md](06-technical-requirements.md) #16. Needs a rebuild + fresh boot with
that fix before Step 1 below will work — if you still hit "does not exist" after rebuilding, check
`adb logcat -d | grep -i "avc:.*denied"` for a `service_manager` denial instead (the sepolicy problem
[10-build-and-product-integration.md](10-build-and-product-integration.md) separately describes).

**Manual workaround (only needed on a boot image that predates the #16 fix):** start the service
by hand before running Step 1 below:
```bash
adb root
adb shell am start-service com.kpit.hvac/com.kpit.hvac.service.AllianceCarHvacService
```

**Second bug found right after #16 — app never receives events at all (#17):** even with the
service running, injecting a property returned success but the app's panel never reacted.
`AllianceCarHvacManager` never connected to the service (and so never sent the remote `registerCallback()`)
until some outbound `setX()` call happened — but `toggleAc()` is itself gated on state that can
only arrive via that same callback, so nothing could ever break the cycle. Fixed by making listener
registration force the connection; see 06-technical-requirements.md #17. **Launch `hvac_app` before
running Step 1's `service call`**, not after — the service can only broadcast to listeners that were
already registered at the time the event fires.

**Both confirmed fixed (2026-08-03):** rebuilt with #16/#17 (plus `directBootAware`,
06-technical-requirements.md #18) and rebooted — Step 1 now works with no manual `am start-service`
needed, and the panel unlocks/reacts once `hvac_app` is launched first. Step 2's full per-control
matrix below is still to be walked through row by row.

### Why the panel is locked on first boot
`HvacViewModel` starts `mCurrentVehicleState = -1` and only enables the AC button — and, once AC is
toggled on, the rest of the panel — once `mCurrentVehicleState >= 5`
(`hmi/hvac_app/src/com/kpit/hmi/hvac/viewmodel/HvacViewModel.java:27,55,102-133`). That value only
moves via a `PROP_VEHICLE_STATE` event, and `FakeHvacBackend::seedDefaults()` (Stage 3; was
`HvacHandler::seedDefaults()` before the backend split) seeds it to `0.0f` with nothing in the file
ever touching it again (`vps/src/FakeHvacBackend.cpp:48`) — there's no simulated ignition/ECU
signal in this stub. Not an app bug; just means the panel needs one manual nudge per boot before
it's testable (Step 1 below).

### Fixed — `AllianceCarHvacManager.onChangeEvent` only dispatched 4 of the 12 property IDs (found 2026-08-02, fixed 2026-08-02)
`service/comfort/hvac/src/com/kpit/hvac/manager/AllianceCarHvacManager.java`'s binder callback originally
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
read `hvacListener.onACStateChanged(event.getValue() == 0)` — inverted. `AllianceCarHvacManager.setAcState()`
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

### Fixed — AC button never re-enables once vehicle state reaches ≥5 (found 2026-08-03, fixed 2026-08-03)
`hmi/hvac_app/src/com/kpit/hmi/hvac/viewmodel/HvacViewModel.java`'s `pushAboveState()` constructed
`HvacSystemAboveState` with its first two boolean arguments swapped relative to the constructor's
declared parameter order (`isAcEnable, isACActivate, isMaxActivate, isRecycleActivate`) — it passed
`(mIsAcOn, isAcEnabled, mIsMaxOn, mIsCycleOn)` instead of `(isAcEnabled, mIsAcOn, mIsMaxOn,
mIsCycleOn)`. Since `HvacActivity.renderHvacSystemAboveUi()` does
`btnAc.setEnabled(hvacSystemAboveState.isAcEnable())`, the button's clickability was actually
tracking whether AC was already on (`mIsAcOn`), not whether `mCurrentVehicleState >= 5`. The instant
vehicle state crossed 5 with AC still off, `isAcEnable()` returned `false` and the button stayed
disabled in the running app — the only way to unstick it was to inject the AC property from outside
the UI (e.g. an adb `service call`), which set `mIsAcOn = true` and made both swapped fields evaluate
`true` together, masking the bug rather than fixing it. Fixed by correcting the argument order to
`new HvacSystemAboveState(isAcEnabled, mIsAcOn, mIsMaxOn, mIsCycleOn)`.

### Fixed — Fan Up/Down buttons stay enabled past min/max speed (found 2026-08-03, fixed 2026-08-03)
`increaseFanSpeed()`/`decrementFanSpeed()` in `HvacViewModel` already guarded against sending an
out-of-range `setFanSpeed()` call past 0/12, but nothing tied `btnFanDown`/`btnFanUp`'s
`enabled`/alpha state to the current fan speed — they were only ever toggled by the overall panel
lock (`setPanelInteractivity`), so both buttons stayed visually enabled (and tappable, no-op) at
speed 0 and at speed 12. Fixed in `HvacActivity.java` by tracking `mCurrentFanSpeed`/
`mIsPanelEnabled` and adding `updateFanButtonsState()` (down disabled at 0, up disabled at 12, both
still gated by the panel lock), invoked from both the fan-speed observer and
`setPanelInteractivity`; removed the two fan buttons from the blanket panel-lock array so the two
enablement rules don't fight over the same views.

### Fixed — Temp Up appears to do nothing while Temp Down works (found 2026-08-03, fixed 2026-08-03)
A regression of the residual issue flagged in the "`AllianceCarHvacManager.onChangeEvent` only dispatched 4 of
the 12 property IDs" section above: `HvacListener.onTempChanged(int value, int area)` took an `int`,
so `AllianceCarHvacManager`'s dispatcher truncated the echoed float toward zero (`(int) event.getValue()`)
before it ever reached the ViewModel. `incrementTemp`/`decreaseTemp` step in 0.5°C, so every
increment lands on `X.5`; truncating `X.5` toward zero always rounds the *confirmed* value back down
to `X`, silently cancelling the +0.5 the instant the echo arrived, while decrements (landing on
`X.5`, then truncating to a lower whole number each time) always showed a net decrease. Net effect:
Temp Down looked correct every time, Temp Up looked like it did nothing. Fixed by changing
`onTempChanged` to take a `float` end-to-end — `HvacListener`'s interface, `AllianceCarHvacManager`'s dispatch
site (`service/comfort/hvac/src/com/kpit/hvac/manager/AllianceCarHvacManager.java`), and `HvacViewModel`'s
implementation — so the real 0.5° value survives the round trip instead of being cast to `int` along
the way.

### Added — debug logging across the full HVAC stack (2026-08-03)
Added `Log.d`/`ALOGD` tracing end-to-end for easier future debugging, covering: every HMI button
click and every `toggle*`/`increment*`/`decrement*` call in `HvacActivity`/`HvacViewModel` (with the
gating decision and outgoing command), every listener callback received from `AllianceCarHvacManager`,
`checkInterlockingAndEvaluate()`'s decision, and every `render*Ui`/`setPanelInteractivity` call;
`AllianceCarHvacManager`'s listener register/unregister, every outbound `setProperty`, every inbound
`onChangeEvent`, and dispatch fan-out counts; `AllianceCarHvacService`'s `setVehicleProperty`/native-set result,
callback register/unregister, per-property subscribe results, and `onVehiclePropertyChanged`
broadcasts; and the native VHAL path (`base_comfort_vhal_jni.cpp`, `VpsDispatcher.cpp`,
`HvacHandler.cpp`) — JNI init/release/subscribe, every native get/set call, dispatcher routing, and
the simulated ECU's store writes/notify/outside-temp drift loop. Not a bug fix — added specifically
to make the three bugs above (and future ones) easier to diagnose from `adb logcat`. Filter with
`adb logcat -s HvacActivity:* HvacViewModel:* AllianceCarHvacManager:* AllianceCarHvacService:* BaseComfortVhalJni:*
VpsDispatcher:* HvacHandler:*`.

### Clarification — what `adb shell service call hvac_service` actually round-trips through (2026-08-05, updated 2026-08-10 for Stage 3's backend split, updated again 2026-08-12 for Stage 4's out-of-process daemon — see 03-implementation-status.md #13)
Easy to misread as "injecting an event directly into VPS." It isn't — adb never talks to VPS at
all. As of Stage 4, it's also no longer a single-process round trip: `VpsDispatcher`/`HvacHandler`
now live inside `vendor.kpit.vps-service`, a separate daemon reached over the `vendor.kpit.vps`
AIDL interface, so the path crosses a real Binder process boundary twice (once out, once back for
the push). The round trip is **service (in) → AIDL → vps-service (validate, delegate to backend) →
backend (write + fire change callback) → vps-service (subscription check) → AIDL callback →
service (out) → app**:

```
adb service call → AllianceCarHvacService.setVehicleProperty()     [Binder, enters hvac-service]
                  → base_comfort_vhal_jni.cpp                      [now an AIDL/NDK Binder client]
                  → [process boundary] vendor.kpit.vps AIDL → VpsServiceImpl::setFloatProperty()
                  → VpsDispatcher::setFloatProperty()
                  → HvacHandler::setProperty()                 [validates config, delegates to backend]
                  → FakeHvacBackend::setValue()                [writes mStore, fires change callback]
                  → HvacHandler::onBackendValueChanged()        [subscription check, then forwards]
                  → VpsServiceImpl → [process boundary] IVpsCallback::onPropertyEvent()  [oneway push]
                  → (bound JNI callback) AllianceCarHvacService.onVehiclePropertyChanged()
                  → broadcastToListeners → IHVACVehicleCallback.onChangeEvent()  [back out to the app]
```

`AllianceCarHvacService.setVehicleProperty()` (`service/comfort/hvac/src/com/kpit/hvac/service/AllianceCarHvacService.java:94-108`)
itself has no code that calls `onChangeEvent`/`broadcastToListeners` — it only forwards the value
into VPS (now over AIDL, via the JNI Binder client) and returns. The event only fires because
`FakeHvacBackend::setValue()` (`vps/src/FakeHvacBackend.cpp:69-77`, Stage 3, byte-for-byte unchanged
by Stage 4) unconditionally fires its change callback on every write, which
`HvacHandler::onBackendValueChanged()` only forwards on if something is actually subscribed to that
exact `(propId, areaId)` — the same callback path the boot-time `subscribeToVehicleProperties()`
wired up — so adb's write and a real HMI-driven write are indistinguishable once they reach
`vps-service`, and both come back out through `hvac-service`, never directly from `vps-service` to
the app. See Step 0 above before relying on this path — it depends on `vps-service` actually being
up, which was unverified as of this update.

This echo-on-write pattern is not a stub/mock shortcut — `HvacHandler.h`'s class comment says it
mirrors real VHAL/ECU behavior deliberately: a command's `setProperty` call doesn't synchronously
return "it worked," the caller only learns a command took effect by observing the property change
event, same as it would for a change it didn't originate (e.g. `PROP_TEMP_OUTSIDE`'s
`simulationLoop()` drift, `FakeHvacBackend.cpp:98-114`, Stage 3, which writes `mStore` and fires
the change callback directly with no `setProperty()`/Binder call at all). What's stubbed here is
the "hardware" behind the store (an in-memory map instead of a real CAN bus/ECU, and an instant
echo instead of real transport latency) — not the echo-via-event design itself.

Contrast with a real vehicle-signal simulation tool (e.g. a VSP-style raw signal injector like
`vps: 11 00 11 01`): that class of tool writes directly into the VHAL's property cache and fires
the change event with **no app-facing command call in the loop at all** — the equivalent of only
`HvacHandler::simulationLoop()`'s direct `mStore` write, never `setVehicleProperty`/`setProperty()`.
This repo has no adb-reachable entry point shaped like that yet — the only external entry point
into VPS today is the command path (`setVehicleProperty`), which happens to also carry the
echo-back-out because this stub collapses "command" and "signal" into the same write. Building a
true signal-only injector would mean adding a bypass entry point (e.g. `HvacHandler::injectSignal()`
doing the same `mStore` write + `notify()` `simulationLoop()` already does) exposed outside the real
AIDL surface — e.g. via `Binder.onShellCommand()` / `adb shell cmd hvac_service ...` — so it stays
separate from `setVehicleProperty`, the way a real VSP tool is architecturally separate from an
app's command API. Not built as of this note; flagged here in case it's needed later.

### Step 0 — verify `vendor.kpit.vps-service` (Stage 4 daemon) is running (added 2026-08-12)
Since VHAL-alignment Stage 4 (`03-implementation-status.md` item 13), `HvacHandler`/`VpsDispatcher`
no longer run in-process inside `hvac-service` — they live in a separate vendor daemon,
`vendor.kpit.vps-service`, reached over the `vendor.kpit.vps` AIDL interface from
`base_comfort_vhal_jni.cpp` (now a Binder client, not a direct in-process caller). Every command in
Step 1–3 below depends on that daemon being up first; if it isn't, `hvac_service`'s calls never
reach `HvacHandler` at all.

**First real attempt (2026-08-12) found the daemon never starts** — absent from both
`adb shell ps -A` and `adb shell service list`, even though the build packaged the binary, init
script, and VINTF manifest fragment correctly (confirmed present under
`out/target/product/<device>/vendor/{bin/hw,etc/init,etc/vintf/manifest}/`, and
`vendor.kpit.vps.IVpsService/default` present in the built `vendor_service_contexts`). **Root
cause:** `vendor/kpit/automotive/sepolicy/` had `hal_vps.te` (declaring
`vendor_kpit_vps_default_exec` and `init_daemon_domain(vendor_kpit_vps_default)`) and
`service_contexts`, but no `file_contexts` — so `/vendor/bin/hw/vendor.kpit.vps-service` never
actually got labeled `vendor_kpit_vps_default_exec`. Without that label,
`init_daemon_domain`'s domain transition never fires, `AServiceManager_addService` in
`service_main.cpp` fails, its `CHECK_EQ` triggers `LOG(FATAL)`, and the process exits immediately —
invisible in a `ps` snapshot, not a hang or a silent no-op. **Fixed** by adding
`vendor/kpit/automotive/sepolicy/file_contexts` labeling that exact path, matching the pattern real
AOSP uses for `hal_vehicle_default_exec` (`system/sepolicy/vendor/file_contexts:16-17`).
**Confirmed fixed (2026-08-12)** — rebuilt with the `file_contexts` addition and rebooted;
`vendor.kpit.vps-service` now comes up. Step 1–3 below have not yet been individually re-walked
against the new daemon (only the daemon's presence was confirmed) — do that next.

Before running Step 1, confirm the daemon is actually up:
```bash
adb shell ps -A | grep vps-service
adb shell service list | grep vps          # expect: vendor.kpit.vps.IVpsService/default
adb logcat -s VpsService:*                 # tag set by service_main.cpp's SetDefaultTag
```
If it's still missing after a rebuild that includes the `file_contexts` fix, check for further
denials — the sepolicy for this daemon was hand-written and had never been run through
`checkpolicy`/booted before this pass, so more than one gap is plausible:
```bash
adb shell logcat -d | grep -i "avc:.*denied"
```

### Step 1 — unlock the panel
```bash
adb shell service call hvac_service 1 i32 289406987 i32 0 f 5.0
```
Transaction `1` = `setVehicleProperty` (first method in `IHVACVehicleService.aidl`), `11` =
`PROP_VEHICLE_STATE`, `0` = `AREA_GLOBAL`, `5.0` = value. This hits the exact same AIDL entry point
a real VHAL push would use — not a mock/bypass. Then tap **AC** once in the running app to flip
`mIsAcOn`, which unlocks the rest of the panel via `checkInterlockingAndEvaluate()`.

### Step 2 — per-control matrix
For each row: tap the control in the UI and watch for the expected visual change, then check
`adb logcat -s HvacViewModel:* AllianceCarHvacManager:* AllianceCarHvacService:* HvacHandler:*` for the call chain. The
"inject" command simulates the *event* path (as if the signal came from the vehicle instead of the
HMI) via the same `setVehicleProperty` hook as Step 1 — useful to test each property independently
of whatever the ViewModel's optimistic UI update is doing.

| Control          | ViewModel method                             | Prop id / area                    | Confirmed round trip?                                                                                      | Inject (event-path test)                               |
|------------------|----------------------------------------------|-----------------------------------|------------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| AC               | `toggleAc()`                                 | 0x11200001 / `GLOBAL`(0)                   | ✅ (polarity fixed above — verify it no longer flips back off right after turning on)                      | `service call hvac_service 1 i32 287309825 i32 0 f 1.0`       |
| Fan +/−          | `increaseFanSpeed()` / `decrementFanSpeed()` | 0x11400004 / `GLOBAL`(0)                   | ✅ (no optimistic update at all — bars move only via the echoed event)                                     | `service call hvac_service 1 i32 289406980 i32 0 f 7.0`       |
| Vehicle state    | n/a (system signal)                          | 0x1140000B / `GLOBAL`(0)                  | ✅                                                                                                         | `f 3.0` (drops below 5, re-locks AC) / `f 5.0`        |
| Outside temp     | n/a (system signal)                          | 0x1160000C / `GLOBAL`(0)                  | ✅ but logcat-only — `onTempOutsideChanged` only `Log.d()`s, no `LiveData`/UI                              | `f 30.0`                                              |
| Max              | `toggleMax()`                                | 0x11200002 / `GLOBAL`(0)                   | ✅ now dispatched — verify the command also carries `mIsMaxOn` after the Max/Sync fix below, not `mIsAcOn` | `service call hvac_service 1 i32 287309826 i32 0 f 1.0`       |
| Recycle          | `toggleRecycle()`                            | 0x11200003 / `GLOBAL`(0)                   | ✅ now dispatched                                                                                          | `... i32 287309827 i32 0 f 1.0`                               |
| Temp left/right  | `incrementTemp(area)` / `decreaseTemp(area)` | 0x15600005 / `DRIVER`(1) or `PASSENGER`(2) | ✅ now dispatched, but confirmed value is truncated to whole degrees (residual note above)                 | `... i32 358612997 i32 1 f 24.0`                              |
| Sync             | `toggleSync()`                               | 0x11200006 / `GLOBAL`(0)                   | ✅ now dispatched — verify toggle direction after the Max/Sync fix below                                   | `... i32 287309830 i32 0 f 1.0`                               |
| Seat heating L/R | `toggleSeatHeating(area)`                    | 0x15200007 / `DRIVER`(1) or `PASSENGER`(2) | ✅ now dispatched                                                                                          | `... i32 354418695 i32 2 f 1.0`                               |
| Ventilation mode | `toggleVentilationMode(mode)`                | 0x11400008 / `GLOBAL`(0)                   | ✅ now dispatched                                                                                          | `... i32 289406984 i32 0 f 2.0` (1=foot, 2=foot+face, 3=face) |
| Auto             | `toggleAuto()`                               | 0x11200009 / `GLOBAL`(0)                   | ✅ now dispatched                                                                                          | `... i32 287309833 i32 0 f 1.0`                               |
| Defrost          | `toggleDefrost()`                            | 0x1120000A / `GLOBAL`(0)                  | ✅ now dispatched                                                                                          | `... i32 287309834 i32 0 f 1.0`                              |

All 12 rows are now expected to round-trip; none should still land on
`AllianceCarHvacManager: onChangeEvent: not handle this property` in logcat. If any row still hits that log
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
adb shell service call hvac_service 1 i32 289406987 i32 0 f 5.0   # PROP_VEHICLE_STATE = 5 (enables AC)
adb shell service call hvac_service 1 i32 289406987 i32 0 f 3.0   # PROP_VEHICLE_STATE = 3 (below 5, re-locks AC)
adb shell service call hvac_service 1 i32 289406987 i32 0 f 0.0   # PROP_VEHICLE_STATE = 0 (default on boot)

# --- AC state ---
adb shell service call hvac_service 1 i32 287309825 i32 0 f 1.0    # PROP_AC_STATE on
adb shell service call hvac_service 1 i32 287309825 i32 0 f 0.0    # PROP_AC_STATE off

# --- Max ---
adb shell service call hvac_service 1 i32 287309826 i32 0 f 1.0    # PROP_MAX_STATE on
adb shell service call hvac_service 1 i32 287309826 i32 0 f 0.0    # PROP_MAX_STATE off

# --- Recycle ---
adb shell service call hvac_service 1 i32 287309827 i32 0 f 1.0    # PROP_RECYCLE_STATE on
adb shell service call hvac_service 1 i32 287309827 i32 0 f 0.0    # PROP_RECYCLE_STATE off

# --- Fan speed (0-12) ---
adb shell service call hvac_service 1 i32 289406980 i32 0 f 7.0    # PROP_FAN_SPEED = 7
adb shell service call hvac_service 1 i32 289406980 i32 0 f 0.0    # PROP_FAN_SPEED = 0 (off)

# --- Temperature (driver = area 1, passenger = area 2) ---
adb shell service call hvac_service 1 i32 358612997 i32 1 f 24.0   # PROP_TEMP driver
adb shell service call hvac_service 1 i32 358612997 i32 2 f 20.0   # PROP_TEMP passenger

# --- Sync ---
adb shell service call hvac_service 1 i32 287309830 i32 0 f 1.0    # PROP_SYNC on
adb shell service call hvac_service 1 i32 287309830 i32 0 f 0.0    # PROP_SYNC off

# --- Seat heating (driver = area 1, passenger = area 2) ---
adb shell service call hvac_service 1 i32 354418695 i32 1 f 1.0    # PROP_SEAT_HEATING driver on
adb shell service call hvac_service 1 i32 354418695 i32 1 f 0.0    # PROP_SEAT_HEATING driver off
adb shell service call hvac_service 1 i32 354418695 i32 2 f 1.0    # PROP_SEAT_HEATING passenger on
adb shell service call hvac_service 1 i32 354418695 i32 2 f 0.0    # PROP_SEAT_HEATING passenger off

# --- Ventilation mode (1=foot, 2=foot+face, 3=face) ---
adb shell service call hvac_service 1 i32 289406984 i32 0 f 1.0
adb shell service call hvac_service 1 i32 289406984 i32 0 f 2.0
adb shell service call hvac_service 1 i32 289406984 i32 0 f 3.0

# --- Auto ---
adb shell service call hvac_service 1 i32 287309833 i32 0 f 1.0    # PROP_AUTO_MODE on
adb shell service call hvac_service 1 i32 287309833 i32 0 f 0.0    # PROP_AUTO_MODE off

# --- Defrost ---
adb shell service call hvac_service 1 i32 287309834 i32 0 f 1.0   # PROP_DEFROST on
adb shell service call hvac_service 1 i32 287309834 i32 0 f 0.0   # PROP_DEFROST off

# --- Outside temp (logcat-only, no UI — onTempOutsideChanged just Log.d()s) ---
adb shell service call hvac_service 1 i32 291504140 i32 0 f 30.0
```

### Not covered by this pass
`bluetooth_app` — different chain entirely (real Bluetooth profile proxies, no VPS/JNI, no adb
injection equivalent since there's no in-process store to poke). Covered separately in
[12-testing-bluetooth.md](12-testing-bluetooth.md).
