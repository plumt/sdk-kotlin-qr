package net.plumt.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 실제 기기·앱·명세 예시가 내는 형식. */
class RealWorldPayloadTest {

    // ---- Wi-Fi ----

    @Test
    fun `휴대폰 카메라·설정 공유 형식의 Wi-Fi`() {
        // 필드 순서가 S 부터 시작하고 H:false 를 붙이는 형식
        assertEquals(QrPayload.Wifi("MyHome", "pass word!"), QrPayload.parse("WIFI:S:MyHome;T:WPA;P:pass word!;H:false;;"))
        // WPA3 전용 망 공유
        assertEquals(QrPayload.Wifi("Home6E", "pw12345678", WifiSecurity.WPA3), QrPayload.parse("WIFI:T:SAE;S:Home6E;P:pw12345678;H:false;;"))
        // 개방망
        assertEquals(QrPayload.Wifi("Free Cafe", security = WifiSecurity.NONE), QrPayload.parse("WIFI:S:Free Cafe;T:nopass;P:;;"))
        // 숨김 망
        assertEquals(QrPayload.Wifi("hidden", "pw", hidden = true), QrPayload.parse("WIFI:S:hidden;T:WPA;P:pw;H:true;;"))
    }

    @Test
    fun `값을 큰따옴표로 감싸는 생성기의 Wi-Fi`() {
        assertEquals(QrPayload.Wifi("My Net", "secret"), QrPayload.parse("WIFI:S:\"My Net\";T:WPA;P:\"secret\";;"))
    }

    @Test
    fun `기업용 WPA2-EAP Wi-Fi`() {
        val parsed = QrPayload.parse("WIFI:T:WPA2-EAP;S:corp;E:PEAP;PH2:MSCHAPV2;A:anon@corp;I:user@corp;P:pw;;")
        assertEquals(
            QrPayload.Wifi("corp", "pw", WifiSecurity.WPA2_EAP, eapMethod = "PEAP", identity = "user@corp", anonymousIdentity = "anon@corp", phase2Method = "MSCHAPV2"),
            parsed,
        )
    }

    @Test
    fun `마지막 세미콜론이 빠진 Wi-Fi 도 읽는다`() {
        assertEquals(QrPayload.Wifi("abc", "def"), QrPayload.parse("WIFI:S:abc;T:WPA;P:def"))
    }

    // ---- 연락처 ----

    @Test
    fun `구글 연락처 내보내기 vCard 3_0`() {
        val vcard = """
            BEGIN:VCARD
            VERSION:3.0
            FN:홍길동
            N:홍;길동;;;
            EMAIL;TYPE=INTERNET;TYPE=HOME:gildong@example.com
            EMAIL;TYPE=INTERNET;TYPE=WORK:hong@example.org
            TEL;TYPE=CELL:010-1234-5678
            TEL;TYPE=WORK:02-123-4567
            ADR;TYPE=HOME:;;세종대로 110;중구;서울;04524;대한민국
            ORG:예시 주식회사
            TITLE:개발자
            URL:https\://example.com
            NOTE:첫 줄\n둘째 줄
            END:VCARD
        """.trimIndent().replace("\n", "\r\n")
        assertEquals(
            QrPayload.Contact(
                name = "홍길동",
                organization = "예시 주식회사",
                title = "개발자",
                phones = listOf(ContactPhone("010-1234-5678", PhoneType.MOBILE), ContactPhone("02-123-4567", PhoneType.WORK)),
                emails = listOf("gildong@example.com", "hong@example.org"),
                address = "세종대로 110, 중구, 서울, 04524, 대한민국",
                url = "https://example.com",
                note = "첫 줄\n둘째 줄",
            ),
            QrPayload.parse(vcard),
        )
    }

    @Test
    fun `애플 연락처 vCard 3_0 — 그룹 접두사와 type 여러 번`() {
        val vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Apple Inc.//iPhone OS 17.0//EN
            N:Appleseed;John;;;
            FN:John Appleseed
            ORG:Apple Inc.;
            TITLE:Engineer
            item1.EMAIL;type=INTERNET;type=pref:john@example.com
            TEL;type=CELL;type=VOICE;type=pref:+1 408 555 0100
            TEL;type=WORK;type=FAX:+1 408 555 0101
            item2.ADR;type=HOME;type=pref:;;1 Infinite Loop;Cupertino;CA;95014;United States
            item2.X-ABADR:us
            item3.URL;type=pref:https://www.apple.com
            item3.X-ABLabel:_${'$'}!<HomePage>!${'$'}_
            PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRgABAQAAAQABAAD
             /2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8U
            END:VCARD
        """.trimIndent()
        assertEquals(
            QrPayload.Contact(
                name = "John Appleseed",
                organization = "Apple Inc.",
                title = "Engineer",
                phones = listOf(ContactPhone("+1 408 555 0100", PhoneType.MOBILE), ContactPhone("+1 408 555 0101", PhoneType.FAX)),
                emails = listOf("john@example.com"),
                address = "1 Infinite Loop, Cupertino, CA, 95014, United States",
                url = "https://www.apple.com",
            ),
            QrPayload.parse(vcard),
        )
    }

    @Test
    fun `안드로이드 내보내기 vCard 2_1 — QUOTED-PRINTABLE 과 맨 매개변수`() {
        val vcard = listOf(
            "BEGIN:VCARD",
            "VERSION:2.1",
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=ED=99=8D;=EA=B8=B8=EB=8F=99;;;",
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=ED=99=8D=EA=B8=B8=EB=8F=99",
            "TEL;CELL:010-1234-5678",
            "TEL;HOME;VOICE:02-000-0000",
            "EMAIL;HOME:a@example.com",
            "NOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:=EC=95=88=",
            "=EB=85=95",
            "END:VCARD",
        ).joinToString("\r\n")
        assertEquals(
            QrPayload.Contact(
                name = "홍길동",
                phones = listOf(ContactPhone("010-1234-5678", PhoneType.MOBILE), ContactPhone("02-000-0000", PhoneType.HOME)),
                emails = listOf("a@example.com"),
                note = "안녕",
            ),
            QrPayload.parse(vcard),
        )
    }

    @Test
    fun `vCard 4_0 — tel URI 와 따옴표 친 TYPE`() {
        val vcard = """
            BEGIN:VCARD
            VERSION:4.0
            FN:Simon Perreault
            N:Perreault;Simon;;;ing. jr,M.Sc.
            TEL;VALUE=uri;TYPE="work,voice";PREF=1:tel:+1-418-656-9254;ext=102
            TEL;VALUE=uri;TYPE="work,cell,voice,video,text":tel:+1-418-262-6501
            EMAIL;TYPE=work:simon.perreault@example.com
            END:VCARD
        """.trimIndent()
        val contact = QrPayload.parse(vcard) as QrPayload.Contact
        assertEquals("Simon Perreault", contact.name)
        assertEquals(
            listOf(ContactPhone("+1-418-656-9254;ext=102", PhoneType.WORK), ContactPhone("+1-418-262-6501", PhoneType.MOBILE)),
            contact.phones,
        )
        assertEquals(listOf("simon.perreault@example.com"), contact.emails)
    }

    @Test
    fun `접힌 줄을 잇는다`() {
        val vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:A\r\nNOTE:This is a long\r\n  note that\r\n\t continues\r\nEND:VCARD"
        assertEquals("This is a long note that continues", (QrPayload.parse(vcard) as QrPayload.Contact).note)
    }

    @Test
    fun `FN 없이 ORG 만 있는 vCard`() {
        val contact = QrPayload.parse("BEGIN:VCARD\nVERSION:3.0\nORG:가게\nTEL:02-1\nEND:VCARD") as QrPayload.Contact
        assertEquals("", contact.name)
        assertEquals("가게", contact.summary())
    }

    @Test
    fun `MECARD — 성,이름 과 쉼표가 든 주소`() {
        assertEquals(
            QrPayload.Contact(
                name = "Owen Sean",
                phones = listOf(ContactPhone("12125551212")),
                emails = listOf("srowen@example.com"),
                address = "76 9th Avenue, 4th Floor, New York, NY 10011",
            ),
            QrPayload.parse("MECARD:N:Owen,Sean;ADR:76 9th Avenue, 4th Floor, New York, NY 10011;TEL:12125551212;EMAIL:srowen@example.com;;"),
        )
    }

    // ---- 이메일 ----

    @Test
    fun `MATMSG 와 SMTP 이메일`() {
        assertEquals(
            QrPayload.Email("srowen@example.org", "Stuff", "This is some text"),
            QrPayload.parse("MATMSG:TO:srowen@example.org;SUB:Stuff;BODY:This is some text;;"),
        )
        assertEquals(QrPayload.Email("a@example.com", "제목", "본문: 끝"), QrPayload.parse("SMTP:a@example.com:제목:본문: 끝"))
    }

    @Test
    fun `mailto 의 + 와 대문자 키`() {
        assertEquals(QrPayload.Email("a+b@example.net", "hi there", "x"), QrPayload.parse("MAILTO:a+b@example.net?Subject=hi+there&BODY=x"))
        assertEquals(QrPayload.Text("mailto:"), QrPayload.parse("mailto:"))
    }

    // ---- 문자 ----

    @Test
    fun `본문에 물음표가 든 문자`() {
        assertEquals(QrPayload.Sms("010", "뭐해? 지금"), QrPayload.parse(QrPayload.Sms("010", "뭐해? 지금").encode()))
    }

    @Test
    fun `본문의 물음표 바로 뒤에 글자가 이어져도 질의로 읽지 않는다`() {
        listOf("뭐해?응", "a?body=b", "?x", "끝?").forEach { body ->
            assertEquals(body, QrPayload.Sms("010", body), QrPayload.parse(QrPayload.Sms("010", body).encode()))
        }
        assertEquals(QrPayload.Sms("010", "뭐해?응"), QrPayload.parse("smsto:010:뭐해?응"))
    }

    // ---- 역슬래시 이스케이프 ----

    @Test
    fun `역슬래시 뒤 n 이 든 값은 줄바꿈으로 바뀌지 않고 왕복한다`() {
        val contact = QrPayload.Contact(name = "C:\\new", note = "a\\nb\n둘째 줄", address = "D:\\notes")
        assertEquals(contact, QrPayload.parse(contact.encode()))
        assertEquals(contact, QrPayload.parse(contact.encodeMeCard()))
        val event = QrPayload.Event("t\\n", location = "D:\\notes", description = "\\\\n\n끝")
        assertEquals(event, QrPayload.parse(event.encode()))
    }

    // ---- OTP ----

    @Test
    fun `인증 앱 키 URI 형식 예시`() {
        assertEquals(
            QrPayload.Otp("alice@example.com", "JBSWY3DPEHPK3PXP", issuer = "Example"),
            QrPayload.parse("otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example"),
        )
        assertEquals(
            QrPayload.Otp("john.doe@example.com", "HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", issuer = "ACME Co"),
            QrPayload.parse(
                "otpauth://totp/ACME%20Co:john.doe@example.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co&algorithm=SHA1&digits=6&period=30",
            ),
        )
        assertEquals(
            QrPayload.Otp("alice@example.com", "JBSWY3DPEHPK3PXP", issuer = "Example", type = OtpType.HOTP, counter = 0),
            QrPayload.parse("otpauth://hotp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example&counter=0"),
        )
    }

    @Test
    fun `OTP 라벨 구분자 퍼센트 인코딩 콜론과 공백, issuer 매개변수 우선`() {
        val otp = QrPayload.parse("otpauth://totp/Big%20Corp%3A%20alice?secret=JBSWY3DPEHPK3PXP") as QrPayload.Otp
        assertEquals("Big Corp", otp.issuer)
        assertEquals("alice", otp.account)
        assertEquals("Param", (QrPayload.parse("otpauth://totp/Label:bob?secret=JBSWY3DPEHPK3PXP&issuer=Param") as QrPayload.Otp).issuer)
    }

    @Test
    fun `OTP 의 SHA256·8자리·60초`() {
        val otp = QrPayload.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=sha256&digits=8&period=60") as QrPayload.Otp
        assertEquals(OtpAlgorithm.SHA256, otp.algorithm)
        assertEquals(8, otp.digits)
        assertEquals(60, otp.period)
    }

    @Test
    fun `읽을 수 없는 OTP 는 텍스트다`() {
        listOf(
            "otpauth://totp/x?issuer=a",
            "otpauth://totp/x?secret=0189",
            "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=MD5",
            "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=abc",
            "otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP",
            "otpauth://totp/a:b:c?secret=JBSWY3DPEHPK3PXP",
        ).forEach { assertTrue(it, QrPayload.parse(it) is QrPayload.Text) }
    }

    // ---- 일정 ----

    @Test
    fun `VTIMEZONE·VALARM 이 든 VCALENDAR 는 VEVENT 의 값만 읽는다`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example//EN
            BEGIN:VTIMEZONE
            TZID:Asia/Seoul
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+0900
            TZOFFSETTO:+0900
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:1
            DTSTAMP:20261101T000000Z
            DTSTART;TZID=Asia/Seoul:20261105T100000
            DTEND;TZID="Asia/Seoul":20261105T113000
            SUMMARY:회의\, 3층
            LOCATION:본사
            BEGIN:VALARM
            TRIGGER:-PT15M
            DESCRIPTION:알림
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
        val seoul = ZoneId.of("Asia/Seoul")
        assertEquals(
            QrPayload.Event(
                title = "회의, 3층",
                start = EventTime.Local(LocalDateTime.of(2026, 11, 5, 10, 0), seoul),
                end = EventTime.Local(LocalDateTime.of(2026, 11, 5, 11, 30), seoul),
                location = "본사",
            ),
            QrPayload.parse(ics),
        )
    }

    @Test
    fun `종일 일정·UTC·모르는 TZID·깨진 시각`() {
        val allDay = QrPayload.parse("BEGIN:VEVENT\nSUMMARY:휴가\nDTSTART;VALUE=DATE:20261105\nDTEND;VALUE=DATE:20261106\nEND:VEVENT") as QrPayload.Event
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 11, 5)), allDay.start)
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 11, 6)), allDay.end)

        val utc = QrPayload.parse("BEGIN:VEVENT\nSUMMARY:x\nDTSTART:20261105T010000Z\nEND:VEVENT") as QrPayload.Event
        assertEquals(EventTime.Utc(java.time.Instant.parse("2026-11-05T01:00:00Z")), utc.start)

        val windowsZone = QrPayload.parse("BEGIN:VEVENT\nSUMMARY:x\nDTSTART;TZID=Korea Standard Time:20261105T100000\nEND:VEVENT") as QrPayload.Event
        assertEquals(EventTime.Local(LocalDateTime.of(2026, 11, 5, 10, 0)), windowsZone.start)

        val broken = QrPayload.parse("BEGIN:VEVENT\nSUMMARY:x\nDTSTART:20261345T100000\nEND:VEVENT") as QrPayload.Event
        assertNull(broken.start)
    }

    @Test
    fun `VEVENT 없는 VCALENDAR 와 제목 없는 일정은 텍스트다`() {
        assertTrue(QrPayload.parse("BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR") is QrPayload.Text)
        assertTrue(QrPayload.parse("BEGIN:VEVENT\nDTSTART:20261105T100000\nEND:VEVENT") is QrPayload.Text)
    }
}
