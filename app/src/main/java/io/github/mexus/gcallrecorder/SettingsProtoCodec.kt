package io.github.mexus.gcallrecorder

import java.io.ByteArrayOutputStream

data class ProtoField(val num: Int, val wire: Int, val varint: Long, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is ProtoField && num == other.num &&
        wire == other.wire && varint == other.varint && bytes.contentEquals(other.bytes)
    override fun hashCode() = ((num * 31 + wire) * 31 + varint.hashCode()) * 31 + bytes.contentHashCode()
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
                else -> throw IllegalArgumentException("unsupported wire ${f.wire}")
            }
        }
        return o.toByteArray()
    }

    fun selectedNumbers(fields: List<ProtoField>): List<String> =
        fields.filter { it.num == FIELD_NUMBERS && it.wire == 2 }.map { String(it.bytes, Charsets.UTF_8) }

    fun withSelectedNumbers(fields: List<ProtoField>, numbers: List<String>): List<ProtoField> {
        val newNums = numbers.map { ProtoField(FIELD_NUMBERS, 2, 0, it.toByteArray(Charsets.UTF_8)) }
        val firstIdx = fields.indexOfFirst { it.num == FIELD_NUMBERS }
        val insertAt = if (firstIdx >= 0) {
            // count of non-field-6 entries that occur before the first field-6 entry
            fields.subList(0, firstIdx).count { it.num != FIELD_NUMBERS }
        } else {
            fields.count { it.num <= FIELD_NUMBERS }
        }
        val kept = fields.filter { it.num != FIELD_NUMBERS }
        return ArrayList(kept).apply { addAll(insertAt, newNums) }
    }

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
