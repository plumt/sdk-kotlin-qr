package net.plumt.qr.scanner

import net.plumt.qr.CodeFormat
import net.plumt.qr.QrPayload
import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.action.QrActions
import net.plumt.qr.scanner.action.UrlWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

/**
 * 정규식 `\d`·`\s` 는 Android 에서 유니코드 숫자·공백까지 맞춰 JVM 과 결과가 갈렸다. ASCII 명시 클래스로 바꾼 뒤
 * 유니코드 숫자('٣' 아랍-인도, '８' 전각)·공백(U+3000·NBSP·U+2028)이 들어올 때의 동작을 고정한다.
 */
@RunWith(RobolectricTestRunner::class)
class UnicodeClassTest {

    private val zone = ZoneId.of("UTC")

    private fun summary(text: String) =
        ScannerDefaults.summary(ScanResult(text, CodeFormat.QR_CODE, QrPayload.Text(text), emptyList()), zone)

    @Test
    fun `콜론 뒤 ASCII 숫자만 포트다 — 유니코드 숫자는 스킴으로 읽어 웹 주소로 열지 않는다`() {
        assertNull(QrActions.schemeOf("example.com:8080/x"))
        assertEquals("example.com", QrActions.schemeOf("example.com:８０８０/x"))
        assertEquals("localhost", QrActions.schemeOf("localhost:٣"))
        assertTrue(UrlWarning.NON_WEB_SCHEME in QrActions.openUrl("example.com:８０８０/x").warnings)
        assertTrue(UrlWarning.NON_WEB_SCHEME !in QrActions.openUrl("example.com:8080/x").warnings)
    }

    @Test
    fun `요약은 줄 구분자만 접고 전각·고정 공백은 내용으로 둔다`() {
        assertEquals("a b", summary("a b"))
        assertEquals("a b", summary("a\u0085 b"))
        assertEquals("a　b", summary("a　b"))
        assertEquals("a b", summary("a b"))
        assertEquals("a b", summary("a \t\u000B\u000C b"))
    }
}
