# COMMIT MESSAGE RULE

Every commit message must follow this exact structure — one field per line, in this order:

```
[module] <short issue title>
[issue tracker] <ticket id / link>
[description] <what this commit changes>
[symptom] <observable bug behavior before the fix>
[rootcause] <why it happened>
[action] <what was changed to fix it>
[test step] <how it was verified>
[test rate] <test result / pass rate>
```

## Field reference

| Field | Content |
|---|---|
| `[module]` | Affected component, e.g. `hvac`, `bluetooth`, `seat`, `vps`, `base-comfort`. Paired with a short issue title on the same line. |
| `[issue tracker]` | Ticket ID or link (Jira/GitHub/internal tracker). Use `N/A` if untracked. |
| `[description]` | One or two sentences on what the commit does. |
| `[symptom]` | The observable bug behavior before the fix — what a user/tester would see. |
| `[rootcause]` | The actual underlying cause, not just the symptom. |
| `[action]` | What was changed in the code to resolve the root cause. |
| `[test step]` | How the fix was verified — build/flash/manual steps or test case run. |
| `[test rate]` | Result of verification, e.g. `10/10 pass`, `pass on device X`, `regression suite: 42/42`. |

## Example

```
[hvac] AC toggle does not turn off after 2nd tap
[issue tracker] JIRA-1042
[description] Fix HvacService dropping the second setAcState(false) call in the same session.
[symptom] Tapping AC on then off leaves the compressor running; UI shows AC off but VPS state stays on.
[rootcause] HvacHandler cached the last-set boolean and skipped re-emitting an event when the new
value equaled the cached one, so onAcStateChanged() never fired for the second call.
[action] Removed the value-equality short-circuit in HvacHandler::setProperty(); every setProperty
call now always echoes an event back, regardless of whether the value changed.
[test step] Flashed device, toggled AC on/off 10 times via hvac_app, watched onAcStateChanged
callbacks and the outside VPS log.
[test rate] 10/10 pass
```

## Notes
- Keep each field to plain text on its own line — no merging fields, no skipping fields.
- If a field doesn't apply (e.g. a pure refactor with no bug), still include it and write `N/A`
  rather than omitting the line, so the structure stays parseable.
- `[module]`'s line doubles as the commit title — keep it short (same length discipline as a normal
  git subject line, under ~70 chars including the tag).
