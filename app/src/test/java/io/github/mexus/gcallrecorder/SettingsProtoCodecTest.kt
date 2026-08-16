package io.github.mexus.gcallrecorder

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
        // start from a fixture with fields 1 and 3 both = 0, so the assertions below prove the toggle flips them
        val f0 = SettingsProtoCodec.parse(FIXTURE).map {
            if (it.num == 1 || it.num == 3) it.copy(varint = 0L) else it
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
