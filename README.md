# gCallRecorder

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/mexus)

Silent, automatic call recording for Google Dialer on rooted Android (KernelSU/Magisk + LSPosed).

**One APK, two jobs.** `app/` builds a single package (`io.github.mexus.gcallrecorder`) that is *both* an LSPosed module and a launchable root app:

1. **LSPosed module** — unlocks Google Dialer's built-in call recording in regions where Google disables it, and **silences the spoken "this call is being recorded" disclosure**. It fails safe: if the module or LSPosed stops, nothing is recorded (recording is never left running without the disclosure).
2. **Root companion app** — mirrors your phonebook into Dialer's "Automatically record these numbers" list and keeps the native auto-record toggles on, so **every** call — contacts and unknown numbers — records automatically. Its one-screen UI shows root status, requests the permissions it needs, and offers auto-sync / sync-now / clear.

The same installed package is enabled in LSPosed (for the hooks) and appears in your launcher (for the sync UI) — there is nothing else to install.

## Credit / upstream

The LSPosed module in `app/` is a **fork of [vvb2060/CallRecording](https://github.com/vvb2060/CallRecording)** by **vvb2060**, whose work is the foundation of the recording-enable hooks and the bundled native `DexHelper` (dex string-search) library. All of the original idea and the hard parts of hooking Google Dialer's obfuscated internals come from that project. This fork:

- ports the module from the legacy Xposed API 82 to the modern **libxposed API 102**;
- repurposes the disclosure handling to keep the announcement silent (you notify the other party yourself);
- fixes a bit-rotted hook (`withinCrosbyGeoFence` → `withinCallRecordingGeoFence`, renamed by Google);
- couples recording-enable to disclosure-silence so it fails safe.

Please consider starring or contributing back to the upstream project. The vendored `DexHelper` sources under `app/src/main/jni/` and `app/src/main/java/io/github/vvb2060/callrecording/xposed/DexHelper.java` remain under their original authorship and are kept in their original `io.github.vvb2060.callrecording.xposed` package.

## How it works

### Recording + silence (LSPosed module)

Scoped to `com.google.android.dialer`, on each launch the module discovers Dialer methods at runtime (via `DexHelper`) and installs interceptor hooks:

- forces the recording-availability gates on: `canRecordCall`, `isCallRecordingCountry`, `getSupportedLocaleFromCountryCode` → `Locale.US`, `withinCallRecordingGeoFence`;
- silences the disclosure: the Dialer plays a per-locale audio file `files/audioinjector/call_recording_starting_voice_<locale>.wav`; redirecting the locale to `en_US` (a 44-byte silent clip on-device) plus neutralising the public TTS path keeps it inaudible;
- **fail-safe ordering**: the silence hooks are installed first; the enable hooks are only installed if silencing succeeded, so the module never records with an audible announcement, and with the module off the Dialer offers no recording at all.

### Auto-record every call (root sync, in the same app)

Auto-record in current Dialer builds is a Dagger producer graph with no clean boolean to hook, so the app drives Dialer's **own** settings instead:

- reads your contacts (E.164, `READ_CONTACTS`);
- edits Dialer's `CallRecordingSettingsData.pb` Proto DataStore so field 6 (`AlwaysRecordSelectedNumbers`) mirrors your phonebook and the toggles stay on;
- because DataStore keeps an authoritative in-memory copy, the write is applied **kill-first**: force-stop Dialer (root), write the file, restore owner + SELinux context; Dialer cold-reads the new settings on next launch;
- a change-diff avoids restarting Dialer when nothing changed;
- a **hard in-call guard** (app-level call-state check, deferral until idle, and a fail-closed re-check inside the root script) guarantees the Dialer is never force-stopped during a call — saving a contact mid-call queues the sync until you hang up;
- triggers: a **WorkManager periodic job** (15-min floor) as the persistent background trigger, plus an in-app contacts-change observer (debounced) while the app is open, plus boot and a manual "Sync now"; an "Auto-sync" switch and a "Clear list" button in the one-screen UI.

## Requirements

- **Root** — any of KernelSU, KernelSU Next, SukiSU / SukiSU Ultra or other KernelSU fork, Magisk.
- **A Zygisk provider** — ZygiskNext (used here), ReZygisk, or Magisk's built-in Zygisk.
- **LSPosed built against the modern libxposed API (102)** — the module declares `minApiVersion=101` / `targetApiVersion=102` and will not load on an older LSPosed that only speaks the legacy Xposed API. Use a current LSPosed (or a compatible fork) that supports API 102.
- **Google Dialer** (`com.google.android.dialer`).
- After install: enable **gCallRecorder** in LSPosed (scope auto-selects Dialer), grant the app Contacts + Phone permissions, and approve root. For full coverage also turn on Dialer's "Automatically record unknown numbers".

## Support

If this saved you some grief, you can buy me a coffee: **[ko-fi.com/mexus](https://ko-fi.com/mexus)**. (Please also consider supporting [vvb2060](https://github.com/vvb2060), whose upstream module made all of this possible.)

## Legal / consent

Call-recording law varies by jurisdiction. This tool records **your own** calls; **you are responsible** for notifying the other party as your local law requires. The disclosure is silenced specifically so you can give that notice yourself in a way that works for you — not to record people without their knowledge. Use it lawfully.

## License

The upstream [vvb2060/CallRecording](https://github.com/vvb2060/CallRecording) repository ships **no explicit license file**, so no license is granted by it; all rights to the original module code and the vendored `DexHelper` remain with **vvb2060**. This repository is a personal fork published for the author's own use and as attribution to that work — it grants no additional rights over the upstream portions. If you want to reuse any of it, ask vvb2060 about the upstream parts first.
