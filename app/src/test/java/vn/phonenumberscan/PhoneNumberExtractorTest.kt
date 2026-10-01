package vn.phonenumberscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberExtractorTest {
    @Test
    fun extractsCommonVietnameseFormats() {
        val text = "Hotline: 090 123 4567 hoặc (028) 3822-5555"

        assertEquals(
            listOf("0901234567", "02838225555"),
            PhoneNumberExtractor.extract(text)
        )
    }

    @Test
    fun normalizesVietnamCountryCode() {
        assertEquals(
            listOf("+84901234567", "+84987654321"),
            PhoneNumberExtractor.extract("+84 90 123 4567 / 84-987-654-321")
        )
    }

    @Test
    fun rejectsDatesAndRepeatedNoise() {
        val result = PhoneNumberExtractor.extract("Ngày 21/09/2026 - mã 1111111111")

        assertTrue(result.isEmpty())
    }

    @Test
    fun removesDuplicatesAfterNormalization() {
        assertEquals(
            listOf("0901234567"),
            PhoneNumberExtractor.extract("090 123 4567 và 090-123-4567")
        )
    }
}

