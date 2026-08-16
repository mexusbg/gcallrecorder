package io.github.mexus.gcallrecorder

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
