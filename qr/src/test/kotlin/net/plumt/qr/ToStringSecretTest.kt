package net.plumt.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 로그에 찍히는 toString 이 비밀번호·OTP 비밀·주소의 비밀번호를 담지 않는다. */
class ToStringSecretTest {

    private val secrets = listOf("supersecret123", "JBSWY3DPEHPK3PXP", "hunter2pw")

    private fun assertClean(s: String) = secrets.forEach { assertFalse(s, s.contains(it)) }

    @Test
    fun scanResult_omitsRawText() {
        for (raw in listOf(
            "WIFI:T:WPA;S:Cafe;P:supersecret123;;",
            "WIFI:T:WPA;S:;P:supersecret123;;",           // 이름 없는 Wi-Fi — 구조로 못 읽어 Text
            "otpauth://totp/Ex:me?secret=JBSWY3DPEHPK3PXP&issuer=Ex",
            "otpauth://totp/?secret=JBSWY3DPEHPK3PXP",       // 계정 없음 — Text
            "https://me:hunter2pw@example.com/x",
        )) {
            val r = ScanResult(raw, CodeFormat.QR_CODE, QrPayload.parse(raw), listOf(ScanPoint(1f, 2f)))
            val s = r.toString()
            assertClean(s)
            assertTrue(s, s.startsWith("ScanResult(format=QR_CODE, payload="))
            assertTrue(s, s.contains("length=${raw.length}"))
            assertTrue(s, s.contains("ScanPoint(x=1.0, y=2.0)"))
        }
    }

    @Test
    fun text_masksSecretBearingRaw_keepsOrdinaryText() {
        assertEquals("Text(text=****, length=13)", QrPayload.Text(" wifi:P:abc;;").toString())
        assertEquals("Text(text=****, length=19)", QrPayload.Text("OTPAUTH-migration:x").toString())
        assertEquals("Text(text=hello)", QrPayload.Text("hello").toString())
        assertEquals("Text(text=)", QrPayload.Text("").toString())
        assertClean(QrPayload.parse("otpauth://totp/?secret=JBSWY3DPEHPK3PXP").toString())
        // 비교·해시는 원문 그대로다
        assertEquals(QrPayload.Text("WIFI:x"), QrPayload.Text("WIFI:x"))
    }

    @Test
    fun url_masksUserInfo() {
        assertEquals("Url(url=https://****@example.com/x)", QrPayload.Url("https://me:hunter2pw@example.com/x").toString())
        assertEquals("Url(url=https://example.com/a@b)", QrPayload.Url("https://example.com/a@b").toString())
        assertEquals("Url(url=http://example.com)", QrPayload.Url("http://example.com").toString())
        assertEquals("Url(url=example.com)", QrPayload.Url("example.com").toString())
        assertEquals("https://me:hunter2pw@example.com/x", QrPayload.Url("https://me:hunter2pw@example.com/x").url)
    }

    @Test
    fun wifiOtpMatrix_alreadyClean() {
        assertClean(QrPayload.Wifi("Cafe", "supersecret123").toString())
        assertClean(QrPayload.Otp("me", "JBSWY3DPEHPK3PXP").toString())
        assertEquals("CodeMatrix(21x21)", CodeMatrix(21, 21, BooleanArray(441)).toString())
    }
}
