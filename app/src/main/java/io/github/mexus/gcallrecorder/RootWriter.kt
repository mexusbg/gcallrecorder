package io.github.mexus.gcallrecorder

import android.util.Base64

const val DIALER_SETTINGS_PB = "/data/data/com.google.android.dialer/files/CallRecordingSettingsData.pb"

class RootWriter {
    private val target = DIALER_SETTINGS_PB
    private val dialer = "com.google.android.dialer"

    fun hasRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor() == 0 && out == "0"
    }.getOrDefault(false)

    fun writeProto(newBytes: ByteArray): WriteResult {
        if (!hasRoot()) return WriteResult.NoRoot
        val b64 = Base64.encodeToString(newBytes, Base64.NO_WRAP)
        // The script re-checks call state inside root right before force-stop (closes the check->kill race),
        // kills the dialer, confirms the pid is gone, backs up, writes, restores owner + SELinux context.
        val script = """
            set -e
            states=${'$'}(dumpsys telephony.registry 2>/dev/null | grep -oE 'mCallState=[0-9]+' | grep -oE '[0-9]+$' || true)
            if [ -z "${'$'}states" ]; then echo CALL_ACTIVE; exit 42; fi
            if echo "${'$'}states" | grep -qv '^0$'; then echo CALL_ACTIVE; exit 42; fi
            am force-stop $dialer
            for i in ${'$'}(seq 1 20); do pidof $dialer >/dev/null || break; am force-stop $dialer; sleep 0.3; done
            if pidof $dialer >/dev/null; then echo STILL_ALIVE; exit 43; fi
            owner=${'$'}(stat -c %U "$target" 2>/dev/null || echo "")
            cp "$target" "$target.gcallrecorder.bak" 2>/dev/null || true
            echo "$b64" | base64 -d > "$target"
            [ -n "${'$'}owner" ] && chown "${'$'}owner":"${'$'}owner" "$target"
            restorecon "$target" 2>/dev/null || true
            echo OK
        """.trimIndent()
        return runCatching {
            // -M = mount-master (global namespace) so the dialer's private files are visible.
            val p = ProcessBuilder("su", "-M", "-c", script).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            when {
                p.waitFor() == 0 && out.endsWith("OK") -> WriteResult.Ok
                out.contains("CALL_ACTIVE") -> WriteResult.CallActive
                else -> WriteResult.Failed(out.takeLast(200))
            }
        }.getOrElse { WriteResult.Failed(it.message ?: "exec failed") }
    }
}

sealed class WriteResult {
    object Ok : WriteResult() { override fun toString() = "Ok" }
    object NoRoot : WriteResult() { override fun toString() = "NoRoot" }
    object CallActive : WriteResult() { override fun toString() = "CallActive" }
    data class Failed(val msg: String) : WriteResult() { override fun toString() = "Failed: $msg" }
}
