# VIII. Running the Emulator (Windows Dev Machine)

AVD: `C:\Users\linhk\.android\avd\Automotive_1408p_landscape.avd`

```
emulator -avd Automotive_1408p_landscape -writable-system -no-snapshot -packet-streamer-endpoint default
```

- `-writable-system` — makes `/system` and `/vendor` writable (needed for `adb remount`/`adb push`).
- `-no-snapshot` — forces a cold boot every time; disables both snapshot load and save.
- `-packet-streamer-endpoint default` — connects the emulator's virtual Bluetooth radio to Netsim
  (starting it automatically if not already running), so this guest can be discovered by/pair with
  another emulator on the same machine. Needed for [12-testing-bluetooth.md](12-testing-bluetooth.md)
  Tier 2's second-AVD option; harmless to leave on otherwise.
- If `emulator` isn't on `PATH`, use the full path instead:
  `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd Automotive_1408p_landscape -writable-system -no-snapshot -packet-streamer-endpoint default`

### With kernel + verbose logging (debugging boot issues)
PowerShell (captures the log to a file and still shows it live):
```powershell
C:\Users\linhk\AppData\Local\Android\Sdk\emulator\emulator.exe -avd Automotive_1408p_landscape -wipe-data -writable-system -no-snapshot -show-kernel -verbose -packet-streamer-endpoint default 2>&1 | Tee-Object -FilePath emulator_boot.log
emulator -avd Automotive_1408p_landscape -wipe-data -writable-system -no-snapshot -show-kernel -verbose -packet-streamer-endpoint default 2>&1 | Tee-Object -FilePath emulator_boot.log
```
cmd.exe (file only, no live view — `Tee-Object` doesn't exist outside PowerShell):
```cmd
emulator -avd Automotive_1408p_landscape -wipe-data -writable-system -no-snapshot -show-kernel -verbose -packet-streamer-endpoint default > emulator_boot.log 2>&1
```

- `-wipe-data` — resets `userdata.img` to a clean state before boot. Needed after swapping in a
  newly packaged system image ([10-build-and-product-integration.md](10-build-and-product-integration.md)'s
  `pack_emulator.sh`/`emu_img_zip` fix) — stale userdata from a previous, differently-partitioned
  image can itself cause first-stage-mount failures independent of whatever image bug is being chased.
- `-show-kernel` — prints kernel boot log (`dmesg`-equivalent) to the console as the guest boots,
  including early-boot messages that happen before `adb logcat` is reachable.
- `-verbose` — enables emulator-side debug logging (AVD/HAX/HVF setup, disk image resolution, GPU
  backend selection, `config.ini` parsing) — useful for diagnosing the `image.sysdir.1`/zip-layout
  issues described below, not just guest kernel issues.
- `2>&1 | Tee-Object -FilePath emulator_boot.log` — merges stderr into stdout (the emulator's
  `-verbose`/`DEBUG` output goes to stderr) and captures both to a file for later `grep`, without
  losing the live console view.
- `-packet-streamer-endpoint default` — same Netsim/Bluetooth purpose as in the base command above.

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
8. Launch with the same flags as the rest of this section: `-writable-system -no-snapshot -packet-streamer-endpoint default`.
