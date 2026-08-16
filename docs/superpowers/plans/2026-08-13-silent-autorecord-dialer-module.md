# Silent Auto-Record Dialer Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a headless LSPosed module (modern libxposed API 102) for Google Dialer that records every call automatically and silences the audible "call is being recorded" disclosure, failing safe to no-recording.

**Architecture:** Fork of `vvb2060/CallRecording`. A single `XposedModule` entry class hooks Google Dialer at `onPackageReady`, discovering dialer methods at runtime via the vendored native `DexHelper` (dex string-search, obfuscation/rename robust). Recording-enable gates are forced true; the disclosure audio is forced silent; the auto-record decision is forced true. Silence hooks install first and gate the enable hooks, so any silencing failure leaves recording disabled.

**Tech Stack:** Java 21, Android Gradle (from base repo), ndkBuild (DexHelper native, 4 ABIs), `io.github.libxposed:api:102.0.0`, ADB + KernelSU/LSPosed device for verification.

## Global Constraints

- Xposed API: `compileOnly("io.github.libxposed:api:102.0.0")` — modern interceptor-chain API only. No legacy `de.robv.android.xposed` / `XposedBridge` / `IXposedHookLoadPackage`.
- Module descriptor `app/src/main/resources/META-INF/xposed/module.prop`: `minApiVersion=101`, `targetApiVersion=102`, `staticScope=true`, `autoHotReload=true`.
- Scope: `app/src/main/resources/META-INF/xposed/scope.list` = single line `com.google.android.dialer`.
- Entry: `app/src/main/resources/META-INF/xposed/java_init.list` = single line `io.github.mexus.callrec.Module`.
- Module class `extends io.github.libxposed.api.XposedModule`, public no-arg constructor (implicit), hooks installed in `onPackageReady`.
- Namespace / package: `io.github.mexus.callrec`. versionName `1.0`, versionCode `1`.
- Headless: no launcher activity required by function; manifest carries only `label`/`description`.
- **DexHelper is vendored verbatim in its ORIGINAL package `io.github.vvb2060.callrecording.xposed`** (JNI `wrapper.cc` hard-codes that class descriptor). Do not rename it.
- Records ALL calls, incoming and outgoing, with NO emergency/short-number exception (explicit user choice).
- Fail-safe: silence hooks (#1 group) install before enable/auto hooks (#2 group); if silence install fails, skip the enable/auto hooks entirely and log the abort.
- compileSdk 37, minSdk 28 (base) — do not lower below libxposed floor (26).
- Verification device: dialer package `com.google.android.dialer` (v233.x), reached over `adb` with `su` (KernelSU). Force-stop + relaunch dialer to trigger a fresh `onPackageReady`.

---

### Task 1: Fork baseline — copy base project and confirm it builds

Establish a known-good toolchain before any change. We build the unmodified base project once so later failures are attributable to our edits, not the environment.

**Files:**
- Create (copy): entire base project into repo root `d:/MyProjects/gCallRecorder/` — `app/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `.gitignore`, `.gitattributes`.
  - Source: `C:/Users/mexus/AppData/Local/Temp/claude/d--MyProjects-gCallRecorder/5048017e-2d53-40ce-81a9-f3b604eb5bde/scratchpad/CallRecording/`
- Do NOT copy the base `.git/`.

- [ ] **Step 1: Copy base tree into repo root (excluding .git)**

```bash
SRC="C:/Users/mexus/AppData/Local/Temp/claude/d--MyProjects-gCallRecorder/5048017e-2d53-40ce-81a9-f3b604eb5bde/scratchpad/CallRecording"
DST="d:/MyProjects/gCallRecorder"
# copy everything except the source's git metadata
cp -r "$SRC/app" "$SRC/gradle" "$SRC/build.gradle.kts" "$SRC/settings.gradle.kts" \
      "$SRC/gradle.properties" "$SRC/gradlew" "$SRC/gradlew.bat" \
      "$SRC/.gitignore" "$SRC/.gitattributes" "$DST/"
```

- [ ] **Step 2: Confirm Android SDK + NDK available**

Run:
```bash
ls "$ANDROID_HOME/ndk" 2>/dev/null || ls "$HOME/AppData/Local/Android/Sdk/ndk" 2>/dev/null
cat d:/MyProjects/gCallRecorder/gradle.properties | grep -i ndk || true
```
Expected: at least one NDK version directory exists. If none, install via `sdkmanager "ndk;<version>"` (the version the base `Application.mk`/AGP expects) before continuing. Note the SDK path; create `d:/MyProjects/gCallRecorder/local.properties` with `sdk.dir=<path>` if the build cannot find the SDK.

- [ ] **Step 3: Build the untouched base APK**

Run:
```bash
cd d:/MyProjects/gCallRecorder && ./gradlew :app:assembleRelease --no-daemon
```
Expected: BUILD SUCCESSFUL; an APK is produced under `app/build/outputs/apk/release/`. This proves JDK 21 + AGP + NDK + apkzlib signing all work. If it fails, fix the toolchain now (NDK version, SDK path) — not later.

- [ ] **Step 4: Commit the baseline**

```bash
cd d:/MyProjects/gCallRecorder
git add -A
git commit -m "chore: import vvb2060/CallRecording v1.3 as fork baseline"
```

---

### Task 2: Migrate to API-102 skeleton (build files, descriptors, minimal Module)

Convert the project to the modern API with a minimal Module that only logs lifecycle. Prove the module is recognized by LSPosed and its `onPackageReady` fires for the dialer before adding any hook logic.

**Files:**
- Modify: `app/build.gradle.kts` (dependency, namespace, versionCode/Name, packaging for `META-INF/xposed/*`)
- Modify: `app/proguard-rules.pro` (modern keep/adapt rules)
- Modify: `app/src/main/AndroidManifest.xml` (strip legacy xposed meta-data; label/description only)
- Delete: `app/src/main/assets/xposed_init`
- Create: `app/src/main/resources/META-INF/xposed/java_init.list`
- Create: `app/src/main/resources/META-INF/xposed/module.prop`
- Create: `app/src/main/resources/META-INF/xposed/scope.list`
- Create: `app/src/main/java/io/github/mexus/callrec/Module.java`
- Keep (untouched for now): base `Init.java` will be deleted in Task 3 once its logic is ported; delete its `java/io/github/vvb2060/.../xposed/Init.java` now to avoid a dangling legacy-API reference that won't compile.
- Modify: `app/src/main/res/values/strings.xml` / `values-zh-rCN/strings.xml` (keep `app_name`; `app_description` may stay).

**Interfaces:**
- Produces: `io.github.mexus.callrec.Module extends XposedModule`, overriding `onModuleLoaded(ModuleLoadedParam)` and `onPackageReady(PackageReadyParam)`. Later tasks add private hook-install methods called from `onPackageReady`.

- [ ] **Step 1: Swap the Xposed dependency**

In `app/build.gradle.kts`, replace the legacy api dependency:
```kotlin
// remove:
// compileOnly("de.robv.android.xposed:api:82")
// add:
compileOnly("io.github.libxposed:api:102.0.0")
```
Keep `compileOnly("androidx.annotation:annotation:1.3.0")`.

- [ ] **Step 2: Set namespace, version, and META-INF packaging**

In `app/build.gradle.kts`:
```kotlin
android {
    namespace = "io.github.mexus.callrec"
    defaultConfig {
        versionCode = 1
        versionName = "1.0"
        // keep existing externalNativeBuild { ndkBuild { abiFilters ... } }
    }
    packaging {
        resources {
            // was: excludes += "**"
            excludes += "**"
            merges += "META-INF/xposed/**"
        }
        // keep jniLibs { useLegacyPackaging = false }
    }
}
```
Leave the `optimizeReleaseRes` / `delMetadata` signing tasks and `compileOptions` (Java 21) exactly as the base has them.

- [ ] **Step 3: Replace proguard rules**

Overwrite `app/proguard-rules.pro`:
```proguard
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
# Vendored native helper — keep names; native code binds by class/method descriptor
-keep class io.github.vvb2060.callrecording.xposed.DexHelper { *; }
```

- [ ] **Step 4: Rewrite the manifest (strip legacy meta-data)**

Overwrite `app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:label="@string/app_name"
        android:description="@string/app_description"
        android:theme="@android:style/Theme.DeviceDefault"
        tools:ignore="MissingApplicationIcon">
    </application>
</manifest>
```

- [ ] **Step 5: Create the modern descriptor files**

`app/src/main/resources/META-INF/xposed/java_init.list`:
```
io.github.mexus.callrec.Module
```
`app/src/main/resources/META-INF/xposed/scope.list`:
```
com.google.android.dialer
```
`app/src/main/resources/META-INF/xposed/module.prop`:
```
minApiVersion=101
targetApiVersion=102
staticScope=true
autoHotReload=true
```

- [ ] **Step 6: Delete legacy entry artifacts**

```bash
cd d:/MyProjects/gCallRecorder
rm -f app/src/main/assets/xposed_init
rm -f app/src/main/java/io/github/vvb2060/callrecording/xposed/Init.java
```

- [ ] **Step 7: Write the minimal Module**

Create `app/src/main/java/io/github/mexus/callrec/Module.java`:
```java
package io.github.mexus.callrec;

import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public class Module extends XposedModule {
    static final String TAG = "CallRec";
    private static final String DIALER = "com.google.android.dialer";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG, "onModuleLoaded: " + param.getProcessName()
                + " API " + getApiVersion());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!DIALER.equals(param.getPackageName())) return;
        if (!param.isFirstPackage()) return;
        log(Log.INFO, TAG, "onPackageReady: dialer, classloader=" + param.getClassLoader());
        // hooks added in later tasks
    }
}
```

- [ ] **Step 8: Build**

Run:
```bash
cd d:/MyProjects/gCallRecorder && ./gradlew :app:assembleRelease --no-daemon
```
Expected: BUILD SUCCESSFUL. If the compiler cannot resolve `XposedModule`, confirm the dependency line and that AGP resolved `io.github.libxposed:api:102.0.0` (needs `mavenCentral()` in `settings.gradle.kts` repositories — base already has it; add if missing).

- [ ] **Step 9: Install, enable, verify recognition + lifecycle**

```bash
APK=$(ls d:/MyProjects/gCallRecorder/app/build/outputs/apk/release/*.apk | head -1)
adb install -r "$APK"
```
Then in LSPosed Manager: confirm the new module appears (label from `app_name`) and its scope auto-lists `com.google.android.dialer` (staticScope). Enable it. Then:
```bash
adb shell am force-stop com.google.android.dialer
adb logcat -c
adb shell monkey -p com.google.android.dialer -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 10
adb logcat -d | grep -a "CallRec"
```
Expected: a line `CallRec: onPackageReady: dialer, classloader=...`. This proves the API-102 skeleton loads and fires for the dialer. If absent, check `java_init.list` content (exact FQCN), that `META-INF/xposed/*` landed in the APK (`unzip -l "$APK" | grep xposed`), and that the module is enabled + scoped.

- [ ] **Step 10: Commit**

```bash
cd d:/MyProjects/gCallRecorder
git add -A
git commit -m "feat: migrate to modern libxposed API 102 skeleton"
```

---

### Task 3: Vendor DexHelper + recording-enable gate hooks

Port the four recording-enable gate hooks from the base module into the interceptor model, including the renamed geofence method. After this task, the dialer offers call recording in Bulgaria.

**Files:**
- Create (copy verbatim): `app/src/main/java/io/github/vvb2060/callrecording/xposed/DexHelper.java` (already present from the fork copy — confirm it exists and is unchanged; it stays in this package).
- Confirm present (from fork copy): `app/src/main/jni/**` (unchanged).
- Modify: `app/src/main/java/io/github/mexus/callrec/Module.java` (add gate-hook install).

**Interfaces:**
- Consumes: `io.github.vvb2060.callrecording.xposed.DexHelper` — `new DexHelper(ClassLoader)`, `long[] findMethodUsingString(String, boolean, long, short, String, int, long[], long[], long[], boolean)`, `Member decodeMethodIndex(long)`, `long encodeClassIndex(Class)`, `AutoCloseable`. (Signatures verbatim from base `DexHelper.java`.)
- Produces: `Module.installEnableHooks(DexHelper dex, ClassLoader cl)` returning `boolean` (all gates found). `Module.hookConst(Member m, Object value)` helper.

- [ ] **Step 1: Confirm DexHelper + jni copied and native class descriptor unchanged**

Run:
```bash
cd d:/MyProjects/gCallRecorder
test -f app/src/main/java/io/github/vvb2060/callrecording/xposed/DexHelper.java && echo "DexHelper OK"
grep -n "io/github/vvb2060/callrecording/xposed/DexHelper" app/src/main/jni/wrapper.cc
```
Expected: `DexHelper OK` and the `FindClass` descriptor line present. Do NOT change the package or that descriptor.

- [ ] **Step 2: Add a constant-replacement hook helper + gate discovery to Module**

Edit `Module.java` — add imports and methods. The `hookConst` helper installs an interceptor that returns a constant without calling `chain.proceed()` (equivalent to the legacy `XC_MethodReplacement`):
```java
import java.lang.reflect.Member;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import io.github.vvb2060.callrecording.xposed.DexHelper;

private void hookConst(Member m, Object value) {
    hook((java.lang.reflect.Method) m).intercept(chain -> value);
}

/** find first method whose body references `str`, matching the given return/arg shape */
private Member findByString(DexHelper dex, String str, long retClass, short paramCount,
                            String retPrim, long[] paramClasses) {
    long[] hits = dex.findMethodUsingString(
            str, false, retClass, paramCount, retPrim, -1, paramClasses, null, null, true);
    return Arrays.stream(hits).mapToObj(dex::decodeMethodIndex)
            .filter(Objects::nonNull).findFirst().orElse(null);
}
```

- [ ] **Step 3: Implement installEnableHooks**

Add to `Module.java` (mirrors base `hookCanRecordCall` / `hookIsCallRecordingCountry` / `hookGetSupportedLocaleFromCountryCode`, plus the renamed geofence). Each returns Boolean/Locale via the interceptor:
```java
private boolean installEnableHooks(DexHelper dex) {
    boolean ok = true;

    Member canRecordCall = findByString(dex, "canRecordCall", -1, (short) 0, "Z", null);
    if (canRecordCall != null) { hookConst(canRecordCall, true); log(Log.INFO, TAG, "hooked canRecordCall"); }
    else { log(Log.ERROR, TAG, "canRecordCall not found"); ok = false; }

    Member isCountry = findByString(dex, "isCallRecordingCountry", -1, (short) 0, "Z", null);
    if (isCountry != null) { hookConst(isCountry, true); log(Log.INFO, TAG, "hooked isCallRecordingCountry"); }
    else { log(Log.ERROR, TAG, "isCallRecordingCountry not found"); ok = false; }

    long localeId = dex.encodeClassIndex(Locale.class);
    long mapId = dex.encodeClassIndex(Map.class);
    long stringId = dex.encodeClassIndex(String.class);
    Member getLocale = findByString(dex, "getSupportedLocaleFromCountryCode",
            localeId, (short) 2, null, new long[]{mapId, stringId});
    if (getLocale != null) { hookConst(getLocale, Locale.US); log(Log.INFO, TAG, "hooked getSupportedLocaleFromCountryCode"); }
    else { log(Log.ERROR, TAG, "getSupportedLocaleFromCountryCode not found"); ok = false; }

    // renamed from withinCrosbyGeoFence -> withinCallRecordingGeoFence (with a Geofence spelling fallback)
    Member geo = findByString(dex, "withinCallRecordingGeoFence", -1, (short) 0, "Z", null);
    if (geo == null) geo = findByString(dex, "withinCallRecordingGeofence", -1, (short) 0, "Z", null);
    if (geo != null) { hookConst(geo, true); log(Log.INFO, TAG, "hooked geofence"); }
    else { log(Log.WARN, TAG, "geofence method not found"); } // non-fatal, do not flip ok

    return ok;
}
```

- [ ] **Step 4: Call installEnableHooks from onPackageReady**

In `onPackageReady`, after the dialer guard:
```java
try (DexHelper dex = new DexHelper(param.getClassLoader())) {
    boolean enabled = installEnableHooks(dex);
    log(Log.INFO, TAG, "enable hooks installed=" + enabled);
} catch (Throwable t) {
    log(Log.ERROR, TAG, "hook install failed", t);
}
```

- [ ] **Step 5: Build**

Run: `cd d:/MyProjects/gCallRecorder && ./gradlew :app:assembleRelease --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Install and verify all gates found + recording available**

```bash
APK=$(ls d:/MyProjects/gCallRecorder/app/build/outputs/apk/release/*.apk | head -1)
adb install -r "$APK"
adb shell am force-stop com.google.android.dialer
adb logcat -c
adb shell monkey -p com.google.android.dialer -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 12
adb logcat -d | grep -a "CallRec"
```
Expected lines: `hooked canRecordCall`, `hooked isCallRecordingCountry`, `hooked getSupportedLocaleFromCountryCode`, `hooked geofence`, `enable hooks installed=true`. No `not found` for the first three. Then open Dialer → call settings and confirm "Call recording" is present/available, or place a test call and confirm the record button is offered.

- [ ] **Step 7: Commit**

```bash
cd d:/MyProjects/gCallRecorder
git add -A
git commit -m "feat: force call-recording enable gates (canRecordCall, country, locale, geofence)"
```

---

### Task 4: Silence the disclosure

Make the recording-disclosure inaudible while letting the disclosure step complete so recording proceeds. **Primary mechanism = the base module's public `TextToSpeech.synthesizeToFile` hook (empty-text synthesis).** The user confirms that with the base module on this dialer there is currently no audible announcement, so this hook is expected to be sufficient here. The internal-synthesis and beep hooks are a **contingency**, pursued only if the on-device build test (Step 9) shows a disclosure is still audible. Accordingly, silence-success is defined by the public-TTS hook installing, and that is what gates the Task 6 fail-safe.

**Files:**
- Create: `app/src/main/java/io/github/mexus/callrec/SilentAudio.java` (pure helper: the silent-WAV bytes + writer)
- Create: `app/src/test/java/io/github/mexus/callrec/SilentAudioTest.java` (JVM unit test)
- Modify: `app/src/main/java/io/github/mexus/callrec/Module.java` (install silence hooks)
- Modify: `app/build.gradle.kts` (add `testImplementation("junit:junit:4.13.2")` if no unit-test dep exists)

**Interfaces:**
- Produces: `SilentAudio.WAV` (`byte[]`, a valid 44-byte silent PCM WAV), `SilentAudio.write(File)` throwing `IOException`. `Module.installSilenceHooks(DexHelper dex)` returning `boolean`.

- [ ] **Step 1: Write the failing unit test for the silent WAV**

Create `app/src/test/java/io/github/mexus/callrec/SilentAudioTest.java`:
```java
package io.github.mexus.callrec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SilentAudioTest {
    @Test public void wavIsValidRiffHeaderWithNoSamples() {
        byte[] w = SilentAudio.WAV;
        assertEquals("length is a bare 44-byte header", 44, w.length);
        assertArrayEquals("RIFF magic", new byte[]{'R','I','F','F'}, new byte[]{w[0],w[1],w[2],w[3]});
        assertArrayEquals("WAVE magic", new byte[]{'W','A','V','E'}, new byte[]{w[8],w[9],w[10],w[11]});
        assertArrayEquals("fmt  chunk", new byte[]{'f','m','t',' '}, new byte[]{w[12],w[13],w[14],w[15]});
        assertArrayEquals("data chunk", new byte[]{'d','a','t','a'}, new byte[]{w[36],w[37],w[38],w[39]});
        assertEquals("data size 0", 0, w[40] | w[41] | w[42] | w[43]);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd d:/MyProjects/gCallRecorder && ./gradlew :app:testReleaseUnitTest --tests "io.github.mexus.callrec.SilentAudioTest" --no-daemon`
Expected: FAIL — `SilentAudio` does not exist (compilation error). Add `testImplementation("junit:junit:4.13.2")` to `app/build.gradle.kts` dependencies if the failure is instead "cannot resolve junit".

- [ ] **Step 3: Implement SilentAudio**

Create `app/src/main/java/io/github/mexus/callrec/SilentAudio.java` (bytes copied verbatim from the base module's built-in silent WAV):
```java
package io.github.mexus.callrec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class SilentAudio {
    private SilentAudio() {}

    static final byte[] WAV = {
            82, 73, 70, 70, 36, 0, 0, 0, 87, 65, 86,
            69, 102, 109, 116, 32, 16, 0, 0, 0, 1, 0,
            1, 0, -128, 62, 0, 0, 0, 125, 0, 0, 2,
            0, 16, 0, 100, 97, 116, 97, 0, 0, 0, 0};

    static void write(File f) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(WAV);
        }
    }
}
```

- [ ] **Step 4: Run the unit test to verify it passes**

Run: `cd d:/MyProjects/gCallRecorder && ./gradlew :app:testReleaseUnitTest --tests "io.github.mexus.callrec.SilentAudioTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Port the public-TTS silence hook (PRIMARY silence mechanism)**

Add to `Module.java` (mirrors base `synthesizeToFile` hook, in intercept form). Empty text yields silent synthesis; the silent WAV is the failure fallback. This is the hook the user confirms already silences the announcement on this dialer:
```java
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;

private boolean hookPublicTts() {
    try {
        java.lang.reflect.Method synth = TextToSpeech.class.getDeclaredMethod(
                "synthesizeToFile", CharSequence.class, Bundle.class, File.class, String.class);
        hook(synth).intercept(chain -> {
            Object[] a = { "", chain.getArg(1), chain.getArg(2), chain.getArg(3) };
            Object r = chain.proceed(a);
            if (!Objects.equals(r, TextToSpeech.SUCCESS)) {
                File file = (File) chain.getArg(2);
                try { SilentAudio.write(file); r = TextToSpeech.SUCCESS; }
                catch (Exception e) { log(Log.ERROR, TAG, "silent wav write failed", e); }
                try {
                    java.lang.reflect.Field f = chain.getThisObject().getClass()
                            .getDeclaredField("mUtteranceProgressListener");
                    f.setAccessible(true);
                    UtteranceProgressListener l = (UtteranceProgressListener) f.get(chain.getThisObject());
                    if (l != null) l.getClass()
                            .getMethod("onDone", String.class).invoke(l, (String) chain.getArg(3));
                } catch (ReflectiveOperationException e) { log(Log.WARN, TAG, "onDone skip", e); }
            }
            return r;
        });
        log(Log.INFO, TAG, "hooked public synthesizeToFile");
        return true;
    } catch (NoSuchMethodException e) {
        log(Log.ERROR, TAG, "public synthesizeToFile not found", e);
        return false;
    }
}
```

- [ ] **Step 6 (CONTINGENCY — only if Step 9 shows audible disclosure): Discover the dialer-internal disclosure/synthesis target on-device**

Skip this and Step 7's internal/beep block if the Step-9 build test confirms silence via the public-TTS hook alone. If a disclosure is still audible, pin the exact internal method from a live recorded call, not guesswork. With the enable hooks from Task 3 active, place a real call and record it, capturing the disclosure/TTS classes that actually fire:
```bash
adb logcat -c
# place a call to a second phone, tap record (or after Task 5, automatic), let disclosure play, hang up
adb logcat -d | grep -aiE "disclosure|TtsFileProvider|TtsInstance|synthesizeTextIntoFile|playStartDisclosure|CallRecordingButtonController|Beep" | head -60
```
Identify which produces the audible audio. Primary candidates (from static scan of dialer 233):
- `com/android/dialer/tts/internal/synthesis/TtsFileProvider` → `synthesizeTextIntoFile` (internal synthesis to a file)
- `com/android/dialer/callrecording/disclosure/impl/TtsCallRecordingDisclosure` → `playStartDisclosure`
- Beep path: `playBeep` / `starting_voice-beep_sound.ogg`
Record the confirmed class+method for the next step.

- [ ] **Step 7: Hook the confirmed internal disclosure target to produce silence**

Add `installSilenceHooks(DexHelper dex)` to `Module.java`. Use `DexHelper.findMethodUsingString` with a string uniquely present in the confirmed method (e.g. `"synthesizeTextIntoFile"` or `"playStartDisclosure"` or the beep filename), then either (a) force its synthesized output to `SilentAudio.WAV`, or (b) if it is the player, let it run but with silent input. Concrete form for the file-synthesis case (adjust the needle + arg index to the method confirmed in Step 6):
```java
private boolean installSilenceHooks(DexHelper dex) {
    // PRIMARY: public-TTS empty-text synthesis. Success of THIS hook defines silence-success,
    // because the user confirms it already silences the announcement on this dialer.
    boolean ok = hookPublicTts(); // returns true if the hook installed

    // CONTINGENCY (best-effort, not counted toward ok): only relevant if Step 9 shows
    // audible disclosure. Remove/keep based on the Step-6 finding.
    Member synth = findByString(dex, "synthesizeTextIntoFile", -1, (short) -1, null, null);
    if (synth != null) {
        hook((java.lang.reflect.Method) synth).intercept(chain -> {
            Object r = chain.proceed();
            for (Object arg : chain.getArgs()) {
                if (arg instanceof File) { try { SilentAudio.write((File) arg); } catch (Exception ignored) {} }
            }
            return r;
        });
        log(Log.INFO, TAG, "hooked internal disclosure synthesis (contingency)");
    }
    Member beep = findByString(dex, "playBeep", -1, (short) -1, null, null);
    if (beep != null) { hook((java.lang.reflect.Method) beep).intercept(chain -> null); log(Log.INFO, TAG, "muted beep (contingency)"); }

    return ok;
}
```
> Change `hookPublicTts()` to return `boolean` (`true` on successful install, `false` in the `NoSuchMethodException` branch). The internal-synthesis/beep block only matters if Step 9 shows audible disclosure; its needle/arg index and synthesis-vs-player choice come from the Step-6 finding. If the public-TTS hook alone silences (expected), the contingency `findByString` calls simply return null and no-op.

- [ ] **Step 8: Wire silence install into onPackageReady (ahead of enable) and rebuild**

Update `onPackageReady` so silence installs first (fail-safe ordering finalized in Task 6):
```java
try (DexHelper dex = new DexHelper(param.getClassLoader())) {
    boolean silenced = installSilenceHooks(dex);
    log(Log.INFO, TAG, "silence installed=" + silenced);
    installEnableHooks(dex);
}
```
Run: `cd d:/MyProjects/gCallRecorder && ./gradlew :app:assembleRelease --no-daemon` → BUILD SUCCESSFUL.

- [ ] **Step 9: Install and verify no audible disclosure on a real recorded call**

```bash
APK=$(ls d:/MyProjects/gCallRecorder/app/build/outputs/apk/release/*.apk | head -1)
adb install -r "$APK"
adb shell am force-stop com.google.android.dialer
```
Place a call to a second device, start recording, and listen on the second device: expected NO spoken disclosure and NO beep audible to either party. Confirm `silence installed=true` and `hooked internal disclosure synthesis` in `adb logcat -d | grep CallRec`. Play back the saved recording to confirm the disclosure segment is silent but recording captured the conversation.

- [ ] **Step 10: Commit**

```bash
cd d:/MyProjects/gCallRecorder
git add -A
git commit -m "feat: silence recording disclosure (internal synthesis + beep + public TTS)"
```

---

### Task 5: Auto-record every call

Force the dialer's automatic-record decision true so all incoming and outgoing calls record without a manual press.

**Files:**
- Modify: `app/src/main/java/io/github/mexus/callrec/Module.java` (add auto-record hook)

**Interfaces:**
- Produces: `Module.installAutoRecordHooks(DexHelper dex)` returning `boolean`.

- [ ] **Step 1: Implement installAutoRecordHooks**

Add to `Module.java`:
```java
private boolean installAutoRecordHooks(DexHelper dex) {
    boolean ok = false;
    Member canAuto = findByString(dex, "canRecordAutomatically", -1, (short) 0, "Z", null);
    if (canAuto != null) { hookConst(canAuto, true); log(Log.INFO, TAG, "hooked canRecordAutomatically"); ok = true; }
    else { log(Log.ERROR, TAG, "canRecordAutomatically not found"); }

    // outer contact-list gate — force true so unknown numbers also auto-record
    Member inList = findByString(dex, "produceIsCallInAutoRecordContactsList", -1, (short) -1, "Z", null);
    if (inList != null) { hookConst(inList, true); log(Log.INFO, TAG, "hooked produceIsCallInAutoRecordContactsList"); }
    else { log(Log.WARN, TAG, "auto-record contacts-list gate not found (may be unnecessary)"); }

    return ok;
}
```

- [ ] **Step 2: Call it from onPackageReady (after enable hooks)**

```java
installEnableHooks(dex);
installAutoRecordHooks(dex);
```

- [ ] **Step 3: Build**

Run: `cd d:/MyProjects/gCallRecorder && ./gradlew :app:assembleRelease --no-daemon` → BUILD SUCCESSFUL.

- [ ] **Step 4: Install and verify auto-record on incoming AND outgoing**

```bash
APK=$(ls d:/MyProjects/gCallRecorder/app/build/outputs/apk/release/*.apk | head -1)
adb install -r "$APK"
adb shell am force-stop com.google.android.dialer
adb logcat -c
```
Expected `hooked canRecordAutomatically` in logcat. Then:
- Place an OUTGOING call → confirm recording starts on its own (no manual tap), recording file created.
- Receive an INCOMING call from both a known contact and an unknown number → confirm both auto-record.
Verify via the dialer's recorded-calls list or the recordings storage location.

- [ ] **Step 5: Commit**

```bash
cd d:/MyProjects/gCallRecorder
git add -A
git commit -m "feat: auto-record all incoming and outgoing calls"
```

---

### Task 6: Fail-safe coupling + full integration verification

Enforce that enable/auto hooks install only when silencing succeeded, so the module never records with an audible announcement. Then verify the full end-to-end behavior and the module-off fail-safe.

**Files:**
- Modify: `app/src/main/java/io/github/mexus/callrec/Module.java` (couple install order)

**Interfaces:**
- Consumes: `installSilenceHooks`, `installEnableHooks`, `installAutoRecordHooks` (all from prior tasks).

- [ ] **Step 1: Gate enable/auto behind silence success**

Replace the hook-install block in `onPackageReady`:
```java
try (DexHelper dex = new DexHelper(param.getClassLoader())) {
    boolean silenced = installSilenceHooks(dex);
    if (!silenced) {
        log(Log.ERROR, TAG, "ABORT: disclosure not silenced -> leaving recording disabled (fail-safe)");
        return;
    }
    boolean enabled = installEnableHooks(dex);
    boolean auto = installAutoRecordHooks(dex);
    log(Log.INFO, TAG, "ready: silenced=" + silenced + " enabled=" + enabled + " auto=" + auto);
} catch (Throwable t) {
    log(Log.ERROR, TAG, "hook install failed -> recording disabled (fail-safe)", t);
}
```

- [ ] **Step 2: Build**

Run: `cd d:/MyProjects/gCallRecorder && ./gradlew :app:assembleRelease --no-daemon` → BUILD SUCCESSFUL.

- [ ] **Step 3: Install and run the full integration checklist**

```bash
APK=$(ls d:/MyProjects/gCallRecorder/app/build/outputs/apk/release/*.apk | head -1)
adb install -r "$APK"
adb shell am force-stop com.google.android.dialer
```
Verify, on a two-device call:
1. Outgoing call auto-records; no audible disclosure to either party.
2. Incoming call (unknown number) auto-records; no audible disclosure.
3. `adb logcat -d | grep CallRec` shows `ready: silenced=true enabled=true auto=true`, no `not found` for required hooks.
4. Play back both recordings: conversation captured, disclosure segment silent.

- [ ] **Step 4: Verify the module-off fail-safe**

Disable the module in LSPosed, force-stop dialer, place a call. Expected: dialer offers no recording / does not record (gates revert to native Bulgaria = off). Re-enable when done.

- [ ] **Step 5: Verify the silence-fail fail-safe (fault injection)**

Temporarily change the internal-disclosure needle in `installSilenceHooks` to a bogus string so `installSilenceHooks` returns false, build, install, force-stop dialer, and confirm logcat shows `ABORT: disclosure not silenced` and that recording is NOT enabled (no `hooked canRecordCall`). Then revert the needle, rebuild, reinstall. (Do not commit the bogus needle.)

- [ ] **Step 6: Final commit + tag**

```bash
cd d:/MyProjects/gCallRecorder
git add -A
git commit -m "feat: fail-safe couple recording-enable to disclosure-silence success"
git tag v1.0
```

---

## Self-Review

**Spec coverage:**
- Goal 1 (silence disclosure) → Task 4 (internal synthesis + beep + public TTS). ✓
- Goal 2 (auto-record all in+out, no exception) → Task 5. ✓
- API 102 migration (deps, entry files, module class, hook style, proguard) → Task 2, applied throughout. ✓
- Fail-safe (runtime-only hooks + silence-first coupling + both fault paths verified) → Task 6 (+ inherent from Task 3 gates). ✓
- Renamed geofence fix → Task 3 Step 3. ✓
- DexHelper vendored verbatim in original package (JNI coupling) → Global Constraints + Task 3 Step 1. ✓
- Headless, namespace, versions, scope, module.prop values → Global Constraints + Task 2. ✓

**Placeholder scan:** The only deferred item is the exact internal-disclosure needle/arg index in Task 4 — this is an explicit on-device discovery step (Step 6) feeding Step 7, with concrete candidate classes and a concrete fallback, not a hand-wave. Acceptable because the target is obfuscation/version-specific and must be confirmed against the live dialer, matching the module's whole discovery-based design.

**Type consistency:** `findByString` signature and `hookConst(Member, Object)` used identically across Tasks 3–5. `installSilenceHooks`/`installEnableHooks`/`installAutoRecordHooks` all take `DexHelper` and return `boolean`, consumed in Task 6. `SilentAudio.WAV` / `SilentAudio.write(File)` defined in Task 4 Step 3, used in Task 4 Steps 5/7. Consistent.
