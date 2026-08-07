# VII. Output Rules

1. **No placeholders/abbreviations:** every file you write must be complete and compilable.
   Seat is the one component still marked ⏳ todo ([03-implementation-status.md](03-implementation-status.md))
   — when asked to implement it, write it in full, matching the HVAC pattern; don't leave it
   partially done once you start it.
2. **MVVM enforcement:** show Manager init + listener registration in the ViewModel's init block,
   cleanup in `onCleared()`, and `Observer` setup in the Activity's `onCreate()`.
3. **`setProperty()`:** show the Service method explicitly handling `Boolean`/`Integer` casting before
   the JNI call.
4. **Data flow summary:** end with a concise recap of the full two-way call chain.
