# XIII. AIDL Callback Threading — Keeping Binder Callbacks Off the Main Thread

**Scope:** one rule, enforced identically across both domains: a callback that arrives over
Binder — whether it's this repo's own AIDL (`IHVACVehicleCallback`, `IIviBluetoothListener`) or a
framework class built on Binder underneath (`MediaController.Callback`,
`BluetoothProfile.ServiceListener`, a `BroadcastReceiver`) — executes on a thread you do not
control and must not do two things on: (1) block it, and (2) mutate UI-bound state directly from
it. Every hop across a process boundary in this codebase is written specifically to prevent both.

Codified in [06-technical-requirements.md](06-technical-requirements.md), rule VI.1–2:

```
1. ANR prevention: Services use an ExecutorService or HandlerThread for Binder IPC, JNI calls,
   and property updates — never on the main thread. All onChangeEvent calls from JNI run on this
   background thread too.
2. AIDL callbacks: every listener method must be oneway to avoid blocking the IPC thread.
```

Two distinct failure modes this guards against — **not the same bug**:

- **Server-side ANR:** if a system service does real work (native calls, profile-proxy calls,
  broadcasting to N listeners) on its own main thread, its process can be killed for
  unresponsiveness — and because this is a `persistent="true"` system service shared across the
  whole car, that's a platform-wide outage, not one app crashing.
- **Client-side crash / stuck UI:** an AIDL listener callback always runs on the app's Binder
  thread pool (see §2 below), never the thread that called `registerCallback()`. Forwarding that
  value straight into `LiveData.setValue()` or touching a `View` throws — `LiveData`'s "Cannot
  invoke setValue on a background thread" `IllegalStateException`, or `CalledFromWrongThreadException`
  for raw View mutation.

Both domains share the base-class pair this contract actually lives in —
`AllianceCarBaseService`/`BaseConnectivityService` on the service side,
`AllianceCarBaseManager`/`BaseConnectivityManager` on the client side — which is why the rule is
identical in HVAC and Bluetooth rather than being domain-specific.

## 1. The full thread-hop chain, end to end

An event takes **five thread hops** from its physical origin to a pixel changing on screen, and
every hop exists to keep exactly one thread's obligations narrow. Using HVAC's `PROP_AC_STATE`
toggle as the running example:

```
[VPS/native thread]  HvacHandler::setProperty() echoes the new value as an event
        │             (vps/src/HvacHandler.cpp)
        ▼
[JNI callback thread] base_comfort_vhal_jni.cpp invokes onNativePropertyEvent()
        │
        ▼  HOP 1 — service hops onto its own worker pool immediately
[AllianceCarBaseService.mExecutorPool, 1 of 5 fixed threads]
        │  onNativePropertyEvent() -> mExecutorPool.execute(() -> onVehiclePropertyChanged(...))
        │  ("Immediately hops onto mExecutorPool so the JNI callback thread is released right
        │   away and onVehiclePropertyChanged always runs off the main thread.")
        ▼  HOP 2 — oneway AIDL broadcast, still on the same pool thread
[Binder IPC — crosses process: hvac_service → hvac_app]
        │  broadcastToListeners(l -> l.onChangeEvent(event)) walks the RemoteCallbackList and
        │  calls the oneway AIDL method. Because IHVACVehicleCallback.onChangeEvent is `oneway`,
        │  this call returns immediately on the service's pool thread — it does NOT block waiting
        │  for hvac_app to finish handling the event.
        ▼  HOP 3 — client-side Binder thread pool (NOT hvac_app's main thread)
[hvac_app's own Binder thread pool]
        │  AllianceCarHvacManager.mBinderCallback (IHVACVehicleCallback.Stub) — onChangeEvent() executes here.
        │  This is a thread owned by the Android Binder driver's thread pool for this process,
        │  completely independent of whichever thread called registerCallback().
        ▼  HOP 4 — synchronous in-process dispatch, still on the Binder thread
[same Binder thread]
        │  dispatchToProperty(event) -> hvacListener.onACStateChanged(value != 0)
        │  (a plain Java interface call — HvacListener is NOT itself Binder, so no extra hop here)
        ▼  HOP 5 — the actual bridge back to the main thread
[HvacViewModel.onACStateChanged(...) -> pushAboveState(...) -> LiveData.postValue(...)]
        │  postValue() is thread-safe by design: it schedules delivery on the main Looper
        │  internally and returns immediately, regardless of which thread called it.
        ▼
[Android main thread] LiveData observer fires -> HvacActivity updates the AC button.
```

The Bluetooth/AVRCP path is the same shape with one extra branch worth knowing, because
`MediaController.Callback` is **not raw AIDL** — it's a framework convenience wrapper where you
explicitly choose the callback's thread at registration time:

```java
// IviBluetoothService.java
private final Handler mMainHandler = new Handler(Looper.getMainLooper());
...
mMediaController.registerCallback(mMediaControllerCallback, mMainHandler);
```

So `MediaController.Callback.onMetadataChanged()`/`onPlaybackStateChanged()` land on
`IviBluetoothService`'s **main thread** (because `mMainHandler` was passed in) — the opposite of
the AIDL case, where the framework's Binder thread pool decides, not the registrant. The code
compensates for that explicitly, re-hopping onto the worker pool before doing anything else:

```java
private final MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
    @Override
    public void onMetadataChanged(MediaMetadata metadata) {
        mExecutorPool.execute(() -> publishMetadata(metadata));   // off main thread before any IPC
    }
    @Override
    public void onPlaybackStateChanged(PlaybackState state) {
        mExecutorPool.execute(() -> publishPlaybackState(state));
    }
};
```

From there it's the identical HOP 2–5 chain as HVAC: `broadcastToListeners()` (oneway AIDL) →
`bluetooth_app`'s own Binder thread pool (`IviBluetoothManager.mBinderListener`) → synchronous
`BluetoothListener` dispatch → `BluetoothViewModel.postValue(...)`.

**General lesson, not just an AVRCP quirk:** any time you register a framework callback that
*lets you choose* its thread (a `Handler` parameter, a `registerReceiver(receiver, filter, flags)`
overload, an `Executor` parameter on newer APIs), choose deliberately and then still re-hop onto
the worker pool before doing IPC/broadcast work — passing `Looper.getMainLooper()` does not mean
it's now safe to do blocking work there, it just determines which thread you're hopping *away
from*.

## 2. The fact that trips people up: client-side AIDL `Stub` callbacks never run on your main thread by default

It's easy to assume "the client registered this callback from the main thread, so it'll fire on
the main thread." **It will not.** An AIDL `Stub` implementation's methods execute on whichever
thread the Binder driver's thread pool hands the incoming transaction to — a small pool of threads
Android manages per-process, entirely decoupled from where `registerCallback()`/`registerListener()`
was originally called. True symmetrically on both ends of every AIDL interface in this repo:

- **Server side** (`IHVACVehicleService.Stub`, `IIviBluetoothService.Stub`): every method body is
  a one-line `mExecutorPool.execute(() -> ...)` hop, because an incoming AIDL call from a client
  also lands on this process's Binder thread pool, not any thread the service controls:
  ```java
  private final IIviBluetoothService.Stub mBinder = new IIviBluetoothService.Stub() {
      @Override
      public void connect(String macAddress) {
          mExecutorPool.execute(() -> connectDevice(macAddress));
      }
      ...
  };
  ```
- **Client side** (`AllianceCarHvacManager.mBinderCallback`, `IviBluetoothManager.mBinderListener`): same
  story in reverse — the *service's* outbound call lands on the *app's* Binder thread pool. Neither
  `Stub` body posts to a `Handler` or hops onto an executor of its own; they dispatch straight to
  the registered listeners synchronously:
  ```java
  private final IIviBluetoothListener.Stub mBinderListener = new IIviBluetoothListener.Stub() {
      @Override
      public void onDeviceConnectionChanged(BluetoothDeviceInfo device, boolean connected) {
          synchronized (mListeners) {
              for (BluetoothListener listener : mListeners) {
                  listener.onDeviceConnectionChanged(device, connected);
              }
          }
      }
      ...
  };
  ```
  That's deliberate and safe *only because* every `BluetoothListener`/`HvacListener`/`SystemListener`
  implementation in this repo (i.e. every ViewModel) ends its handling in `LiveData.postValue()`,
  never `setValue()`. The thread-safety obligation is pushed all the way to the last hop rather
  than solved at every intermediate layer — one rule, enforced once, at the boundary where it
  actually matters (UI thread affinity), instead of redundant `Handler`-hopping at every
  intermediate class.

## 3. Why every listener AIDL interface is declared `oneway`

```java
// IIviBluetoothListener.aidl
// oneway per rule IV.2 - never let a slow/dead HMI listener block the Service's IPC thread.
oneway interface IIviBluetoothListener {
    void onDeviceConnectionChanged(in BluetoothDeviceInfo device, boolean connected);
    void onPlaybackStateChanged(int state, long positionMs);
    void onMediaMetadataChanged(in MediaPlaybackInfo info);
}
```
```java
// IHVACVehicleCallback.aidl
interface IHVACVehicleCallback {
    oneway void onChangeEvent(in HvacEvent event);
}
```

`oneway` changes the call contract in one specific way: the caller's transaction returns
**immediately** after the arguments are marshalled, without waiting for the callee to actually
execute the method or return a result. Two consequences that matter here:

1. **No return value, no propagated exception.** Every AIDL method here is `void`; a `oneway`
   method literally cannot return anything meaningful to the caller, and any exception thrown
   inside the callee is dropped, not surfaced to the caller. That's the correct trade for a
   fire-and-forget event fan-out — the service doesn't need (and shouldn't want) a response from
   every listener before moving to the next one.
2. **The server-side pool thread never blocks on a slow client.** Without `oneway`,
   `hvac_service` broadcasting `onChangeEvent()` to `hvac_app` would be a *synchronous* Binder
   call — the service's `mExecutorPool` thread would sit blocked until `hvac_app`'s Binder thread
   pool actually finished processing the event, for every registered listener, one at a time
   inside `broadcastToListeners()`. With only 5 pool threads shared by a system service across
   every registered client, one misbehaving or dead client app could starve that pool and
   delay/hang events for every other client — the multi-tenant version of the same ANR risk rule
   VI.1 already guards against for a single process.

Contrast with `IHVACVehicleService.setVehicleProperty()` / `IIviBluetoothService.connect()` —
those are **not** `oneway`, and that's also deliberate: they're outbound commands from a single
app to the one system service, where the caller (the app) legitimately wants ordinary synchronous
Binder semantics (a `RemoteException` is a meaningful, catchable signal there — see every
`AllianceCarHvacManager`/`IviBluetoothManager` command method's
`try { service.xxx(); } catch (RemoteException e)` pattern). `oneway` is specifically for the
fan-out-to-many-listeners direction, not every AIDL method in the system.

## 4. `RemoteCallbackList` — why the broadcast loop is defensive by construction

Both `AllianceCarBaseService` and `BaseConnectivityService` share this identical helper:

```java
protected final int broadcastToListeners(ListenerInvocation<T> invocation) {
    int count = mCallbacks.beginBroadcast();
    try {
        for (int i = 0; i < count; i++) {
            try {
                invocation.invoke(mCallbacks.getBroadcastItem(i));
            } catch (RemoteException e) {
                Log.w(TAG, "Listener callback failed, will be pruned", e);
            }
        }
    } finally {
        mCallbacks.finishBroadcast();
    }
    return count;
}
```

This matters for the threading story specifically because it's what makes `oneway` broadcast safe
at scale: `RemoteCallbackList` auto-detects and prunes listeners whose process has died (a client
app was killed, crashed, or swiped away), so a broadcast to a stale registration doesn't
accumulate as permanent dead weight, and the inner `try/catch` means **one** listener throwing
`RemoteException` never aborts delivery to the rest. `mCallbacks.kill()` is called in every
`onDestroy()` to release all registrations cleanly when the service itself goes down.

## 5. Rule catalog (quick reference)

| # | Rule | Enforced where |
|---|------|------------------|
| 1 | Every AIDL **listener/callback** interface (service→client fan-out) is `oneway`. Outbound **command** interfaces (client→service) are ordinary synchronous AIDL. | `IIviBluetoothListener.aidl`, `IHVACVehicleCallback.aidl` vs. `IIviBluetoothService.aidl`, `IHVACVehicleService.aidl` |
| 2 | A service's `Stub` methods never execute business logic inline — every method body is a one-line hop onto `mExecutorPool`. | `IviBluetoothService.mBinder`, and identically in `AllianceCarHvacService` |
| 3 | Any event origin outside your own worker pool (native/JNI callback thread, `BroadcastReceiver.onReceive()`, a framework callback registered with an explicit `Handler`) re-hops onto `mExecutorPool` **before** doing any IPC or broadcast work. | `AllianceCarBaseService.onNativePropertyEvent()`, `IviBluetoothService.mConnectivityReceiver`, `IviBluetoothService.mMediaControllerCallback` |
| 4 | Client-side AIDL `Stub` callbacks dispatch synchronously to registered listeners — no extra Handler hop — because the *next* layer (ViewModel) is responsible for the final main-thread hop. | `AllianceCarHvacManager.mBinderCallback`, `IviBluetoothManager.mBinderListener` |
| 5 | ViewModels **never** call `LiveData.setValue()` from a listener callback — always `postValue()`, which is thread-safe from any calling thread. | Every `push*State()` method in `HvacViewModel.java`/`BluetoothViewModel.java` — zero `setValue()` calls exist in either file |
| 6 | `RemoteCallbackList.beginBroadcast()`/`finishBroadcast()` always paired in a `try/finally`; one listener's `RemoteException` is caught per-iteration and never aborts the rest of the broadcast. | `broadcastToListeners()` in both `AllianceCarBaseService` and `BaseConnectivityService` |
| 7 | A framework callback that lets you pick its thread via a `Handler` parameter is not automatically "safe" on that thread — it still must re-hop onto the worker pool before doing IPC/broadcast work. | `IviBluetoothService.mMediaControllerCallback` registered with `mMainHandler`, then immediately re-hopping via `mExecutorPool.execute(...)` in every override |

## 6. Self-check scenarios

Each corresponds to a rule above, phrased as "what breaks if you remove this" — useful for
onboarding or a design-review discussion:

1. **Drop `oneway` from `IIviBluetoothListener`.** What changes about `broadcastToListeners()`'s
   behavior if `bluetooth_app` is suspended by the OOM killer mid-broadcast, or its Binder thread
   pool is itself busy? *(The service's pool thread blocks waiting for that call to complete —
   with only 5 pool threads shared across every client of `bluetooth_service`, enough stuck
   clients exhaust the pool and delay every other app's events too.)*
2. **Replace `postValue()` with `setValue()` in `HvacViewModel.pushAboveState()`.** Which thread
   is `pushAboveState()` actually running on when it's called from `onACStateChanged()`, and what
   exception fires, immediately, every single time the AC button state changes? *(The app's own
   Binder thread pool, per §2 — `IllegalStateException` from `LiveData`'s main-thread assertion.)*
3. **Delete the `mExecutorPool.execute(...)` wrapper inside `IviBluetoothService.mBinder.connect()`
   and call `connectDevice(macAddress)` directly.** `connectDevice()` calls
   `setConnectionPolicy()` on a live Bluetooth profile proxy — a real IPC call to the Bluetooth
   mainline module's own process. What thread does that Binder call now block, and what
   user-facing symptom follows for *every other* client of `bluetooth_service`, not just the one
   that issued this `connect()` call? *(`bluetooth_service`'s Binder thread pool, not just this
   caller's request — every other AIDL call into `bluetooth_service` queues up behind it.)*
4. **Register `mMediaControllerCallback` without passing `mMainHandler`.** Which thread does
   `onMetadataChanged()` now run on, and does the existing `mExecutorPool.execute(...)` re-hop
   inside it become redundant, harmful, or still necessary? *(Still necessary — the default-thread
   behavior for `MediaController.registerCallback(callback)` without a `Handler` is the *calling*
   thread's Looper, which is itself running inside `onConnectivitySourceConnect()` on
   `mExecutorPool` already in this code path — so it'd happen to still work here, but only by
   accident of where registration happens to be called from, which is exactly the kind of
   thread-affinity assumption rule VI.1 exists to make you stop relying on.)*

## 7. Source files for this topic

- [06-technical-requirements.md](06-technical-requirements.md) — rules 1–2 (threading/AIDL), rule
  5 (Connectivity resync-on-bind, a related but distinct rule), and the full build-lesson log.
- [05-bluetooth-architecture.md](05-bluetooth-architecture.md) — why AVRCP goes through
  `MediaSessionManager` instead of `BluetoothAvrcpController` in the first place (the precondition
  for §1's branch).
- `service/comfort/base/src/com/kpit/comfort/base/service/AllianceCarBaseService.java` and the
  Connectivity-domain twin, `service/connectivity/base/src/com/kpit/connectivity/base/service/BaseConnectivityService.java`
  — the shared `mExecutorPool`/`RemoteCallbackList`/`broadcastToListeners()` machinery both
  domains inherit.
- `service/comfort/base/src/com/kpit/comfort/base/manager/AllianceCarBaseManager.java` and
  `service/connectivity/base/src/com/kpit/connectivity/base/manager/BaseConnectivityManager.java`
  — client-side connection/reconnection/`DeathRecipient` handling (`getService()`/`connectLocked()`).
- `service/connectivity/bluetooth/src/com/kpit/bluetooth/service/IviBluetoothService.java` — the
  single richest file for this topic: native-thread-free but still exercises JNI-callback-style
  origin hops (`BroadcastReceiver`, `MediaController.Callback`) plus the full oneway-AIDL fan-out.
- `hmi/hvac_app/src/com/kpit/hmi/hvac/viewmodel/HvacViewModel.java` and
  `hmi/bluetooth_app/src/com/kpit/hmi/bluetooth/viewmodel/BluetoothViewModel.java` — confirm for
  yourself: `grep -n "setValue\|postValue"` on either file shows `postValue()` only.
