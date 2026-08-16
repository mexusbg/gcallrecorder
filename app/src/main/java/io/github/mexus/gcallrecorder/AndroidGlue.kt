package io.github.mexus.gcallrecorder

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

const val PREFS = "gcallrecorder"

class RootProtoStore(private val rw: RootWriter) : ProtoStore {
    private val target = DIALER_SETTINGS_PB
    override fun read(): ByteArray? = runCatching {
        // -M = KernelSU/Magisk mount-master: run in the global mount namespace so the
        // dialer's private /data/data files are visible (the app's own namespace hides them).
        val p = ProcessBuilder("su", "-M", "-c", "base64 \"$target\"").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        if (p.waitFor() != 0 || out.isEmpty()) null else Base64.decode(out, Base64.DEFAULT)
    }.onFailure { Log.e("gcallrecorder", "proto read failed", it) }.getOrNull()
    override fun write(bytes: ByteArray): WriteResult = rw.writeProto(bytes)
}

class GuardIdler(private val g: CallStateGuard) : Idler {
    override fun runWhenIdle(action: () -> Unit) = g.runWhenIdle(action)
}

object Syncer {
    private val running = AtomicBoolean(false)

    /** Available background-sync intervals, in minutes. 0 = Never (auto-sync off). */
    val INTERVAL_MINUTES = intArrayOf(0, 15, 30, 60, 360, 720, 1440)
    val INTERVAL_LABELS = arrayOf(
        "Never", "Every 15 minutes", "Every 30 minutes", "Every hour",
        "Every 6 hours", "Every 12 hours", "Every 24 hours")
    private const val DEFAULT_INTERVAL = 15

    fun syncIntervalMinutes(ctx: Context) =
        ctx.getSharedPreferences(PREFS, 0).getInt("sync_interval_min", DEFAULT_INTERVAL)
    fun setSyncIntervalMinutes(ctx: Context, minutes: Int) =
        ctx.getSharedPreferences(PREFS, 0).edit().putInt("sync_interval_min", minutes).apply()

    /** Auto-sync (periodic + on-change observer) is on whenever a non-"Never" interval is set. */
    fun autoSyncEnabled(ctx: Context) = syncIntervalMinutes(ctx) > 0
    private fun setOnceField(ctx: Context): Int? =
        ctx.getSharedPreferences(PREFS, 0).getInt("set_once_field", -1).takeIf { it >= 0 }

    private fun engine(ctx: Context): SyncEngine =
        SyncEngine(RootProtoStore(RootWriter()), GuardIdler(CallStateGuard(ctx)), setOnceField(ctx))

    fun syncNow(ctx: Context): SyncOutcome {
        if (!running.compareAndSet(false, true)) return SyncOutcome.Skipped
        try {
            val simRegion = (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .simCountryIso?.uppercase()?.ifBlank { "US" } ?: "US"
            val numbers = ContactsReader(ctx.contentResolver).readE164Numbers(simRegion)
            return engine(ctx).sync(numbers)
        } finally {
            running.set(false)
        }
    }
    fun clear(ctx: Context): SyncOutcome {
        if (!running.compareAndSet(false, true)) return SyncOutcome.Skipped
        try {
            return engine(ctx).clear()
        } finally {
            running.set(false)
        }
    }
}
