package io.github.mexus.gcallrecorder

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract

class ContactsObserver(private val ctx: Context) :
    ContentObserver(Handler(Looper.getMainLooper())) {
    private val debounce = Handler(Looper.getMainLooper())
    private val run = Runnable {
        if (Syncer.autoSyncEnabled(ctx)) Thread {
            runCatching { Syncer.syncNow(ctx) }
        }.start()
    }
    fun register() = ctx.contentResolver.registerContentObserver(
        ContactsContract.Contacts.CONTENT_URI, true, this)
    override fun onChange(selfChange: Boolean) {
        debounce.removeCallbacks(run); debounce.postDelayed(run, 30_000)
    }
}
