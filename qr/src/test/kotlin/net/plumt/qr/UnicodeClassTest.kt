package net.plumt.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 정규식 `\d`·`\s` 는 Android 에서 유니코드 숫자·공백까지 맞춰 JVM 과 결과가 갈렸다. ASCII 명시 클래스로 바꾼 뒤의 동작을 고정한다.
 */
class UnicodeClassTest {

    private val at = "@"

    private fun event(start: String) =
        QrPayload.parse("BEGIN:VEVENT\nSUMMARY:x\nDTSTART;VALUE=DATE:$start\nEND:VEVENT") as QrPayload.Event

    @Test
    fun `일정 시각은 ASCII 숫자만 받는다 — 유니코드 숫자 시각은 없는 것으로 본다`() {
        assertEquals(LocalDate.of(2026, 11, 5), (event("20261105").start as EventTime.AllDay).date)
        assertNull(event("٢٠٢٦١١٠٥").start)
        assertNull(event("２０２６１１０５").start)
        assertNull(event("2026110٥").start)
        assertNotNull(event("20261105").start)
    }

    @Test
    fun `사용자 정보는 앞에 어떤 공백이 있어도 가린다`() {
        listOf(" ", "　", " ", " ", "\t").forEach { lead ->
            val s = QrPayload.Url(lead + "https://user:secret" + at + "bank.com/").toString()
            assertFalse("앞 공백 U+%04X".format(lead[0].code), s.contains("secret"))
        }
        assertEquals("https://****@bank.com/", QrPayload.summarize("　https://user:secret" + at + "bank.com/　"))
    }
}
