# vendor/kpit/automotive

3-tier decoupled AAOS custom system services, modular C++ VPS, and MVVM HMI — implemented at
`[AOSP_ROOT]/vendor/kpit/automotive/`.

Full spec, build lessons, and design rationale live under [`kpit/docs/`](kpit/docs/), indexed from
[`kpit/instruction.md`](kpit/instruction.md). Commit messages in this repo follow the structure
defined in [`kpit/commit_rule.md`](kpit/commit_rule.md).

## Architecture

Three decoupled components, two domains (**Comfort**, **Connectivity**):

1. **HMI Apps** — `hmi/` — MVVM UI apps (`hvac_app`, `bluetooth_app`). The Activity never talks to
   the Manager SDK directly — always through a ViewModel, which owns the Manager instance,
   listener lifecycle, and state.
2. **Services & Managers** — `service/` — combined System Service + Manager SDK per domain.
   - `service/comfort/` — `base` (shared plumbing), `hvac` (done), `seat` (todo)
   - `service/connectivity/` — `base` (shared plumbing), `bluetooth` (done), `wifi` (todo)
   - Bluetooth skips `vps/`/JNI entirely — no VHAL property exists for it, so it calls Android's
     hidden Bluetooth profile proxies (HFP/A2DP) and `MediaSessionManager`/`MediaController`
     (AVRCP) directly.
3. **VPS (Vehicle Platform Service)** — `vps/` — C++ `libvps.so`. A polymorphic `IVpsHandler`
   interface with a `VpsDispatcher` that routes by property ID to domain handlers
   (`HvacHandler`, future `SeatHandler`). Runs inside `vendor.kpit.vps-service` (`vps/service/`,
   `/vendor`), reached from each domain's JNI bridge over the `vendor.kpit.vps` AIDL interface
   (`vps/aidl/`) — a real Binder HAL boundary, not an in-process call (VHAL-alignment Stage 4,
   `kpit/docs/03-implementation-status.md` item 13).

## Status

| Component                          | Status |
|-------------------------------------|--------|
| `hmi/hvac_app`                      | done |
| `hmi/bluetooth_app`                 | done |
| `service/comfort/base`              | done |
| `service/comfort/hvac`              | done |
| `service/comfort/seat`              | todo |
| `service/connectivity/base`         | done |
| `service/connectivity/bluetooth`    | done |
| `service/connectivity/wifi`         | not created |
| `vps` (HVAC handler)                | done |
| `vps` (Seat handler)                | not created |

## Signal flow

Both domains share the same shape: **HMI → ViewModel → Manager → Service**, diverging only at the
Service:

- **Comfort (HVAC/Seat):** Service → JNI → Binder → `vendor.kpit.vps-service` → `VpsDispatcher` →
  domain handler → (simulated) vehicle property store.
- **Connectivity (Bluetooth/WiFi):** Service calls the relevant Android framework API directly
  (e.g. `BluetoothHeadsetClient`/`BluetoothA2dpSink`, `MediaSessionManager`) — no JNI, no VPS.

Events flow the same path in reverse, fanned out to registered listeners via
`RemoteCallbackList`, and delivered to the ViewModel over `oneway` AIDL callbacks.

## Repo layout

```
vendor/kpit/automotive/
├── hmi/                 MVVM apps (hvac_app, bluetooth_app)
├── service/
│    ├── comfort/        base, hvac, seat
│    └── connectivity/   base, bluetooth
├── vps/                 libvps.so (C++) + vendor.kpit.vps-service (aidl/, service/) HAL daemon
├── products/            kpit_apps.mk product packaging
└── sepolicy/            service_manager types for hvac_service/bluetooth_service, hal_vps.te
```

Branch history mirrors this layout — `hmi`, `vps`, and `service` were each built as their own
branch with small, module-scoped commits before merging into `main`.

## Building & running

See [`kpit/docs/08-emulator-setup.md`](kpit/docs/08-emulator-setup.md) for emulator setup
(`Automotive_1408p_landscape` AVD) and
[`kpit/docs/10-build-and-product-integration.md`](kpit/docs/10-build-and-product-integration.md)
for product wiring (`aosp_car_x86_64-userdebug` / `sdk_car_x86_64-userdebug` lunch targets) and
sepolicy notes.
