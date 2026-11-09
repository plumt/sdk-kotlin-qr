package net.plumt.qr

import com.google.zxing.WriterException
import net.plumt.qr.internal.engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 잘못된 인자는 정확한 타입·메시지의 예외, 엔진 실패는 원인을 담은 [QrCodeException]. */
class ValidationTest {

    private fun iae(expected: String, block: () -> Unit) {
        val e = assertThrows(IllegalArgumentException::class.java) { block() }
        assertEquals(expected, e.message)
    }

    private fun iaeContains(fragment: String, block: () -> Unit) {
        val e = assertThrows(IllegalArgumentException::class.java) { block() }
        assertTrue("'${e.message}' 에 '$fragment' 가 없다", e.message!!.contains(fragment))
    }

    // ---- 내용 인코딩 ----

    @Test
    fun `Url 검사`() {
        iae("url 이 비어 있다") { QrPayload.Url("  ").encode() }
        iae("url 에 공백이 있다: https://a b") { QrPayload.Url("https://a b").encode() }
        assertEquals("https://a", QrPayload.Url(" https://a ").encode())
    }

    @Test
    fun `Wifi 검사`() {
        iae("ssid 가 비어 있다") { QrPayload.Wifi("").encode() }
        assertEquals("WIFI:T:WPA;S: ;;", QrPayload.Wifi(" ").encode())
    }

    @Test
    fun `Contact 검사`() {
        iae("name·organization·phones·emails 중 하나는 있어야 한다") { QrPayload.Contact(title = "x", note = "y").encode() }
        iae("name·organization·phones·emails 중 하나는 있어야 한다") { QrPayload.Contact(name = " ").encodeMeCard() }
        iae("phones[1].number 가 비어 있다") { QrPayload.Contact("a", phones = listOf(ContactPhone("1"), ContactPhone(" "))).encode() }
        iae("emails[0] 가 비어 있다") { QrPayload.Contact("a", emails = listOf("")).encode() }
        iae("url 에 줄바꿈이 있다") { QrPayload.Contact("a", url = "https://a\nb").encode() }
    }

    @Test
    fun `Email·Phone·Sms·Product 검사`() {
        iae("address 가 비어 있다") { QrPayload.Email(" ").encode() }
        iae("address 에 공백·?·& 가 있다: a b@c") { QrPayload.Email("a b@c").encode() }
        iae("address 에 공백·?·& 가 있다: a?b") { QrPayload.Email("a?b").encode() }
        iae("number 가 비어 있다") { QrPayload.Phone("").encode() }
        iae("number 가 비어 있다") { QrPayload.Sms(" ").encode() }
        iae("number 에 : 나 ? 가 있다: 0:1") { QrPayload.Sms("0:1").encode() }
        iae("code 가 비어 있다") { QrPayload.Product("").encode() }
    }

    @Test
    fun `Event 검사`() {
        iae("title 이 비어 있다") { QrPayload.Event(" ").encode() }
        val a = LocalDateTime.of(2026, 1, 2, 10, 0)
        iae("end(Local(2026-01-02T09:00)) 가 start(Local(2026-01-02T10:00)) 보다 앞선다") {
            QrPayload.Event("x", EventTime.Local(a), EventTime.Local(a.minusHours(1))).encode()
        }
        iaeContains("가 start(") {
            QrPayload.Event("x", EventTime.AllDay(LocalDate.of(2026, 1, 2)), EventTime.AllDay(LocalDate.of(2026, 1, 1))).encode()
        }
        iaeContains("가 start(") {
            QrPayload.Event("x", EventTime.Utc(Instant.parse("2026-01-02T00:00:00Z")), EventTime.Utc(Instant.parse("2026-01-01T00:00:00Z"))).encode()
        }
        iae("start 의 연도(10000) 는 1..9999 범위여야 한다") { QrPayload.Event("x", EventTime.AllDay(LocalDate.of(10000, 1, 1))).encode() }
        iae("end 의 연도(0) 는 1..9999 범위여야 한다") { QrPayload.Event("x", end = EventTime.Local(LocalDateTime.of(0, 1, 1, 0, 0))).encode() }
        // 종류나 시간대가 다르면 비교하지 않는다
        QrPayload.Event("x", EventTime.Local(a, ZoneId.of("Asia/Seoul")), EventTime.Local(a.minusHours(1), ZoneId.of("UTC"))).encode()
        QrPayload.Event("x", EventTime.Utc(Instant.parse("2026-01-02T00:00:00Z")), EventTime.AllDay(LocalDate.of(2026, 1, 1))).encode()
        // 같은 시각은 허용한다
        QrPayload.Event("x", EventTime.Local(a), EventTime.Local(a)).encode()
    }

    @Test
    fun `Geo 경계값`() {
        QrPayload.Geo(90.0, 180.0).encode()
        QrPayload.Geo(-90.0, -180.0).encode()
        iae("latitude(90.0001) 는 -90..90 범위여야 한다") { QrPayload.Geo(90.0001, 0.0).encode() }
        iae("latitude(-91.0) 는 -90..90 범위여야 한다") { QrPayload.Geo(-91.0, 0.0).encode() }
        iae("latitude(NaN) 는 -90..90 범위여야 한다") { QrPayload.Geo(Double.NaN, 0.0).encode() }
        iae("longitude(180.5) 는 -180..180 범위여야 한다") { QrPayload.Geo(0.0, 180.5).encode() }
        iae("longitude(Infinity) 는 -180..180 범위여야 한다") { QrPayload.Geo(0.0, Double.POSITIVE_INFINITY).encode() }
        iae("altitude(NaN) 는 유한한 수여야 한다") { QrPayload.Geo(0.0, 0.0, Double.NaN).encode() }
    }

    @Test
    fun `Otp 검사`() {
        val ok = QrPayload.Otp("a", "JBSWY3DPEHPK3PXP")
        iae("account 가 비어 있다") { ok.copy(account = " ").encode() }
        iae("account 에 : 를 쓸 수 없다: a:b") { ok.copy(account = "a:b").encode() }
        iae("issuer 에 : 를 쓸 수 없다: x:y") { ok.copy(issuer = "x:y").encode() }
        iae("secret 은 base32(A-Z, 2-7) 여야 한다 (길이 4)") { ok.copy(secret = "AB18").encode() }
        iae("secret 은 base32(A-Z, 2-7) 여야 한다 (길이 0)") { ok.copy(secret = "").encode() }
        iae("secret 은 base32(A-Z, 2-7) 여야 한다 (길이 5)") { ok.copy(secret = "=ABCD").encode() }
        iae("digits(5) 는 6..8 범위여야 한다") { ok.copy(digits = 5).encode() }
        iae("digits(9) 는 6..8 범위여야 한다") { ok.copy(digits = 9).encode() }
        iae("period(0) 는 1 이상이어야 한다") { ok.copy(period = 0).encode() }
        iae("counter(-1) 는 0 이상이어야 한다") { ok.copy(counter = -1).encode() }
        ok.copy(digits = 6).encode()
        ok.copy(digits = 8).encode()
        ok.copy(period = 1, counter = 0).encode()
    }

    @Test
    fun `짝 없는 서로게이트는 퍼센트 인코딩할 수 없다`() {
        iae("짝 없는 서로게이트가 있어 UTF-8 로 나타낼 수 없다") { QrPayload.Email("a@b", "x\uD800").encode() }
        iae("짝 없는 서로게이트가 있어 UTF-8 로 나타낼 수 없다") { QrPayload.Otp("\uDC00", "JBSWY3DPEHPK3PXP").encode() }
        iae("짝 없는 서로게이트가 있어 UTF-8 로 나타낼 수 없다") { QrPayload.Geo(0.0, 0.0, query = "\uD800x").encode() }
        QrPayload.Email("a@b", "이모지 😀").encode()
    }

    @Test
    fun `비밀값은 예외 메시지에 들어가지 않는다`() {
        val e = assertThrows(IllegalArgumentException::class.java) { QrPayload.Otp("a", "seed-1234-secret").encode() }
        assertTrue(!e.message!!.contains("seed-1234-secret"))
    }

    // ---- 설정 ----

    @Test
    fun `설정 생성 검사`() {
        iae("margin(-1) 은 0 이상이어야 한다") { QrCodeOptions(margin = -1) }
        iae("minVersion(0) 은 1..40 범위여야 한다") { QrCodeOptions(minVersion = 0) }
        iae("minVersion(41) 은 1..40 범위여야 한다") { QrCodeOptions(minVersion = 41) }
        QrCodeOptions(margin = 0, minVersion = 1)
        QrCodeOptions(minVersion = 40)
        iae("width(-1)·height(0) 는 0 이상이어야 한다") { BarcodeOptions(width = -1) }
        iae("width(0)·height(-5) 는 0 이상이어야 한다") { BarcodeOptions(height = -5) }
        iae("margin(-1) 은 0 이상이어야 한다") { BarcodeOptions(margin = -1) }
        iae("width*height(400000000) 가 너무 크다 — 최대 ${CodeMatrix.MAX_CELLS} 칸") { BarcodeOptions(20_000, 20_000) }
        // Android 에서 ARGB 로 바꿔도 감당할 상한(4096x4096)
        assertEquals(4096 * 4096, CodeMatrix.MAX_CELLS)
        BarcodeOptions(4096, 4096)
        iae("width*height(16781312) 가 너무 크다 — 최대 ${CodeMatrix.MAX_CELLS} 칸") { BarcodeOptions(4097, 4096) }
        iaeContains("너무 크다") { Barcode.encode("12345670", CodeFormat.EAN_8, BarcodeOptions(4096, 4096, margin = 5000)) }
        iae("formats 가 비어 있다 — 아무것도 읽지 않는 설정은 호출부 실수다") { ReadOptions(formats = emptySet()) }
    }

    // ---- 생성 ----

    @Test
    fun `QR 생성 검사`() {
        iae("내용이 비어 있다") { QrCode.encode("") }
        iae("내용을 ISO-8859-1 로 나타낼 수 없다") { QrCode.encode("한글", QrCodeOptions(charset = Charsets.ISO_8859_1)) }
        iaeContains("내용이 최대 용량을 넘는다") { QrCode.encode("a".repeat(3000)) }
        iaeContains("내용이 최대 용량을 넘는다") { QrCode.encode("a".repeat(1300), QrCodeOptions(errorCorrection = ErrorCorrection.H)) }
        // 버전 40-L 숫자 모드 최대 7089자
        assertEquals(177 + 8, QrCode.encode("1".repeat(7089), QrCodeOptions(errorCorrection = ErrorCorrection.L)).width)
        iaeContains("최대 용량") { QrCode.encode("1".repeat(7090), QrCodeOptions(errorCorrection = ErrorCorrection.L)) }
        // 바이트 모드는 UTF-8 ECI 표지가 붙어 명세 최대 2953 바이트보다 1 바이트 적다
        QrCode.encode("a".repeat(2952), QrCodeOptions(errorCorrection = ErrorCorrection.L))
        iaeContains("최대 용량") { QrCode.encode("a".repeat(2953), QrCodeOptions(errorCorrection = ErrorCorrection.L)) }
        iae("내용을 UTF-8 로 나타낼 수 없다") { QrCode.encode("a\uD800") }
        iae("url 이 비어 있다") { QrCode.encode(QrPayload.Url("")) }
        iaeContains("너무 크다") { QrCode.encode("a", QrCodeOptions(margin = 100_000)) }
    }

    @Test
    fun `1차원 바코드 내용 검사`() {
        iae("내용이 비어 있다") { Barcode.encode("", CodeFormat.CODE_128) }
        iae("EAN_13 체크 숫자가 틀렸다: 4006381333932 (맞는 값: 4006381333931)") { Barcode.encode("4006381333932", CodeFormat.EAN_13) }
        iae("EAN_13 는 12자리 또는 13자리여야 한다 (받은 길이: 11)") { Barcode.encode("40063813339", CodeFormat.EAN_13) }
        iae("EAN_13 는 숫자만 받는다 (받은 값: 40063813339a)") { Barcode.encode("40063813339a", CodeFormat.EAN_13) }
        iae("EAN_8 체크 숫자가 틀렸다: 96385075 (맞는 값: 96385074)") { Barcode.encode("96385075", CodeFormat.EAN_8) }
        iae("UPC_A 는 11자리 또는 12자리여야 한다 (받은 길이: 13)") { Barcode.encode("0360002914521", CodeFormat.UPC_A) }
        iae("UPC_E 는 번호 체계 0 또는 1 로 시작해야 한다 (받은 값: 24252614)") { Barcode.encode("24252614", CodeFormat.UPC_E) }
        iae("UPC_E 체크 숫자가 틀렸다: 04252615 (맞는 값: 04252614)") { Barcode.encode("04252615", CodeFormat.UPC_E) }
        iae("ITF 는 숫자만 받는다 (받은 값: 12a4)") { Barcode.encode("12a4", CodeFormat.ITF) }
        iae("ITF 는 2..80 의 짝수 자릿수여야 한다 (받은 길이: 3)") { Barcode.encode("123", CodeFormat.ITF) }
        iae("ITF 는 2..80 의 짝수 자릿수여야 한다 (받은 길이: 82)") { Barcode.encode("1".repeat(82), CodeFormat.ITF) }
        iae("CODE_39 가 담을 수 없는 글자 'b' — 0-9 A-Z - . \$ / + % 와 공백만 받는다") { Barcode.encode("Abc", CodeFormat.CODE_39) }
        iae("CODE_39 는 80자 이하여야 한다 (받은 길이: 81)") { Barcode.encode("A".repeat(81), CodeFormat.CODE_39) }
        iae("CODE_128 는 ASCII 만 받는다 (받은 글자: '한')") { Barcode.encode("a한", CodeFormat.CODE_128) }
        iae("CODE_93 는 ASCII 만 받는다 (받은 글자: 'é')") { Barcode.encode("é", CodeFormat.CODE_93) }
        iae("CODABAR 의 시작 문자(A)가 있으면 끝 문자(A~D)도 있어야 한다") { Barcode.encode("A123", CodeFormat.CODABAR) }
        iae("CODABAR 의 본문이 비어 있다") { Barcode.encode("AB", CodeFormat.CODABAR) }
        iae("CODABAR 가 담을 수 없는 글자 'X' — 0-9 - \$ : / . + 만 받는다") { Barcode.encode("12X", CodeFormat.CODABAR) }
        // 경계: 80자 허용
        Barcode.encode("A".repeat(80), CodeFormat.CODE_39)
        Barcode.encode("a".repeat(80), CodeFormat.CODE_128)
        Barcode.encode("12", CodeFormat.ITF)
        Barcode.encode("1".repeat(80), CodeFormat.ITF)
        Barcode.encode("a1b", CodeFormat.CODABAR)
        Barcode.encode("0425261", CodeFormat.UPC_E)
        Barcode.encode("9638507", CodeFormat.EAN_8)
    }

    @Test
    fun `2차원 바코드 용량 초과`() {
        iaeContains("AZTEC 생성 에 실패했다") { Barcode.encode("a".repeat(5000), CodeFormat.AZTEC) }
        iaeContains("DATA_MATRIX 생성 에 실패했다") { Barcode.encode("a".repeat(5000), CodeFormat.DATA_MATRIX) }
        iae("PDF_417 생성 에 실패했다 — 내용이 최대 용량을 넘는다: Unable to fit message in columns") { Barcode.encode("a".repeat(5000), CodeFormat.PDF_417) }
    }

    // ---- 읽기 ----

    @Test
    fun `읽기 배열 검사`() {
        iae("width(0)·height(1) 는 1 이상이어야 한다") { CodeReader.read(ByteArray(1), 0, 1) }
        iae("width(1)·height(0) 는 1 이상이어야 한다") { CodeReader.readAll(IntArray(1), 1, 0) }
        iae("rowStride(3) 는 width(4) 이상이어야 한다") { CodeReader.read(ByteArray(16), 4, 4, rowStride = 3) }
        iae("배열 크기(15) 가 rowStride*(height-1)+width(16) 보다 작다") { CodeReader.read(ByteArray(15), 4, 4) }
        iae("배열 크기(15) 가 rowStride*(height-1)+width(16) 보다 작다") { CodeReader.read(IntArray(15), 4, 4) }
        iae("배열 크기(20) 가 rowStride*(height-1)+width(22) 보다 작다") { CodeReader.readAll(ByteArray(20), 4, 4, rowStride = 6) }
        // 경계: 마지막 행은 패딩이 없어도 된다
        CodeReader.read(ByteArray(22), 4, 4, rowStride = 6)
        CodeReader.read(ByteArray(1), 1, 1)
        CodeReader.readAll(IntArray(1), 1, 1)
    }

    // ---- 엔진 실패 감싸기 ----

    @Test
    fun `엔진의 그 밖의 실패는 원인을 담은 QrCodeException`() {
        val cause = IllegalStateException("엔진 내부")
        val e = assertThrows(QrCodeException::class.java) { engine<Unit>("시험") { throw cause } }
        assertEquals("시험 에 실패했다: 엔진 내부", e.message)
        assertSame(cause, e.cause)

        val writer = WriterException("unexpected")
        val w = assertThrows(QrCodeException::class.java) { engine<Unit>("시험") { throw writer } }
        assertSame(writer, w.cause)

        val own = QrCodeException("그대로")
        assertSame(own, assertThrows(QrCodeException::class.java) { engine<Unit>("시험") { throw own } })

        val tooBig = WriterException("Data too big")
        val t = assertThrows(IllegalArgumentException::class.java) { engine<Unit>("시험") { throw tooBig } }
        assertEquals("시험 에 실패했다 — 내용이 최대 용량을 넘는다: Data too big", t.message)
        assertSame(tooBig, t.cause)

        val bad = IllegalArgumentException("bad")
        val b = assertThrows(IllegalArgumentException::class.java) { engine<Unit>("시험") { throw bad } }
        assertEquals("시험 에 실패했다 — 내용을 확인한다: bad", b.message)
        assertSame(bad, b.cause)

        assertEquals(1, engine("시험") { 1 })
    }

    @Test
    fun `읽기 엔진 실패는 원인을 담은 QrCodeException`() {
        val cause = ArrayIndexOutOfBoundsException("x")
        val e = assertThrows(QrCodeException::class.java) { net.plumt.qr.internal.Decoders.reading<Unit> { throw cause } }
        assertEquals("코드 읽기에 실패했다: x", e.message)
        assertSame(cause, e.cause)
        val own = QrCodeException("own")
        assertSame(own, assertThrows(QrCodeException::class.java) { net.plumt.qr.internal.Decoders.reading<Unit> { throw own } })
    }

    @Test
    fun `QrCodeException 은 RuntimeException 이고 cause 를 보존한다`() {
        val cause = RuntimeException("c")
        val e = QrCodeException("m", cause)
        assertTrue(RuntimeException::class.java.isAssignableFrom(e.javaClass))
        assertEquals("m", e.message)
        assertSame(cause, e.cause)
        assertEquals(null, QrCodeException("m").cause)
    }
}
