# Call Agent (Android)

Background app for the company's Honor phones: every 12 hours it checks the call
log and scans storage for new call recordings, and uploads whatever it finds to
your server. Works the same whether the phone has native auto call recording or
needs the Cube ACR fallback — it doesn't hardcode a folder path.

## Get an APK — no Android Studio needed
1. Push this folder to a GitHub repo.
2. GitHub Actions builds it automatically on push (see the **Actions** tab), or
   trigger it manually with "Run workflow."
3. Download the APK from that workflow run's **Artifacts** section.

## Install on a phone over AnyDesk
1. Get the APK onto the phone (AnyDesk file transfer, or a download link) and
   install it — allow "install from unknown sources" when prompted.
2. Open the app and fill in:
   - **Employee ID** — any short identifier for that person.
   - **Server URL** — your temporary tunnel URL for now, your VPS URL later.
     No rebuild needed to change this — just edit it in the app and hit Save again.
3. Tap **Grant Permissions** — approve call log access, and turn on "All files
   access" on the screen that opens next.
4. Tap **Disable Battery Optimization** and approve it — MagicOS is aggressive
   about killing background apps otherwise.
5. Tap **Sync Now** to test immediately instead of waiting 12 hours.

## If a phone has no native auto call recording
Install **Cube ACR** from the Play Store → open it → grant the Accessibility
permission it asks for (that's what lets it capture call audio) → in its own
settings, turn on "record all calls automatically." Nothing else to configure —
the agent scans the whole phone for new audio files regardless of which app
produced them.

## What your server needs to handle
`POST {serverUrl}/api/calls/sync` — multipart/form-data with:
- a `payload` part — `application/json`, shape below, containing **every** call
  log entry since the last sync (not just ones with a matched recording)
- zero or more `recording` parts — the audio files that matched a call by
  timestamp (within 5 minutes), filename as sent

`payload` JSON shape:
```json
{
  "employeeId": "employee_1",
  "syncedAtMs": 1758210000000,
  "callCount": 3,
  "missedCount": 1,
  "logIntegrity": null,
  "calls": [
    {
      "callLogId": 482,
      "phoneNumber": "+998901234567",
      "callType": "missed",
      "missed": true,
      "callTimestampMs": 1758209000000,
      "durationSeconds": 0,
      "recordingFilename": null
    },
    {
      "callLogId": 483,
      "phoneNumber": "+998901234567",
      "callType": "incoming",
      "missed": false,
      "callTimestampMs": 1758209400000,
      "durationSeconds": 184,
      "recordingFilename": "call_20250918_1430.m4a"
    }
  ]
}
```

- `callType` — `incoming` / `outgoing` / `missed` / `rejected` / `voicemail` / `unknown`
- `missed` — convenience boolean, `true` for `missed` or `rejected`
- Every call in the log is sent, whether or not a recording exists for it.
  `recordingFilename` is `null` when no audio file was matched (expected for
  missed calls) or the exact filename of one of the `recording` multipart
  parts in the same request when there is one — match on that to attach the
  right audio to the right log entry, no guessing needed.
- `logIntegrity` — `null` normally. A non-null string (`call_log_shrank`,
  `possible_gap`, `no_new_entries_since_last_sync`) is a **heuristic flag**
  that the device's call log may have had entries removed since the last
  sync (e.g. someone cleared call history). It's not proof — treat it as
  "worth a look," not "confirmed tampering."

Return any 2xx status on success. A non-2xx response (or no response) leaves
that sync cycle unmarked, so the whole batch is retried next time — dedupe
on the server by `employeeId` + `callLogId` in case a batch partially landed
before a failure.

## Notes
- Sync runs every 12 hours via WorkManager, plus on-demand via "Sync Now,"
  and re-arms itself after a device reboot.
- **Nothing from before install is ever uploaded.** The first time the sync
  worker ever runs on a device, it records that moment as a permanent
  "install floor." Every run after that — including all future ones —
  hard-filters out any call log entry or recording file timestamped before
  that floor, regardless of what LAST_SYNC or file-modified timestamps say.
  A pre-existing voice memo or old recording sitting on the phone before
  this app was installed will never be picked up, even if its file gets
  touched/moved later and its modified-time changes.
- Recordings are only ever attached to a call if they land within 5 minutes
  of that call's actual logged timestamp — a stray audio file with a
  coincidentally-recent modified time still won't upload unless it happens
  to line up with a real call in the log.
- `usesCleartextTraffic="true"` is set in the manifest so a plain `http://`
  tunnel or IP works while you're pointing this at your PC. Once you're on a
  real HTTPS domain (VPS), you can tighten this if you want.
