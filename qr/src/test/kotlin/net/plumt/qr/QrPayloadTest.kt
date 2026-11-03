package net.plumt.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 형식별 생성·해석 왕복. */
class QrPayloadTest {

    private fun roundTrip(payload: QrPayload) = assertEquals(payload, QrPayload.parse(payload.encode()))

    @Test
    fun `URL 은 스킴이 없으면 https 를 붙인다`() {
        assertEquals("https://example.com", QrPayload.Url("example.com").encode())
        assertEquals("http://a.b", QrPayload.Url("http://a.b").encode())
        roundTrip(QrPayload.Url("https://example.com/path?q=1&r=%20"))
    }

    @Test
    fun `Wi-Fi 특수문자를 이스케이프하고 되읽는다`() {
        val wifi = QrPayload.Wifi("My;Net:5G", "p\\a,ss\"", WifiSecurity.WPA)
        assertEquals("WIFI:T:WPA;S:My\\;Net\\:5G;P:p\\\\a\\,ss\\\";;", wifi.encode())
        roundTrip(wifi)
    }

    @Test
    fun `Wi-Fi 의 모든 특수문자 조합 왕복`() {
        listOf(";", ",", ":", "\\", "\"", "a;b,c:d\\e\"f", "\\;", ";;", "끝\\", "\"quoted\"", "와이파이 5G").forEach { s ->
            roundTrip(QrPayload.Wifi(ssid = s, password = "p$s"))
        }
    }

    @Test
    fun `비밀번호 없는 Wi-Fi 는 P 필드를 쓰지 않는다`() {
        val wifi = QrPayload.Wifi(ssid = "Cafe", security = WifiSecurity.NONE)
        assertEquals("WIFI:T:nopass;S:Cafe;;", wifi.encode())
        roundTrip(wifi)
    }

    @Test
    fun `숨김 WEP·WPA3 Wi-Fi 왕복`() {
        roundTrip(QrPayload.Wifi("Hidden", "pw", WifiSecurity.WEP, hidden = true))
        roundTrip(QrPayload.Wifi("Home6E", "pw123456", WifiSecurity.WPA3))
    }

    @Test
    fun `WPA2-EAP 필드 왕복`() {
        val wifi = QrPayload.Wifi(
            ssid = "corp",
            password = "p;w",
            security = WifiSecurity.WPA2_EAP,
            eapMethod = "PEAP",
            identity = "user@example.com",
            anonymousIdentity = "anon@example.com",
            phase2Method = "MSCHAPV2",
        )
        assertEquals(
            "WIFI:T:WPA2-EAP;S:corp;P:p\\;w;E:PEAP;A:anon@example.com;I:user@example.com;PH2:MSCHAPV2;;",
            wifi.encode(),
        )
        roundTrip(wifi)
    }

    @Test
    fun `EAP 가 아닌 망은 EAP 필드를 쓰지 않는다`() {
        val encoded = QrPayload.Wifi("home", "pw", WifiSecurity.WPA, eapMethod = "PEAP").encode()
        assertFalse(encoded.contains("E:"))
    }

    @Test
    fun `WPA2 토큰은 WPA 로, SAE 는 WPA3 로 읽는다`() {
        val parsed = QrPayload.parse("WIFI:S:Home;T:WPA2;P:secret;;") as QrPayload.Wifi
        assertEquals(WifiSecurity.WPA, parsed.security)
        assertEquals("secret", parsed.password)
        assertEquals(WifiSecurity.WPA3, (QrPayload.parse("WIFI:S:Home;T:SAE;P:secret;;") as QrPayload.Wifi).security)
    }

    @Test
    fun `Wi-Fi toString 은 비밀번호를 가린다`() {
        assertFalse(QrPayload.Wifi("home", "hunter22").toString().contains("hunter22"))
    }

    @Test
    fun `연락처 왕복`() {
        roundTrip(QrPayload.Contact("홍길동", phones = listOf(ContactPhone("010-1234-5678")), emails = listOf("a@example.com")))
    }

    @Test
    fun `모든 필드를 채운 연락처의 vCard 왕복`() {
        roundTrip(
            QrPayload.Contact(
                name = "김 \"민준\"; 테스트, 이름\\",
                organization = "예시 주식회사; 개발팀",
                title = "엔지니어, 시니어",
                phones = listOf(
                    ContactPhone("010-1234-5678", PhoneType.MOBILE),
                    ContactPhone("02-123-4567", PhoneType.WORK),
                    ContactPhone("031-000-0000", PhoneType.HOME),
                    ContactPhone("02-123-4568", PhoneType.FAX),
                    ContactPhone("+82 70 0000 0000", PhoneType.OTHER),
                ),
                emails = listOf("a@example.com", "b@example.com"),
                address = "서울특별시 중구 세종대로 110; 3층",
                url = "https://example.com/a,b;c",
                note = "첫 줄\n둘째 줄: 콜론, 쉼표; 세미콜론 \\ 역슬래시",
            ),
        )
    }

    @Test
    fun `MECARD 로 쓰고 되읽는다 — 직함과 전화 종류는 빠진다`() {
        val contact = QrPayload.Contact(
            name = "Doe, Jane",
            organization = "Org;Inc",
            title = "버려지는 직함",
            phones = listOf(ContactPhone("010", PhoneType.MOBILE), ContactPhone("02")),
            emails = listOf("a@example.com"),
            address = "서울: 중구",
            url = "https://example.com",
            note = "메모\\끝",
        )
        val encoded = contact.encodeMeCard()
        assertTrue(encoded, encoded.startsWith("MECARD:N:Doe\\, Jane;"))
        assertEquals(
            contact.copy(title = "", phones = listOf(ContactPhone("010"), ContactPhone("02"))),
            QrPayload.parse(encoded),
        )
    }

    @Test
    fun `vCard 매개변수 붙은 TEL 과 N 만 있는 이름을 읽는다`() {
        val vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:홍;길동;;;\r\nTEL;TYPE=CELL:010\r\nEND:VCARD"
        assertEquals(
            QrPayload.Contact("길동 홍", phones = listOf(ContactPhone("010", PhoneType.MOBILE))),
            QrPayload.parse(vcard),
        )
    }

    @Test
    fun `한 줄로 뭉개진 vCard 도 읽는다`() {
        assertEquals(
            QrPayload.Contact("김민준", phones = listOf(ContactPhone("010-1234-5678"))),
            QrPayload.parse("BEGIN:VCARD VERSION:3.0 FN:김민준 TEL:010-1234-5678 END:VCARD"),
        )
    }

    @Test
    fun `MECARD 를 연락처로 읽는다`() {
        assertEquals(
            QrPayload.Contact("김민준", phones = listOf(ContactPhone("01012345678")), emails = listOf("jane@example.org")),
            QrPayload.parse("MECARD:N:김민준;TEL:01012345678;EMAIL:jane@example.org;;"),
        )
    }

    @Test
    fun `이메일 제목·본문 왕복`() {
        roundTrip(QrPayload.Email("a@example.com", "안녕 하세요", "본문&끝"))
        roundTrip(QrPayload.Email("a+tag@example.com", "1+1=2 ?", "줄\n바꿈 %20 그대로"))
        roundTrip(QrPayload.Email("a@example.com"))
    }

    @Test
    fun `전화·문자 왕복`() {
        roundTrip(QrPayload.Phone("010-1234-5678"))
        roundTrip(QrPayload.Phone("+82-10-1234-5678"))
        roundTrip(QrPayload.Sms("010", "hi"))
        roundTrip(QrPayload.Sms("010", "본문: 콜론\n줄바꿈"))
        roundTrip(QrPayload.Sms("010"))
    }

    @Test
    fun `sms 의 body 쿼리와 mmsto 를 읽는다`() {
        assertEquals(QrPayload.Sms("+821012345678", "안녕 하세요"), QrPayload.parse("sms:+821012345678?body=%EC%95%88%EB%85%95+%ED%95%98%EC%84%B8%EC%9A%94"))
        assertEquals(QrPayload.Sms("010", "hi"), QrPayload.parse("MMSTO:010:hi"))
    }

    @Test
    fun `위치 왕복 — 고도·검색어 포함`() {
        roundTrip(QrPayload.Geo(37.5, 127.0))
        roundTrip(QrPayload.Geo(-33.8688, 151.2093, altitude = 58.0))
        roundTrip(QrPayload.Geo(90.0, -180.0, query = "남극? & 북극 = 끝+"))
        roundTrip(QrPayload.Geo(0.00001, 0.00002))
        assertEquals("geo:0.000010,0.000020", QrPayload.Geo(0.00001, 0.00002).encode())
    }

    @Test
    fun `geo 의 매개변수와 q 를 읽는다`() {
        assertEquals(QrPayload.Geo(37.786971, -122.399677), QrPayload.parse("geo:37.786971,-122.399677;u=35"))
        assertEquals(QrPayload.Geo(0.0, 0.0, query = "1600 Amphitheatre Parkway"), QrPayload.parse("geo:0,0?q=1600+Amphitheatre+Parkway"))
        assertEquals(QrPayload.Text("geo:91,0"), QrPayload.parse("geo:91,0"))
        assertEquals(QrPayload.Text("geo:abc"), QrPayload.parse("geo:abc"))
    }

    @Test
    fun `일정 왕복 — 종일·시간대 없음·TZID·UTC`() {
        val seoul = ZoneId.of("Asia/Seoul")
        roundTrip(QrPayload.Event("제품 리뷰", EventTime.Local(LocalDateTime.of(2026, 10, 23, 14, 0)), location = "3층 회의실"))
        roundTrip(
            QrPayload.Event(
                title = "회의, 분기; 결산 \\ 1차",
                start = EventTime.Local(LocalDateTime.of(2026, 11, 5, 10, 0, 30), seoul),
                end = EventTime.Local(LocalDateTime.of(2026, 11, 5, 11, 30), seoul),
                location = "본사\n3층",
                description = "안건: 1, 2; 3",
            ),
        )
        roundTrip(QrPayload.Event("휴가", EventTime.AllDay(LocalDate.of(2026, 11, 5)), EventTime.AllDay(LocalDate.of(2026, 11, 8))))
        roundTrip(QrPayload.Event("출시", EventTime.Utc(Instant.parse("2026-11-05T01:00:00Z")), EventTime.Utc(Instant.parse("2026-11-05T02:00:00Z"))))
        roundTrip(QrPayload.Event("제목만"))
    }

    @Test
    fun `일정 시각 표기`() {
        val event = QrPayload.Event(
            "x",
            EventTime.AllDay(LocalDate.of(2026, 11, 5)),
            EventTime.Local(LocalDateTime.of(2026, 11, 6, 9, 0), ZoneId.of("Asia/Seoul")),
        )
        assertEquals(
            "BEGIN:VEVENT\nSUMMARY:x\nDTSTART;VALUE=DATE:20261105\nDTEND;TZID=Asia/Seoul:20261106T090000\nEND:VEVENT",
            event.encode(),
        )
        assertTrue(QrPayload.Event("x", EventTime.Utc(Instant.parse("2026-11-05T01:02:03.999Z"))).encode().contains("DTSTART:20261105T010203Z"))
    }

    @Test
    fun `한 줄로 뭉개진 VEVENT 를 읽는다`() {
        assertEquals(
            QrPayload.Event("제품 리뷰", EventTime.Local(LocalDateTime.of(2026, 10, 23, 14, 0)), location = "3층 회의실"),
            QrPayload.parse("BEGIN:VEVENT SUMMARY:제품 리뷰 DTSTART:20261023T140000 LOCATION:3층 회의실 END:VEVENT"),
        )
    }

    @Test
    fun `OTP 왕복`() {
        roundTrip(QrPayload.Otp("alice@example.com", "JBSWY3DPEHPK3PXP", issuer = "Example"))
        roundTrip(QrPayload.Otp("홍길동 계정", "JBSWY3DPEHPK3PXP====", issuer = "예시 회사 & Co"))
        roundTrip(QrPayload.Otp("bob", "jbswy3dpehpk3pxp", algorithm = OtpAlgorithm.SHA512, digits = 8, period = 60))
        roundTrip(QrPayload.Otp("carol", "JBSWY3DPEHPK3PXP", issuer = "X", type = OtpType.HOTP, counter = 42))
    }

    @Test
    fun `OTP 기본값은 문자열에 쓰지 않는다`() {
        assertEquals(
            "otpauth://totp/Example:alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example",
            QrPayload.Otp("alice@example.com", "JBSWY3DPEHPK3PXP", issuer = "Example").encode(),
        )
        assertFalse(QrPayload.Otp("a", "JBSWY3DPEHPK3PXP").toString().contains("JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun `모르는 형식과 깨진 Wi-Fi 는 원문 텍스트다`() {
        assertEquals(QrPayload.Text("hello"), QrPayload.parse("hello"))
        assertEquals(QrPayload.Text("WIFI:T:WPA;;"), QrPayload.parse("WIFI:T:WPA;;"))
        assertEquals(QrPayload.Text("WIFI:S:;T:WPA;P:x;;"), QrPayload.parse("WIFI:S:;T:WPA;P:x;;"))
        assertEquals(QrPayload.Text("a@example.com"), QrPayload.parse("a@example.com"))
        assertEquals(QrPayload.Text("010-1234-5678"), QrPayload.parse("010-1234-5678"))
        assertEquals(QrPayload.Text("kakaotalk://open"), QrPayload.parse("kakaotalk://open"))
        assertEquals(QrPayload.Text(""), QrPayload.parse(""))
    }

    @Test
    fun `원문 텍스트는 앞뒤 공백까지 그대로 둔다`() {
        assertEquals(QrPayload.Text("  두 칸 \n"), QrPayload.parse("  두 칸 \n"))
        roundTrip(QrPayload.Text("여러 줄\n텍스트\t탭"))
    }

    @Test
    fun `접두사는 대소문자와 앞뒤 공백을 가리지 않는다`() {
        assertEquals(QrPayload.Url("HTTPS://EXAMPLE.COM"), QrPayload.parse("  HTTPS://EXAMPLE.COM \n"))
        assertEquals(QrPayload.Phone("010"), QrPayload.parse("TEL:010"))
        assertEquals(QrPayload.Wifi("a", "b"), QrPayload.parse(" wifi:s:a;p:b;; "))
    }

    @Test
    fun `상품 형식을 함께 받으면 상품 번호로 풀고 그 밖의 형식은 내용 판별과 같다`() {
        assertEquals(QrPayload.Product("8801234567893"), QrPayload.parse(" 8801234567893\n", CodeFormat.EAN_13))
        assertEquals(QrPayload.Product("012345678905"), QrPayload.parse("012345678905", CodeFormat.UPC_A))
        assertEquals(QrPayload.parse("tel:010"), QrPayload.parse("tel:010", CodeFormat.QR_CODE))
        assertEquals(QrPayload.Text("12345678"), QrPayload.parse("12345678", CodeFormat.CODE_128))
        assertEquals("8801234567893", QrPayload.Product("8801234567893").encode())
    }
}
