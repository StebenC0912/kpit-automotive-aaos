# VI. Technical & System Requirements

1. **ANR prevention:** Services use an `ExecutorService` or `HandlerThread` for Binder IPC, JNI calls,
   and property updates — never on the main thread. All `onChangeEvent` calls from JNI run on this
   background thread too.
2. **AIDL callbacks:** every listener method must be `oneway` to avoid blocking the IPC thread.
3. **Boot:** system services declare `android:persistent="true"` and `sharedUserId="android.uid.system"`.
   `persistent="true"` only makes `ActivityManagerService` start the app's process and call
   `Application.onCreate()` at boot — it does **not** start any `<service>` declared inside that app.
   Each domain app needs its own `Application` subclass to actually start its `Service`; see #16.
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
   restart/reboot/app-install-while-already-paired. See [05-bluetooth-architecture.md](05-bluetooth-architecture.md)
   for the Bluetooth-specific fix.
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
     unverifiable and fragile assumption from this vendor tree. Revisited (and still not taken, for a
     different reason) in [10-build-and-product-integration.md](10-build-and-product-integration.md)'s
     sepolicy notes, when weighing whether to keep `vendor: true` on `libvps`/`libbase_comfort_jni`.
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
      `sdk_version`, which conflicts with `platform_apis: true` (needed for `ServiceManager` access, #6).
    - Fix: removed `vendor: true` from both modules — nothing in this spec required vendor-partition
      placement; they now install to `/system/priv-app` instead, still privileged/platform-signed.
    - Lesson: don't assume a `PropertyErrorf` message's parenthetical fully describes the guard
      condition — read the actual source, since the real condition here was broader than the message
      suggested.
11. **Full build verified successful (2026-07-30):** after fixes 8–10 above, a full build of
    `vendor/kpit/automotive/` — `hvac_app`, `bluetooth_app`, both `service/comfort` and
    `service/connectivity` trees (HVAC + Bluetooth only; Seat/WiFi not yet implemented), and `vps/`
    (`libvps.so`) — completed with no errors. No outstanding build issues remain for the components
    marked ✅ in [02-directory-structure.md](02-directory-structure.md)/[03-implementation-status.md](03-implementation-status.md).
    **Update (2026-07-31):** a later rebuild surfaced two more errors — a `javac` filename mismatch
    and an unreachable Bluetooth API — see #13 and #14 below. Both fixed; no outstanding issues as of
    2026-07-31.
    **Update (2026-08-01):** full product build (`m`) re-run after the artifact-path-requirement,
    manifest-XML, AVRCP rework, EdgeToEdge/NonNull/Material-theme, `jni_headers`, x86 `size_t`
    shift-overflow, and sepolicy fixes in
    [10-build-and-product-integration.md](10-build-and-product-integration.md) completed successfully
    — no errors. This confirms the artifact-path allowed-list fix for
    `libbase_comfort_jni`/`libvps` (previously applied-but-unconfirmed) is correct. No outstanding
    build issues remain as of 2026-08-01.
    Boot-test/logcat verification (10-build-and-product-integration.md's "Build & verify commands")
    not yet performed.
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
    (plural, matching #7's own reference to "`HvacProperties.java`"); only the file on disk was
    typo'd singular. Fix: renamed the file to `HvacProperties.java`; the class and every caller were
    already correct, so no source changes were needed.
    [02-directory-structure.md](02-directory-structure.md) still says `HvacProperty` in the helper
    list — that's the pre-existing shorthand name for "the HVAC property constants file", not a claim
    about the literal filename.
14. **Build lesson — `BluetoothHeadsetClient`/`BluetoothA2dpSink` `connect()`/`disconnect()`
    unreachable from `vendor/kpit` (2026-07-31):** full root cause and fix
    (`setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED/FORBIDDEN)`) are written up in
    [05-bluetooth-architecture.md](05-bluetooth-architecture.md) rather than duplicated here — same
    "mainline module strips non-`@SystemApi` members from its exported stub" story as the
    `BluetoothAvrcpController` lesson there, just applying at the method level instead of the whole class.
15. **Build lesson — wedged Bazel server hangs `soong_build`, and the follow-up `rm -rf out/` itself
    hangs in `D` state (2026-08-03):**
    - Symptom: `m` fails in the Bazel mixed-builds analysis phase with `internal error: bazel command
      failed: exit status 37` after ~120s of `... still trying to connect to local Bazel server (PID)
      ...`, ending `FATAL: couldn't connect to server (PID) after 120 seconds.`
    - Root cause: a Bazel server process from an earlier build (`ps aux | grep -i bazel`) was still
      alive but wedged — idle, near-zero CPU time, `out/bazel/output/server/jvm.out` empty (no crash,
      it just never became reachable). Every subsequent build tries to talk to that same stuck instance
      and times out instead of spawning a fresh one. Not disk space (`df -h` showed plenty free) or
      memory — confirm both anyway before assuming this cause.
    - First fix attempted: `kill <pid>` the wedged server, retry the build. If the PID is already gone
      (`kill: ... no such process`) by the time you check, the server exited on its own but left the
      workspace in a bad state — proceed to the targeted clean from lesson #9:
      `rm -rf out/soong/workspace out/soong/bp2build out/bazel out/soong/bazelsocket.sock`.
    - Second symptom: that `rm -rf` itself hung with no terminal output and no change in `df -h` usage.
      `ps -eo pid,stat,etime,cmd | grep '[r]m -rf'` showed state `D+` (uninterruptible sleep) and
      climbing `etime` with no progress — a real kernel-level block, not just a slow walk over a large
      tree. Cause: Bazel's sandboxing leaves mount-namespace bind-mounts under `out/bazel` per action;
      when its server dies uncleanly these can dangle with nothing backing them, and any `rm`/`ls`
      touching that path blocks forever. A `D`-state process can't be `kill -9`'d — it only clears once
      the blocking I/O resolves.
    - Fix: find and clear the dangling mount(s), which un-wedges the existing `D`-state `rm` on its own
      (no need to kill it):
      ```bash
      mount | grep -i bazel
      findmnt | grep -i "out/bazel\|out/soong"
      sudo umount -l <mountpoint>   # for each match
      ```
      If `mount`/`findmnt` show nothing there, the stall is beneath Bazel entirely — check
      `sudo dmesg | tail -50` (plain `dmesg` fails with `read kernel buffer failed: Operation not
      permitted` on this host — needs `sudo`) for storage-layer (SCSI/ATA) errors instead.
    - In this instance `mount`/`findmnt` found nothing, and a few minutes later the `D`-state `rm`
      process had simply vanished from `ps` on its own — not still `D`, not defunct, just gone. That
      means it either finished or exited from an earlier Ctrl+C, not that anything was actively fixed.
      Don't assume success — always verify the targeted paths are actually gone before rebuilding:
      ```bash
      ls -la out/soong/workspace out/soong/bp2build out/bazel out/soong/bazelsocket.sock 2>&1
      ```
      All four should report `No such file or directory`. If any still exist, the delete was cut short
      (most likely by an earlier interrupt) — just re-run the same `rm -rf` line again, it's idempotent.
    - For a full `rm -rf out/` from scratch (rebuild-from-zero) afterward, prefer `rsync`'s delete mode
      over plain `rm -rf` — it reports live progress/rate/ETA, so a repeat of this same stall shows up
      immediately as the rate dropping to zero instead of silent output for minutes:
      ```bash
      mkdir -p /mnt/storage/.empty
      rsync -a --delete --info=progress2 /mnt/storage/.empty/ /mnt/storage/android_auto_os/out/
      rmdir /mnt/storage/.empty /mnt/storage/android_auto_os/out
      ```
      Fallback if `rsync` isn't installed (captures errors to a log while showing a live file counter,
      no precomputed total so no percentage/ETA):
      ```bash
      rm -rfv out/ 2>&1 | tee /tmp/rm_out_errors.log | pv -l -i 5 > /dev/null
      ```
    - The `rsync --delete` from the previous bullet then hit the *same* `D`-state symptom on one of its
      three processes (`rsync -a` forks generator + sender/receiver — expect 3 PIDs, only one
      necessarily blocked at a time). `mount`/`findmnt` again found nothing under `out/`, so this
      wasn't a dangling-mount repeat — don't stop the diagnosis at "no mounts found" a second time.
      Go straight to the kernel stack of the stuck PID, which gives the real syscall instead of guessing:
      ```bash
      sudo cat /proc/<stuck_pid>/stack
      ```
      Here it showed `__wait_on_buffer → ext4_read_bh → ext4_get_link → vfs_readlink → sys_readlink` —
      `rsync -a` calls `readlink()` on every symlink to preserve it, and this one was blocked reading
      the actual filesystem block for a symlink's target off disk, at the `ext4`/buffer-cache layer.
      That's below Bazel, below any mount — a raw block I/O wait, and a `D`-state process here truly
      cannot be `kill -9`'d until the I/O resolves.
    - Before assuming hardware failure (which would justify a disruptive host reboot — the only thing
      that reliably clears a wedged block-layer I/O request), confirm the disk is actually stalled
      and not just saturated:
      ```bash
      sudo smartctl -a /dev/sda | grep -i -E "reallocated|pending|uncorrectable|health"
      sudo iostat -x 5 3
      ```
      In this instance SMART came back clean (`PASSED`, 0 reallocated/pending/uncorrectable sectors)
      and `iostat` showed `sda` at 92-95% `%util` with a 26-28 deep queue (`aqu-sz`) and real ongoing
      throughput, plus `w_await` of 160-500ms — a disk (spinning or throttled virtual) genuinely
      saturated by the sheer number of tiny random metadata I/Os from deleting millions of individual
      symlinks (the Bazel sandbox forest), not a dead/hung one. Conclusion: no reboot needed — the
      stuck PID was just queued behind ~27 other pending requests on an overloaded disk. Let it run.
    - Don't use `du -sh` to watch progress on a disk in this state — `du` has to stat every remaining
      entry in the same symlink forest `rsync`/`rm` is deleting, so it competes for the same saturated
      I/O and can take as long to return as the deletion itself; wrapped in `watch`, this looks
      identical to "the command shows nothing," which reads as another hang even though nothing is
      wrong. Use `df -h` alone instead — it's a `statfs()` call, not a directory walk, so it returns
      instantly regardless of disk load and still shows real progress via rising free space:
      ```bash
      watch -n 15 'df -h /mnt/storage'
      ```
    - Net lesson: for any `D`-state process on this host, check `/proc/<pid>/stack` before reaching for
      `mount`/`findmnt`/reboot — it immediately tells you which layer (mount, filesystem, raw block
      I/O) is actually involved instead of working through them by trial and error.
16. **Boot lesson — `persistent="true"` starts the process, not the `<service>` inside it;
    `hvac_service`/`bluetooth_service` never registered (2026-08-03):**
    - Symptom: on a fresh boot, [11-testing-hvac.md](11-testing-hvac.md) Step 1
      (`adb shell service call hvac_service 1 i32 11 i32 0 f 5.0`) fails with `Service hvac_service
      does not exist` — the very first documented test command, never previously run.
    - Root cause: rule 3 above is incomplete. `android:persistent="true"` makes
      `ActivityManagerService` fork `com.kpit.hvac`/`com.kpit.bluetooth`'s process at boot and call
      `Application.onCreate()` — that's it. It does not start the `<service>` (`HvacService`/
      `IviBluetoothService`) declared inside the app's manifest. `onCreate()` is where
      `ServiceManager.addService(...)` lives (rule 6), so if nothing ever calls
      `startService()`/`bindService()` on those `Service` classes, that `addService()` call never
      runs and the service never registers — regardless of sepolicy. Nothing anywhere in
      `vendor/kpit/` did: no `BOOT_COMPLETED` receiver, no custom `Application` class, and the
      client-side `Manager`s only do `ServiceManager.getService()` (a lookup, not a start — rule 6).
    - Ruled out the other obvious suspect first: the `service_manager` sepolicy types added in
      [10-build-and-product-integration.md](10-build-and-product-integration.md)'s "reintroduced for
      `service_manager` types" note. `kpit/emulator/emulator_boot.log` (kernel/QEMU-console log, not
      app logcat — but SELinux `service_manager`-class denials route through the kernel audit log
      too) has **zero** `avc: denied ... tclass=service_manager` entries anywhere in the whole boot.
      If `HvacService.onCreate()` had even attempted `addService()` and been denied, or if `shell`
      had been denied `find`, one of those would show up. Neither did — meaning the registration
      attempt itself never happened, not that it happened and got blocked.
    - Fix: added `HvacApplication`
      (`service/comfort/hvac/src/com/kpit/hvac/service/HvacApplication.java`) and
      `IviBluetoothApplication`
      (`service/connectivity/bluetooth/src/com/kpit/bluetooth/service/IviBluetoothApplication.java`)
      — minimal `Application` subclasses whose `onCreate()` calls `startService()` on their domain's
      `Service`. Wired via `android:name` on the `<application>` tag in both `AndroidManifest.xml`
      files. No `BOOT_COMPLETED` receiver needed — `persistent="true"` already guarantees
      `Application.onCreate()` runs at boot (rule 3), this fix just uses that same guarantee to reach
      one level deeper, into the `Service`. Both `Android.bp`s already glob their `service/**/*.java`
      directory for `srcs`, so no build-file change was needed.
    - **Confirmed (2026-08-03):** rebuilt, repackaged, rebooted — `hvac_service` now registers on
      its own at boot, no manual `am start-service` needed. [11-testing-hvac.md](11-testing-hvac.md)
      Step 1 passes directly.
17. **Bug — `HvacManager`/`IviBluetoothManager` never connect to their service until an outbound
    call happens, so registered listeners receive nothing (found 2026-08-03, right after #16):**
    - Symptom: with #16's fix applied and `hvac_service` actually running, injecting
      `PROP_VEHICLE_STATE` via `adb shell service call hvac_service 1 i32 11 i32 0 f 5.0` returns a
      successful `Parcel` — but `hvac_app`'s UI never reacts, and stays locked forever.
    - Root cause: `BaseComfortManager`/`BaseConnectivityManager`'s connection to the remote service
      (`ServiceManager.getService()` + the remote `registerCallback()`/`registerListener()` call
      that tells the service where to send events) only happens lazily, inside `getService()`,
      called from `connectLocked()`. Nothing calls `getService()` until an *outbound* command method
      does — `HvacManager.setProperty()` / `IviBluetoothManager.connect()`/`disconnect()`/
      `sendMediaCommand()`. `registerSystemListener()`/`registerPropertyListener()`/
      `registerBluetoothListener()` — the only calls `HvacViewModel`/`BluetoothViewModel` make in
      their constructors — only appended to a local `List`; they never touched `getService()`. So
      the manager singleton never connects, never sends the remote `registerCallback()`, and the
      service's `RemoteCallbackList` has nothing to broadcast to — no event, including one injected
      directly via `adb shell service call`, can ever reach the app.
    - For HVAC specifically this is a hard deadlock, not just a missed event: `HvacViewModel
      .toggleAc()` (the doc's suggested "tap AC to unlock the panel" nudge,
      [11-testing-hvac.md](11-testing-hvac.md) Step 1) is itself gated on `mCurrentVehicleState >= 5`
      — a value that can only change via the same callback event that can never arrive. There is no
      user action that breaks the cycle. `bluetooth_app` doesn't deadlock the same way (its buttons
      aren't gated on connection state), but its manager still never connects on its own, since the
      app has no connect/disconnect button at all ([12-testing-bluetooth.md](12-testing-bluetooth.md))
      — only `sendMediaCommand()` (Play/Pause/Next/Previous) would ever trigger a connection attempt.
    - Fix: `registerSystemListener()`/`registerPropertyListener()` (`HvacManager.java`) and
      `registerBluetoothListener()` (`IviBluetoothManager.java`) now call `getService()` (return
      value discarded) right after adding the listener — registering interest in events is itself
      now enough to force the connection attempt, independent of whether/when any outbound command
      is ever sent. `getService()` is idempotent/cheap to call repeatedly (short-circuits once
      already connected and the binder is alive), so no behavior change for the existing call sites.
    - Test-order note: the manager only registers its remote callback with whatever service instance
      is running *at connection time* — launch `hvac_app`/`bluetooth_app` (or otherwise get
      `HvacViewModel`/`BluetoothViewModel` constructed) **before** injecting a property via `adb
      shell service call`, not after, or the injected event will still be dropped even with this fix.
    - **Confirmed (2026-08-03):** same rebuild/reboot as #16 — `hvac_app`'s panel now unlocks and
      reacts to injected `PROP_VEHICLE_STATE` (and other) events as expected. `bluetooth_app`'s side
      of this fix built successfully but hasn't been separately exercised yet (no Bluetooth peer
      used in this pass) — see [12-testing-bluetooth.md](12-testing-bluetooth.md).
18. **Applied — `android:directBootAware` (planned 2026-08-03, applied 2026-08-03):** raised while
    reviewing #16/#17 above — worth distinguishing from both:
    - `persistent="true"` (rule 3) controls whether the app's *process* gets force-started at boot at
      all. `directBootAware` controls whether a component may run *before the user unlocks the
      device* under FBE (file-based encryption) — without it, a component only starts after the
      first post-reboot unlock, and its credential-encrypted storage (`SharedPreferences`, etc.)
      stays inaccessible until then. Independent knobs; neither implies the other.
    - Not the cause of #16 or #17: the very first captured logcat for this tree already showed
      `com.kpit.hvac`'s process starting and running ART startup at boot, before either fix existed
      — so the process itself was never gated behind an unlock. #16/#17's symptoms were both
      downstream of the process already running (nothing started the `<service>`; nothing forced the
      manager to connect), not an unlock-timing problem.
    - Still worth adding defensively — if this target ever enables FBE + a lock screen (not confirmed
      either way for the current AVD config), an un-aware persistent app's CE-backed storage would be
      inaccessible until unlock even though the process is alive, which would be a much harder bug to
      diagnose than #16/#17 were.
    - **Applied (2026-08-03):** #16/#17 confirmed building successfully on their own first, per the
      "deliberately deferred" plan below (kept the two change sets isolated so a build/boot failure
      couldn't be ambiguous between them). `android:directBootAware="true"` now added to both
      `<application>` tags — `service/comfort/hvac/AndroidManifest.xml` and
      `service/connectivity/bluetooth/AndroidManifest.xml`. **Confirmed (2026-08-03):** rebuilt and
      rebooted with this change in alongside #16/#17 — no crash, `hvac_service` still registers and
      `hvac_app` still reacts as expected.
    - **Verified (2026-08-03) it's safe to apply as-is, no storage migration needed first:** the
      real risk with `directBootAware` isn't the flag itself, it's that the *default* storage APIs
      (`getSharedPreferences()`, `openFileOutput()`, `getFilesDir()`, a plain `SQLiteOpenHelper`) all
      resolve to credential-encrypted (CE) storage, which isn't mounted yet during Direct Boot — any
      of those calls from a Direct-Boot-aware component before unlock throws and crashes. Grepped
      `service/` (`HvacService`/`HvacApplication`, `IviBluetoothService`/`IviBluetoothApplication`,
      both `BaseComfortService`/`BaseConnectivityService`, both base managers) for
      `SharedPreferences`/`openFileOutput`/`getFilesDir`/`getDatabasePath`/`SQLiteOpenHelper` — zero
      hits. These components only do Binder IPC, an `ExecutorService`, JNI (`System.loadLibrary` off
      `/system`, not CE storage), and platform service lookups (`BluetoothAdapter`,
      `MediaSessionManager`) — none of it touches CE storage, so the flag alone won't crash them.
    - **Tripwire for later:** if a future feature persists signal data (e.g. logging VHAL events to a
      database) is added to either service, it needs `Context.createDeviceProtectedStorageContext()`
      — a DE-backed `Context` whose `getSharedPreferences()`/`openOrCreateDatabase()`/etc. calls
      target device-protected (DE) storage instead of the default CE storage — **not** plain CE
      storage, precisely because CE is the thing that's *unavailable* during the Direct Boot window
      these services are being made aware of in the first place. Whether it actually needs DE storage
      depends on whether the write can happen before first unlock: for this domain (vehicle signals),
      that's plausible — ignition/door/HVAC state can change before any lock-screen unlock gesture.
      If a future write path is meant to run before unlock, route it through a
      `createDeviceProtectedStorageContext()`-backed `Context`; if it's fine to only persist after
      unlock, defer the write (e.g. gate on `Intent.ACTION_USER_UNLOCKED`) and plain default (CE)
      storage is fine, no DE context needed.
