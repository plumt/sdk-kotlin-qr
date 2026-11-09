package net.plumt.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * 해석 시간 상한. 카메라는 프레임마다 [QrPayload.parse] 를 부르므로, 어떤 내용의 코드도 분석 스레드를 오래 붙들면 안 된다.
 *
 * 줄바꿈 없이 뭉개진 vCard·iCalendar 를 나누는 경계 탐색이 공백 덩어리마다 되돌아가면(이차 시간) 공백 수천 칸짜리 QR 하나로
 * 프레임마다 수 초가 걸린다. 경계 탐색은 한 번 훑기(선형)여야 하고, 길이 상한을 넘는 입력은 구조를 풀지 않는다.
 */
class ParseTimeBoundTest {

    /** 한 입력의 해석(+요약) 시간 상한. 선형이면 수 ms, 이차 시간이던 때는 같은 입력에 수 초가 걸렸다. */
    private val budgetNanos = 1_000_000_000L

    private fun assertFast(label: String, input: String) {
        // 처음 한 번은 클래스 적재·JIT 를 데운다
        QrPayload.parse(input)
        val start = System.nanoTime()
        QrPayload.parse(input)
        QrPayload.summarize(input, ZoneOffset.UTC)
        val took = System.nanoTime() - start
        assertTrue("$label: ${took / 1_000_000}ms 걸렸다(상한 ${budgetNanos / 1_000_000}ms)", took < budgetNanos)
    }

    private val limit = QrPayload.MAX_PARSE_LENGTH

    @Test
    fun `뭉개진 vCard 의 긴 공백 덩어리 뒤에 키워드가 없어도 선형 시간이다`() {
        assertFast("VCARD 공백", "BEGIN:VCARD" + " ".repeat(limit - 12) + "x")
        assertFast("VCARD 탭·공백", "BEGIN:VCARD" + " \t".repeat((limit - 12) / 2) + "x")
        assertFast("VCARD 영숫자 모드 최대", "BEGIN:VCARD" + " ".repeat(4296 - 12) + "X")
    }

    @Test
    fun `뭉개진 iCalendar 의 긴 공백 덩어리도 선형 시간이다`() {
        assertFast("VEVENT 공백", "BEGIN:VEVENT" + " ".repeat(limit - 13) + "x")
        assertFast("VCALENDAR 공백", "BEGIN:VCALENDAR" + " ".repeat(limit - 16) + "x")
    }

    @Test
    fun `키워드에 가까운 조각이 반복돼도 선형 시간이다`() {
        val n = limit / 4
        assertFast("거의 키워드", "BEGIN:VCARD" + " TE".repeat(n))
        assertFast("그룹 접두사", "BEGIN:VCARD" + " a.".repeat(n))
        assertFast("긴 그룹 토큰", "BEGIN:VCARD " + "a".repeat(limit - 20) + ".")
        assertFast("키워드 뒤 구분자 없음", "BEGIN:VEVENT" + " SUMMARY".repeat(limit / 8 - 2))
        assertFast("QP 소프트 줄바꿈", "BEGIN:VCARD\nNOTE;ENCODING=QUOTED-PRINTABLE:" + "=\n".repeat(limit / 2 - 30))
        assertFast("콜론 없는 QP 이어 붙이기", "BEGIN:VCARD\n" + "a=\n".repeat(limit / 3 - 5))
    }

    @Test
    fun `공백 뒤 키워드에서는 여전히 줄을 나눈다`() {
        val collapsed = "BEGIN:VCARD   VERSION:3.0 \t FN:홍길동  item1.TEL;TYPE=CELL:010-1  END:VCARD"
        val contact = QrPayload.parse(collapsed) as QrPayload.Contact
        assertEquals("홍길동", contact.name)
        assertEquals(listOf(ContactPhone("010-1", PhoneType.MOBILE)), contact.phones)
        val event = QrPayload.parse("BEGIN:VEVENT  SUMMARY:회의   DTSTART:20261105T100000Z END:VEVENT") as QrPayload.Event
        assertEquals("회의", event.title)
        // 키워드 뒤에 : 나 ; 가 없으면 경계가 아니다 — 값 안의 공백으로 남는다
        val note = QrPayload.parse("BEGIN:VCARD FN:a NOTE:hello  FNx world END:VCARD") as QrPayload.Contact
        assertEquals("hello  FNx world", note.note)
        // 유니코드 공백( )은 경계로 보지 않는다(정규식 \s 와 같은 규칙)
        val nbsp = QrPayload.parse("BEGIN:VCARD FN:a NOTE:b END:VCARD") as QrPayload.Contact
        assertEquals("a NOTE:b", nbsp.name)
    }

    @Test
    fun `길이 상한을 넘는 입력은 구조를 풀지 않고 원문 텍스트다`() {
        val wifiAtLimit = "WIFI:S:" + "a".repeat(limit - 10) + ";;"
        assertEquals(limit - 1, wifiAtLimit.length)
        assertTrue(QrPayload.parse(wifiAtLimit) is QrPayload.Wifi)
        val exact = "https://example.com/" + "a".repeat(limit - 20)
        assertEquals(limit, exact.length)
        assertTrue(QrPayload.parse(exact) is QrPayload.Url)
        val over = exact + "a"
        assertEquals(QrPayload.Text(over), QrPayload.parse(over))
        // 거대한 입력(1MB)도 곧바로 돌아온다
        val huge = "BEGIN:VCARD" + " ".repeat(1_000_000) + "x"
        assertFast("거대 입력", huge)
        assertEquals(QrPayload.Text(huge), QrPayload.parse(huge))
        // 상한을 넘은 비밀 원문도 요약·toString 에서 가린다
        val longWifi = "WIFI:S:x;P:" + "p".repeat(limit) + ";;"
        assertEquals("Wi-Fi", QrPayload.summarize(longWifi))
        assertEquals("Text(text=****, length=${longWifi.length})", QrPayload.parse(longWifi).toString())
        // 상품 바코드는 형식으로 판별하므로 길이와 무관하다
        assertEquals(QrPayload.Product("1".repeat(limit + 1)), QrPayload.parse("1".repeat(limit + 1), CodeFormat.EAN_13))
    }
}
