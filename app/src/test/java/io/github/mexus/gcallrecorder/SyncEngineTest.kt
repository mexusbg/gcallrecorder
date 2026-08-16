package io.github.mexus.gcallrecorder

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
    private class DeferringIdler : Idler {
        var stored: (() -> Unit)? = null
        override fun runWhenIdle(action: () -> Unit) { stored = action }
    }

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

    @Test fun deferredWhenIdlerDefers() {
        val store = FakeStore(FIXTURE)
        val idler = DeferringIdler()
        val eng = SyncEngine(store, idler, setOnceField = null)
        val out = eng.sync(listOf("+100","+200"))
        assertEquals(SyncOutcome.Deferred, out)
        assertNull(store.wrote)
        assertNotNull(idler.stored)
    }
}
