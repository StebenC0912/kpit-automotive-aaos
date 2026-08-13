# vspManagerTool

Standalone, Windows-first Java desktop tool for watching and setting HVAC VHAL properties
against a running `emulator_car_x86_64`/`generic_car_x86_64` instance, without editing any
on-device source. See `kpit/docs/03-implementation-status.md` item 5 for the design rationale.

This is a plain Java + Swing application with **zero external dependencies** — no Gradle, no
Maven, nothing to download. It talks to the device exclusively through `adb` via
`ProcessBuilder`, the same way a developer would type `adb shell dumpsys hvac_service` by hand.

## Prerequisites

- A JDK (11+) on the machine running the tool, with `javac`/`java`/`jar` on `PATH`.
- `adb` on `PATH`, or point the tool at its full path in the "adb path" field once it's running.
- An on-device build that includes `AllianceCarHvacService.dump()`'s `--get`/`--set` debug
  entry point (`kpit/docs/03-implementation-status.md` item 4). As of 2026-08-13 that fix is
  implemented in source but **not yet rebuilt/tested on a device** — this tool has no effect
  until that rebuild happens.

## Build

Windows:
```
build.bat
```

Linux/macOS (for sanity-building inside this checkout, or on a non-Windows dev machine):
```
./build.sh
```

Both produce `build/vspManagerTool.jar`.

## Run

```
java -jar build/vspManagerTool.jar
```

1. Set the "adb path" field if `adb` isn't on `PATH`.
2. Click **Refresh Devices** and pick a device/emulator serial (leave blank for the only
   attached device).
3. Click **Start Watching** — polls `adb shell dumpsys hvac_service` every ~400ms and fills in
   the table; every value change is also appended to the history log below.
4. Click **Unlock Vehicle State (5.0)** first (mirrors `11-testing-hvac.md` Step 1) — most
   other properties only mean anything in the real `hvac_app` once vehicle state is `>= 5`.
5. Select any row, type a new value, click **Set** to send
   `adb shell dumpsys hvac_service --set <PROP_NAME> -a <area> -f <value>`.

`PROP_TEMP_OUTSIDE` drifts on its own every ~5s (simulated outside-temperature sensor) — a
manual Set on that row snapping back shortly after is expected, not a bug.

If the table shows "vps-service / native VHAL bridge not ready" instead of values, the
on-device `vendor.kpit.vps-service` daemon (`kpit/docs/03-implementation-status.md` item 13,
Stage 4) isn't up yet — see `kpit/docs/11-testing-hvac.md` Step 0.

## Tests

`test/com/kpit/vspmanager/parse/DumpParserTest.java` is a hand-rolled smoke test (no JUnit
dependency) covering the normal 14-row dump, the not-ready error line, and malformed input.
Run it directly:

```
mkdir -p build/test-classes
javac -d build/test-classes $(find src test -name '*.java')
java -cp build/test-classes com.kpit.vspmanager.parse.DumpParserTest
```
