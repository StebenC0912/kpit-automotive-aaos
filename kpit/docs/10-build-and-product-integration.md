# X. Build, Product Integration & Emulator Packaging

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
- Seat/WiFi have no buildable modules yet ([03-implementation-status.md](03-implementation-status.md)),
  so nothing to add for them until implemented.

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
([05-bluetooth-architecture.md](05-bluetooth-architecture.md)). Everything else in this tree
(`BIND_COMFORT_SERVICE`, `ACCESS_COMFORT_SERVICE`, `BIND_HVAC_SERVICE`, `BIND_CONNECTIVITY_SERVICE`,
`BIND_BLUETOOTH_SERVICE`) is a custom `signature`-level permission, which auto-grants to
same-signature apps and needs no allowlist entry. So `hvac_app`, `bluetooth_app`, and `hvac-service`
need nothing here.
- `vendor/kpit/automotive/service/connectivity/bluetooth/privapp_permissions_bluetooth.xml` —
  allowlists both `BLUETOOTH_PRIVILEGED` and `MEDIA_CONTENT_CONTROL` for `com.kpit.bluetooth`.
  **Boot lesson (2026-08-02):** `MEDIA_CONTENT_CONTROL` was missing from this file from the AVRCP
  rework ([05-bluetooth-architecture.md](05-bluetooth-architecture.md)) up through the first
  successful full build (06-technical-requirements.md #11) — a `signature|privileged` permission
  being *declared* in the manifest and the app being *system-signed* are both necessary but not
  sufficient; every such permission also needs its own allowlist entry here regardless of signature,
  or `system_server` throws `IllegalStateException` and crash-loops at boot (full writeup in
  [05-bluetooth-architecture.md](05-bluetooth-architecture.md)'s "Correction"). The build itself
  doesn't catch this — it's a runtime-only failure, only visible via `logcat`/
  `adb shell getprop sys.boot_completed` after the guest boots.
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

Root fix: `hvac-service`/`bluetooth-service` are deliberately coredomain system apps
(06-technical-requirements.md #10 already made this call for the APKs themselves, same Treble
reason). A native library a coredomain process loads in-process therefore has to live on `/system`
too — `/vendor` is for vendor-domain processes (real HALs), which `VpsDispatcher` explicitly isn't
(06-technical-requirements.md #7, "alternative not taken"). Removed `vendor: true` from
`libbase_comfort_jni` (`service/comfort/base/Android.bp`) and `libvps` (`vps/Android.bp`) — both now
install to `/system/lib64`. `system_app` gets read/execute/map on plain `system_file` for free from
existing base policy, so no custom sepolicy is needed at all; deleted `vendor/kpit/automotive/sepolicy/`
and the `BOARD_SEPOLICY_DIRS` line in `kpit_apps.mk` entirely rather than leaving a now-pointless
rule in place.
- Lesson: for a coredomain app's in-process native dependency, check partition placement
  (`vendor: true` or not) before reaching for a custom sepolicy type — a `neverallow` on
  `coredomain`-vs-`/vendor` access in general can't be satisfied by narrowing the *type* being
  accessed, only by not being on `/vendor` at all.

### SEPolicy — reintroduced for `service_manager` types, then hit a duplicate-declaration build failure (2026-08-03)
Different concern from the "reverted" SEPolicy note directly above — that one was about
`/vendor`-vs-coredomain *file* access (still correctly reverted, stays reverted); this one is about
the `service_manager` types `hvac_service`/`bluetooth_service` register under, needed for
[11-testing-hvac.md](11-testing-hvac.md)/[12-testing-bluetooth.md](12-testing-bluetooth.md)'s
`adb shell service call hvac_service ...`/`adb shell service call bluetooth_service ...`
functional-test commands to work at all.

**Why it's needed:** `AllianceCarHvacService`/`IviBluetoothService` call `ServiceManager.addService("hvac_service"/
"bluetooth_service", mBinder)` (06-technical-requirements.md #6), but neither name had a
`service_contexts` entry anywhere in this tree, so both fell back to the generic
`default_android_service` SELinux type. Existing base policy lets `system_app` add/find that generic
type (so the apps themselves still work), but `shell` has no `find` on it — so
`adb shell service call hvac_service ...` gets back `service_manager: Waiting for service... service
does not exist` instead of a permission error. Fix: added
`vendor/kpit/automotive/sepolicy/{service.te, service_contexts}`, wired via `BOARD_SEPOLICY_DIRS +=
vendor/kpit/automotive/sepolicy` in `kpit_apps.mk`, declaring dedicated `service_manager_type`s for
both names and granting `system_app`/`shell` explicit `{ add find }` / `find`.

**Note (2026-08-03):** this sepolicy fix only matters once the services are actually running long
enough to call `addService()` at all — see 06-technical-requirements.md #16 for a separate, more
fundamental gap (neither service ever started in the first boot-test attempt) that had to be fixed
first.

**Build failure hit while adding it:**
```
vendor/kpit/automotive/sepolicy/service.te:3:ERROR 'Duplicate declaration of type' at token ';' on line 84894:
type bluetooth_service, service_manager_type;
```
from the `sepolicy_neverallows_vendor` Neverallow check (`checkpolicy -M`).

**Root cause:** `bluetooth_service` is *not* a fresh name — it's already declared as a public AOSP
type in `system/sepolicy/public/service.te`, backing the real Bluetooth stack's own `"bluetooth"`
service (`system/sepolicy/private/service_contexts:173`, `bluetooth →
u:object_r:bluetooth_service:s0`). `vendor/kpit`'s `service.te` redeclared the same type name, and
checkpolicy rejects two `type` declarations for one name even when both say the same thing
(`service_manager_type`) — sepolicy is one merged namespace across AOSP `public/private` and every
`BOARD_SEPOLICY_DIRS` vendor directory, not scoped per source file. `hvac_service` has no such
collision — it's a genuinely new type name nothing else in the tree declares — so only the
`bluetooth_service` line failed. (Registering the binder service itself under the literal name
`"bluetooth_service"` in `IviBluetoothService.java` is unrelated and fine — that's a
`service_contexts` *key* naming a running service, distinct from the `service.te` *type* it maps to,
and AOSP's own `"bluetooth"` service uses a different key already.)

**Fix:** removed the redundant `type bluetooth_service, service_manager_type;` line from
`vendor/kpit/automotive/sepolicy/service.te`, keeping only `hvac_service`'s declaration. The existing
`allow system_app/shell bluetooth_service:service_manager { add find };` rules didn't need to change
— they still resolve fine against the type's pre-existing public declaration. `service_contexts`'s
own `bluetooth_service u:object_r:bluetooth_service:s0` line also didn't need to change, since a
`service_contexts` entry only *references* a type by name, it doesn't declare one.
- Lesson: before declaring a new `service_manager_type` in a vendor `.te` file, grep
  `system/sepolicy/{public,private}/service.te` for the exact name first — a name that reads as
  "obviously ours" (matching this tree's own service name) can still collide with an AOSP type of the
  same name backing an unrelated real service.

### SEPolicy — when custom policy is (and isn't) the right fix (2026-08-03)
Quick reference tying the two notes above together — same tree, two different sepolicy problems, not
a reversal of the same decision:

| Problem | Category | Fixable with sepolicy? | What actually fixed it |
|---|---|---|---|
| `system_app` (coredomain) reading `libvps.so` off `/vendor` | `neverallow coredomain ~vendor_file` — blocks the whole *category*, not a specific type | ❌ no | moved the libs off `/vendor` (dropped `vendor: true`) |
| `shell` couldn't `find` `hvac_service`/`bluetooth_service` | plain missing `allow` — nothing forbids it | ✅ yes | added `service.te`/`service_contexts` granting `shell` `find` |

**Rule of thumb:** if the block is a `neverallow` on a whole category (coredomain-vs-`/vendor`,
untrusted_app-vs-system_file, etc.), no `.te` file can open a hole in it — the fix is architectural
(move the file, change the domain, cross a real HAL boundary). If it's just a missing `allow` for a
one-off access pattern specific to your service, adding a scoped type + `allow` rule is the correct,
intended use of vendor sepolicy.

### SEPolicy — keeping `vendor: true` despite the coredomain neverallow: only a real HAL boundary works (2026-08-03)
Follow-up question to the "reverted" note above: what if `libvps`/`libbase_comfort_jni` *must* stay
on `/vendor` (e.g. a real SoC vendor blob, licensing, or partition-independent updates)? The
`neverallow` isn't scoped to a type, so **no sepolicy rule can reopen it** — `hvac-service` is
coredomain (`platform_apis: true`, needed for `ServiceManager`, 06-technical-requirements.md #6) and
Full-Treble simply forbids any coredomain process from touching a `/vendor` file, full stop.

| Approach | Works? | Why |
|---|---|---|
| Add an `allow` rule scoped to the lib's own type | ❌ | neverallow blocks by category (coredomain × vendor_file), not by type |
| Keep `vendor: true`, keep loading it via `System.loadLibrary()` from `hvac-service` | ❌ | this *is* the blocked access — coredomain opening a `/vendor` file |
| Keep `vendor: true`, move the code into its own vendor HAL process (`hwbinder` service), have `AllianceCarHvacService` talk to it over AIDL/HIDL instead of `dlopen`-ing it | ✅ | the HAL boundary is the Treble-sanctioned crossing point — coredomain talks to vendor code over Binder, never touches the vendor `.so` file itself |
| Drop `platform_apis: true` so the caller itself isn't coredomain | ✅ but costly | loses `ServiceManager` registry access (06-technical-requirements.md #6) — `addService`/`getService` breaks |

**Concretely**, keeping `libvps` on `/vendor` would mean splitting it into two pieces: a small
`vendor: true` HAL service process (e.g. `IVpsHal.aidl`, running as its own vendor domain, `init`
service) that keeps whatever SoC-specific code needs to live on `/vendor`, and `AllianceCarHvacService`/JNI
becomes a Binder client of that HAL instead of loading the `.so` in-process
(06-technical-requirements.md #7's "alternative not taken" — the same HAL approach rejected earlier
for a different reason: matching AOSP's real `IVehicle` interface). That's substantially more work
than the one-line fix actually applied (new AIDL interface, new `init.rc` service entry, new sepolicy
for the HAL's own domain) — only worth it if there's a concrete reason to keep the code off `/system`.
Not needed for this tree today; `libvps`/`libbase_comfort_jni` have no such requirement, so they stay
on `/system` per the "reverted" note above.

**Update (2026-08-11):** this row of the table was taken after all, as VHAL-alignment Stage 4
(`kpit/docs/03-implementation-status.md` item 13) — not because `libvps` gained a concrete
`/vendor`-only requirement, but to close the gap with real VHAL structure. `libbase_comfort_jni`
stays on `/system` exactly as concluded above (it's still the coredomain caller); `libvps` moved
to a new `vendor: true` daemon, `vendor.kpit.vps-service`, with `base_comfort_vhal_jni.cpp` now the
Binder client this section describes. Not build/boot verified this session — see item 13's Stage 4
writeup for the verification ceiling reached.

### Boot lesson — `pack_emulator.sh` packaged plain `system.img` instead of `system-qemu.img`, causing a boot loop (2026-08-01)
Symptom: guest boots the kernel, then `init` prints
`partition(s) not found in /sys, waiting for their uevent(s): super, vbmeta`, times out after ~10s,
aborts (`InitFatalReboot: signal 6`), and reboots to `bootloader` — repeating forever. Confirmed via
`-show-kernel -verbose` ([08-emulator-setup.md](08-emulator-setup.md)'s emulator commands) piped to a
log file.

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
new zip into the SDK's `system-images/<api-tag>/android-automotive/x86_64/` per
[08-emulator-setup.md](08-emulator-setup.md), no `config.ini` changes needed. The staged `system.img`
is now ~5.5 GB (up from ~1.3 GB) since it's really `system-qemu.img` under the hood; packaged zip
total is ~877 MB.

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
`config.ini`'s `image.sysdir.1` directly ([08-emulator-setup.md](08-emulator-setup.md)) rather than
going through Studio's package-detection path.

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

# extract the resulting zip per 08-emulator-setup.md, then:
emulator -avd Automotive_1408p_landscape -writable-system -no-snapshot
```

Verify after boot:
```bash
adb shell pm list packages | grep -E "kpit|com.kpit"        # hvac-app/bluetooth-app/services installed
adb shell dumpsys package com.kpit.bluetooth | grep -A2 BLUETOOTH_PRIVILEGED  # privapp allowlist took
adb logcat -d | grep -i "avc:.*denied"                       # SELinux denials — see SEPolicy notes above
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

### Build lesson — see 05-bluetooth-architecture.md for the AVRCP/`BluetoothAvrcpController` rework
Next build failed with `could not resolve BluetoothAvrcpController` — a real API-reachability
problem, not a syntax slip. Full root cause, fix (`MediaSessionManager`/`MediaController`), and the
`MEDIA_CONTENT_CONTROL` permission it needed are written up in
[05-bluetooth-architecture.md](05-bluetooth-architecture.md).

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
1. Implement Seat (AIDL, manager/service, VPS handler) and WiFi
   ([03-implementation-status.md](03-implementation-status.md)) — no build/product changes needed
   for either until real modules exist.
2. ~~Re-run the full build~~ — **done, successful (2026-08-01)**, no errors. Full fix list already
   in 06-technical-requirements.md #11's 2026-08-01 update — not repeated here.
3. ~~Boot-test in the emulator and grep `logcat` for `avc: denied` tied to
   `hvac-service`/`libbase_comfort_jni`/`libvps`~~ — **done (2026-08-03)**, as part of the #16/#17/#18
   rebuild cycle below: boots clean, `hvac_service` registers on its own, no crash.
4. Functional test of `hvac_app`'s controls — procedure and known findings in
   [11-testing-hvac.md](11-testing-hvac.md). First real attempt (2026-08-03) hit Step 1 immediately
   (`hvac_service` never registered, 06-technical-requirements.md #16) then a second bug right after
   (app never received events, #17). **Both fixed and confirmed (2026-08-03)**: Step 1 passes and
   the panel now unlocks/reacts as designed. Full 12-row control matrix
   ([11-testing-hvac.md](11-testing-hvac.md) Step 2) not individually walked through yet.
5. ~~Rebuild, repackage, and reboot the emulator with the #16/#17 fixes~~ — **done (2026-08-03)**,
   confirmed passing.
6. `android:directBootAware="true"` added to both domain apps' `<application>` tags
   (06-technical-requirements.md #18) — **confirmed (2026-08-03)**: rebuilt/rebooted alongside
   #16/#17 with no crash, same passing behavior.
7. `bluetooth_service`/`bluetooth_app` side of #16/#17 built successfully in the same pass but
   hasn't been functionally exercised yet (no Bluetooth peer used) — Tier 1/Tier 2 of
   [12-testing-bluetooth.md](12-testing-bluetooth.md) still **not yet performed.**
