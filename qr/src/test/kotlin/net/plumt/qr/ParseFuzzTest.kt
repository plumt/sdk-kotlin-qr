package net.plumt.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneOffset
import kotlin.random.Random

/** 아무 문자열이나 넣어도 parse·summary 는 던지지 않고, parse 가 준 구조화된 값은 언제나 다시 쓸 수 있다. */
class ParseFuzzTest {

    private val prefixes = listOf(
        "", "WIFI:", "wifi:S:", "BEGIN:VCARD\n", "BEGIN:VCARD ", "MECARD:", "MECARD:N:", "BEGIN:VEVENT\n", "BEGIN:VCALENDAR\n",
        "mailto:", "MATMSG:", "SMTP:", "tel:", "sms:", "smsto:", "mmsto:", "geo:", "otpauth://", "otpauth://totp/",
        "otpauth://hotp/", "http://", "https://",
    )
    private val alphabet = "aZ09:;,\\\"'=?&%+-./ \n\r\t#@:()한글\u0000\uD83D\uDE00\uD800ETNSPHIATYEFCLDRGVUOB"

    private fun check(input: String) {
        val payload = try {
            QrPayload.parse(input)
        } catch (e: Throwable) {
            fail("parse 가 던졌다: ${escape(input)} → $e")
            return
        }
        if (payload !is QrPayload.Text) {
            val encoded = try {
                payload.encode()
            } catch (e: Throwable) {
                fail("parse 결과를 쓸 수 없다: ${escape(input)} → $payload → $e")
                return
            }
            assertEquals("다시 읽은 값이 같아야 한다: ${escape(encoded)}", payload, QrPayload.parse(encoded))
        } else {
            assertEquals(input, payload.text)
        }
        try {
            payload.summary(ZoneOffset.UTC)
            QrPayload.summarize(input, ZoneOffset.UTC)
            for (format in CodeFormat.entries) QrPayload.parse(input, format)
        } catch (e: Throwable) {
            fail("summary·parse(format) 가 던졌다: ${escape(input)} → $e")
        }
    }

    @Test
    fun `무작위 문자열`() {
        val random = Random(20261102)
        repeat(20_000) {
            val length = random.nextInt(0, 60)
            val body = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
            check(prefixes[random.nextInt(prefixes.size)] + body)
        }
    }

    @Test
    fun `올바른 인코딩을 자르고 바꾼 문자열`() {
        val samples = listOf(
            QrPayload.Wifi("a;b", "p:w", WifiSecurity.WPA2_EAP, eapMethod = "PEAP", identity = "i").encode(),
            QrPayload.Contact("이름", "Org", "T", listOf(ContactPhone("010", PhoneType.MOBILE)), listOf("a@b.c"), "주소", "https://x", "노트\n둘").encode(),
            QrPayload.Contact("이름", phones = listOf(ContactPhone("010"))).encodeMeCard(),
            QrPayload.Email("a@example.com", "제목", "본문").encode(),
            QrPayload.Sms("010", "hi?there").encode(),
            QrPayload.Geo(37.5, 127.0, 10.0, "q").encode(),
            QrPayload.Otp("acct", "JBSWY3DPEHPK3PXP", "Iss", OtpType.HOTP, OtpAlgorithm.SHA256, 8, 30, 5).encode(),
            "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:x\nDTSTART;TZID=Asia/Seoul:20261105T100000\nEND:VEVENT\nEND:VCALENDAR",
            "BEGIN:VCARD\nVERSION:2.1\nFN;ENCODING=QUOTED-PRINTABLE:=ED=99=\n=8D\nEND:VCARD",
        )
        val random = Random(7)
        samples.forEach { sample ->
            for (cut in 0..sample.length) check(sample.substring(0, cut))
            repeat(500) {
                val chars = sample.toCharArray()
                repeat(random.nextInt(1, 4)) { chars[random.nextInt(chars.size)] = alphabet[random.nextInt(alphabet.length)] }
                check(String(chars))
            }
        }
    }

    private fun escape(s: String) = s.replace("\n", "\\n").replace("\r", "\\r")
}
