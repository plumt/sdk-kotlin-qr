package net.plumt.qr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.imageio.ImageIO

/** 값 타입·열거형·격자의 공개 동작. */
class ModelTest {

    // ---- CodeMatrix ----

    private val sample = CodeMatrix(3, 2, booleanArrayOf(true, false, true, false, true, false))

    @Test
    fun `격자 접근`() {
        assertTrue(sample[0, 0])
        assertFalse(sample[1, 0])
        assertTrue(sample[1, 1])
        assertArrayEquals(booleanArrayOf(false, true, false), sample.row(1))
        assertArrayEquals(booleanArrayOf(true, false, true, false, true, false), sample.toBooleanArray())
        assertEquals("CodeMatrix(3x2)", sample.toString())
    }

    @Test
    fun `격자는 불변이다 — 넘긴 배열과 돌려준 배열을 고쳐도 바뀌지 않는다`() {
        val cells = booleanArrayOf(true, false)
        val m = CodeMatrix(2, 1, cells)
        cells[0] = false
        m.toBooleanArray()[0] = false
        m.row(0)[0] = false
        assertTrue(m[0, 0])
    }

    @Test
    fun `격자 범위 검사`() {
        assertThrows(IllegalArgumentException::class.java) { CodeMatrix(0, 1, BooleanArray(0)) }
        assertEquals(
            "cells 크기(3) 가 width*height(4) 와 다르다",
            assertThrows(IllegalArgumentException::class.java) { CodeMatrix(2, 2, BooleanArray(3)) }.message,
        )
        assertEquals("(3, 0) 는 0..2, 0..1 범위여야 한다", assertThrows(IllegalArgumentException::class.java) { sample[3, 0] }.message)
        assertThrows(IllegalArgumentException::class.java) { sample[0, -1] }
        assertEquals("y(2) 는 0..1 범위여야 한다", assertThrows(IllegalArgumentException::class.java) { sample.row(2) }.message)
        assertEquals("factor(0) 는 1 이상이어야 한다", assertThrows(IllegalArgumentException::class.java) { sample.scaled(0) }.message)
        assertThrows(IllegalArgumentException::class.java) { sample.scaled(40_000) }
    }

    @Test
    fun `확대`() {
        assertSame(sample, sample.scaled(1))
        val s = sample.scaled(2)
        assertEquals(6, s.width)
        assertEquals(4, s.height)
        for (y in 0 until 4) for (x in 0 until 6) assertEquals(sample[x / 2, y / 2], s[x, y])
    }

    @Test
    fun `같음과 해시`() {
        val same = CodeMatrix(3, 2, sample.toBooleanArray())
        assertEquals(sample, same)
        assertEquals(sample.hashCode(), same.hashCode())
        assertNotEquals(sample, CodeMatrix(2, 3, sample.toBooleanArray()))
        assertNotEquals(sample, CodeMatrix(3, 2, BooleanArray(6)))
        assertNotEquals(sample, "x")
    }

    @Test
    fun `ARGB 변환`() {
        assertArrayEquals(
            intArrayOf(CodeMatrix.BLACK, CodeMatrix.WHITE, CodeMatrix.BLACK, CodeMatrix.WHITE, CodeMatrix.BLACK, CodeMatrix.WHITE),
            sample.toArgb(),
        )
        assertArrayEquals(intArrayOf(1, 2, 1, 2, 1, 2), sample.toArgb(1, 2))
        assertEquals(0xFF000000.toInt(), CodeMatrix.BLACK)
        assertEquals(0xFFFFFFFF.toInt(), CodeMatrix.WHITE)
    }

    @Test
    fun `PNG 는 표준 디코더로 같은 픽셀로 읽힌다`() {
        listOf(sample, QrCode.encode("png").scaled(3), CodeMatrix(9, 1, BooleanArray(9) { it % 2 == 0 }), CodeMatrix(1, 1, booleanArrayOf(true)))
            .forEach { m ->
                val image = ImageIO.read(ByteArrayInputStream(m.toPng()))
                assertEquals(m.width, image.width)
                assertEquals(m.height, image.height)
                for (y in 0 until m.height) for (x in 0 until m.width) {
                    val dark = (image.getRGB(x, y) and 0xFFFFFF) == 0
                    assertEquals("($x,$y)", m[x, y], dark)
                }
            }
    }

    @Test
    fun `PNG 를 다시 읽어 QR 을 푼다`() {
        val m = QrCode.encode("PNG 왕복").scaled(4)
        val image = ImageIO.read(ByteArrayInputStream(m.toPng()))
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        assertEquals("PNG 왕복", CodeReader.read(argb, image.width, image.height)?.text)
    }

    // ---- 열거형 ----

    @Test
    fun `형식 갈래와 집합`() {
        assertEquals(13, CodeFormat.ALL.size)
        assertEquals(setOf(CodeFormat.QR_CODE), CodeFormat.QR_ONLY)
        assertEquals(setOf(CodeFormat.EAN_13, CodeFormat.EAN_8, CodeFormat.UPC_A, CodeFormat.UPC_E), CodeFormat.PRODUCT)
        assertEquals(CodeFormat.Kind.MATRIX, CodeFormat.PDF_417.kind)
        assertEquals(CodeFormat.Kind.LINEAR, CodeFormat.ITF.kind)
    }

    @Test
    fun `Wi-Fi 보안 토큰 판별`() {
        assertEquals(WifiSecurity.NONE, WifiSecurity.fromToken(null))
        assertEquals(WifiSecurity.NONE, WifiSecurity.fromToken(" "))
        assertEquals(WifiSecurity.NONE, WifiSecurity.fromToken("NoPass"))
        assertEquals(WifiSecurity.WEP, WifiSecurity.fromToken("wep"))
        assertEquals(WifiSecurity.WPA, WifiSecurity.fromToken("WPA"))
        assertEquals(WifiSecurity.WPA, WifiSecurity.fromToken("WPA2"))
        assertEquals(WifiSecurity.WPA, WifiSecurity.fromToken("모름"))
        assertEquals(WifiSecurity.WPA3, WifiSecurity.fromToken("sae"))
        assertEquals(WifiSecurity.WPA3, WifiSecurity.fromToken("WPA3"))
        assertEquals(WifiSecurity.WPA2_EAP, WifiSecurity.fromToken("WPA2-EAP"))
        assertEquals(WifiSecurity.WPA2_EAP, WifiSecurity.fromToken("wpa-eap"))
        assertEquals(WifiSecurity.WPA2_EAP, WifiSecurity.fromToken("EAP"))
        WifiSecurity.entries.forEach { assertEquals(it, WifiSecurity.fromToken(it.token)) }
    }

    // ---- EventTime ----

    @Test
    fun `일정 시각의 순간 변환과 초 미만 절삭`() {
        val seoul = ZoneId.of("Asia/Seoul")
        assertEquals(Instant.parse("2026-11-04T15:00:00Z"), EventTime.AllDay(LocalDate.of(2026, 11, 5)).toInstant(seoul))
        val floating = EventTime.Local(LocalDateTime.of(2026, 11, 5, 10, 0, 0, 999_999_999))
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 0), floating.dateTime)
        assertNull(floating.toZonedDateTime())
        assertEquals(Instant.parse("2026-11-05T01:00:00Z"), floating.toInstant(seoul))
        assertEquals(Instant.parse("2026-11-05T10:00:00Z"), floating.toInstant(ZoneOffset.UTC))
        val zoned = EventTime.Local(LocalDateTime.of(2026, 11, 5, 10, 0), seoul)
        assertEquals(seoul, zoned.toZonedDateTime()!!.zone)
        assertEquals(Instant.parse("2026-11-05T01:00:00Z"), zoned.toInstant(ZoneOffset.UTC))
        val utc = EventTime.Utc(Instant.parse("2026-11-05T01:00:00.5Z"))
        assertEquals(Instant.parse("2026-11-05T01:00:00Z"), utc.instant)
        assertEquals(utc.instant, utc.toInstant(seoul))
    }

    @Test
    fun `일정 시각의 같음·해시·표기`() {
        val d = LocalDateTime.of(2026, 1, 1, 0, 0)
        assertEquals(EventTime.Local(d), EventTime.Local(d.withNano(5)))
        assertEquals(EventTime.Local(d).hashCode(), EventTime.Local(d.withNano(5)).hashCode())
        assertNotEquals(EventTime.Local(d), EventTime.Local(d, ZoneOffset.UTC))
        assertNotEquals(EventTime.Local(d, ZoneOffset.UTC).hashCode(), 0)
        assertFalse(EventTime.Local(d) as Any == EventTime.AllDay(d.toLocalDate()))
        assertEquals(EventTime.AllDay(d.toLocalDate()).hashCode(), EventTime.AllDay(d.toLocalDate()).hashCode())
        assertEquals(EventTime.Utc(Instant.EPOCH).hashCode(), EventTime.Utc(Instant.EPOCH).hashCode())
        assertFalse(EventTime.Utc(Instant.EPOCH) as Any == EventTime.AllDay(d.toLocalDate()))
        assertEquals("AllDay(2026-01-01)", EventTime.AllDay(d.toLocalDate()).toString())
        assertEquals("Local(2026-01-01T00:00)", EventTime.Local(d).toString())
        assertEquals("Local(2026-01-01T00:00, Z)", EventTime.Local(d, ZoneOffset.UTC).toString())
        assertEquals("Utc(1970-01-01T00:00:00Z)", EventTime.Utc(Instant.EPOCH).toString())
    }

    @Test
    fun `값 타입의 기본값`() {
        val options = QrCodeOptions()
        assertEquals(ErrorCorrection.M, options.errorCorrection)
        assertEquals(4, options.margin)
        assertEquals(Charsets.UTF_8, options.charset)
        assertNull(options.minVersion)
        assertEquals(BarcodeOptions(0, 0, null), BarcodeOptions())
        assertEquals(ReadOptions(CodeFormat.ALL, tryHarder = false, alsoInverted = false, characterSet = null), ReadOptions())
        assertEquals(PhoneType.OTHER, ContactPhone("1").type)
        assertEquals(ScanPoint(1f, 2f), ScanPoint(1f, 2f))
        val otp = QrPayload.Otp("a", "JBSWY3DPEHPK3PXP")
        assertEquals(OtpType.TOTP, otp.type)
        assertEquals(OtpAlgorithm.SHA1, otp.algorithm)
        assertEquals(6, otp.digits)
        assertEquals(30, otp.period)
        assertEquals(0L, otp.counter)
    }

    @Test
    fun `요약의 각 형식`() {
        assertEquals("https://a", QrPayload.Url(" https://a ").summary())
        assertEquals("a@b", QrPayload.Email(" a@b ").summary())
        assertEquals("010", QrPayload.Sms(" 010 ", "x").summary())
        assertEquals("Iss:acct", QrPayload.Otp("acct", "JBSWY3DPEHPK3PXP", "Iss").summary())
        assertEquals("acct", QrPayload.Otp("acct", "JBSWY3DPEHPK3PXP").summary())
        assertEquals("123", QrPayload.Product(" 123 ").summary())
        assertEquals("제목", QrPayload.Event(" 제목 ").summary())
        assertEquals("", QrPayload.Text("  ").summary())
        assertEquals("", QrPayload.summarize(""))
        assertEquals("Wi-Fi", QrPayload.Wifi(" ").summary())
        assertEquals("", QrPayload.Contact().summary())
    }

    @Test
    fun `읽기 결과는 내용 형식을 함께 준다`() {
        val r = ScanResult("tel:1", CodeFormat.QR_CODE, QrPayload.Phone("1"), listOf(ScanPoint(0f, 0f)))
        assertEquals(QrPayload.Phone("1"), r.payload)
        assertEquals(r, r.copy())
    }

    @Test
    fun `읽기 문자셋 지정`() {
        val m = QrCode.encode("abc").scaled(3)
        val cells = m.toBooleanArray()
        val data = ByteArray(cells.size) { if (cells[it]) 0 else -1 }
        assertEquals("abc", CodeReader.read(data, m.width, m.height, options = ReadOptions(characterSet = Charsets.UTF_8, tryHarder = true))?.text)
        assertEquals(listOf("abc"), CodeReader.readAll(m.toArgb(), m.width, m.height).map { it.text })
    }
}
