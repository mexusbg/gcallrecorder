package io.github.mexus.gcallrecorder

object PhoneNumbers {
    /** Accept only numbers already in +country form; strip spaces/dashes/parens. Return null otherwise. */
    fun keepIfE164(candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        val trimmed = candidate.trim()
        if (!trimmed.startsWith("+")) return null
        val cleaned = "+" + trimmed.substring(1).filter { it.isDigit() }
        return if (cleaned.length in 5..17) cleaned else null
    }

    fun dedupSorted(nums: Collection<String>): List<String> = nums.toSortedSet().toList()
}
