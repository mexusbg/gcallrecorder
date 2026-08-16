# Silent Auto-Record Dialer Module — Design

**Date:** 2026-08-13
**Status:** Approved design, pending spec review
**Author:** mexus (with Claude)

## Overview

A headless LSPosed module for Google Dialer (`com.google.android.dialer`) that:

1. **Silences the audible "this call is being recorded" disclosure** while keeping call recording fully functional. The user notifies parties themselves; the built-in Bulgarian TTS announcement is unusable.
2. **Auto-records every call** (incoming and outgoing, no exceptions) by hooking the dialer's automatic-record decision.
3. Is built against the **modern libxposed API 102** (`io.github.libxposed:api:102.0.0`), migrating away from the legacy Xposed API 82 used by the base module.

It is a fork of [vvb2060/CallRecording](https://github.com/vvb2060/CallRecording) (module `io.github.vvb2060.callrecording` v1.3), rewritten for the modern API with two behavior changes and a fix for a bit-rotted hook.

### Goals

- Recording works in Bulgaria (not a Google-supported call-recording country) without any audible disclosure to either party.
- All calls recorded automatically, no manual button press, no per-contact list maintenance.
- Fail-safe: if the module or LSPosed stops working, recording simply does not happen — never recording-with-loud-announcement, never silent recording without the module.
- Modern API 102 module (new entry mechanism, hook style, scope declaration).

### Non-goals

- No settings UI (headless, like the base module).
- No visual-indicator hiding (on-screen recording indicator left as-is; the user is fine being visibly notified).
- No emergency-number exception (user explicitly chose to record everything).
- No consent/legal enforcement — the user handles party notification out of band.

## Background — recon findings

Established during device analysis (KernelSU root, LSPosed on 2512BPNDAG / Android 17):

- **Base module** `io.github.vvb2060.callrecording` v1.3 (versionCode 4), scoped only to `com.google.android.dialer` in the LSPosed config DB, enabled. Source matches repo HEAD tag `v1.3` (manifest, versionCode/Name, log strings, native lib set all identical; live log `W/CallRecording: withinCrosbyGeoFence method not found` confirms it is this module running).
- **Dialer** version `233.0.958033597-publicbeta` (versionCode 19967233), single `base.apk`, 5 dex.
- **Bit-rot found:** base module's `withinCrosbyGeoFence` hook no longer matches — Google renamed Crosby → CallRecording. Dialer now exports `withinCallRecordingGeoFence` / `withinCallRecordingGeofence`. Non-fatal (Log.w only) but must be fixed.
- **New unhooked gate:** a Fermat pipeline (`com/android/dialer/fermat/enabledfn/FermatFeatureResolver.isCallRecordingEnabled`) also gates recording; observed logging `[Call Recording] disabled by flag` / `Fermat not supported`. Not required for the legacy recording path but noted.

### Relevant dialer symbols (string-scanned)

Recording enablement gates (base module already targets these by string):
- `canRecordCall` (`Z`, no-arg) — classes.dex
- `isCallRecordingCountry` (`Z`) — classes.dex
- `getSupportedLocaleFromCountryCode` (returns `Locale`, args `Map,String`) — classes.dex, classes2.dex
- `withinCallRecordingGeoFence` / `withinCallRecordingGeofence` (`Z`) — classes.dex **(renamed target)**

Disclosure / announcement:
- `com/android/dialer/callrecording/disclosure/impl/TtsCallRecordingDisclosure` — spoken disclosure via TTS
- `builtInCallRecordingDisclosure`, `playStartDisclosure`
- Beep path: `playBeep`, `starting_voice-beep_sound.ogg`, `ending_voice-beep_sound.ogg`, beep disclosure types `BEEP_SOUND` / `BEEP_ONLY` / `SINGLE_BEEP` / `RECURRING_BEEP`
- Recording start is sequenced *after* disclosure completion: `onAutomaticRecordingStartDisclosurePlaybackCompleted`, `CallRecordingButtonController_startCallRecording_playStartingAudio` → `_startRecording`
- `android.speech.tts.TextToSpeech#synthesizeToFile` — how the spoken disclosure audio is generated (base module already hooks this on the failure path)

Auto-record decision:
- `canRecordAutomatically` — classes.dex **(primary target)**
- `produceIsCallInAutoRecordContactsList` — classes2.dex (contact-list gate)
- `shouldRecordAudioData`
- Settings: `AlwaysRecordNonContact`, `AlwaysRecordSelectedNumbers`, `AlwaysRecordingSetAtLeastOnce`
- `startRecording`, `canRecordAutomatically`

## Architecture

Single headless module, one entry class extending `XposedModule`. On each load of `com.google.android.dialer`, the module discovers dialer methods at runtime via the bundled `DexHelper` (native dex string-search, robust to obfuscation and version renames) and installs hooks.

Two mechanisms:

**A. Silence the disclosure (goal 1).** Force the disclosure audio to be silent rather than skipping the disclosure step. The recording state machine still runs the disclosure and fires its completion callback, so recording proceeds normally.
- Force `TextToSpeech.synthesizeToFile` to *always* write the module's built-in 44-byte silent WAV and return `SUCCESS` (not only on TTS failure, as the base module does). This silences the spoken disclosure.
- Neutralize the beep disclosure path (mute/short-circuit the beep player) so that if the dialer resolves to a beep disclosure type instead of TTS, nothing audible plays. Exact target pinned at implement-time.

**B. Auto-record every call (goal 2).** Hook `canRecordAutomatically` to return `true` unconditionally, so every incoming and outgoing call auto-records without a manual button press. If a single method proves insufficient (e.g. an outer contact-list gate short-circuits first), additionally force `produceIsCallInAutoRecordContactsList` → `true`. No emergency exception.

### Hook set

All hooks are installed inside `onPackageLoaded` when `param.getPackageName() == "com.google.android.dialer"` and `param.isFirstPackage()`. Dialer methods are located via `DexHelper.findMethodUsingString(...)` → `decodeMethodIndex` → `java.lang.reflect.Method`, then hooked with `hook(method).intercept(...)`. TTS methods are located via reflection on `android.speech.tts.TextToSpeech`.

| # | Target | How found | Action | Origin |
|---|--------|-----------|--------|--------|
| 1 | `TextToSpeech.synthesizeToFile(CharSequence,Bundle,File,String)` | reflection | **always** write silent WAV + return SUCCESS + fire `onDone` | repurposed from base (was failure-only) |
| 2 | beep disclosure player | DexHelper | mute / report complete without audible playback | new |
| 3 | `canRecordCall` | DexHelper string | replace → `true` | base |
| 4 | `isCallRecordingCountry` | DexHelper string | replace → `true` | base |
| 5 | `getSupportedLocaleFromCountryCode` | DexHelper string+sig | replace → `Locale.US` | base |
| 6 | `withinCallRecordingGeoFence` (+ `...Geofence`) | DexHelper string | replace → `true` | **fixed** (was `withinCrosbyGeoFence`) |
| 7 | `canRecordAutomatically` (+ `produceIsCallInAutoRecordContactsList` if needed) | DexHelper string | replace → `true` | new |
| 8 | `TextToSpeech.dispatchOnInit`, `TextToSpeech.isLanguageAvailable` | reflection | force init success / language available | base (TTS reliability) |

Base module's `onResume` version-check toast is kept (harmless diagnostic).

### Fail-safe design

The fail-safe is inherent to Xposed hooks plus the dialer's native gating, and reinforced by install ordering:

1. **Hooks are runtime-only.** They set no persistent state. If the module is disabled or LSPosed stops, none of the gates (`canRecordCall`, `isCallRecordingCountry`, geofence) are overridden. In Bulgaria these are natively `false`, so the dialer offers no recording at all → zero recording. There is no scenario where recording continues without the module.
2. **Install ordering couples silence to enablement.** In `onPackageLoaded`, install the silence hooks (#1, #2) *first*. Only if both install successfully proceed to install the enable/auto-record hooks (#3–#7). If silencing fails to install, recording is never enabled → there is never a "recording with a loud announcement" state. Log the abort loudly.
3. **Silent-audio, not skip.** Because recording start is gated on disclosure completion, injecting silent audio (letting the disclosure "complete") is safer than skipping the disclosure (which risks the completion callback never firing and recording never starting).

## API 102 migration

Migrate from legacy Xposed API 82 to modern libxposed API 102.

### Dependencies

```kotlin
compileOnly("io.github.libxposed:api:102.0.0")
```

(The bundled `DexHelper` native library and its JNI wrapper are retained unchanged for method discovery — the module does not use libxposed's reflective helper.)

### Entry mechanism

Remove `assets/xposed_init`. Add modern service files under `META-INF/xposed/`:

- `java_init.list` — fully-qualified name of the module entry class (e.g. `io.github.mexus.callrec.Module`).
- `module.prop` — module id, name, author, version, versionCode, min API (schema pinned from javadoc at implement-time).
- `scope.list` — single line `com.google.android.dialer`.

Legacy manifest meta-data (`xposedminversion`, `xposedscope`, `xposeddescription`) is dropped; the modern framework reads `module.prop` and `scope.list`.

### Module class

```
legacy:  class Init implements IXposedHookLoadPackage {
             void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) { ... lpparam.classLoader ... }
         }

modern:  class Module extends XposedModule {
             Module(XposedInterface base, ModuleLoadedParam param) { super(base, param); }
             void onPackageLoaded(PackageLoadedParam param) {
                 if (!"com.google.android.dialer".equals(param.getPackageName())) return;
                 if (!param.isFirstPackage()) return;
                 ClassLoader cl = param.getClassLoader();
                 ... install hooks ...
             }
         }
```

### Hook rewrite

Each legacy hook maps to a `Hooker` via the interceptor-chain model:

- **Method replacement** (return a constant, skip original) — hooks #3, #4, #5, #6, #7:
  ```
  legacy:  XposedBridge.hookMethod(m, new XC_MethodReplacement() {
               Object replaceHookedMethod(MethodHookParam p) { return true; } });
  modern:  hook(m).intercept(chain -> true);   // never calls chain.proceed() → original skipped
  ```
- **Before/after mutation** (modify args, inspect/replace result) — hooks #1, #8:
  ```
  modern:  hook(m).intercept(chain -> {
               // mutate chain.getArgs() as needed
               Object r = chain.proceed(args);
               // post-process / override result
               return r;
           });
  ```

The 44-byte silent WAV constant, the `onDone` reflection for `synthesizeToFile`, and the TTS reliability logic port verbatim into the new intercept bodies.

### Proguard / R8

```
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep class * extends io.github.libxposed.api.XposedModule { public <init>(...); }
-dontwarn io.github.libxposed.annotation.**
```

### Deferred (pinned at implement-time)

The API *shape* is confirmed (interceptor chain: `hook` → `HookBuilder` → `.intercept(Hooker)`; `Hooker.intercept(Chain)`; `chain.proceed()`). Exact leaf details to pin from the javadoc at [libxposed.github.io/api](https://libxposed.github.io/api/) when implementing:

- Precise `Hooker` / `Chain` / `HookBuilder` method names and generic signatures, and whether a `@XposedHooker`-style annotation is required.
- `module.prop` field schema.
- Whether static-vs-instance hook targets need any special handling in the chain model.

Dialer method targets #2 and #7 also require live `DexHelper` string discovery + version-tolerant needles, as Google renames symbols between releases (as already happened to #6).

## Project layout

Fork the base repo structure, new package `io.github.mexus.callrec`:

```
app/
  build.gradle.kts                    # api:102.0.0, versionCode/Name, ndkBuild (DexHelper), signing
  proguard-rules.pro                  # modern keep/adapt rules
  src/main/
    AndroidManifest.xml               # label/description only; no legacy xposed meta-data
    java/io/github/mexus/callrec/
      Module.java                     # XposedModule entry, onPackageLoaded, all hooks
      DexHelper.java                  # unchanged from base
    jni/                              # unchanged DexHelper native (all 4 ABIs)
    res/values*/strings.xml           # app_name, description
  src/main/META-INF/xposed/           # (or resources dir per AGP) java_init.list, module.prop, scope.list
```

Build/signing follows the base module (debug keystore, v2 signature, metadata stripping) unless a release key is provided.

## Verification plan

1. Build the module APK; install; enable in LSPosed scoped to `com.google.android.dialer`.
2. Force-stop dialer, relaunch, confirm `onPackageLoaded` fires and all 8 hooks install (log each; confirm no "method not found" for #6/#7).
3. Place an outgoing call and receive an incoming call:
   - Recording starts automatically for both (check recording file created).
   - No audible disclosure to either party (silent — verify on a two-device test or recording playback showing no spoken/beep disclosure).
4. Disable the module; confirm the dialer offers no recording (fail-safe holds).
5. Confirm no regression from the Fermat gate (recording still starts on the legacy path).

## Risks / open items

- **Method discovery drift:** dialer updates rename symbols (already happened for the geofence hook). Needles must be as generic as safe; log clearly on miss. This is inherent to the string-search approach and accepted.
- **Beep path target (#2):** exact method to mute is not yet pinned; if the dialer only ever uses the TTS disclosure in this configuration, #2 may be unnecessary — decide during implementation from live logs.
- **`canRecordAutomatically` sufficiency (#7):** may need the contact-list gate too; verify with a call from an unknown number and a known contact.
- **Modern API instantiation quirks:** confirm `XposedModule` constructor signature and lifecycle against javadoc before writing hooks.
- **Emergency calls** are recorded with no exception, per explicit user choice.
