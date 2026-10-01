package vn.phonenumberscan

/** Extracts plausible Vietnamese and international telephone numbers from OCR text. */
object PhoneNumberExtractor {
    private val candidateRegex = Regex(
        pattern = "(?<![\\p{L}\\d])(?:\\+?\\d[\\d\\s().-]{6,}\\d)(?![\\p{L}\\d])"
    )

    fun extract(text: String): List<String> = candidateRegex.findAll(text)
        .mapNotNull { match -> normalize(match.value) }
        .distinct()
        .toList()

    internal fun normalize(candidate: String): String? {
        val trimmed = candidate.trim()
        val hasLeadingPlus = trimmed.startsWith('+')
        val digits = trimmed.filter(Char::isDigit)

        // E.164 permits at most 15 digits. Seven digits keeps local numbers useful.
        if (digits.length !in 7..15) return null
        if (digits.toSet().size == 1) return null

        val normalized = when {
            hasLeadingPlus -> "+$digits"
            // OCR often reads a Vietnamese international number without the plus sign.
            digits.startsWith("84") && digits.length in 10..11 -> "+$digits"
            else -> digits
        }

        return if (isPlausible(normalized)) normalized else null
    }

    private fun isPlausible(number: String): Boolean {
        val digits = number.removePrefix("+")
        if (number.startsWith("+")) return digits.length in 8..15 && digits.first() != '0'

        // Vietnamese mobile/landline/toll-free numbers and generic local numbers.
        if (digits.startsWith('0')) return digits.length in 9..11
        return digits.length in 7..11
    }
}

