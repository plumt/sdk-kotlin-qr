package net.plumt.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 맨 이메일·전화번호 추정(옵트인)과 저장된 형식 이름 되읽기. */
class ParseOptionsTest {

    private val email = ParseOptions(inferEmail = true)
    private val phone = ParseOptions(inferPhone = true)

    @Test
    fun `기본은 추정하지 않는다`() {
        assertEquals(QrPayload.Text("user@example.com"), QrPayload.parse("user@example.com"))
        assertEquals(QrPayload.Text("010-1234-5678"), QrPayload.parse("010-1234-5678"))
        assertEquals(QrPayload.Text("user@example.com"), QrPayload.parse("user@example.com", ParseOptions.DEFAULT))
        assertEquals(QrPayload.Text("010-1234-5678"), QrPayload.parse("010-1234-5678", ParseOptions()))
        assertEquals(ParseOptions(), ParseOptions.DEFAULT)
        assertEquals(ParseOptions(inferEmail = true, inferPhone = true), ParseOptions.INFER_ALL)
        // 하나만 켜면 그것만 추정한다
        assertEquals(QrPayload.Text("010-1234-5678"), QrPayload.parse("010-1234-5678", email))
        assertEquals(QrPayload.Text("user@example.com"), QrPayload.parse("user@example.com", phone))
    }

    @Test
    fun `맨 이메일 주소`() {
        listOf(
            "user@example.com", "a@example.org", "first.last+tag@MAIL.EXAMPLE.CO.KR", "x_y%z-1@A-B.EXAMPLE", "USER@EXAMPLE.COM",
            "1@example.net", "a".repeat(64) + "@example.com",
        ).forEach { assertEquals(it, QrPayload.Email(it), QrPayload.parse(it, email)) }
        // 앞뒤 공백은 무시하고 주소는 다듬은 값이다
        assertEquals(QrPayload.Email("user@example.com"), QrPayload.parse("  user@example.com\n", email))
        listOf(
            "", "@example.com", "user@", "user@example", "user@@example.com", "a@b@example.com", ".user@example.com",
            "user.@example.com", "us..er@example.com", "user@-EXAMPLE.COM", "user@EXAMPLE-.COM", "user@example.c",
            "user@example.c0m", "user@ex ample.com", "us er@example.com", "user@EXAMPLE..COM", "user@.EXAMPLE.COM",
            "사용자@example.com", "user@예시.com", "user@example.한국", "user!@example.com", "user@exam_ple.com",
            "a".repeat(65) + "@example.com", "u@" + "a".repeat(64) + ".com", "u@" + "a.".repeat(130) + "com",
            "mail user@example.com", "user@example.com 입니다",
        ).forEach { assertEquals(it, QrPayload.Text(it), QrPayload.parse(it, email)) }
    }

    @Test
    fun `맨 전화번호`() {
        listOf(
            "010-1234-5678", "01012345678", "+82 10-1234-5678", "+82-10-1234-5678", "(02) 123-4567", "1588-1234", "+1 (555) 010-9999",
            "1234567", "123456789012345", "02 123 4567",
        ).forEach { assertEquals(it, QrPayload.Phone(it), QrPayload.parse(it, phone)) }
        assertEquals(QrPayload.Phone("010-1234-5678"), QrPayload.parse(" 010-1234-5678 ", phone))
        listOf(
            "", "+", "123456", "1234567890123456", "112", "2024-01-15", "2024 01 15", "1999-12-31",
            "010.1234.5678", "01.23.45.67.89", "010-1234-5678-", "-010-1234", "++82 10 1234 5678", "+82+10", "010-1234-567a",
            "(02 123-4567", "02) 123-4567", "((02)) 1234567", "+(82) 10 1234 5678)", "０１０-１２３４-５６７８", "010—1234—5678",
            "1234567" + "-".repeat(26) + "8", "전화 010-1234-5678", "010-1234-5678 집",
        ).forEach { assertEquals(it, QrPayload.Text(it), QrPayload.parse(it, phone)) }
        // 날짜처럼 생겼어도 연·월·일 범위 밖이면 번호다
        assertEquals(QrPayload.Phone("2024-13-15"), QrPayload.parse("2024-13-15", phone))
        assertEquals(QrPayload.Phone("2024-01-32"), QrPayload.parse("2024-01-32", phone))
        assertEquals(QrPayload.Phone("1899-01-15"), QrPayload.parse("1899-01-15", phone))
        assertEquals(QrPayload.Phone("2024-1-155"), QrPayload.parse("2024-1-155", phone))
        // 괄호 뒤로 시작하는 번호(+ 다음 괄호)
        assertEquals(QrPayload.Phone("+(82) 10 1234 5678"), QrPayload.parse("+(82) 10 1234 5678", phone))
    }

    @Test
    fun `스킴이 있는 형식은 추정보다 먼저다`() {
        assertEquals(QrPayload.Phone("010-1234-5678"), QrPayload.parse("tel:010-1234-5678", ParseOptions.INFER_ALL))
        assertEquals(QrPayload.Email("a@example.com", "hi"), QrPayload.parse("mailto:a@example.com?subject=hi", ParseOptions.INFER_ALL))
        assertEquals(QrPayload.Url("https://user@example.com"), QrPayload.parse("https://user@example.com", ParseOptions.INFER_ALL))
        // 길이 상한을 넘는 원문은 추정하지 않는다
        val long = "1".repeat(QrPayload.MAX_PARSE_LENGTH + 1)
        assertEquals(QrPayload.Text(long), QrPayload.parse(long, ParseOptions.INFER_ALL))
    }

    @Test
    fun `형식을 아는 해석 — 1차원 바코드는 전화를 추정하지 않는다`() {
        val all = ParseOptions.INFER_ALL
        assertEquals(QrPayload.Text("01012345678"), QrPayload.parse("01012345678", CodeFormat.CODE_128, all))
        assertEquals(QrPayload.Text("01012345678"), QrPayload.parse("01012345678", CodeFormat.ITF, all))
        assertEquals(QrPayload.Phone("010-1234-5678"), QrPayload.parse("tel:010-1234-5678", CodeFormat.CODE_128, all))
        assertEquals(QrPayload.Email("a@example.com"), QrPayload.parse("a@example.com", CodeFormat.CODE_128, all))
        assertEquals(QrPayload.Phone("01012345678"), QrPayload.parse("01012345678", CodeFormat.QR_CODE, all))
        assertEquals(QrPayload.Phone("01012345678"), QrPayload.parse("01012345678", CodeFormat.DATA_MATRIX, all))
        assertEquals(QrPayload.Product("8801234567893"), QrPayload.parse(" 8801234567893 ", CodeFormat.EAN_13, all))
        // 빈 상품 번호는 Text
        assertEquals(QrPayload.Text(" "), QrPayload.parse(" ", CodeFormat.EAN_13, all))
        // 기본 설정이면 두 오버로드가 같다
        for (f in CodeFormat.entries) {
            for (t in listOf("hello", "4006381333931", "https://example.com", "")) {
                assertEquals("$f $t", QrPayload.parse(t, f), QrPayload.parse(t, f, ParseOptions.DEFAULT))
            }
        }
    }

    @Test
    fun `추정한 값도 다시 쓰고 읽으면 같다`() {
        listOf("user@example.com", "x_y%z-1@A-B.EXAMPLE", "+82 10-1234-5678", "(02) 123-4567").forEach { raw ->
            val p = QrPayload.parse(raw, ParseOptions.INFER_ALL)
            assertTrue(raw, p !is QrPayload.Text)
            assertEquals(raw, p, QrPayload.parse(p.encode()))
            assertEquals(raw, raw, p.summary())
        }
    }

    @Test
    fun `무작위 입력 — 던지지 않고 구조화된 값은 다시 쓸 수 있다`() {
        val random = Random(42)
        val alphabet = "a1@.+-_% ()0９٣ 가😀#?&"
        repeat(20_000) {
            val text = buildString { repeat(random.nextInt(40)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val p = QrPayload.parse(text, ParseOptions.INFER_ALL)
            if (p !is QrPayload.Text) assertEquals(text, p, QrPayload.parse(p.encode()))
            QrPayload.parse(text, CodeFormat.entries[random.nextInt(CodeFormat.entries.size)], ParseOptions.INFER_ALL)
        }
    }

    // ---- 형식 이름 ----

    @Test
    fun `저장된 형식 이름을 되읽는다`() {
        for (f in CodeFormat.entries) {
            assertSame(f, CodeFormat.fromName(f.name))
            assertSame(f, CodeFormat.fromName(f.name.lowercase()))
            assertSame(f, CodeFormat.fromName(f.name.replace("_", "")))
            assertSame(f, CodeFormat.fromName(f.name.replace('_', '-')))
        }
        assertSame(CodeFormat.PDF_417, CodeFormat.fromName("PDF417"))
        assertSame(CodeFormat.PDF_417, CodeFormat.fromName("pdf-417"))
        assertSame(CodeFormat.EAN_13, CodeFormat.fromName("ean13"))
        assertSame(CodeFormat.QR_CODE, CodeFormat.fromName(" qr code "))
        assertSame(CodeFormat.UPC_E, CodeFormat.fromName("Upc_E"))
        listOf("", "QR", "PDF41", "EAN_14", "UNKNOWN", "QR_CODE2", "ıtf", "ＩＴＦ", "QR.CODE").forEach { assertNull(it, CodeFormat.fromName(it)) }
    }
}
