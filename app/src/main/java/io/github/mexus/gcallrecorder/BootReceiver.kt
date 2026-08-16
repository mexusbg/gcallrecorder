package io.github.mexus.gcallrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Syncer.autoSyncEnabled(ctx)) {
            SyncScheduler.apply(ctx) // re-arm periodic work at the stored interval (WorkManager also self-restores)
            val pr = goAsync()
            Thread {
                runCatching { if (Syncer.autoSyncEnabled(ctx)) Syncer.syncNow(ctx) }
                pr.finish()
            }.start()
        }
    }
}
