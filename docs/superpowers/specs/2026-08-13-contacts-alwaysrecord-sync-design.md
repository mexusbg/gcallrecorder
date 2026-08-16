# Contacts → Always-Record Sync — Design

**Date:** 2026-08-13
**Status:** Approved design, pending spec review
**Author:** mexus (with Claude)
**Relates to:** the `io.github.mexus.callrec` LSPosed module (v1.0) which unlocks + silences call recording. This adds the "auto-record all calls" half (goal 2) that the module could not do via hooks.

## Overview

A small root-enabled Android companion app that mirrors the phone's contacts into Google Dialer's "Automatically record these numbers" list (`AlwaysRecordSelectedNumbers`) and keeps the two native auto-record toggles on. Together with the dialer's native "Automatically record unknown numbers" toggle, this makes **every** call — contacts and unknowns — auto-record, using the dialer's own settings rather than fragile method hooks.

The LSPosed module already unlocked the native call-recording settings and silences the disclosure. This app only manages settings data; it installs no hooks.

### Why a separate root app (spike findings)

Established empirically on-device (Google Dialer `com.google.android.dialer` v233, KernelSU):

- Auto-record has no clean boolean hook in dialer 233 — the decision is a Dagger producer graph over `ListenableFuture`s (`produceShouldStartAutomaticRecording`), too fragile to force.
- The dialer's native settings **do** drive auto-record: "Automatically record unknown numbers" (proto field 1) covers non-contacts; "Automatically record these numbers" (proto field 6, repeated E.164) covers listed contacts. Both were confirmed to auto-record.
- The settings live in a Proto DataStore file: `/data/data/com.google.android.dialer/files/CallRecordingSettingsData.pb`.
- **External writes to that file are honored only if written while the dialer process is fully dead.** DataStore keeps an authoritative in-memory copy; writing while the process is alive is clobbered (verified: a write while alive was reverted on the next force-stop). Kill-first, then write, then the dialer cold-reads on next launch (verified: injected real contacts appeared in the UI and were honored; multi-number contacts work).
- Because the write requires force-stopping the dialer, the writer cannot live inside the dialer process (an LSPosed hook cannot cleanly kill its own host). Hence a separate app.

### Proto schema (captured on-device)

`CallRecordingSettingsData.pb`, wire-decoded:

| field | wire | meaning | handling |
|------:|------|---------|----------|
| 1 | varint | `AlwaysRecordNonContact` (1=on) | **force to 1** (enforce toggle) |
| 2 | varint | (observed 2) | preserve |
| 3 | varint | (observed 1) | preserve |
| 4 | varint | (observed 1) | preserve |
| 5 | varint | (observed 0) | preserve |
| 6 | length-delim string, **repeated** | `AlwaysRecordSelectedNumbers` (E.164, e.g. `+359888284800`) | **replace with full contact set** (or empty on Clear) |
| 7 | varint | (observed 3) | preserve |

`AlwaysRecordingSetAtLeastOnce` is one of the varint fields (to be pinned during implementation by toggling it in the UI and diffing the `.pb`); the codec must **set it on** as part of "enforce toggles," and otherwise **preserve every non-field-6 field** including unknown ones.

### Goals

- Keep "Automatically record these numbers" equal to the current phonebook (E.164), updated on contacts add/edit/delete.
- Keep both native toggles on (`AlwaysRecordNonContact` + `AlwaysRecordingSetAtLeastOnce`) so a settings reset can't silently disable auto-record.
- Never disrupt an active call: **never force-stop the dialer while a call is in progress.**
- Minimize disruption: force-stop the dialer only when the effective settings actually change.
- Provide manual controls: "Sync now", "Clear list", and an "Auto-sync" on/off switch.

### Non-goals

- No method hooking (that's the LSPosed module's job).
- No management of the disclosure/silence (module's job).
- No contact editing — read-only on contacts.
- Not a general call-recorder UI — recordings are the dialer's.

## Architecture

`io.github.mexus.callrecsync`, a **new Gradle module** (`syncapp/`) in this repository alongside the existing LSPosed `app/`. Plain Android app, no Xposed. Root (`su`) is used **only** for force-stop + proto write + `chown`/`restorecon`; contacts are read with the normal `READ_CONTACTS` runtime permission. Call state is read with `READ_PHONE_STATE`.

### Components (each independently testable)

- **ContactsReader** — `readE164Numbers(): Set<String>`. Queries `ContactsContract.CommonDataKinds.Phone`, prefers `NORMALIZED_NUMBER` (E.164); falls back to `PhoneNumberUtils.formatNumberToE164(number, simRegion)`. Drops entries that don't start with `+` (service codes, un-normalizable). Dedups.
- **SettingsProtoCodec** — pure, no Android deps. `parse(bytes): Settings` (ordered fields incl. unknowns), `build(Settings): bytes`. Helpers: `withSelectedNumbers(set)`, `withTogglesOn()`, `withClearedNumbers()`. Preserves every field except the ones it intends to change. Hand-rolled protobuf (varint + length-delimited); no `.proto` dependency.
- **CallStateGuard** — `isCallActive(): Boolean` via `TelephonyManager.getCallState()`; and `runWhenIdle(action)` which, if a call is active, registers a `TelephonyCallback`/`PhoneStateListener` and fires `action` once state returns to `IDLE` (single-shot), else runs immediately.
- **RootWriter** — `writeProto(bytes): Result`. One `su` invocation that: re-checks no call is active, force-stops the dialer, polls `pidof` until gone (bounded), backs up the current `.pb`, `base64 -d`>file, `chown` to the file's existing owner (derived via `stat -c %U`, not hardcoded), `restorecon`. Returns failure if root is unavailable or the dialer can't be confirmed stopped.
- **SyncEngine** — `sync(reason)`: if auto-sync disabled and reason≠manual, return. Read contacts → read current `.pb` → compute target proto (field 6 = contact set, toggles on, other fields preserved). If target bytes == current bytes, **skip** (no kill, no write). Else, via `CallStateGuard.runWhenIdle`, call `RootWriter.writeProto`, then verify by re-reading + parsing. Record last-sync status. `clear()`: target proto = current with field 6 emptied (toggles left as-is); same write path.
- **Triggers:**
  - `ContactsObserver` — `ContentObserver` on `Phone.CONTENT_URI`, debounced ~30 s, calls `SyncEngine.sync(CONTACTS_CHANGED)`. Debounce timer does not fire while a call is active (defers via CallStateGuard).
  - `BootReceiver` — `BOOT_COMPLETED` → `SyncEngine.sync(BOOT)`.
  - Manual — "Sync now" button → `sync(MANUAL)`.
- **MainActivity (single screen)** — shows: root status, contacts count, current listed-count, last-sync time + result, toggle states, an **Auto-sync** switch, **Sync now**, and **Clear list** (with confirm). Persists Auto-sync flag in `SharedPreferences`.

### Data flow

```
contacts change ─┐
boot ────────────┼─▶ SyncEngine.sync ─▶ ContactsReader + read .pb
manual ──────────┘        │
                          ▼
                  SettingsProtoCodec.build(target)
                          │  target == current?  ── yes ─▶ skip (no kill)
                          ▼ no
                  CallStateGuard.runWhenIdle ── call active? ── yes ─▶ defer, run on IDLE
                          ▼ idle
                  RootWriter.writeProto (kill-first, chown, restorecon)
                          │
                          ▼
                  verify re-read ─▶ record status ; dialer honors on next launch
```

### The in-call guard (hard requirement)

Killing the dialer during a call would drop/disrupt it. The guard is layered:

1. `ContactsObserver` debounce does not trigger a sync while a call is active (e.g. saving a contact mid-call queues, never kills).
2. `SyncEngine` routes every write through `CallStateGuard.runWhenIdle` — if a call is active, the sync is deferred and fired once the call ends.
3. `RootWriter` re-checks call state **inside** the `su` script immediately before the force-stop (closes the check→kill race if a call starts in between); aborts the write if a call is active, leaving the file untouched (a later trigger retries).

### Clear list + Auto-sync

- **Clear list** writes field 6 empty (kill-first), removing all contact numbers from auto-record. Confirmed via a dialog.
- Because the sync mirrors the phonebook, a Clear would be immediately refilled by the next contacts-change if auto-sync were on. The **Auto-sync switch** gates the observer/boot triggers: turn it off, Clear, and the list stays empty. Manual "Sync now" always works regardless of the switch.

### Error handling

- No root → UI shows "root unavailable", all writes no-op, no crash.
- Proto parse failure (dialer update changed the format) → abort the write, keep the backup, surface a clear error rather than writing a malformed file.
- Call active at write time → defer (never a hard failure).
- Post-write verification: re-read + parse the `.pb`; if it doesn't match the target, report failure and keep the backup.
- Owner uid / SELinux differences across devices → derive owner from the existing file (`stat`), always `restorecon`; never hardcode `u0_a195`.

### Testing

- **JVM unit (SettingsProtoCodec):** parse a real captured `.pb` (fixture from this device); round-trip identity; `withSelectedNumbers` replaces only field 6 and preserves fields 1–5,7 incl. an injected unknown field; `withTogglesOn` sets field 1 (and the pinned AlwaysRecordingSetAtLeastOnce field); `withClearedNumbers` empties field 6 only. Byte-exact assertions.
- **JVM unit (normalization):** raw/formatted numbers → E.164; non-dialable dropped; dedup.
- **JVM unit (SyncEngine diff):** target==current → skip; changed → write; clear path.
- **Device/manual:** add a contact → appears in list; edit number → old gone/new present; delete → removed; unchanged edit → no dialer kill; **sync attempted during an active call → dialer NOT killed, runs after hang-up**; Clear with auto-sync off → stays empty; toggles remain on after a sync.

## Project layout

```
app/                      # existing LSPosed module (unchanged)
syncapp/                  # NEW
  build.gradle.kts        # plain android app, io.github.mexus.callrecsync
  src/main/AndroidManifest.xml   # READ_CONTACTS, READ_PHONE_STATE, RECEIVE_BOOT_COMPLETED, QUERY dialer
  src/main/java/io/github/mexus/callrecsync/
    ContactsReader.kt
    SettingsProtoCodec.kt        # pure, unit-tested
    CallStateGuard.kt
    RootWriter.kt
    SyncEngine.kt
    ContactsObserver.kt          # ContentObserver + debounce
    BootReceiver.kt
    MainActivity.kt
  src/test/java/io/github/mexus/callrecsync/
    SettingsProtoCodecTest.kt
    NormalizationTest.kt
    SyncEngineTest.kt
settings.gradle.kts       # add :syncapp
```

Signing/build reuse the repo's toolchain (JBR 21, `--no-configuration-cache`).

## Risks / open items

- **AlwaysRecordingSetAtLeastOnce field number** — pin during implementation (toggle in UI, diff the `.pb`). Until pinned, "enforce toggles" sets field 1 and preserves the rest.
- **Dialer force-stop UX** — brief; mitigated by the change-diff (no kill when unchanged) and in-call guard. Acceptable per user.
- **Dialer updates** — could change field numbers or the DataStore path. The codec preserves unknown fields and only touches 1/6 (+ the pinned toggle); a format change is detected by parse failure → abort + report, never a bad write.
- **Number matching** — the dialer resolves listed E.164 to contacts for display; using `NORMALIZED_NUMBER` matches what the UI stores. Numbers that don't normalize to E.164 are skipped (rare; service codes).
- **Very large phonebooks** (this device: ~1500 numbers) — proto stays small (KB); write is fine.
