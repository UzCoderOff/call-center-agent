# Ledger — Android app

The firm's staff app. Staff install it, sign in with their portal username
and password, and use the portal inside it. Nothing to configure on the
phone — the server addresses are built in (`gradle.properties`).

For people whose account has **"collect calls"** switched on in the portal
(call-center staff), the app also collects the phone's calls and call
recordings — a few minutes after each call, and hourly as a safety net. For
everyone else it never asks for those
permissions and never reads anything on the phone.

**Building, signing and handing out the app: see `DEPLOY.md` in
`call-center-backend`.**

## How it works

```
LauncherActivity ── not signed in ──> LoginActivity (username + password)
        │                                   │
        └──── signed in ──> refresh session + "collect calls?" from the server
                                  │
              collect calls: setup not finished ──> PermissionsActivity (setup guide)
                                  │
                           PortalActivity (the portal in a WebView)
```

- **Sign-in** (`Api.signIn`) returns a device token, stored encrypted with an
  Android Keystore key (`SecureStore`). The password is never stored. The
  token renews the portal session whenever it expires, so staff stay signed
  in; a developer can sign a phone out from the portal.
- **The portal** runs in a WebView and talks to the app through
  `window.LedgerApp` (sign out, renew session, sync now, share diagnostics).
  `tel:` links open the dialer; other sites open in the browser.
- **Sync** (`SyncWorker`) runs a few minutes after **every call** (Android
  wakes the app when the call log changes, even when it's closed), hourly as
  a safety net, after sign-in, and on demand. It looks for recordings only
  in folders where recordings were found before, walking all storage at
  most daily — so frequent syncs stay light on the battery. Each run:
  - reads call-log entries since a day before the last successful sync, so
    calls that were still in progress during a sync aren't lost; entries
    already sent are skipped;
  - waits until a call ended 2+ minutes ago, so the recording is finished;
  - matches recordings to calls by file time around the call's **end**
    (long calls used to lose their recordings), closest match first;
  - reports call-log rows deleted before they could sync (gaps in the log's
    row ids) — a heuristic, shown on the employee's page in the portal;
  - uploads even when nothing is new, as a heartbeat for the portal's "phone
    sync" status;
  - never reads anything from before collection started on this phone.
- **Phone setup** (`PermissionsActivity`, `PhoneMaker`): a guide, one step
  per screen, with numbered "tap this" lines — call log, all-files access,
  battery, and on Honor / Huawei / Xiaomi the maker's auto-launch screen
  (opened directly; Honor's menu names in Uzbek and Russian). Each step is
  checked when the person comes back and the next one opens by itself;
  auto-launch can't be read by an app, so the person confirms it. Profile →
  *Telefonni sozlash* in the portal opens it again.
- **Icon**: adaptive (`res/drawable/ic_launcher_*.xml`, with a themed
  version for Android 13+); the same mark is the portal's logo.
- **Diagnostics**: every sync appends to an on-phone log; Profile → *Share
  diagnostics* sends it (Telegram, email…) without a cable.

## If a phone has no native call recording

Install **Cube ACR** from the Play Store, grant its Accessibility permission,
and turn on "record all calls automatically". The app scans all storage for
new audio files, whichever app made them.
