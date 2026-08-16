package io.github.mexus.gcallrecorder

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

class CallStateGuard(private val ctx: Context) {
    private val tm get() = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @Suppress("DEPRECATION")
    fun isCallActive(): Boolean = tm.callState != TelephonyManager.CALL_STATE_IDLE

    /** Run now if idle; else run once, the next time the call state returns to IDLE. */
    fun runWhenIdle(action: () -> Unit) {
        if (!isCallActive()) { action(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runWhenIdleModern(action)
        } else {
            runWhenIdleLegacy(action)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun runWhenIdleModern(action: () -> Unit) {
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

    @Suppress("DEPRECATION")
    private fun runWhenIdleLegacy(action: () -> Unit) {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_IDLE) {
                    tm.listen(this, PhoneStateListener.LISTEN_NONE)
                    action()
                }
            }
        }
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }
}
