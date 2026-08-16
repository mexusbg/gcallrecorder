package io.github.mexus.gcallrecorder

interface ProtoStore { fun read(): ByteArray?; fun write(bytes: ByteArray): WriteResult }
interface Idler { fun runWhenIdle(action: () -> Unit) }

sealed class SyncOutcome {
    object Skipped : SyncOutcome() { override fun toString() = "Skipped (no change)" }
    object NoCurrent : SyncOutcome() { override fun toString() = "NoCurrent" }
    object Deferred : SyncOutcome() { override fun toString() = "Deferred (call active)" }
    data class Wrote(val result: WriteResult) : SyncOutcome() { override fun toString() = "Wrote($result)" }
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
        var ran = false
        var result: WriteResult = WriteResult.Failed("not run")
        idler.runWhenIdle { ran = true; result = store.write(target) }
        return if (ran) SyncOutcome.Wrote(result) else SyncOutcome.Deferred
    }
}
