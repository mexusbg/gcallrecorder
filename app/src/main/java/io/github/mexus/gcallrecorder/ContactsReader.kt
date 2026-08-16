package io.github.mexus.gcallrecorder

import android.content.ContentResolver
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils

class ContactsReader(private val cr: ContentResolver) {
    fun readE164Numbers(simRegion: String): List<String> {
        val out = HashSet<String>()
        val proj = arrayOf(Phone.NORMALIZED_NUMBER, Phone.NUMBER)
        cr.query(Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
            val iNorm = c.getColumnIndex(Phone.NORMALIZED_NUMBER)
            val iNum = c.getColumnIndex(Phone.NUMBER)
            while (c.moveToNext()) {
                val norm = if (iNorm >= 0) c.getString(iNorm) else null
                val e164 = PhoneNumbers.keepIfE164(norm)
                    ?: PhoneNumbers.keepIfE164(
                        if (iNum >= 0) PhoneNumberUtils.formatNumberToE164(c.getString(iNum), simRegion) else null)
                if (e164 != null) out.add(e164)
            }
        }
        return PhoneNumbers.dedupSorted(out)
    }
}
