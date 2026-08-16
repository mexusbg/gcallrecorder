# Contacts → Always-Record Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a root companion Android app that mirrors the phone's contacts into Google Dialer's "Automatically record these numbers" list and keeps the native auto-record toggles on, so every call auto-records — with a hard in-call guard.

**Architecture:** New Gradle module `syncapp/` (`io.github.mexus.callrecsync`) in this repo, separate from the LSPosed `app/`. It reads contacts (normal permission), edits the dialer's `CallRecordingSettingsData.pb` Proto DataStore, and applies it by force-stopping the dialer (root) so it cold-reads the new file. A pure protobuf codec does the read/modify/write; a change-diff avoids needless dialer kills; a layered guard never kills during a call.

**Tech Stack:** Kotlin, Android (minSdk 28, compileSdk 37), AGP from repo, JUnit4 (JVM unit tests), raw `su` exec for root. Build with JBR 21 + `--no-configuration-cache`.

**Spec:** `docs/superpowers/specs/2026-08-13-contacts-alwaysrecord-sync-design.md`

## Global Constraints

- Package / namespace: `io.github.mexus.callrecsync`. New module dir: `syncapp/`. Add `:syncapp` to `settings.gradle.kts`.
- Target proto file (on device): `/data/data/com.google.android.dialer/files/CallRecordingSettingsData.pb`.
- Proto schema (wire): field 1 = `AlwaysRecordNonContact` (varint, force 1); fields 2,3,4,5,7 = varints (preserve, incl. unknowns); field 6 = repeated length-delimited E.164 string = `AlwaysRecordSelectedNumbers` (the managed list). Only fields 1 and 6 (+ a pinned set-once field) are ever written; every other field is preserved byte-for-byte.
- **Write is honored only when the dialer process is fully dead** at write time (kill-first). Writing while alive is clobbered. After write, the dialer cold-reads on next launch.
- **Never force-stop the dialer while a call is active.** Guard at three layers: observer defers during calls; SyncEngine routes writes through `runWhenIdle`; the `su` script re-checks call state immediately before the force-stop and aborts if a call is active.
- Root usage is limited to: check call state, force-stop dialer, poll pid, backup, write file, `chown` (to the file's existing owner via `stat`, never hardcoded), `restorecon`. Contacts use `READ_CONTACTS`; call state uses `READ_PHONE_STATE`.
- Minimize disruption: if the target proto bytes equal the current bytes, skip (no kill, no write).
- Every gradle call: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` and `--no-configuration-cache`. App builds/install use `:syncapp:assembleDebug` (debug-signed is fine; personal app).
- Captured proto fixture (this device), hex:
  `08011002180120012800320d2b333539383837383830373731320d2b333539383838373339363233320d2b3335393838383238343830303803`
  decodes to fields: 1=1, 2=2, 3=1, 4=1, 5=0, 6=["+359887880771","+359888739623","+359888284800"], 7=3.

---

### Task 1: Scaffold `:syncapp` module

Create the new app module so later tasks have a place to live and a green build baseline.

**Files:**
- Create: `syncapp/build.gradle.kts`
- Create: `syncapp/src/main/AndroidManifest.xml`
- Create: `syncapp/src/main/java/io/github/mexus/callrecsync/MainActivity.kt`
- Create: `syncapp/src/main/res/values/strings.xml`
- Create: `syncapp/src/main/res/layout/activity_main.xml`
- Modify: `settings.gradle.kts` (add `include(":syncapp")`)

**Interfaces:**
- Produces: an installable debug APK `io.github.mexus.callrecsync` with a launcher `MainActivity`.

- [ ] **Step 1: Add the module to settings**

In `settings.gradle.kts`, add after the existing `include(":app")` (or alongside whatever include exists):
```kotlin
include(":syncapp")
```

- [ ] **Step 2: Create `syncapp/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.mexus.callrecsync"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.mexus.callrecsync"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    buildFeatures { viewBinding = true }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
```
If the Kotlin Android plugin id fails to resolve, confirm the root `build.gradle.kts`/version catalog provides `org.jetbrains.kotlin.android`; add the classpath/plugin there if the repo doesn't already. Report if the repo's AGP version rejects this module config.

- [ ] **Step 3: Manifest**

`syncapp/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <queries>
        <package android:name="com.google.android.dialer" />
    </queries>

    <application
        android:label="CallRec Sync"
        android:theme="@style/Theme.AppCompat.DayNight">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 4: Minimal MainActivity + layout + strings**

`syncapp/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">CallRec Sync</string>
</resources>
```
`syncapp/src/main/res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:orientation="vertical" android:padding="16dp"
    android:layout_width="match_parent" android:layout_height="match_parent">
    <TextView android:id="@+id/status" android:layout_width="wrap_content"
        android:layout_height="wrap_content" android:text="CallRec Sync" />
</LinearLayout>
```
`syncapp/src/main/java/io/github/mexus/callrecsync/MainActivity.kt`:
```kotlin
package io.github.mexus.callrecsync

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
```

- [ ] **Step 5: Build + install**

Run:
```bash
cd d:/MyProjects/gCallRecorder && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :syncapp:assembleDebug --no-daemon --no-configuration-cache
```
Expected: BUILD SUCCESSFUL, APK at `syncapp/build/outputs/apk/debug/syncapp-debug.apk`. (Device optional this task.)

- [ ] **Step 6: Commit**

```bash
git add syncapp settings.gradle.kts
git -c user.name="mexus" -c user.email="mexus@fidweb.net" commit -m "feat(syncapp): scaffold contacts->always-record sync module"
```

---

### Task 2: SettingsProtoCodec (pure, TDD)

The read/modify/write core. Pure Kotlin, no Android deps, fully unit-tested against the captured fixture. Preserves field order (byte-exact round-trip) and every field except the ones it changes.

**Files:**
- Create: `syncapp/src/main/java/io/github/mexus/callrecsync/SettingsProtoCodec.kt`
- Test: `syncapp/src/test/java/io/github/mexus/callrecsync/SettingsProtoCodecTest.kt`

**Interfaces:**
- Produces:
  - `data class ProtoField(val num: Int, val wire: Int, val varint: Long, val bytes: ByteArray)`
  - `object SettingsProtoCodec`:
    - `fun parse(data: ByteArray): List<ProtoField>`
    - `fun build(fields: List<ProtoField>): ByteArray`
    - `fun selectedNumbers(fields: List<ProtoField>): List<String>`
    - `fun withSelectedNumbers(fields: List<ProtoField>, numbers: List<String>): List<ProtoField>`
    - `fun withClearedNumbers(fields: List<ProtoField>): List<ProtoField>`
    - `fun withTogglesOn(fields: List<ProtoField>, setOnceField: Int?): List<ProtoField>`
  - Constants: `const val FIELD_NON_CONTACT = 1`, `const val FIELD_NUMBERS = 6`.

- [ ] **Step 1: Write failing tests**

`syncapp/src/test/java/io/github/mexus/callrecsync/SettingsProtoCodecTest.kt`:
```kotlin
package io.github.mexus.callrecsync

import org.junit.Assert.*
import org.junit.Test

class SettingsProtoCodecTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val FIXTURE = hex(
        "08011002180120012800320d2b333539383837383830373731" +
        "320d2b333539383838373339363233320d2b333539383838323834383030" + "3803")

    @Test fun parsesFixtureFields() {
        val f = SettingsProtoCodec.parse(FIXTURE)
        assertEquals(9, f.size)
        assertEquals(1L, f.first { it.num == 1 }.varint)
        assertEquals(listOf("+359887880771","+359888739623","+359888284800"),
            SettingsProtoCodec.selectedNumbers(f))
    }

    @Test fun roundTripIsByteExact() {
        val f = SettingsProtoCodec.parse(FIXTURE)
        assertArrayEquals(FIXTURE, SettingsProtoCodec.build(f))
    }

    @Test fun withSelectedNumbersReplacesOnlyField6() {
        val f = SettingsProtoCodec.parse(FIXTURE)
        val out = SettingsProtoCodec.withSelectedNumbers(f, listOf("+12023400102","+359886406757"))
        assertEquals(listOf("+12023400102","+359886406757"), SettingsProtoCodec.selectedNumbers(out))
        // non-field-6 varints preserved
        for (n in listOf(1,2,3,4,5,7))
            assertEquals(f.first { it.num == n }.varint, out.first { it.num == n }.varint)
    }

    @Test fun clearedRemovesAllNumbersKeepsRest() {
        val f = SettingsProtoCodec.parse(FIXTURE)
        val out = SettingsProtoCodec.withClearedNumbers(f)
        assertTrue(SettingsProtoCodec.selectedNumbers(out).isEmpty())
        assertEquals(6, out.size) // 9 - 3 number fields
        assertEquals(3L, out.first { it.num == 7 }.varint)
    }

    @Test fun togglesOnSetsNonContactAndSetOnce() {
        // start from a fixture with field 1 = 0
        val f0 = SettingsProtoCodec.parse(FIXTURE).map {
            if (it.num == 1) it.copy(varint = 0L) else it
        }
        val out = SettingsProtoCodec.withTogglesOn(f0, setOnceField = 3)
        assertEquals(1L, out.first { it.num == 1 }.varint)
        assertEquals(1L, out.first { it.num == 3 }.varint)
    }

    @Test fun preservesUnknownField() {
        // append an unknown varint field 9 = 5 before rebuilding
        val f = SettingsProtoCodec.parse(FIXTURE) + ProtoField(9, 0, 5L, ByteArray(0))
        val out = SettingsProtoCodec.withSelectedNumbers(f, listOf("+100"))
        assertEquals(5L, out.first { it.num == 9 }.varint)
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run: `cd d:/MyProjects/gCallRecorder && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :syncapp:testDebugUnitTest --tests "io.github.mexus.callrecsync.SettingsProtoCodecTest" --no-daemon --no-configuration-cache`
Expected: FAIL — `SettingsProtoCodec` unresolved.

- [ ] **Step 3: Implement the codec**

`syncapp/src/main/java/io/github/mexus/callrecsync/SettingsProtoCodec.kt`:
```kotlin
package io.github.mexus.callrecsync

import java.io.ByteArrayOutputStream

data class ProtoField(val num: Int, val wire: Int, val varint: Long, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is ProtoField && num == other.num &&
        wire == other.wire && varint == other.varint && bytes.contentEquals(other.bytes)
    override fun hashCode() = (num * 31 + wire) * 31 + varint.hashCode()
}

object SettingsProtoCodec {
    const val FIELD_NON_CONTACT = 1
    const val FIELD_NUMBERS = 6

    fun parse(data: ByteArray): List<ProtoField> {
        val out = ArrayList<ProtoField>()
        var i = 0
        while (i < data.size) {
            val (tag, ni) = readVarint(data, i); i = ni
            val num = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
            when (wire) {
                0 -> { val (v, n2) = readVarint(data, i); i = n2
                       out.add(ProtoField(num, 0, v, ByteArray(0))) }
                2 -> { val (len, n2) = readVarint(data, i); i = n2
                       val b = data.copyOfRange(i, i + len.toInt()); i += len.toInt()
                       out.add(ProtoField(num, 2, 0, b)) }
                else -> throw IllegalArgumentException("unsupported wire $wire at $i")
            }
        }
        return out
    }

    fun build(fields: List<ProtoField>): ByteArray {
        val o = ByteArrayOutputStream()
        for (f in fields) {
            writeVarint(o, ((f.num.toLong()) shl 3) or f.wire.toLong())
            when (f.wire) {
                0 -> writeVarint(o, f.varint)
                2 -> { writeVarint(o, f.bytes.size.toLong()); o.write(f.bytes) }
            }
        }
        return o.toByteArray()
    }

    fun selectedNumbers(fields: List<ProtoField>): List<String> =
        fields.filter { it.num == FIELD_NUMBERS && it.wire == 2 }.map { String(it.bytes, Charsets.UTF_8) }

    fun withSelectedNumbers(fields: List<ProtoField>, numbers: List<String>): List<ProtoField> {
        val newNums = numbers.map { ProtoField(FIELD_NUMBERS, 2, 0, it.toByteArray(Charsets.UTF_8)) }
        val firstIdx = fields.indexOfFirst { it.num == FIELD_NUMBERS }
        val kept = fields.filter { it.num != FIELD_NUMBERS }
        val insertAt = if (firstIdx >= 0)
            kept.indexOfFirst { origIndexAfter(fields, it) > firstIdx }.let { if (it < 0) kept.size else it }
        else kept.indexOfFirst { it.num > FIELD_NUMBERS }.let { if (it < 0) kept.size else it }
        return ArrayList(kept).apply { addAll(insertAt, newNums) }
    }

    private fun origIndexAfter(all: List<ProtoField>, f: ProtoField) = all.indexOf(f)

    fun withClearedNumbers(fields: List<ProtoField>): List<ProtoField> =
        fields.filter { it.num != FIELD_NUMBERS }

    fun withTogglesOn(fields: List<ProtoField>, setOnceField: Int?): List<ProtoField> =
        fields.map {
            when (it.num) {
                FIELD_NON_CONTACT -> it.copy(varint = 1L)
                setOnceField -> it.copy(varint = 1L)
                else -> it
            }
        }

    private fun readVarint(d: ByteArray, start: Int): Pair<Long, Int> {
        var i = start; var shift = 0; var v = 0L
        while (true) { val b = d[i].toInt() and 0xff; i++; v = v or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) break; shift += 7 }
        return v to i
    }
    private fun writeVarint(o: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) { val b = (v and 0x7f).toInt(); v = v ushr 7
            if (v != 0L) o.write(b or 0x80) else { o.write(b); break } }
    }
}
```

- [ ] **Step 4: Run tests, verify pass**

Run the same test command as Step 2. Expected: PASS (all 6). Fix `withSelectedNumbers` insertion if `roundTrip`/`preservesUnknownField` ordering fails — the goal is: non-field-6 fields keep original order, field-6 run sits where it originally sat (or before the first field with a higher number).

- [ ] **Step 5: Commit**

```bash
git add syncapp/src/main/java/io/github/mexus/callrecsync/SettingsProtoCodec.kt syncapp/src/test
git -c user.name="mexus" -c user.email="mexus@fidweb.net" commit -m "feat(syncapp): protobuf codec for CallRecordingSettingsData"
```

---

### Task 3: Number normalization + ContactsReader

Pure normalization logic (TDD) plus the Android contacts query that uses it.

**Files:**
- Create: `syncapp/src/main/java/io/github/mexus/callrecsync/PhoneNumbers.kt`
- Create: `syncapp/src/main/java/io/github/mexus/callrecsync/ContactsReader.kt`
- Test: `syncapp/src/test/java/io/github/mexus/callrecsync/PhoneNumbersTest.kt`

**Interfaces:**
- Produces:
  - `object PhoneNumbers { fun keepIfE164(candidate: String?): String? ; fun dedupSorted(nums: Collection<String>): List<String> }`
  - `class ContactsReader(private val cr: android.content.ContentResolver) { fun readE164Numbers(simRegion: String): List<String> }`

- [ ] **Step 1: Write failing tests for normalization**

`syncapp/src/test/java/io/github/mexus/callrecsync/PhoneNumbersTest.kt`:
```kotlin
package io.github.mexus.callrecsync

import org.junit.Assert.*
import org.junit.Test

class PhoneNumbersTest {
    @Test fun keepsWellFormedE164() { assertEquals("+359888284800", PhoneNumbers.keepIfE164("+359888284800")) }
    @Test fun stripsSpacesAndDashesWhenAlreadyPlus() {
        assertEquals("+12023326595", PhoneNumbers.keepIfE164("+1 202-332-6595"))
    }
    @Test fun dropsServiceCodesAndNonPlus() {
        assertNull(PhoneNumbers.keepIfE164("*#*#4636#*#*"))
        assertNull(PhoneNumbers.keepIfE164("0888123456"))
        assertNull(PhoneNumbers.keepIfE164(null))
        assertNull(PhoneNumbers.keepIfE164(""))
    }
    @Test fun dedupAndSort() {
        assertEquals(listOf("+1","+2"), PhoneNumbers.dedupSorted(listOf("+2","+1","+2")))
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `... :syncapp:testDebugUnitTest --tests "io.github.mexus.callrecsync.PhoneNumbersTest" ...` (JBR21 + `--no-configuration-cache`). Expected: FAIL (unresolved).

- [ ] **Step 3: Implement PhoneNumbers**

`syncapp/src/main/java/io/github/mexus/callrecsync/PhoneNumbers.kt`:
```kotlin
package io.github.mexus.callrecsync

object PhoneNumbers {
    /** Accept only numbers already in +country form; strip spaces/dashes/parens. Return null otherwise. */
    fun keepIfE164(candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        val trimmed = candidate.trim()
        if (!trimmed.startsWith("+")) return null
        val cleaned = "+" + trimmed.substring(1).filter { it.isDigit() }
        return if (cleaned.length in 5..17) cleaned else null
    }

    fun dedupSorted(nums: Collection<String>): List<String> = nums.toSortedSet().toList()
}
```
(Design note: the dialer stores `NORMALIZED_NUMBER` which is already E.164, so `ContactsReader` prefers that column and only falls back to `keepIfE164` on raw. `keepIfE164` deliberately drops national-format numbers lacking `+` — the contacts provider's normalized column supplies `+` form for real numbers.)

- [ ] **Step 4: Run, verify pass**

Same command as Step 2. Expected: PASS (4).

- [ ] **Step 5: Implement ContactsReader**

`syncapp/src/main/java/io/github/mexus/callrecsync/ContactsReader.kt`:
```kotlin
package io.github.mexus.callrecsync

import android.content.ContentResolver
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils

class ContactsReader(private val cr: ContentResolver) {
    fun readE164Numbers(simRegion: String): List<String> {
        val out = HashSet<String>()
        val proj = arrayOf(Phone.NORMALIZED_NUMBER, Phone.NUMBER)
        cr.query(Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
            val iNorm = c.getColumnIndex(Phone.NORMALIZED_NUMBER)
            val iNum = c.getColumnIndex(Phone.NUMBER)
            while (c.moveToNext()) {
                val norm = if (iNorm >= 0) c.getString(iNorm) else null
                val e164 = PhoneNumbers.keepIfE164(norm)
                    ?: PhoneNumbers.keepIfE164(
                        if (iNum >= 0) PhoneNumberUtils.formatNumberToE164(c.getString(iNum), simRegion) else null)
                if (e164 != null) out.add(e164)
            }
        }
        return PhoneNumbers.dedupSorted(out)
    }
}
```

- [ ] **Step 6: Build (compile check) + commit**

Run: `... :syncapp:assembleDebug ...` (JBR21 + `--no-configuration-cache`) → BUILD SUCCESSFUL.
```bash
git add syncapp/src/main/java/io/github/mexus/callrecsync/PhoneNumbers.kt syncapp/src/main/java/io/github/mexus/callrecsync/ContactsReader.kt syncapp/src/test/java/io/github/mexus/callrecsync/PhoneNumbersTest.kt
git -c user.name="mexus" -c user.email="mexus@fidweb.net" commit -m "feat(syncapp): E.164 normalization + ContactsReader"
```

---

### Task 4: CallStateGuard + RootWriter

Root execution and the in-call guard. Device-verified (root/call state can't be JVM-unit-tested); the `su` script carries the kill-first + in-call re-check + chown/restorecon logic.

**Files:**
- Create: `syncapp/src/main/java/io/github/mexus/callrecsync/CallStateGuard.kt`
- Create: `syncapp/src/main/java/io/github/mexus/callrecsync/RootWriter.kt`

**Interfaces:**
- Produces:
  - `class CallStateGuard(private val ctx: android.content.Context) { fun isCallActive(): Boolean ; fun runWhenIdle(action: () -> Unit) }`
  - `class RootWriter { fun hasRoot(): Boolean ; fun writeProto(newBytes: ByteArray): WriteResult }`
  - `sealed class WriteResult { object Ok: WriteResult(); object NoRoot: WriteResult(); object CallActive: WriteResult(); data class Failed(val msg: String): WriteResult() }`
- Consumes: nothing from prior tasks (bytes come from SyncEngine in Task 5).

- [ ] **Step 1: Implement CallStateGuard**

`syncapp/src/main/java/io/github/mexus/callrecsync/CallStateGuard.kt`:
```kotlin
package io.github.mexus.callrecsync

import android.content.Context
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

class CallStateGuard(private val ctx: Context) {
    private val tm get() = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @Suppress("DEPRECATION")
    fun isCallActive(): Boolean = tm.callState != TelephonyManager.CALL_STATE_IDLE

    /** Run now if idle; else run once, the next time the call state returns to IDLE. */
    fun runWhenIdle(action: () -> Unit) {
        if (!isCallActive()) { action(); return }
        val exec = ctx.mainExecutor
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                if (state == TelephonyManager.CALL_STATE_IDLE) {
                    tm.unregisterTelephonyCallback(this); action()
                }
            }
        }
        tm.registerTelephonyCallback(exec, cb)
    }
}
```
(minSdk 28 note: `registerTelephonyCallback` is API 31+. For API 28–30 use the legacy `PhoneStateListener` path. Add a Build.VERSION branch: on `< S`, register a `PhoneStateListener` for `LISTEN_CALL_STATE` that fires the action on IDLE and then `LISTEN_NONE`. Device runs Android 17 (API 37) so the modern path is primary; keep the legacy branch compiling for the declared minSdk.)

- [ ] **Step 2: Implement RootWriter**

`syncapp/src/main/java/io/github/mexus/callrecsync/RootWriter.kt`:
```kotlin
package io.github.mexus.callrecsync

import android.util.Base64

class RootWriter {
    private val target = "/data/data/com.google.android.dialer/files/CallRecordingSettingsData.pb"
    private val dialer = "com.google.android.dialer"

    fun hasRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor() == 0 && out == "0"
    }.getOrDefault(false)

    fun writeProto(newBytes: ByteArray): WriteResult {
        if (!hasRoot()) return WriteResult.NoRoot
        val b64 = Base64.encodeToString(newBytes, Base64.NO_WRAP)
        // The script re-checks call state inside root right before force-stop (closes the check->kill race),
        // kills the dialer, confirms the pid is gone, backs up, writes, restores owner + SELinux context.
        val script = """
            set -e
            state=${'$'}(dumpsys telephony.registry 2>/dev/null | grep -m1 'mCallState' | grep -oE '[0-9]+' | head -1)
            if [ "${'$'}state" != "" ] && [ "${'$'}state" != "0" ]; then echo CALL_ACTIVE; exit 42; fi
            am force-stop $dialer
            for i in ${'$'}(seq 1 20); do pidof $dialer >/dev/null || break; am force-stop $dialer; sleep 0.3; done
            if pidof $dialer >/dev/null; then echo STILL_ALIVE; exit 43; fi
            owner=${'$'}(stat -c %U "$target" 2>/dev/null || echo "")
            cp "$target" "$target.callrecsync.bak" 2>/dev/null || true
            echo "$b64" | base64 -d > "$target"
            [ -n "${'$'}owner" ] && chown "${'$'}owner":"${'$'}owner" "$target"
            restorecon "$target" 2>/dev/null || true
            echo OK
        """.trimIndent()
        return runCatching {
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            when {
                p.waitFor() == 0 && out.endsWith("OK") -> WriteResult.Ok
                out.contains("CALL_ACTIVE") -> WriteResult.CallActive
                else -> WriteResult.Failed(out.takeLast(200))
            }
        }.getOrElse { WriteResult.Failed(it.message ?: "exec failed") }
    }
}

sealed class WriteResult {
    object Ok : WriteResult()
    object NoRoot : WriteResult()
    object CallActive : WriteResult()
    data class Failed(val msg: String) : WriteResult()
}
```

- [ ] **Step 3: Build**

Run: `... :syncapp:assembleDebug ...` → BUILD SUCCESSFUL. Fix the API-level branch in CallStateGuard if the compiler flags `registerTelephonyCallback` on minSdk (guard with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`, else `PhoneStateListener`).

- [ ] **Step 4: Device smoke test (controller/device owner) — DEFERRED**

This step is device-only and runs during integration (Task 8); note it here: install, tap a temporary debug button (or run via a unit hook) to write the current proto back unchanged and confirm `WriteResult.Ok` + the file's owner/context preserved. Do NOT block Task 4 completion on it — the controller runs it with the device.

- [ ] **Step 5: Commit**

```bash
git add syncapp/src/main/java/io/github/mexus/callrecsync/CallStateGuard.kt syncapp/src/main/java/io/github/mexus/callrecsync/RootWriter.kt
git -c user.name="mexus" -c user.email="mexus@fidweb.net" commit -m "feat(syncapp): root writer (kill-first, in-call guard) + call-state guard"
```

---

### Task 5: SyncEngine (diff/skip/defer/clear)

Orchestration with the change-diff (no needless kills) and the in-call deferral. The pure decision — "does the target differ from current?" — is unit-tested with injected fakes.

**Files:**
- Create: `syncapp/src/main/java/io/github/mexus/callrecsync/SyncEngine.kt`
- Test: `syncapp/src/test/java/io/github/mexus/callrecsync/SyncEngineTest.kt`

**Interfaces:**
- Consumes: `SettingsProtoCodec`, `WriteResult`.
- Produces:
  - `interface ProtoStore { fun read(): ByteArray? ; fun write(bytes: ByteArray): WriteResult }`
  - `interface Idler { fun runWhenIdle(action: () -> Unit) }`
  - `class SyncEngine(store, idler, setOnceField: Int?)`:
    - `fun computeTarget(current: ByteArray, numbers: List<String>): ByteArray`
    - `fun sync(numbers: List<String>): SyncOutcome`
    - `fun clear(): SyncOutcome`
  - `sealed class SyncOutcome { object Skipped; object NoCurrent; data class Wrote(val result: WriteResult) }`

- [ ] **Step 1: Write failing tests**

`syncapp/src/test/java/io/github/mexus/callrecsync/SyncEngineTest.kt`:
```kotlin
package io.github.mexus.callrecsync

import org.junit.Assert.*
import org.junit.Test

class SyncEngineTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val FIXTURE = hex("08011002180120012800320d2b333539383837383830373731" +
        "320d2b333539383838373339363233320d2b3335393838383238343830303803")

    private class FakeStore(var cur: ByteArray?) : ProtoStore {
        var wrote: ByteArray? = null
        override fun read() = cur
        override fun write(bytes: ByteArray): WriteResult { wrote = bytes; cur = bytes; return WriteResult.Ok }
    }
    private object NowIdler : Idler { override fun runWhenIdle(action: () -> Unit) = action() }

    @Test fun skipsWhenTargetEqualsCurrent() {
        // current already equals target for these numbers + toggles-on(field1 already 1)
        val store = FakeStore(FIXTURE)
        val eng = SyncEngine(store, NowIdler, setOnceField = null)
        val target = eng.computeTarget(FIXTURE, listOf("+359887880771","+359888739623","+359888284800"))
        store.cur = target
        assertEquals(SyncOutcome.Skipped, eng.sync(listOf("+359887880771","+359888739623","+359888284800")))
        assertNull(store.wrote)
    }

    @Test fun writesWhenNumbersChange() {
        val store = FakeStore(FIXTURE)
        val eng = SyncEngine(store, NowIdler, setOnceField = null)
        val out = eng.sync(listOf("+100","+200"))
        assertTrue(out is SyncOutcome.Wrote)
        assertEquals(listOf("+100","+200"), SettingsProtoCodec.selectedNumbers(SettingsProtoCodec.parse(store.cur!!)))
    }

    @Test fun clearEmptiesNumbers() {
        val store = FakeStore(FIXTURE)
        val eng = SyncEngine(store, NowIdler, setOnceField = null)
        eng.clear()
        assertTrue(SettingsProtoCodec.selectedNumbers(SettingsProtoCodec.parse(store.cur!!)).isEmpty())
    }

    @Test fun noCurrentReturnsNoCurrent() {
        assertEquals(SyncOutcome.NoCurrent, SyncEngine(FakeStore(null), NowIdler, null).sync(listOf("+1")))
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `... :syncapp:testDebugUnitTest --tests "io.github.mexus.callrecsync.SyncEngineTest" ...`. Expected: FAIL (unresolved).

- [ ] **Step 3: Implement SyncEngine**

`syncapp/src/main/java/io/github/mexus/callrecsync/SyncEngine.kt`:
```kotlin
package io.github.mexus.callrecsync

interface ProtoStore { fun read(): ByteArray?; fun write(bytes: ByteArray): WriteResult }
interface Idler { fun runWhenIdle(action: () -> Unit) }

sealed class SyncOutcome {
    object Skipped : SyncOutcome()
    object NoCurrent : SyncOutcome()
    data class Wrote(val result: WriteResult) : SyncOutcome()
}

class SyncEngine(
    private val store: ProtoStore,
    private val idler: Idler,
    private val setOnceField: Int?,
) {
    fun computeTarget(current: ByteArray, numbers: List<String>): ByteArray {
        var f = SettingsProtoCodec.parse(current)
        f = SettingsProtoCodec.withSelectedNumbers(f, numbers)
        f = SettingsProtoCodec.withTogglesOn(f, setOnceField)
        return SettingsProtoCodec.build(f)
    }

    fun sync(numbers: List<String>): SyncOutcome = apply(numbers, clear = false)
    fun clear(): SyncOutcome = apply(emptyList(), clear = true)

    private fun apply(numbers: List<String>, clear: Boolean): SyncOutcome {
        val cur = store.read() ?: return SyncOutcome.NoCurrent
        val target = if (clear) {
            SettingsProtoCodec.build(SettingsProtoCodec.withClearedNumbers(SettingsProtoCodec.parse(cur)))
        } else computeTarget(cur, numbers)
        if (target.contentEquals(cur)) return SyncOutcome.Skipped
        var result: WriteResult = WriteResult.Failed("not run")
        idler.runWhenIdle { result = store.write(target) }
        return SyncOutcome.Wrote(result)
    }
}
```
(Note: with the synchronous `NowIdler` in tests, `write` runs before returning. In production the real `Idler` may defer; `SyncOutcome.Wrote` then reflects the deferred write's eventual result via the callback — the UI reads status from the store/last-result, not this return value, when deferred. This is acceptable: the return value is authoritative only for the synchronous/idle path.)

- [ ] **Step 4: Run, verify pass**

Same command as Step 2. Expected: PASS (4).

- [ ] **Step 5: Commit**

```bash
git add syncapp/src/main/java/io/github/mexus/callrecsync/SyncEngine.kt syncapp/src/test/java/io/github/mexus/callrecsync/SyncEngineTest.kt
git -c user.name="mexus" -c user.email="mexus@fidweb.net" commit -m "feat(syncapp): SyncEngine with change-diff and in-call deferral"
```

---

### Task 6: Triggers (ContactsObserver + BootReceiver) + AndroidProtoStore/Idler wiring

Wire the pure engine to Android: a real `ProtoStore` (reads the file via root/`RootWriter`), a real `Idler` (CallStateGuard), a debounced contacts observer, and a boot receiver. Auto-sync gating lives here (reads the prefs flag).

**Files:**
- Create: `syncapp/src/main/java/io/github/mexus/callrecsync/AndroidGlue.kt` (RootProtoStore + GuardIdler + a `SyncService`-style entry `Syncer`)
- Create: `syncapp/src/main/java/io/github/mexus/callrecsync/ContactsObserver.kt`
- Create: `syncapp/src/main/java/io/github/mexus/callrecsync/BootReceiver.kt`
- Modify: `syncapp/src/main/AndroidManifest.xml` (register BootReceiver)

**Interfaces:**
- Consumes: `SyncEngine`, `ProtoStore`, `Idler`, `RootWriter`, `CallStateGuard`, `ContactsReader`.
- Produces:
  - `class RootProtoStore(rw: RootWriter) : ProtoStore` — `read()` via `su cat|base64`, `write()` via `rw.writeProto`.
  - `class GuardIdler(g: CallStateGuard) : Idler`.
  - `object Syncer { fun syncNow(ctx, reason): SyncOutcome ; fun clear(ctx): SyncOutcome ; fun autoSyncEnabled(ctx): Boolean ; fun setAutoSync(ctx, on) }`
  - `const val PREFS = "callrecsync"`, key `auto_sync` (default true), key `set_once_field` (pinned, default -1 → null).

- [ ] **Step 1: Implement AndroidGlue (store, idler, Syncer)**

`syncapp/src/main/java/io/github/mexus/callrecsync/AndroidGlue.kt`:
```kotlin
package io.github.mexus.callrecsync

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Base64

const val PREFS = "callrecsync"

class RootProtoStore(private val rw: RootWriter) : ProtoStore {
    private val target = "/data/data/com.google.android.dialer/files/CallRecordingSettingsData.pb"
    override fun read(): ByteArray? = runCatching {
        val p = ProcessBuilder("su", "-c", "base64 \"$target\"").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() != 0 || out.isEmpty()) null else Base64.decode(out, Base64.DEFAULT)
    }.getOrNull()
    override fun write(bytes: ByteArray): WriteResult = rw.writeProto(bytes)
}

class GuardIdler(private val g: CallStateGuard) : Idler {
    override fun runWhenIdle(action: () -> Unit) = g.runWhenIdle(action)
}

object Syncer {
    fun autoSyncEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, 0).getBoolean("auto_sync", true)
    fun setAutoSync(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("auto_sync", on).apply()
    private fun setOnceField(ctx: Context): Int? =
        ctx.getSharedPreferences(PREFS, 0).getInt("set_once_field", -1).takeIf { it >= 0 }

    private fun engine(ctx: Context): SyncEngine =
        SyncEngine(RootProtoStore(RootWriter()), GuardIdler(CallStateGuard(ctx)), setOnceField(ctx))

    fun syncNow(ctx: Context): SyncOutcome {
        val simRegion = (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
            .simCountryIso?.uppercase()?.ifBlank { "US" } ?: "US"
        val numbers = ContactsReader(ctx.contentResolver).readE164Numbers(simRegion)
        return engine(ctx).sync(numbers)
    }
    fun clear(ctx: Context): SyncOutcome = engine(ctx).clear()
}
```

- [ ] **Step 2: Implement ContactsObserver (debounced) + BootReceiver**

`syncapp/src/main/java/io/github/mexus/callrecsync/ContactsObserver.kt`:
```kotlin
package io.github.mexus.callrecsync

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract

class ContactsObserver(private val ctx: Context) :
    ContentObserver(Handler(Looper.getMainLooper())) {
    private val debounce = Handler(Looper.getMainLooper())
    private val run = Runnable { if (Syncer.autoSyncEnabled(ctx)) Syncer.syncNow(ctx) }
    fun register() = ctx.contentResolver.registerContentObserver(
        ContactsContract.Contacts.CONTENT_URI, true, this)
    override fun onChange(selfChange: Boolean) {
        debounce.removeCallbacks(run); debounce.postDelayed(run, 30_000)
    }
}
```
`syncapp/src/main/java/io/github/mexus/callrecsync/BootReceiver.kt`:
```kotlin
package io.github.mexus.callrecsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Syncer.autoSyncEnabled(ctx)) {
            Thread { Syncer.syncNow(ctx) }.start()
        }
    }
}
```
Register in the manifest `<application>`:
```xml
<receiver android:name=".BootReceiver" android:exported="true">
    <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
</receiver>
```
(The observer is registered from `MainActivity` while the app process lives; a persistent background observer would need a foreground service — out of scope. Boot + manual + on-app-open sync cover the gaps. Note this limitation in the report.)

- [ ] **Step 3: Build**

Run: `... :syncapp:assembleDebug ...` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add syncapp/src/main/java/io/github/mexus/callrecsync/AndroidGlue.kt syncapp/src/main/java/io/github/mexus/callrecsync/ContactsObserver.kt syncapp/src/main/java/io/github/mexus/callrecsync/BootReceiver.kt syncapp/src/main/AndroidManifest.xml
git -c user.name="mexus" -c user.email="mexus@fidweb.net" commit -m "feat(syncapp): root proto store, contacts observer, boot receiver"
```

---

### Task 7: MainActivity UI

The single-screen UI: root status, counts, last-sync result, Auto-sync switch, Sync now, Clear list (confirm). Runs sync off the main thread; requests `READ_CONTACTS`/`READ_PHONE_STATE` at runtime.

**Files:**
- Modify: `syncapp/src/main/java/io/github/mexus/callrecsync/MainActivity.kt`
- Modify: `syncapp/src/main/res/layout/activity_main.xml`
- Modify: `syncapp/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `Syncer`, `RootWriter`, `ContactsObserver`, `ContactsReader`.

- [ ] **Step 1: Layout**

`activity_main.xml` — vertical LinearLayout with: `TextView status`, `Switch autoSync`, `Button syncNow`, `Button clearList`, `TextView lastResult`. (Full XML — each with an id; use `androidx.appcompat.widget.SwitchCompat`.)
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:orientation="vertical" android:padding="16dp"
    android:layout_width="match_parent" android:layout_height="match_parent">
    <TextView android:id="@+id/status" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:textStyle="bold" />
    <androidx.appcompat.widget.SwitchCompat android:id="@+id/autoSync"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="Auto-sync on contacts change" android:layout_marginTop="12dp" />
    <Button android:id="@+id/syncNow" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:text="Sync now" android:layout_marginTop="12dp" />
    <Button android:id="@+id/clearList" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:text="Clear list" android:layout_marginTop="4dp" />
    <TextView android:id="@+id/lastResult" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:layout_marginTop="16dp" />
</LinearLayout>
```

- [ ] **Step 2: MainActivity**

`MainActivity.kt`:
```kotlin
package io.github.mexus.callrecsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var observer: ContactsObserver
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ensurePerms()
        val status = findViewById<TextView>(R.id.status)
        val last = findViewById<TextView>(R.id.lastResult)
        val auto = findViewById<SwitchCompat>(R.id.autoSync)
        auto.isChecked = Syncer.autoSyncEnabled(this)
        auto.setOnCheckedChangeListener { _, on -> Syncer.setAutoSync(this, on) }
        findViewById<Button>(R.id.syncNow).setOnClickListener { runSync(last) { Syncer.syncNow(this) } }
        findViewById<Button>(R.id.clearList).setOnClickListener {
            AlertDialog.Builder(this).setMessage("Clear the always-record list?")
                .setPositiveButton("Clear") { _, _ -> runSync(last) { Syncer.clear(this) } }
                .setNegativeButton("Cancel", null).show()
        }
        status.text = "Root: ${if (RootWriter().hasRoot()) "yes" else "NO"}"
        observer = ContactsObserver(this).also { it.register() }
    }
    private fun runSync(last: TextView, block: () -> SyncOutcome) {
        Thread {
            val r = runCatching { block() }.getOrElse { SyncOutcome.Wrote(WriteResult.Failed(it.message ?: "err")) }
            runOnUiThread { last.text = "Last: $r" }
        }.start()
    }
    private fun ensurePerms() {
        val need = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }
    override fun onDestroy() { super.onDestroy(); if (::observer.isInitialized) contentResolver.unregisterContentObserver(observer) }
}
```

- [ ] **Step 3: Build + install**

Run: `... :syncapp:assembleDebug ...` → BUILD SUCCESSFUL. (Install happens in Task 8.)

- [ ] **Step 4: Commit**

```bash
git add syncapp/src/main/java/io/github/mexus/callrecsync/MainActivity.kt syncapp/src/main/res
git -c user.name="mexus" -c user.email="mexus@fidweb.net" commit -m "feat(syncapp): main screen (auto-sync, sync now, clear)"
```

---

### Task 8: Device integration + pin the set-once field

Install on the device and verify the whole feature end-to-end with the device owner. Also pin the `AlwaysRecordingSetAtLeastOnce` field number and store it in prefs so `withTogglesOn` sets it.

**Files:**
- None (verification + a one-line prefs seed via the app or adb).

- [ ] **Step 1: Install + grant + root**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :syncapp:assembleDebug --no-daemon --no-configuration-cache
adb install -r syncapp/build/outputs/apk/debug/syncapp-debug.apk
adb shell appops set io.github.mexus.callrecsync ... # grant contacts/phone via UI prompt on first launch
```
Open the app; grant READ_CONTACTS + READ_PHONE_STATE; confirm "Root: yes" (approve the su prompt). Device-owner action.

- [ ] **Step 2: Pin the set-once field**

Read the current proto, then in the dialer UI toggle "Automatically record unknown numbers" OFF→ON (or the always-record master), and diff the `.pb` to find which varint field flips to represent `AlwaysRecordingSetAtLeastOnce`. Controller does this:
```bash
adb shell "su -c 'xxd /data/data/com.google.android.dialer/files/CallRecordingSettingsData.pb'"   # before
# (device owner toggles the setting)
adb shell "su -c 'xxd .../CallRecordingSettingsData.pb'"   # after; note which field changed
```
Store the pinned field number in prefs so `withTogglesOn` sets it:
```bash
adb shell "run-as io.github.mexus.callrecsync sh -c 'echo pinned via app'"   # or set via a temporary app control
```
If pinning is inconclusive, leave `set_once_field=-1` (null) — the existing proto already has it on, and `withTogglesOn` still forces field 1; document this in the report.

- [ ] **Step 3: Functional verification (device owner)**

Verify and record results:
1. **Sync now** → open dialer "Automatically record these numbers" → shows all contacts. `AlwaysRecordNonContact` stays on.
2. **Add a contact** → within ~30 s (or via Sync now) it appears in the list.
3. **Edit a contact's number** → old number gone, new present after sync.
4. **Delete a contact** → its number removed after sync.
5. **Unchanged edit** (rename only, same numbers) → `Sync now` reports Skipped, dialer NOT force-stopped (observe it stays running: `adb shell pidof com.google.android.dialer` unchanged).
6. **In-call guard:** start a call; during the call, add/save a contact (or tap Sync now) → dialer is NOT force-stopped (call continues); after hang-up, the sync runs and the list updates. Confirm `WriteResult.CallActive`/deferral, not a dropped call.
7. **Clear list** with Auto-sync OFF → list empties and stays empty (no refill). Turn Auto-sync ON + Sync now → repopulates.
8. Place a real call to a **contact** → auto-records, silent (goal-2 end-to-end with the module).

- [ ] **Step 4: Commit any fixes + tag**

Commit any fixes surfaced by device testing. When all pass:
```bash
git -c user.name="mexus" -c user.email="mexus@fidweb.net" commit -am "fix(syncapp): device-verification fixes" # if any
git tag -a syncapp-v1.0 -m "Contacts->always-record sync verified on device"
```

---

## Self-Review

**Spec coverage:**
- Root companion app, contacts→field 6 mirror → Tasks 3,5,6. ✓
- Proto codec preserve/replace → Task 2. ✓
- Kill-first write, chown via stat, restorecon, backup → Task 4. ✓
- In-call guard (3 layers: observer defer, runWhenIdle, su re-check) → Tasks 4 (RootWriter script + CallStateGuard), 5 (idler), 6 (observer). ✓
- Change-diff skip → Task 5. ✓
- Enforce toggles (field 1 + set-once) → Task 2 (`withTogglesOn`), Task 8 (pin set-once). ✓
- Clear list + Auto-sync switch → Tasks 5 (`clear`), 6 (prefs gating), 7 (UI). ✓
- Triggers: observer + boot + manual → Task 6, 7. ✓
- Error handling (no root, parse fail, call active, verify) → Task 4 (WriteResult), 5, 7. ✓
- Testing (codec/normalization/diff unit; device integration) → Tasks 2,3,5 (unit), 8 (device). ✓
- New module layout, `:syncapp`, package → Task 1. ✓

**Placeholder scan:** The only deferred item is the set-once field number, with a concrete pin procedure in Task 8 and a safe default (null → preserve existing on-state + still force field 1). No TODO/vague steps. Task 4/8 device steps are explicitly deferred to the controller+device, consistent with the module plan's pattern.

**Type consistency:** `ProtoField`, `SettingsProtoCodec.{parse,build,selectedNumbers,withSelectedNumbers,withClearedNumbers,withTogglesOn}`, `ProtoStore`, `Idler`, `WriteResult`, `SyncOutcome`, `SyncEngine(store,idler,setOnceField)` used identically across Tasks 2,4,5,6,7. `Syncer.{syncNow,clear,autoSyncEnabled,setAutoSync}` consistent between 6 and 7. `keepIfE164`/`dedupSorted`/`readE164Numbers` consistent between 3 and 6.
