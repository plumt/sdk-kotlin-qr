package net.plumt.qr.scanner.action

import net.plumt.qr.CodeFormat
import net.plumt.qr.EventTime
import net.plumt.qr.QrPayload
import net.plumt.qr.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class QrActionsTest {

    private fun r(p: QrPayload, text: String = p.encode()) = ScanResult(text, CodeFormat.QR_CODE, p, emptyList())

    @Test
    fun primary_forEveryPayloadType() {
        val wifi = QrPayload.Wifi("Cafe", "pw12345678")
        val contact = QrPayload.Contact(name = "Kim")
        val event = QrPayload.Event("Meet", EventTime.AllDay(LocalDate.of(2026, 1, 2)))
        val geo = QrPayload.Geo(37.5, 127.0)
        val otp = QrPayload.Otp("me", "JBSWY3DPEHPK3PXP", "Ex")
        assertTrue(QrActions.primary(QrPayload.Url("https://example.com")) is QrAction.OpenUrl)
        assertEquals(QrAction.ConnectWifi(wifi), QrActions.primary(wifi))
        assertEquals(QrAction.AddContact(contact), QrActions.primary(contact))
        assertEquals(QrAction.AddEvent(event), QrActions.primary(event))
        assertEquals(QrAction.Dial("010-1234"), QrActions.primary(QrPayload.Phone(" 010-1234 ")))
        assertEquals(QrAction.SendSms("010", "hi"), QrActions.primary(QrPayload.Sms(" 010 ", "hi")))
        assertEquals(QrAction.SendEmail("a@example.com", "s", "b"), QrActions.primary(QrPayload.Email(" a@example.com ", "s", "b")))
        assertEquals(QrAction.ShowLocation(geo), QrActions.primary(geo))
        assertEquals(QrAction.AddOtp(otp), QrActions.primary(otp))
        assertEquals(QrAction.SearchProduct("4006381333931"), QrActions.primary(QrPayload.Product(" 4006381333931 ")))
        assertEquals(QrAction.CopyText("hello"), QrActions.primary(QrPayload.Text("hello")))
    }

    @Test
    fun all_addsCopiesButNeverRawSecrets() {
        val wifi = QrPayload.Wifi("Cafe", "pw12345678")
        assertEquals(listOf(QrAction.ConnectWifi(wifi), QrAction.CopyWifiPassword(wifi)), QrActions.all(r(wifi)))
        val open = QrPayload.Wifi("Open", security = net.plumt.qr.WifiSecurity.NONE)
        assertEquals(listOf(QrAction.ConnectWifi(open)), QrActions.all(r(open)))
        val otp = QrPayload.Otp("me", "JBSWY3DPEHPK3PXP")
        assertEquals(listOf(QrAction.AddOtp(otp), QrAction.CopyOtpSecret(otp)), QrActions.all(r(otp)))
        assertEquals(listOf(QrAction.CopyText("t")), QrActions.all(r(QrPayload.Text("t"))))
        val phone = QrPayload.Phone("010")
        assertEquals(listOf(QrAction.Dial("010"), QrAction.CopyText("tel:010")), QrActions.all(r(phone)))
        val url = QrActions.all(r(QrPayload.Url("https://example.com")))
        assertEquals(2, url.size)
        assertEquals(QrAction.CopyText("https://example.com"), url[1])
    }

    @Test
    fun toString_hidesSecrets() {
        val wifi = QrPayload.Wifi("Cafe", "pw-secret-1")
        val otp = QrPayload.Otp("me", "JBSWY3DPEHPK3PXP")
        for (a in listOf(QrAction.ConnectWifi(wifi), QrAction.CopyWifiPassword(wifi), QrAction.AddOtp(otp), QrAction.CopyOtpSecret(otp),
            QrAction.CopyText("pw-secret-1", sensitive = true))) {
            val s = a.toString()
            assertFalse(s, s.contains("pw-secret-1"))
            assertFalse(s, s.contains("JBSWY3DPEHPK3PXP"))
        }
        assertEquals("CopyText(text=hi, sensitive=false)", QrAction.CopyText("hi").toString())
    }

    // ---- 링크 살피기 ----

    @Test
    fun openUrl_plainHttps() {
        val a = QrActions.openUrl("https://Example.COM/path?q=1#x")
        assertEquals("https://Example.COM/path?q=1#x", a.url)
        assertEquals("example.com", a.host)
        assertEquals("example.com", a.displayHost)
        assertTrue(a.warnings.isEmpty())
    }

    @Test
    fun openUrl_http_warns() {
        assertEquals(setOf(UrlWarning.NOT_HTTPS), QrActions.openUrl("HTTP://example.com").warnings)
    }

    @Test
    fun openUrl_userInfoTrick_showsRealHost() {
        val a = QrActions.openUrl("https://bank.example.com@evil.com:8443/login")
        assertEquals("evil.com", a.host)
        assertTrue(UrlWarning.USER_INFO in a.warnings)
        // 사용자 정보에 @ 가 여럿이어도 마지막 뒤가 호스트다
        assertEquals("evil.com", QrActions.openUrl("https://a@b@evil.com/").host)
        // 경로의 @ 는 사용자 정보가 아니다
        val p = QrActions.openUrl("https://example.com/@user")
        assertEquals("example.com", p.host)
        assertFalse(UrlWarning.USER_INFO in p.warnings)
    }

    @Test
    fun openUrl_internationalizedDomain_punycode() {
        // 키릴 문자로 쓴 "apple" 흉내
        val a = QrActions.openUrl("https://аррӏе.example/")
        assertTrue(a.host.startsWith("xn--"))
        assertEquals("аррӏе.example", a.displayHost)
        assertTrue(UrlWarning.INTERNATIONALIZED in a.warnings)
        // 이미 퓨니코드로 적힌 주소도 같다
        val b = QrActions.openUrl("https://${a.host}/")
        assertEquals(a.host, b.host)
        assertEquals("аррӏе.example", b.displayHost)
        assertTrue(UrlWarning.INTERNATIONALIZED in b.warnings)
        // 한글 도메인
        val k = QrActions.openUrl("https://한글.example")
        assertTrue(k.host.startsWith("xn--"))
        assertEquals("한글.example", k.displayHost)
    }

    @Test
    fun openUrl_ipAddresses() {
        val v4 = QrActions.openUrl("http://203.0.113.7:8080/x")
        assertEquals("203.0.113.7", v4.host)
        assertEquals(setOf(UrlWarning.NOT_HTTPS, UrlWarning.IP_ADDRESS), v4.warnings)
        val v6 = QrActions.openUrl("https://[2001:db8::1]:443/")
        assertEquals("[2001:db8::1]", v6.host)
        assertTrue(UrlWarning.IP_ADDRESS in v6.warnings)
        val broken6 = QrActions.openUrl("https://[2001:db8::1/")
        assertTrue(UrlWarning.IP_ADDRESS in broken6.warnings)
    }

    @Test
    fun openUrl_unreadableHosts() {
        for (u in listOf("https://", "https:///path", "https://@/", "https://:80/", "noscheme")) {
            val a = QrActions.openUrl(u)
            assertTrue(u, UrlWarning.UNREADABLE_HOST in a.warnings || a.host.isNotEmpty())
        }
        assertEquals("", QrActions.openUrl("https://").host)
        assertTrue(UrlWarning.UNREADABLE_HOST in QrActions.openUrl("https:///x").warnings)
        // 라벨이 63자를 넘는 등 도메인 규칙 위반
        val bad = QrActions.openUrl("https://" + "a".repeat(64) + ".example/")
        assertTrue(UrlWarning.UNREADABLE_HOST in bad.warnings)
        assertEquals("a".repeat(64) + ".example", bad.host)
    }

    @Test
    fun openUrl_backslashAndTrailingDot() {
        // 일부 브라우저는 \ 를 / 로 본다 — 호스트는 \ 앞에서 끊는다
        assertEquals("evil.example", QrActions.openUrl("https://evil.example\\@good.example/").host)
        assertEquals("example.com", QrActions.openUrl("https://example.com./").host)
    }

    @Test
    fun openUrl_noScheme_addsHttps() {
        val a = QrActions.openUrl("  example.com/x  ")
        assertEquals("https://example.com/x", a.url)
        assertEquals("example.com", a.host)
        assertTrue(a.warnings.isEmpty())
        // 콜론 뒤가 포트면 스킴이 아니다
        val port = QrActions.openUrl("localhost:8080/x")
        assertEquals("https://localhost:8080/x", port.url)
        assertEquals("localhost", port.host)
        assertEquals("https://example.com:", QrActions.openUrl("example.com:").url)
        // 스킴 없는 주소 payload 도 https 로 연다
        assertEquals("https://example.com", (QrActions.primary(QrPayload.Url("example.com")) as QrAction.OpenUrl).url)
    }

    @Test
    fun openUrl_nonWebSchemes_areFlagged() {
        val intent = QrActions.openUrl("intent://scan/#Intent;scheme=zxing;package=com.example;end")
        assertEquals("intent://scan/#Intent;scheme=zxing;package=com.example;end", intent.url)
        assertTrue(UrlWarning.NON_WEB_SCHEME in intent.warnings)
        assertEquals("scan", intent.host)
        for (u in listOf("javascript:alert(1)", "JavaScript:alert(document.cookie)", "data:text/html,<b>x</b>", "file:///sdcard/a", "market://details?id=x", "content://x/y")) {
            val a = QrActions.openUrl(u)
            assertEquals(u, u, a.url)
            assertTrue(u, UrlWarning.NON_WEB_SCHEME in a.warnings)
            assertFalse(u, UrlWarning.NOT_HTTPS in a.warnings)
        }
        val js = QrActions.openUrl("javascript:alert(1)")
        assertEquals("", js.host)
        assertEquals(setOf(UrlWarning.NON_WEB_SCHEME, UrlWarning.UNREADABLE_HOST), js.warnings)
        // 권한부가 스킴 바로 뒤가 아니다
        assertTrue(UrlWarning.UNREADABLE_HOST in QrActions.openUrl("mailto:a" + "@" + "b.example?x=http://y").warnings)
        assertFalse(UrlWarning.NON_WEB_SCHEME in QrActions.openUrl("HTTPS://Example.com").warnings)
        assertEquals(setOf(UrlWarning.NOT_HTTPS), QrActions.openUrl("HTTP://example.com").warnings)
        assertEquals("ftp", QrActions.schemeOf("FTP://x"))
        assertNull(QrActions.schemeOf("host:443"))
        assertNull(QrActions.schemeOf("host:443?x"))
        assertNull(QrActions.schemeOf(""))
    }

    @Test
    fun openUrl_numericHosts_followWhatwgIpv4() {
        for ((u, ip) in listOf(
            "https://2130706433/" to "127.0.0.1",
            "https://0x7f000001/" to "127.0.0.1",
            "https://0X7F.1/" to "127.0.0.1",
            "https://127.1/" to "127.0.0.1",
            "https://127.0.1/" to "127.0.0.1",
            "https://0177.0.0.1/" to "127.0.0.1",
            "https://0x7f.0.0.0x1/" to "127.0.0.1",
            "https://203.0.113.9./" to "203.0.113.9",
            "https://0/" to "0.0.0.0",
            "https://0x/" to "0.0.0.0",
            "https://4294967295/" to "255.255.255.255",
            "https://user@203.1/x" to "203.0.0.1",
            "https://１２７.0.0.1/" to "127.0.0.1",
        )) {
            val a = QrActions.openUrl(u)
            assertEquals(u, ip, a.host)
            assertEquals(u, ip, a.displayHost)
            assertTrue(u, UrlWarning.IP_ADDRESS in a.warnings)
            assertFalse(u, UrlWarning.UNREADABLE_HOST in a.warnings)
        }
        // 숫자로 끝나지만 IPv4 로 읽히지 않으면 브라우저도 열지 못한다 — 읽지 못한 호스트
        for (u in listOf("https://4294967296/", "https://256.1.1.1/", "https://1.2.3.4.5/", "https://example.123/", "https://08/",
            "https://1.2.3.256/", "https://99999999999999999999999/", "https://0xffffffffffffffffffff/")) {
            val a = QrActions.openUrl(u)
            assertTrue(u, UrlWarning.IP_ADDRESS in a.warnings)
            assertTrue(u, UrlWarning.UNREADABLE_HOST in a.warnings)
        }
        // 숫자로 끝나지 않으면 도메인이다
        for (u in listOf("https://1.2.3.4a/", "https://example.com/", "https://0x7f.example/", "https://a.0xg/", "https://0x1g/x")) {
            assertFalse(u, UrlWarning.IP_ADDRESS in QrActions.openUrl(u).warnings)
        }
        assertEquals("127.0.0.1", net.plumt.qr.scanner.action.Ipv4.parse("127.0.0.1"))
        assertNull(net.plumt.qr.scanner.action.Ipv4.parse(""))
        assertNull(net.plumt.qr.scanner.action.Ipv4.parse("1..2"))
    }

    @Test
    fun fuzz_neverThrows() {
        val rnd = Random(77)
        val alphabet = "abc.:/@[]\\?#xn--한аé0123 %\u0000😀"
        repeat(3000) {
            val s = buildString { repeat(rnd.nextInt(0, 40)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            for (prefix in listOf("", "https://", "http://")) {
                val a = QrActions.openUrl(prefix + s)
                assertTrue(a.host == a.host.lowercase() || UrlWarning.UNREADABLE_HOST in a.warnings || UrlWarning.IP_ADDRESS in a.warnings)
            }
        }
    }
}
