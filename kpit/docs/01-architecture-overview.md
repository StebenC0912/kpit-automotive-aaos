# I. Architecture Overview

Three decoupled components, two domains (**Comfort**, **Connectivity**):

1. **HMI Apps** — `hmi/` — MVVM UI apps (`hvac_app`, `bluetooth_app`).
   Activity never talks to the Manager SDK directly — always through a `ViewModel`.
   Activity only renders UI and observes `LiveData`/`StateFlow`.
   ViewModel owns the Manager instance, listener lifecycle, and state — has zero knowledge of JNI/HAL/Service internals.

2. **Services & Managers** — `service/` — combined System Service + Manager SDK per domain.
   - `service/comfort/` — `base` (shared plumbing), `hvac` (✅ done), `seat` (⏳ todo)
   - `service/connectivity/` — `base` (shared plumbing), `bluetooth` (✅ done), `wifi` (⏳ todo)
   - Bluetooth is the odd one out: no VHAL property exists for MAC address/device name/HFP-A2DP-AVRCP
     state, so it skips `vps/`/JNI entirely. HFP/A2DP call Android's hidden Bluetooth profile
     proxies directly (`BluetoothHeadsetClient`, `BluetoothA2dpSink`); AVRCP calls the
     profile-agnostic `MediaSessionManager`/`MediaController` instead, since `BluetoothAvrcpController`
     is unreachable outside the Bluetooth mainline module in this AOSP version. Full rationale in
     [05-bluetooth-architecture.md](05-bluetooth-architecture.md).

3. **VPS (Vehicle Platform Service)** — `vps/` — C++ `libvps.so`.
   Polymorphic `IVpsHandler` interface; `HvacHandler`/`SeatHandler` each implement it;
   `VpsDispatcher` routes by property ID.

See also: [02-directory-structure.md](02-directory-structure.md), [03-implementation-status.md](03-implementation-status.md), [04-signal-flow.md](04-signal-flow.md).
