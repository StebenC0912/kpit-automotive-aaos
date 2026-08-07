# IX. HMI Knowledge-Share Topics

Framework-side topics for cross-team sharing with the HMI team.

1. **AIDL Callback Threading Rules — Keeping Binder Callbacks Off the Main Thread**
   Manager→ViewModel callbacks arrive over AIDL as `oneway` methods on a background Binder thread, not
   the UI thread. ViewModels must forward this state via `LiveData.postValue()` (never `setValue()` from
   a callback) so updates land safely on the main thread. Covers why listener methods must stay
   non-blocking, and how this pattern is shared across both Comfort (HVAC/Seat) and Connectivity
   (Bluetooth/WiFi) domains.
