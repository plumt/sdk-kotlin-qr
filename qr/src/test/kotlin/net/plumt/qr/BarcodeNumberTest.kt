package net.plumt.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeNumberTest {

    @Test
    fun `알려진 번호의 종류를 판별한다`() {
        assertEquals(BarcodeNumberType.EAN_13, BarcodeNumber.detect("4006381333931"))
        assertEquals(BarcodeNumberType.EAN_13, BarcodeNumber.detect("5901234123457"))
        assertEquals(BarcodeNumberType.EAN_8, BarcodeNumber.detect("96385074"))
        assertEquals(BarcodeNumberType.UPC_A, BarcodeNumber.detect("036000291452"))
        assertEquals(BarcodeNumberType.UPC_A, BarcodeNumber.detect("012345678905"))
        assertEquals(BarcodeNumberType.UPC_E, BarcodeNumber.detect("04252614"))
        assertEquals(BarcodeNumberType.ITF_14, BarcodeNumber.detect("14006381333938"))
        assertEquals(BarcodeNumberType.ISBN_13, BarcodeNumber.detect("9780306406157"))
        assertEquals(BarcodeNumberType.ISBN_13, BarcodeNumber.detect("978-0-306-40615-7"))
        assertEquals(BarcodeNumberType.ISBN_10, BarcodeNumber.detect("0-306-40615-2"))
        assertEquals(BarcodeNumberType.ISBN_10, BarcodeNumber.detect("080442957X"))
        assertEquals(BarcodeNumberType.ISBN_10, BarcodeNumber.detect("080442957x"))
    }

    @Test
    fun `체크 숫자가 틀리거나 형식이 아니면 null·false`() {
        assertNull(BarcodeNumber.detect("4006381333932"))
        assertNull(BarcodeNumber.detect("96385075"))
        assertNull(BarcodeNumber.detect("12345"))
        assertNull(BarcodeNumber.detect(""))
        assertNull(BarcodeNumber.detect("abcdefghijklm"))
        assertNull(BarcodeNumber.detect("030640615X"))
        assertFalse(BarcodeNumber.isValid("4006381333931", BarcodeNumberType.ISBN_13))
        assertTrue(BarcodeNumber.isValid("9780306406157", BarcodeNumberType.EAN_13))
        assertFalse(BarcodeNumber.isValid("24252614", BarcodeNumberType.UPC_E))
        assertFalse(BarcodeNumber.isValid("X306406152", BarcodeNumberType.ISBN_10))
    }

    @Test
    fun `체크 숫자를 계산한다`() {
        assertEquals('1', BarcodeNumber.checkDigit("400638133393", BarcodeNumberType.EAN_13))
        assertEquals('4', BarcodeNumber.checkDigit("9638507", BarcodeNumberType.EAN_8))
        assertEquals('2', BarcodeNumber.checkDigit("03600029145", BarcodeNumberType.UPC_A))
        assertEquals('4', BarcodeNumber.checkDigit("0425261", BarcodeNumberType.UPC_E))
        assertEquals('8', BarcodeNumber.checkDigit("1400638133393", BarcodeNumberType.ITF_14))
        assertEquals('2', BarcodeNumber.checkDigit("030640615", BarcodeNumberType.ISBN_10))
        assertEquals('X', BarcodeNumber.checkDigit("080442957", BarcodeNumberType.ISBN_10))
        assertEquals("9780306406157", BarcodeNumber.withCheckDigit("978-0-306-40615", BarcodeNumberType.ISBN_13))
    }

    @Test
    fun `모든 체크 숫자 계산 결과는 검증을 통과한다`() {
        val random = kotlin.random.Random(1)
        BarcodeNumberType.entries.forEach { type ->
            repeat(200) {
                val body = buildString {
                    when (type) {
                        BarcodeNumberType.ISBN_13 -> append(if (random.nextBoolean()) "978" else "979")
                        BarcodeNumberType.UPC_E -> append(random.nextInt(2))
                        else -> Unit
                    }
                    while (length < type.length - 1) append(random.nextInt(10))
                }
                val full = BarcodeNumber.withCheckDigit(body, type)
                assertTrue("$type $full", BarcodeNumber.isValid(full, type))
            }
        }
    }

    @Test
    fun `변환`() {
        assertEquals("042100005264", BarcodeNumber.upcEToUpcA("04252614"))
        assertEquals("012345000058", BarcodeNumber.upcEToUpcA("01234558"))
        assertEquals("0036000291452", BarcodeNumber.upcAToEan13("036000291452"))
        assertEquals("036000291452", BarcodeNumber.ean13ToUpcA("0036000291452"))
        assertEquals("9780306406157", BarcodeNumber.isbn10ToIsbn13("0306406152"))
        assertEquals("9780804429573", BarcodeNumber.isbn10ToIsbn13("080442957X"))
        assertEquals("0306406152", BarcodeNumber.isbn13ToIsbn10("9780306406157"))
        assertEquals("080442957X", BarcodeNumber.isbn13ToIsbn10("978-0-8044-2957-3"))
    }

    @Test
    fun `UPC-E 펼침의 마지막 자리 규칙 전부`() {
        // 마지막 자리 0·1·2 / 3 / 4 / 5~9 의 네 갈래
        mapOf(
            "0123450" to "01200000345", "0123451" to "01210000345", "0123452" to "01220000345",
            "0123453" to "01230000045", "0123454" to "01234000005", "0123455" to "01234500005", "0123459" to "01234500009",
        ).forEach { (body, upcABody) ->
            val upcE = BarcodeNumber.withCheckDigit(body, BarcodeNumberType.UPC_E)
            assertEquals(BarcodeNumber.withCheckDigit(upcABody, BarcodeNumberType.UPC_A), BarcodeNumber.upcEToUpcA(upcE))
        }
    }

    @Test
    fun `계산·변환은 틀린 입력에 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { BarcodeNumber.checkDigit("12345", BarcodeNumberType.EAN_13) }
        assertThrows(IllegalArgumentException::class.java) { BarcodeNumber.checkDigit("40063813339a", BarcodeNumberType.EAN_13) }
        assertThrows(IllegalArgumentException::class.java) { BarcodeNumber.checkDigit("2123456", BarcodeNumberType.UPC_E) }
        assertThrows(IllegalArgumentException::class.java) { BarcodeNumber.checkDigit("123456789012", BarcodeNumberType.ISBN_13) }
        assertThrows(IllegalArgumentException::class.java) { BarcodeNumber.upcEToUpcA("04252615") }
        assertThrows(IllegalArgumentException::class.java) { BarcodeNumber.ean13ToUpcA("4006381333931") }
        assertThrows(IllegalArgumentException::class.java) { BarcodeNumber.isbn10ToIsbn13("0306406153") }
        val isbn979 = BarcodeNumber.withCheckDigit("979109063607", BarcodeNumberType.ISBN_13)
        assertThrows(IllegalArgumentException::class.java) { BarcodeNumber.isbn13ToIsbn10(isbn979) }
    }

    @Test
    fun `번호 종류는 담는 바코드 형식을 안다`() {
        assertEquals(CodeFormat.EAN_13, BarcodeNumberType.ISBN_13.format)
        assertEquals(CodeFormat.ITF, BarcodeNumberType.ITF_14.format)
        assertNull(BarcodeNumberType.ISBN_10.format)
    }
}
