# SYSTEM ARCHITECT DIRECTION: 3-TIER DECOUPLED AAOS CUSTOM SYSTEM SERVICES, MODULAR C++ VPS, & MVVM HMI

Act as a **Senior System Architect specializing in Android Automotive OS (AAOS) and AOSP source code**.

Task: implement 100% of the source code (Java Activities, ViewModels, Layout XMLs, SDK Managers,
Services, AIDL, C++, JNI, `Android.bp`, `AndroidManifest.xml`) for a 3-tier decoupled architecture at:

`[AOSP_ROOT]/vendor/kpit/automotive/`

---

## Where things live

This doc used to be one 1300-line file. It's now split by topic under [`docs/`](docs/) — pick the
file that matches what you're looking for:

| # | File | What's in it |
|---|------|---------------|
| I | [docs/01-architecture-overview.md](docs/01-architecture-overview.md) | The 3-tier shape: HMI → Service/Manager → VPS, Comfort vs. Connectivity domains |
| II | [docs/02-directory-structure.md](docs/02-directory-structure.md) | Full `vendor/kpit/automotive/` tree, done/todo/not-created per file |
| III | [docs/03-implementation-status.md](docs/03-implementation-status.md) | What's built for HVAC/Bluetooth, package layout table, what's left (Seat/WiFi) |
| IV | [docs/04-signal-flow.md](docs/04-signal-flow.md) | Command/event path (HMI↔Service) and the per-domain property tables |
| V | [docs/05-bluetooth-architecture.md](docs/05-bluetooth-architecture.md) | Why Bluetooth skips VPS/JNI, HFP/A2DP/AVRCP API choices, permissions |
| VI | [docs/06-technical-requirements.md](docs/06-technical-requirements.md) | Threading/AIDL/permission rules (1-7) + build-lesson log (8-15) |
| VII | [docs/07-output-rules.md](docs/07-output-rules.md) | Rules for how generated code/output should look |
| VIII | [docs/08-emulator-setup.md](docs/08-emulator-setup.md) | Running the AVD on the Windows dev machine |
| IX | [docs/09-hmi-knowledge-share.md](docs/09-hmi-knowledge-share.md) | Cross-team topics for the HMI team |
| X | [docs/10-build-and-product-integration.md](docs/10-build-and-product-integration.md) | Product wiring, privapp permissions, **sepolicy**, packaging, build-lesson log |
| XI | [docs/11-testing-hvac.md](docs/11-testing-hvac.md) | Manual `hvac_app` test procedure + `adb shell service call` reference |
| XII | [docs/12-testing-bluetooth.md](docs/12-testing-bluetooth.md) | Manual `bluetooth_app` test procedure |
| XIII | [docs/13-aidl-callback-threading.md](docs/13-aidl-callback-threading.md) | Deep dive: AIDL/Binder callback threading rules, thread-hop chain, `oneway`/`RemoteCallbackList` |

Cross-references in these docs still say things like "section VI.6" or "section X" — that's the
roman numeral in the table above, not a page number.

## Current status (quick read)

- **Done:** HVAC (Comfort) and Bluetooth (Connectivity) — full HMI/Service/Manager/VPS stack,
  product-wired, full build verified (2026-08-01).
- **Todo:** Seat (Comfort) and WiFi (Connectivity) — no modules exist yet.
- **Boot-test:** ✅ confirmed (2026-08-03) — full build + emulator boot passing, see the three
  fixes below. HVAC functional test (XI) confirmed through Step 1 / panel unlock; Bluetooth
  functional test (XII) not yet run (no peer device used in this pass); Step 2's full per-control
  HVAC matrix not yet walked row by row.
- **Sepolicy:** `vendor/kpit/automotive/sepolicy/` exists again as of 2026-08-03, for
  `service_manager` types only (not `/vendor` file access — that's permanently off the table for
  these coredomain services). Full story in
  [docs/10-build-and-product-integration.md](docs/10-build-and-product-integration.md).
- **Boot-start fix (2026-08-03, confirmed):** first real functional-test attempt (XI Step 1) failed
  — `hvac_service` was never registered, because `persistent="true"` only starts the app's process,
  not the `<service>` inside it. Fixed with `HvacApplication`/`IviBluetoothApplication`
  (06-technical-requirements.md #16). Rebuilt and rebooted — confirmed working.
- **Listener-registration fix (2026-08-03, confirmed):** second bug found immediately after the one
  above — with the service running, injected properties still never reached the HMI apps because
  `HvacManager`/`IviBluetoothManager` only connected to their service on an *outbound* call, never
  on listener registration. Fixed in both managers (06-technical-requirements.md #17). Confirmed:
  `hvac_app`'s panel now unlocks and reacts to injected signals.
- **Applied and confirmed (2026-08-03):** `android:directBootAware="true"` on both domain apps,
  added after #16/#17 were confirmed building successfully on their own
  (06-technical-requirements.md #18). Rebuilt/rebooted with this change in — no crash, same passing
  behavior.
