# IV. Bi-directional Signal Flow

Both domains share the same HMI→ViewModel→Manager→Service (and reverse) shape, diverging only at
the Service: Comfort continues into VPS/JNI, Connectivity stops at an OS API call.
[05-bluetooth-architecture.md](05-bluetooth-architecture.md) has the full Bluetooth write-up; this
is the general shape for both.

### Path 1 — Command (HMI → ViewModel → Manager → Service → domain-specific sink)
1. Activity calls a ViewModel method (e.g. `hvacViewModel.toggleAc()`, `bluetoothViewModel.connect(mac)`).
2. ViewModel calls the matching Manager method (e.g. `hvacManager.setAcState(true)`).
3. Manager SDK sends it over Binder via the AIDL interface to the Service.
4. The Service receives the semantic call. What happens next depends on the domain:
   - **Comfort (HVAC/Seat):** translates it into `setProperty(int propertyId, Object value)`, dispatching
     to `nativeSetBooleanProperty`/`nativeSetIntProperty` by the runtime type of `value`.
   - **Connectivity (Bluetooth/WiFi):** calls the relevant Android framework API directly — e.g.
     `IviBluetoothService` calls `BluetoothHeadsetClient`/`BluetoothA2dpSink`'s `connect()`/`disconnect()`,
     or a `MediaController.TransportControls` method for media keys
     ([05-bluetooth-architecture.md](05-bluetooth-architecture.md)). No JNI, no `setProperty()`.
5. **Comfort only:** JNI passes propertyId + value to the C++ `VpsDispatcher`, which routes to
   `HvacHandler`/`SeatHandler` by property ID. Connectivity has no equivalent step — the framework API
   call in step 4 is the final hop
   ([01-architecture-overview.md](01-architecture-overview.md), [05-bluetooth-architecture.md](05-bluetooth-architecture.md)).

### Path 2 — Event (domain-specific source → Service → Manager → ViewModel → HMI)
1. The signal originates differently per domain:
   - **Comfort:** the ECU triggers an event; the C++ Handler captures it and invokes a JNI callback into
     the Service's generic `onChangeEvent(int propertyId, Object value)`.
   - **Connectivity:** Android's Bluetooth stack pushes the event directly into the Service — a
     `BroadcastReceiver` for HFP/A2DP (`ACTION_CONNECTION_STATE_CHANGED`), a `MediaController.Callback`
     for AVRCP ([05-bluetooth-architecture.md](05-bluetooth-architecture.md)) — no JNI, no ECU involved.
2. The Service maps the raw signal to the semantic AIDL listener call — e.g. `onAcStateChanged(boolean)`
   for Comfort, `onDeviceConnectionChanged(...)` for Connectivity — and fans it out via
   `RemoteCallbackList` (shared by `BaseComfortService`/`BaseConnectivityService`).
3. The Manager's AIDL stub receives the callback and forwards it to the registered ViewModel listener.
4. ViewModel updates state via `liveData.postValue(...)` (thread-safe).
5. Activity, observing `LiveData`, updates the UI on the main thread.

### Domain properties

**HVAC:**
| Signal                  | Command                       | Event                               |
|-------------------------|--------------------------------|-------------------------------------|
| AC state (bool)         | `setAcState(boolean)`         | `onAcStateChanged(boolean)`         |
| Temperature (int)       | `setTemperature(int)`         | `onTemperatureChanged(int)`         |
| Seat heater level (int) | `setSeatHeaterLevel(int)`     | `onSeatHeaterLevelChanged(int)`     |
| Seat ventilation (bool) | `setSeatVentilation(boolean)` | `onSeatVentilationChanged(boolean)` |

**Bluetooth** (no VHAL/JNI — see [05-bluetooth-architecture.md](05-bluetooth-architecture.md)):
| Signal            | Command                              | Event                                                     |
|--------------------|--------------------------------------|-----------------------------------------------------------|
| Device connection | `connect`/`disconnect(String mac)`   | `onDeviceConnectionChanged(BluetoothDeviceInfo, boolean)` |
| Device identity   | — (carried in `BluetoothDeviceInfo`) | same event as above                                       |
| Media command     | `sendMediaCommand(int action)`       | `onPlaybackStateChanged(int state, long positionMs)`      |
| Media metadata    | — (read-only)                        | `onMediaMetadataChanged(MediaPlaybackInfo)`               |
