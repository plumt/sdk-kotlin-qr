package net.plumt.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class QrPayloadSummaryTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun `와이파이는 네트워크 이름만 보이고 비밀번호는 빠진다`() {
        val summary = QrPayload.summarize("WIFI:T:WPA;S:AcmeTest;P:test1234;;")
        assertEquals("AcmeTest", summary)
        assertFalse(summary.contains("test1234"))
    }

    @Test
    fun `연락처는 이름을 보인다`() {
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nN:테스트;예시\nFN:예시 테스트\nTEL:010-0000-1234\nEND:VCARD"
        assertEquals("예시 테스트", QrPayload.summarize(vcard))
    }

    @Test
    fun `이름 없는 연락처는 소속·전화·이메일 순으로 보인다`() {
        assertEquals("010-1", QrPayload.Contact(phones = listOf(ContactPhone("010-1"))).summary())
        assertEquals("Org", QrPayload.Contact(organization = "Org", phones = listOf(ContactPhone("010-1"))).summary())
        assertEquals("a@example.com", QrPayload.Contact(emails = listOf("a@example.com")).summary())
    }

    @Test
    fun `일정은 제목과 시작 시각을 보인다`() {
        val event = "BEGIN:VEVENT\nSUMMARY:회의\nDTSTART:20261105T100000\nEND:VEVENT"
        assertEquals("회의 · 2026.11.05 10:00", QrPayload.summarize(event, seoul))
    }

    @Test
    fun `종일 일정은 날짜만 보인다`() {
        assertEquals("휴가 · 2026.11.05", QrPayload.Event("휴가", EventTime.AllDay(LocalDate.of(2026, 11, 5))).summary(seoul))
    }

    @Test
    fun `UTC·TZID 일정 시각은 요청한 시간대로 보이고 시간대 없는 시각은 그대로 보인다`() {
        assertEquals("x · 2026.11.05 10:00", QrPayload.Event("x", EventTime.Utc(Instant.parse("2026-11-05T01:00:00Z"))).summary(seoul))
        assertEquals("x · 2026.11.05 01:00", QrPayload.Event("x", EventTime.Local(LocalDateTime.of(2026, 11, 5, 1, 0))).summary(seoul))
        val newYork = EventTime.Local(LocalDateTime.of(2026, 11, 5, 9, 0), ZoneId.of("America/New_York"))
        assertEquals("x · 2026.11.05 23:00", QrPayload.Event("x", newYork).summary(seoul))
    }

    @Test
    fun `여러 줄 텍스트는 첫 줄만 보인다`() {
        assertEquals("첫 줄", QrPayload.summarize("첫 줄\n둘째 줄"))
        assertEquals("첫 줄", QrPayload.summarize("\n  첫 줄  \n둘째 줄"))
    }

    @Test
    fun `링크와 전화는 그대로 보인다`() {
        assertEquals("https://example.com", QrPayload.summarize("https://example.com"))
        assertEquals("010-1234", QrPayload.summarize("tel:010-1234"))
    }

    @Test
    fun `보일 값이 비면 원문 첫 줄로 돌아간다`() {
        assertEquals("BEGIN:VCARD", QrPayload.summarize("BEGIN:VCARD\nVERSION:3.0\nEND:VCARD"))
    }

    @Test
    fun `이름 없는 와이파이도 비밀번호를 보이지 않는다`() {
        listOf("WIFI:S:;T:WPA;P:secret99;;", "WIFI:S: ;T:WPA;P:secret99;;", "WIFI:T:WPA;P:secret99;;", "wifi:P:secret99").forEach {
            val summary = QrPayload.summarize(it)
            assertFalse(it, summary.contains("secret99"))
            assertEquals("Wi-Fi", summary)
        }
    }

    @Test
    fun `OTP 는 계정 이름만 보이고 비밀은 빠진다`() {
        val summary = QrPayload.summarize("otpauth://totp/Acme:me%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Acme")
        assertEquals("Acme:me@example.com", summary)
        assertEquals("OTP", QrPayload.summarize("otpauth://totp?secret=JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun `구조로 못 읽은 OTP 원문도 비밀을 보이지 않는다`() {
        listOf(
            "otpauth://totp/Acme:bob?secret=NOT-BASE32-1&issuer=Acme",
            "otpauth://weird/x?secret=SEEDSEED",
            "otpauth-migration://offline?data=CjEKCkhlbGxvId6tvu8SBGJvYhoEQWNtZSABKAEwAhABGAEgAA%3D%3D",
            "OTPAUTH://TOTP/?secret=SEEDSEED",
        ).forEach {
            val summary = QrPayload.summarize(it)
            assertFalse(it, summary.contains("secret", ignoreCase = true) || summary.contains("SEED") || summary.contains("data="))
        }
        assertEquals("Acme:bob", QrPayload.summarize("otpauth://totp/Acme:bob?secret=NOT-BASE32-1&issuer=Acme"))
    }

    @Test
    fun `종류가 빠진 와이파이는 비밀번호가 있으면 WPA 로 본다`() {
        val wifi = QrPayload.parse("WIFI:S:home;P:pw123456;;") as QrPayload.Wifi
        assertEquals(WifiSecurity.WPA, wifi.security)
        assertEquals(WifiSecurity.NONE, (QrPayload.parse("WIFI:S:cafe;;") as QrPayload.Wifi).security)
    }

    @Test
    fun `위치는 검색어가 있으면 검색어를 보인다`() {
        assertEquals("37.5, 127.0", QrPayload.Geo(37.5, 127.0).summary())
        assertEquals("서울역", QrPayload.Geo(37.5, 127.0, query = "서울역").summary())
    }
}
